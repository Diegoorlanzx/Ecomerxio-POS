package com.tiendaposs.backup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tiendaposs.support.AbstractIntegrationTest;
import com.tiendaposs.support.Api;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Fase 5: notificaciones con reglas y canales, copias de seguridad, restauración, exportación y sucursales. */
class NotificationsBackupIT extends AbstractIntegrationTest {

    Api api;
    Store store;
    String owner;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, json);
        store = newStore();
        owner = store.ownerToken();
    }

    long product(String name, int price, int stock) throws Exception {
        return api.post("/api/products", owner, """
                {"name":"%s","unit":"UNIDAD","cost":%d,"initialStock":%d,"stockMin":2,
                 "presentations":[{"name":"Unidad","factor":1,"price":%d,"isBase":true}]}""".formatted(name, price / 2, stock, price))
                .expect(201).json().get("presentations").get(0).get("id").asLong();
    }

    long openShift(String token) throws Exception {
        long reg = api.get("/api/registers", token).json().get(0).get("id").asLong();
        return api.post("/api/shifts/open", token, "{\"registerId\":%d,\"openingCash\":0}".formatted(reg)).expect(200).json().get("id").asLong();
    }

    JsonNode sell(long pres, String method, int amount, String ref) throws Exception {
        return api.post("/api/pos/sales", owner, """
                {"clientRef":"%s","lines":[{"presentationId":%d,"quantity":1}],"payments":[{"method":"%s","amount":%d%s}]}"""
                .formatted(UUID.randomUUID(), pres, method, amount, ref == null ? "" : ",\"reference\":\"" + ref + "\"")).expect(201).json();
    }

    int scan() throws Exception {
        return api.post("/api/notifications/scan", owner, null).expect(200).json().get("created").asInt();
    }

    List<String> types(String token) throws Exception {
        List<String> out = new ArrayList<>();
        for (JsonNode n : api.get("/api/notifications", token).expect(200).json().get("items")) out.add(n.get("type").asText());
        return out;
    }

    void rule(String type, boolean enabled, Integer threshold, boolean email, boolean webhook) throws Exception {
        api.put("/api/notifications/rules", owner, "[{\"type\":\"%s\",\"enabled\":%s,\"threshold\":%s,\"email\":%s,\"webhook\":%s,\"whatsapp\":false}]"
                .formatted(type, enabled, threshold == null ? "null" : threshold, email, webhook)).expect(200);
    }

    // ---------------- notificaciones ----------------

    @Test
    void stockAlertsAreCreatedOnceVisibleByPermissionAndMarkedRead() throws Exception {
        product("Hielo", 3000, 0);
        assertThat(scan()).isGreaterThan(0);
        assertThat(types(owner)).contains("STOCK_CRITICO");
        assertThat(scan()).isZero(); // no se repite
        // El cajero no tiene permiso de inventario: no lo ve.
        String cajero = newEmployee(store, "CAJERO");
        assertThat(types(cajero)).doesNotContain("STOCK_CRITICO");

        JsonNode inbox = api.get("/api/notifications", owner).json();
        long unread = inbox.get("unread").asLong();
        long id = inbox.get("items").get(0).get("id").asLong();
        api.post("/api/notifications/" + id + "/read", owner, null).expect(204);
        assertThat(api.get("/api/notifications/unread-count", owner).json().get("unread").asLong()).isEqualTo(unread - 1);
        api.post("/api/notifications/read-all", owner, null).expect(200);
        assertThat(api.get("/api/notifications/unread-count", owner).json().get("unread").asLong()).isZero();

        // Regla apagada: no se crean más de ese tipo.
        rule("STOCK_CRITICO", false, null, false, false);
        product("Bolsa", 200, 0);
        scan();
        assertThat(api.get("/api/notifications", owner).json().get("items").findValuesAsText("title")).noneMatch(t -> t.contains("Bolsa"));
        JsonNode rules = api.get("/api/notifications/rules", owner).json();
        assertThat(rules).hasSizeGreaterThan(15);
    }

    @Test
    void cashDifferenceUnusualVoidsAndPaymentRules() throws Exception {
        long pres = product("Gaseosa", 4000, 50);
        long shift = openShift(owner);
        rule("PAGO_POR_VERIFICAR", true, 0, false, false);
        sell(pres, "NEQUI", 4000, "N111");
        sell(pres, "NEQUI", 4000, "N111");
        JsonNode s1 = sell(pres, "EFECTIVO", 4000, null);
        JsonNode s2 = sell(pres, "EFECTIVO", 4000, null);
        rule("DEVOLUCIONES_INUSUALES", true, 2, false, false);
        api.post("/api/pos/sales/" + s1.get("id").asLong() + "/void", owner, "{\"reason\":\"Error\"}").expect(200);
        api.post("/api/pos/sales/" + s2.get("id").asLong() + "/void", owner, "{\"reason\":\"Error\"}").expect(200);
        // Cierra con 10.000 de más (esperado 0 en efectivo).
        api.post("/api/shifts/" + shift + "/close", owner, "{\"countedCash\":10000,\"closeNotes\":\"Sobró plata\"}").expect(200);
        scan();
        List<String> t = types(owner);
        assertThat(t).contains("PAGO_POR_VERIFICAR", "PAGO_INCONSISTENTE", "DEVOLUCIONES_INUSUALES", "DIFERENCIA_CAJA");
        JsonNode diff = null;
        for (JsonNode n : api.get("/api/notifications", owner).json().get("items")) if (n.get("type").asText().equals("DIFERENCIA_CAJA")) diff = n;
        assertThat(diff.get("title").asText()).contains("Sobrante de $10.000");

        // Con umbral más alto, una diferencia igual ya no avisa.
        rule("DIFERENCIA_CAJA", true, 20000, false, false);
        long shift2 = openShift(owner);
        api.post("/api/shifts/" + shift2 + "/close", owner, "{\"countedCash\":10000,\"closeNotes\":\"Otra vez\"}").expect(200);
        scan();
        long cajaCount = types(owner).stream().filter("DIFERENCIA_CAJA"::equals).count();
        assertThat(cajaCount).isEqualTo(1);
    }

    @Test
    void purchaseNotificationsAndDailyReport() throws Exception {
        long pid = api.post("/api/products", owner, """
                {"name":"Jugo","unit":"UNIDAD","cost":1000,"initialStock":20,"presentations":[{"name":"Unidad","factor":1,"price":2000,"isBase":true}]}""")
                .expect(201).json().get("id").asLong();
        long sup = api.post("/api/suppliers", owner, "{\"name\":\"Distri\",\"creditDays\":1}").expect(201).json().get("id").asLong();
        String compras = newEmployee(store, "COMPRAS");
        JsonNode o = api.post("/api/purchases", compras, "{\"supplierId\":%d,\"lines\":[{\"productId\":%d,\"packs\":5,\"costPerPack\":1000}]}"
                .formatted(sup, pid)).expect(201).json();
        api.post("/api/purchases/" + o.get("id").asLong() + "/submit", compras, null).expect(200);
        scan();
        assertThat(types(owner)).contains("PEDIDO_POR_APROBAR");
        api.post("/api/purchases/" + o.get("id").asLong() + "/approve", owner, null).expect(200);
        api.post("/api/purchases/" + o.get("id").asLong() + "/receive", owner, "{\"lines\":[{\"orderLineId\":%d,\"unitsReceived\":5}]}"
                .formatted(o.get("lines").get(0).get("id").asLong())).expect(200);
        // Reporte diario: a partir de la hora 0 (o sea, ya).
        api.put("/api/notifications/channels", owner, "{\"dailyReportHour\":0}").expect(200);
        openShift(owner);
        api.post("/api/pos/sales", owner, "{\"clientRef\":\"%s\",\"lines\":[{\"presentationId\":%d,\"quantity\":2}],\"payments\":[{\"method\":\"EFECTIVO\",\"amount\":4000}]}"
                .formatted(UUID.randomUUID(), api.get("/api/products/" + pid, owner).json().get("presentations").get(0).get("id").asLong())).expect(201);
        scan();
        List<String> t = types(owner);
        assertThat(t).contains("MERCANCIA_RECIBIDA", "PAGO_PROVEEDOR", "REPORTE_DIARIO");
        String body = null;
        for (JsonNode n : api.get("/api/notifications", owner).json().get("items")) if (n.get("type").asText().equals("REPORTE_DIARIO")) body = n.get("body").asText();
        assertThat(body).contains("Ventas de hoy: $4.000 en 1 ventas").contains("Jugo (2)");
    }

    @Test
    void externalChannelsRecordDeliveries() throws Exception {
        api.put("/api/notifications/channels", owner, "{\"webhookUrl\":\"http://inseguro.test/hook\",\"dailyReportHour\":21}").expect(400);
        api.put("/api/notifications/channels", owner, "{\"emailTo\":\"no-es-correo\",\"dailyReportHour\":21}").expect(400);
        JsonNode ch = api.put("/api/notifications/channels", owner, """
                {"emailTo":"dueno@tienda.test","webhookUrl":"https://127.0.0.1:1/hook","webhookSecret":"s3cr3t","whatsappTo":"3001234567","dailyReportHour":21}""")
                .expect(200).json();
        assertThat(ch.get("webhookSecretSet").asBoolean()).isTrue();
        assertThat(ch.has("webhookSecret")).isFalse();
        assertThat(ch.get("whatsappTo").asText()).isEqualTo("573001234567");
        rule("STOCK_CRITICO", true, null, true, true);
        product("Hielo", 3000, 0);
        scan();
        JsonNode d = api.get("/api/notifications/deliveries", owner).expect(200).json();
        assertThat(d.findValuesAsText("channel")).contains("EMAIL", "WEBHOOK");
        for (JsonNode x : d) {
            if (x.get("channel").asText().equals("EMAIL")) assertThat(x.get("status").asText()).isEqualTo("SIN_CONFIGURAR");
            if (x.get("channel").asText().equals("WEBHOOK")) assertThat(x.get("status").asText()).isEqualTo("FALLIDO");
        }
        assertThat(api.post("/api/notifications/channels/test", owner, "{\"channel\":\"WHATSAPP\"}").expect(200).json().get("status").asText())
                .isEqualTo("SIN_CONFIGURAR");
        String cajero = newEmployee(store, "CAJERO");
        api.get("/api/notifications/channels", cajero).expect(403);
    }

    // ---------------- copias de seguridad ----------------

    byte[] download(long id) throws Exception {
        MvcResult r = mvc.perform(get("/api/backups/" + id + "/download").header("Authorization", bearer(owner))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return r.getResponse().getContentAsByteArray();
    }

    MvcResult restoreFile(byte[] data, String password) throws Exception {
        return mvc.perform(post("/api/backups/restore-file").header("Authorization", bearer(owner)).header("X-Password", password)
                .contentType(MediaType.APPLICATION_OCTET_STREAM).content(data)).andReturn();
    }

    static byte[] gunzip(byte[] b) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(b))) {
            return in.readAllBytes();
        }
    }

    static byte[] gzip(byte[] b) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(out)) {
            g.write(b);
        }
        return out.toByteArray();
    }

    @Test
    void backupRestoreAndSafetyCopy() throws Exception {
        long pres = product("Papas", 3000, 10);
        openShift(owner);
        sell(pres, "EFECTIVO", 3000, null);
        JsonNode b = api.post("/api/backups", owner, null).expect(200).json();
        assertThat(b.get("kind").asText()).isEqualTo("MANUAL");
        assertThat(b.get("rowCounts").get("products").asInt()).isEqualTo(1);
        assertThat(b.get("rowCounts").get("sales").asInt()).isEqualTo(1);
        long backupId = b.get("id").asLong();
        byte[] file = download(backupId);
        assertThat(new String(gunzip(file), StandardCharsets.UTF_8)).contains("\"format\":\"tiendaposs-backup\"").contains("Papas");

        // Después de la copia: un producto nuevo y otra venta.
        product("Galletas", 2000, 5);
        sell(pres, "EFECTIVO", 3000, null);
        assertThat(api.get("/api/products", owner).json().get("total").asInt()).isEqualTo(2);

        api.post("/api/backups/" + backupId + "/restore", owner, "{\"password\":\"mala\"}").expect(400);
        String cajero = newEmployee(store, "CAJERO");
        api.post("/api/backups/" + backupId + "/restore", cajero, "{\"password\":\"Clave1234\"}").expect(403);
        String admin = newEmployee(store, "ADMINISTRADOR");
        api.get("/api/backups", admin).expect(403);

        JsonNode res = api.post("/api/backups/" + backupId + "/restore", owner, "{\"password\":\"Clave1234\"}").expect(200).json();
        assertThat(res.get("restored").get("products").asInt()).isEqualTo(1);
        // Los empleados creados después de la copia ya no existen: pedir token nuevo con el dueño.
        owner = login(store.ownerEmail(), "Clave1234");
        assertThat(api.get("/api/products", owner).json().get("total").asInt()).isEqualTo(1);
        assertThat(api.get("/api/products/" + api.get("/api/products", owner).json().get("items").get(0).get("id").asLong(), owner).json()
                .get("stock").decimalValue()).isEqualByComparingTo("9");
        JsonNode list = api.get("/api/backups", owner).json();
        assertThat(list.get("backups").findValuesAsText("kind")).contains("MANUAL", "ANTES_RESTAURAR");
        assertThat(api.get("/api/audit?size=5", owner).raw()).contains("RESTAURACION");

        // Se puede deshacer con la copia "antes de restaurar" (desde archivo).
        long safety = res.get("safetyBackupId").asLong();
        MvcResult undo = restoreFile(download(safety), "Clave1234");
        assertThat(undo.getResponse().getStatus()).as(undo.getResponse().getContentAsString()).isEqualTo(200);
        owner = login(store.ownerEmail(), "Clave1234");
        assertThat(api.get("/api/products", owner).json().get("total").asInt()).isEqualTo(2);
    }

    @Test
    void tamperedOrForeignBackupsAreRejected() throws Exception {
        product("Papas", 3000, 10);
        long id = api.post("/api/backups", owner, null).expect(200).json().get("id").asLong();
        byte[] file = download(id);
        assertThat(restoreFile("no soy una copia".getBytes(), "Clave1234").getResponse().getStatus()).isEqualTo(400);

        // Copia de otra tienda.
        Store other = newStore();
        api.post("/api/products", other.ownerToken(), """
                {"name":"Ajena","unit":"UNIDAD","presentations":[{"name":"Unidad","factor":1,"price":1000,"isBase":true}]}""").expect(201);
        ObjectNode root = (ObjectNode) json.readTree(gunzip(file));
        root.put("tenantId", other.tenantId());
        MvcResult r = restoreFile(gzip(json.writeValueAsBytes(root)), "Clave1234");
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("otra tienda");

        // Filas alteradas para meterse en otra tienda.
        root = (ObjectNode) json.readTree(gunzip(file));
        ArrayNode cats = (ArrayNode) root.path("tables").path("finance_categories");
        ((ObjectNode) cats.get(0)).put("tenant_id", other.tenantId());
        r = restoreFile(gzip(json.writeValueAsBytes(root)), "Clave1234");
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("otra tienda");
        // Nada cambió.
        assertThat(api.get("/api/products", owner).json().get("total").asInt()).isEqualTo(1);
        assertThat(api.get("/api/products", other.ownerToken()).json().get("total").asInt()).isEqualTo(1);
    }

    @Test
    void dataExportZipAndBranches() throws Exception {
        long pres = product("Arepas", 5000, 10);
        openShift(owner);
        sell(pres, "EFECTIVO", 5000, null);
        MvcResult r = mvc.perform(get("/api/data-export").header("Authorization", bearer(owner))).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        List<String> names = new ArrayList<>();
        String productos = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(r.getResponse().getContentAsByteArray()))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                names.add(e.getName());
                byte[] content = zip.readAllBytes();
                if (e.getName().equals("productos.csv")) productos = new String(content, StandardCharsets.UTF_8);
            }
        }
        assertThat(names).contains("productos.csv", "ventas.csv", "clientes.csv", "movimientos_inventario.csv", "usuarios.csv", "LEEME.txt");
        assertThat(productos).contains("codigo;nombre").contains("Arepas");

        JsonNode branches = api.get("/api/branches", owner).expect(200).json();
        assertThat(branches).hasSize(1);
        assertThat(branches.get(0).get("main").asBoolean()).isTrue();
        assertThat(branches.get(0).get("registers").asInt()).isEqualTo(1);
        api.post("/api/registers", owner, "{\"name\":\"Caja 2\"}").expect(200);
        JsonNode upd = api.put("/api/branches/" + branches.get(0).get("id").asLong(), owner, "{\"name\":\"Sede centro\"}").expect(200).json();
        assertThat(upd.get(0).get("name").asText()).isEqualTo("Sede centro");
        assertThat(upd.get(0).get("registers").asInt()).isEqualTo(2);
    }
}
