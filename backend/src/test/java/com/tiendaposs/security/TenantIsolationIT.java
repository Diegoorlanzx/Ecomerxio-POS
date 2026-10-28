package com.tiendaposs.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.settings.ModulesService;
import com.tiendaposs.support.AbstractIntegrationTest;
import com.tiendaposs.support.Api;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueba de ataque entre tiendas (IDOR): el dueño de una tienda recorre TODOS los endpoints de la API que reciben un id
 * en la ruta, con los ids de los datos de otra tienda. Falla si alguna respuesta exitosa trae datos de la otra tienda
 * (marca {@link #MARK}) o si los datos de la otra tienda cambian después de la ronda.
 */
class TenantIsolationIT extends AbstractIntegrationTest {

    private static final String MARK = "ZZSECRETO";
    private static final Pattern VAR = Pattern.compile("\\{([A-Za-z0-9_]+)(:[^}]*)?}");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    @Autowired
    JdbcTemplate jdbc;

    Api api;

    @Test
    void anotherStoreCannotReadOrChangeDataById() throws Exception {
        api = new Api(mvc, json);
        Store victim = newStoreWithEinvoice();
        Store attacker = newStoreWithEinvoice();
        enableEverything(victim.ownerToken());
        enableEverything(attacker.ownerToken());
        populate(victim.ownerToken());

        List<Long> ids = victimIds(victim.tenantId());
        assertThat(ids).as("la tienda víctima debe tener datos").hasSizeGreaterThan(8);
        List<String> tables = tenantTables();
        Map<String, String> before = fingerprint(tables, victim.tenantId());

        String token = attacker.ownerToken();
        List<String> leaks = new ArrayList<>();
        List<String> writes = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int requests = 0;
        Map<Integer, Integer> statuses = new TreeMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mapping.getHandlerMethods().entrySet()) {
            Map<String, Boolean> numeric = pathVars(e.getValue());
            if (!numeric.containsValue(true)) continue;
            for (String pattern : patterns(e.getKey())) {
                if (!pattern.startsWith("/api/") || pattern.startsWith("/api/platform") || pattern.startsWith("/api/public")
                        || pattern.startsWith("/api/webhooks")) continue;
                for (var m : e.getKey().getMethodsCondition().getMethods()) {
                    String method = m.name();
                    for (Long id : ids) {
                        String url = fill(pattern, numeric, id);
                        Api.Res r = api.call(method, url, token, "GET".equals(method) || "DELETE".equals(method) ? null : "{}");
                        requests++;
                        statuses.merge(r.status(), 1, Integer::sum);
                        if (r.status() >= 500) errors.add(method + " " + url + " -> " + r.status());
                        if (r.status() == 401) {
                            token = login(attacker.ownerEmail(), "Clave1234");
                            r = api.call(method, url, token, "GET".equals(method) || "DELETE".equals(method) ? null : "{}");
                        }
                        if (r.status() >= 200 && r.status() < 300 && r.raw().contains(MARK)) {
                            leaks.add(method + " " + url + " -> " + r.status());
                        }
                    }
                    if (!"GET".equals(method)) {
                        Map<String, String> now = fingerprint(tables, victim.tenantId());
                        if (!now.equals(before)) {
                            writes.add(method + " " + pattern + " cambió: " + changed(before, now));
                            before = now;
                        }
                    }
                }
            }
        }
        System.out.println("BARRIDO " + requests + " peticiones, estados " + statuses);
        assertThat(requests).as("se recorrieron endpoints").isGreaterThan(1000);
        // Control: con su propio token la víctima SÍ ve su marca (la detección funciona).
        assertThat(api.get("/api/products?q=" + MARK, victim.ownerToken()).raw()).contains(MARK);
        assertThat(leaks).as("lecturas de datos de otra tienda").isEmpty();
        assertThat(writes).as("cambios en datos de otra tienda").isEmpty();
        assertThat(errors).as("un id ajeno debe dar 4xx, no un error del servidor").isEmpty();
    }

    // ---------------------------------------------------------------- datos

    private void enableEverything(String owner) throws Exception {
        List<String> all = new ArrayList<>(ModulesService.MODULES.stream().map(ModulesService.ModuleInfo::code).toList());
        api.put("/api/settings/pos/modules", owner, json.createObjectNode().put("businessType", "MIXTO")
                .set("modules", json.valueToTree(all))).expect(200);
    }

    /** Datos de la víctima en varios módulos; todo lleva la marca para detectar fugas. Lo que falle no detiene la prueba. */
    private void populate(String owner) throws Exception {
        JsonNode p = api.post("/api/products", owner, """
                {"name":"%s producto","unit":"UNIDAD","cost":1000,"initialStock":50,"stockMin":5,
                 "presentations":[{"name":"Unidad","factor":1,"barcode":"7700000000017","price":2000,"isBase":true}]}
                """.formatted(MARK)).expect(201).json();
        long pid = p.get("id").asLong();
        long pres = p.get("presentations").get(0).get("id").asLong();
        long customer = api.post("/api/pos/customers", owner, "{\"name\":\"%s cliente\",\"docNumber\":\"52999111\"}".formatted(MARK))
                .expect(201).json().get("id").asLong();
        long supplier = api.post("/api/suppliers", owner, "{\"name\":\"%s proveedor\",\"creditDays\":10}".formatted(MARK))
                .expect(201).json().get("id").asLong();
        long reg = api.get("/api/registers", owner).json().get(0).get("id").asLong();
        api.post("/api/shifts/open", owner, "{\"registerId\":%d,\"openingCash\":50000}".formatted(reg)).expect(200);
        api.post("/api/pos/sales", owner, """
                {"clientRef":"%s","customerId":%d,"notes":"%s venta","lines":[{"presentationId":%d,"quantity":2}],
                 "payments":[{"method":"EFECTIVO","amount":4000}]}""".formatted(UUID.randomUUID(), customer, MARK, pres));
        api.post("/api/pos/accounts", owner, "{\"name\":\"%s cuenta\"}".formatted(MARK));
        api.post("/api/purchases", owner, "{\"supplierId\":%d,\"notes\":\"%s orden\",\"lines\":[{\"productId\":%d,\"packs\":5,\"costPerPack\":1000}]}"
                .formatted(supplier, MARK, pid));
        api.post("/api/receipts", owner, "{\"supplierId\":%d,\"lines\":[{\"productId\":%d,\"unitsReceived\":4,\"unitCost\":900}]}"
                .formatted(supplier, pid));
        api.post("/api/quotes", owner, """
                {"customerName":"%s cotización","lines":[{"presentationId":%d,"quantity":1}]}""".formatted(MARK, pres));
        api.post("/api/finance/entries", owner, "{\"kind\":\"GASTO\",\"description\":\"%s gasto\",\"amount\":10000,\"method\":\"EFECTIVO\",\"paidFrom\":\"BANCO\"}"
                .formatted(MARK));
        api.post("/api/users", owner, """
                {"fullName":"%s empleado","email":"%s@prueba.com","password":"Clave1234","roleIds":[%d]}""".formatted(MARK, unique("victima"), roleId(owner, "CAJERO")));
    }

    private List<String> tenantTables() {
        return jdbc.queryForList("""
                SELECT c.table_name FROM information_schema.columns c
                JOIN information_schema.columns i ON i.table_name = c.table_name AND i.table_schema = c.table_schema AND i.column_name = 'id'
                WHERE c.table_schema = 'public' AND c.column_name = 'tenant_id' ORDER BY 1""", String.class);
    }

    private List<Long> victimIds(long tenantId) {
        TreeSet<Long> ids = new TreeSet<>();
        for (String t : tenantTables()) {
            ids.addAll(jdbc.queryForList("SELECT id::bigint FROM " + t + " WHERE tenant_id = ?", Long.class, tenantId));
        }
        ids.add(tenantId);
        return new ArrayList<>(ids);
    }

    private Map<String, String> fingerprint(List<String> tables, long tenantId) {
        Map<String, String> out = new TreeMap<>();
        for (String t : tables) {
            if (t.equals("audit_logs") || t.equals("notifications") || t.equals("client_operations")) continue;
            out.put(t, jdbc.queryForObject("SELECT md5(coalesce(string_agg(x::text, '|' ORDER BY x.id), '')) FROM " + t
                    + " x WHERE tenant_id = ?", String.class, tenantId));
        }
        return out;
    }

    private static String changed(Map<String, String> a, Map<String, String> b) {
        return a.keySet().stream().filter(k -> !Objects.equals(a.get(k), b.get(k))).toList().toString();
    }

    // ---------------------------------------------------------------- rutas

    private static Set<String> patterns(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null) {
            Set<String> s = new LinkedHashSet<>();
            for (PathPattern p : info.getPathPatternsCondition().getPatterns()) s.add(p.getPatternString());
            return s;
        }
        return info.getPatternValues();
    }

    /** Variables de la ruta → si son numéricas (ids). */
    private static Map<String, Boolean> pathVars(HandlerMethod h) {
        Map<String, Boolean> out = new HashMap<>();
        for (var p : h.getMethodParameters()) {
            p.initParameterNameDiscovery(new org.springframework.core.DefaultParameterNameDiscoverer());
            PathVariable pv = p.getParameterAnnotation(PathVariable.class);
            if (pv == null) continue;
            String name = !pv.value().isEmpty() ? pv.value() : !pv.name().isEmpty() ? pv.name() : p.getParameterName();
            Class<?> type = p.getParameterType();
            out.put(name, type == Long.class || type == long.class || type == Integer.class || type == int.class);
        }
        return out;
    }

    private static String fill(String pattern, Map<String, Boolean> numeric, Long id) {
        Matcher m = VAR.matcher(pattern);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Boolean.TRUE.equals(numeric.get(m.group(1))) ? String.valueOf(id) : "x");
        m.appendTail(sb);
        return sb.toString();
    }
}
