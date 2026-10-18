package com.tiendaposs.fiscal;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.support.AbstractIntegrationTest;
import com.tiendaposs.support.Api;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Fase 13A: datos fiscales, resoluciones, clasificación tributaria, clientes y preparación. */
class FiscalIT extends AbstractIntegrationTest {

    Api api;
    Store store;
    String owner;
    final LocalDate today = LocalDate.now(ZoneId.of("America/Bogota"));

    @BeforeEach
    void setUp() {
        api = new Api(mvc, json);
    }

    private void newShop() throws Exception {
        store = newStore();
        owner = store.ownerToken();
    }

    private static final String COMPLETE_PROFILE = """
            {"personType":"JURIDICA","idType":"NIT","idNumber":"800197268","dv":"4","legalName":"Tienda Prueba SAS",
             "ivaResponsible":true,"responsibilities":["O-15"],"address":"Calle 1 # 2-3","municipalityCode":"11001",
             "municipalityName":"Bogotá","invoicingEmail":"facturas@prueba.com"}""";

    private String resolution(String type, String prefix, long from, long to, LocalDate validTo) {
        return """
                {"documentType":"%s","resolutionNumber":"18760000%d","prefix":"%s","rangeFrom":%d,"rangeTo":%d,
                 "validFrom":"%s","validTo":"%s"}""".formatted(type, from, prefix, from, to, today.minusDays(10), validTo);
    }

    @Test
    void theProfileStartsFromTheStoreAndRequiresAValidVerificationDigit() throws Exception {
        newShop();
        JsonNode p = api.get("/api/fiscal/profile", owner).expect(200).json();
        assertThat(p.get("environment").asText()).isEqualTo("PRUEBAS");
        assertThat(p.get("tradeName").asText()).isNotBlank();

        JsonNode bad = api.put("/api/fiscal/profile", owner, COMPLETE_PROFILE.replace("\"dv\":\"4\"", "\"dv\":\"5\"")).expect(400).json();
        assertThat(bad.get("fields").get("dv").asText()).contains("4");
        api.put("/api/fiscal/profile", owner, COMPLETE_PROFILE.replace("[\"O-15\"]", "[\"O-15\",\"R-99-PN\"]")).expect(400);

        JsonNode ok = api.put("/api/fiscal/profile", owner, COMPLETE_PROFILE).expect(200).json();
        assertThat(ok.get("dv").asText()).isEqualTo("4");
        assertThat(ok.get("responsibilities").get(0).asText()).isEqualTo("O-15");
        assertThat(api.get("/api/fiscal/dv?number=800.197.268", owner).json().get("dv").asText()).isEqualTo("4");
    }

    @Test
    void productionNeedsACompleteProfile() throws Exception {
        newShop();
        api.put("/api/fiscal/profile", owner, "{\"environment\":\"PRODUCCION\"}").expect(400);
        api.put("/api/fiscal/profile", owner, COMPLETE_PROFILE.replace("}", ",\"environment\":\"PRODUCCION\"}")).expect(200);
    }

    @Test
    void resolutionsRejectOverlapsAndNeverMoveTheConsecutiveBackwards() throws Exception {
        newShop();
        JsonNode r = api.post("/api/fiscal/resolutions", owner, resolution("POS_ELECTRONICO", "POS", 1, 1000, today.plusYears(1))).expect(200).json();
        assertThat(r.get("status").asText()).isEqualTo("VIGENTE");
        assertThat(r.get("nextNumber").asLong()).isEqualTo(1);
        assertThat(r.get("remaining").asLong()).isEqualTo(1000);

        // Mismo tipo, prefijo y ambiente, rango cruzado: no.
        api.post("/api/fiscal/resolutions", owner, resolution("POS_ELECTRONICO", "POS", 900, 2000, today.plusYears(1))).expect(400);
        // Otro prefijo: sí.
        api.post("/api/fiscal/resolutions", owner, resolution("POS_ELECTRONICO", "PV", 900, 2000, today.plusYears(1))).expect(200);

        long id = r.get("id").asLong();
        String moved = resolution("POS_ELECTRONICO", "POS", 1, 1000, today.plusYears(1)).replace("}", ",\"nextNumber\":850}");
        JsonNode used = api.put("/api/fiscal/resolutions/" + id, owner, moved).expect(200).json();
        assertThat(used.get("status").asText()).isEqualTo("POR_AGOTAR");
        api.put("/api/fiscal/resolutions/" + id, owner, moved.replace("850", "10")).expect(400);

        JsonNode soon = api.post("/api/fiscal/resolutions", owner, resolution("FACTURA", "FE", 1, 500, today.plusDays(10))).expect(200).json();
        assertThat(soon.get("status").asText()).isEqualTo("POR_VENCER");
    }

    @Test
    void onlyProductsWithZeroTaxNeedClassification() throws Exception {
        newShop();
        long zero = api.post("/api/products", owner, """
                {"name":"Arroz","unit":"KILOGRAMO","cost":3000,"taxRate":0,
                 "presentations":[{"name":"Kilo","factor":1,"price":4000,"isBase":true}]}""").expect(201).json().get("id").asLong();
        long taxed = api.post("/api/products", owner, """
                {"name":"Gaseosa","unit":"UNIDAD","cost":2000,"taxRate":19,
                 "presentations":[{"name":"Unidad","factor":1,"price":3000,"isBase":true}]}""").expect(201).json().get("id").asLong();

        JsonNode pending = api.get("/api/fiscal/products?pending=true", owner).expect(200).json();
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).get("id").asLong()).isEqualTo(zero);
        assertThat(pending.get(0).get("unitCode").asText()).isEqualTo("KGM");

        JsonNode res = api.post("/api/fiscal/products/classify", owner,
                "{\"productIds\":[%d,%d],\"taxClass\":\"EXCLUIDO\"}".formatted(zero, taxed)).expect(200).json();
        assertThat(res.get("changed").asInt()).isEqualTo(1);
        assertThat(res.get("skippedTaxed").asInt()).isEqualTo(1);
        assertThat(api.get("/api/fiscal/products?pending=true", owner).json()).isEmpty();
        api.post("/api/fiscal/products/classify", owner, "{\"productIds\":[%d],\"taxClass\":\"GRAVADO\"}".formatted(zero)).expect(400);

        // También se clasifica desde el formulario del producto; con IVA > 0 se ignora (es gravado).
        JsonNode exempt = api.post("/api/products", owner, """
                {"name":"Cuaderno","unit":"UNIDAD","cost":1000,"taxRate":0,"taxClass":"EXENTO",
                 "presentations":[{"name":"Unidad","factor":1,"price":2000,"isBase":true}]}""").expect(201).json();
        assertThat(exempt.get("taxClass").asText()).isEqualTo("EXENTO");
        JsonNode withVat = api.post("/api/products", owner, """
                {"name":"Jabón","unit":"UNIDAD","cost":1000,"taxRate":19,"taxClass":"EXENTO",
                 "presentations":[{"name":"Unidad","factor":1,"price":2000,"isBase":true}]}""").expect(201).json();
        assertThat(withVat.path("taxClass").isMissingNode() || withVat.path("taxClass").isNull()).isTrue();
        assertThat(api.get("/api/fiscal/products?pending=true", owner).json()).isEmpty();
    }

    @Test
    void readinessListsWhatIsMissingUntilEverythingIsDone() throws Exception {
        newShop();
        JsonNode before = api.get("/api/fiscal/readiness", owner).expect(200).json();
        assertThat(before.get("ready").asBoolean()).isFalse();
        assertThat(stepStatus(before, "PRUEBA")).isEqualTo("PENDIENTE");

        api.put("/api/fiscal/profile", owner, COMPLETE_PROFILE.replace("}",
                ",\"registeredAsIssuer\":true,\"providerAuthorized\":true,\"providerName\":\"Proveedor de prueba\"}")).expect(200);
        api.post("/api/fiscal/resolutions", owner, resolution("POS_ELECTRONICO", "POS", 1, 1000, today.plusYears(1))).expect(200);

        JsonNode after = api.get("/api/fiscal/readiness", owner).expect(200).json();
        assertThat(stepStatus(after, "DATOS")).isEqualTo("OK");
        assertThat(stepStatus(after, "INSCRIPCION")).isEqualTo("OK");
        assertThat(stepStatus(after, "PROVEEDOR")).isEqualTo("OK");
        assertThat(stepStatus(after, "RESOLUCION")).isEqualTo("OK");
        // Falta el documento de prueba (Fase 13B): todavía no está lista.
        assertThat(stepStatus(after, "PRUEBA")).isEqualTo("PENDIENTE");
        assertThat(after.get("ready").asBoolean()).isFalse();
    }

    @Test
    void cashiersCannotConfigureAndStoresAreIsolated() throws Exception {
        newShop();
        String cashier = newEmployee(store, "CAJERO");
        api.get("/api/fiscal/profile", cashier).expect(403);
        long id = api.post("/api/fiscal/resolutions", owner, resolution("FACTURA", "FE", 1, 100, today.plusYears(1))).expect(200).json().get("id").asLong();

        String otherOwner = newStore().ownerToken();
        api.put("/api/fiscal/resolutions/" + id, otherOwner, resolution("FACTURA", "FE", 1, 100, today.plusYears(1))).expect(404);
        assertThat(api.get("/api/fiscal/resolutions", otherOwner).json()).isEmpty();
    }

    @Test
    void customersWithNitNeedTheRightVerificationDigit() throws Exception {
        newShop();
        api.post("/api/pos/customers", owner, """
                {"name":"Empresa X","docType":"NIT","docNumber":"800197268","dv":"9","personType":"JURIDICA"}""").expect(400);
        JsonNode ok = api.post("/api/pos/customers", owner, """
                {"name":"Empresa X","docType":"NIT","docNumber":"800.197.268","dv":"4","personType":"JURIDICA",
                 "invoiceEmail":"compras@x.com","fiscalResponsibilities":["R-99-PN"]}""").expect(201).json();
        assertThat(ok.get("docNumber").asText()).isEqualTo("800197268");
        assertThat(ok.get("fiscalMissing")).isEmpty();

        JsonNode partial = api.post("/api/pos/customers", owner, "{\"name\":\"Don José\"}").expect(201).json();
        assertThat(partial.get("fiscalMissing").toString()).contains("tipo de documento");
    }

    @Test
    void anExpiringResolutionRaisesAnAlert() throws Exception {
        newShop();
        api.post("/api/fiscal/resolutions", owner, resolution("POS_ELECTRONICO", "POS", 1, 1000, today.plusDays(5))).expect(200);
        api.post("/api/notifications/scan", owner, null).expect(200);
        List<String> types = new ArrayList<>();
        for (JsonNode n : api.get("/api/notifications", owner).expect(200).json().get("items")) types.add(n.get("type").asText());
        assertThat(types).contains("RESOLUCION_POR_VENCER");
    }

    private static String stepStatus(JsonNode readiness, String key) {
        for (JsonNode s : readiness.get("steps")) if (s.get("key").asText().equals(key)) return s.get("status").asText();
        return null;
    }
}
