package com.tiendaposs.einvoice;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.support.AbstractIntegrationTest;
import com.tiendaposs.support.Api;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Fase 13B: emisión con el proveedor simulado. En pruebas la cola corre en el mismo hilo al confirmar la transacción. */
class EInvoiceIT extends AbstractIntegrationTest {

    @Autowired
    SimulatedProvider simulator;

    Api api;
    String owner;
    long taxed;      // presentación de un producto con IVA 19 %
    long zeroTax;    // presentación de un producto con IVA 0 % (se clasifica en algunas pruebas)
    long zeroTaxProduct;
    long shiftId;
    final LocalDate today = LocalDate.now(ZoneId.of("America/Bogota"));

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, json);
        owner = newStore().ownerToken();
        taxed = api.post("/api/products", owner, """
                {"name":"Gaseosa","unit":"UNIDAD","cost":2000,"taxRate":19,"initialStock":100,
                 "presentations":[{"name":"Unidad","factor":1,"price":3000,"isBase":true}]}""").expect(201).json()
                .get("presentations").get(0).get("id").asLong();
        JsonNode z = api.post("/api/products", owner, """
                {"name":"Arroz","unit":"UNIDAD","cost":2000,"taxRate":0,"taxClass":"EXCLUIDO","initialStock":100,
                 "presentations":[{"name":"Libra","factor":1,"price":2500,"isBase":true}]}""").expect(201).json();
        zeroTax = z.get("presentations").get(0).get("id").asLong();
        zeroTaxProduct = z.get("id").asLong();
        long reg = api.get("/api/registers", owner).json().get(0).get("id").asLong();
        shiftId = api.post("/api/shifts/open", owner, "{\"registerId\":%d,\"openingCash\":0}".formatted(reg)).expect(200).json().get("id").asLong();
    }

    private void readyAndEnabled() throws Exception {
        api.put("/api/fiscal/profile", owner, """
                {"personType":"JURIDICA","idType":"NIT","idNumber":"800197268","dv":"4","legalName":"Tienda Prueba SAS",
                 "ivaResponsible":true,"responsibilities":["O-15"],"address":"Calle 1","municipalityCode":"11001",
                 "municipalityName":"Bogotá","invoicingEmail":"facturas@prueba.com","registeredAsIssuer":true,
                 "providerAuthorized":true,"providerName":"Simulador"}""").expect(200);
        resolution("POS_ELECTRONICO", "POS", 1, 1000);
        resolution("FACTURA", "FE", 1, 1000);
        api.post("/api/einvoice/enable", owner, "{\"enabled\":true}").expect(400); // falta el documento de prueba
        JsonNode test = api.post("/api/einvoice/test", owner, null).expect(200).json();
        assertThat(test.get("document").get("status").asText()).isEqualTo("ACEPTADO");
        assertThat(api.post("/api/einvoice/enable", owner, "{\"enabled\":true}").expect(200).json().get("enabled").asBoolean()).isTrue();
    }

    private void resolution(String type, String prefix, long from, long to) throws Exception {
        api.post("/api/fiscal/resolutions", owner, """
                {"documentType":"%s","resolutionNumber":"1876%d","prefix":"%s","rangeFrom":%d,"rangeTo":%d,
                 "validFrom":"%s","validTo":"%s"}""".formatted(type, from, prefix, from, to, today.minusDays(5), today.plusYears(1))).expect(200);
    }

    private long customer(String name) throws Exception {
        return api.post("/api/pos/customers", owner, """
                {"name":"%s","docType":"CC","docNumber":"%d","personType":"NATURAL","invoiceEmail":"cliente@correo.com"}"""
                .formatted(name, 10_000_000 + (long) (Math.random() * 80_000_000))).expect(201).json().get("id").asLong();
    }

    private JsonNode sell(long pres, Long customerId, boolean invoice) throws Exception {
        String cust = customerId == null ? "" : ",\"customer\":{\"customerId\":%d}".formatted(customerId);
        return api.post("/api/pos/sales", owner, """
                {"clientRef":"%s","lines":[{"presentationId":%d,"quantity":2}],"payments":[{"method":"EFECTIVO","amount":10000}]%s,
                 "electronicInvoice":%s}""".formatted(UUID.randomUUID(), pres, cust, invoice)).expect(201).json();
    }

    /** El documento de la venta, consultado después del cobro (la respuesta del cobro se arma antes del envío). */
    private JsonNode doc(JsonNode sale) throws Exception {
        long id = sale.get("electronicDocument").get("id").asLong();
        return api.get("/api/einvoice/documents/" + id, owner).expect(200).json().get("document");
    }

    @Test
    void withoutActivationSalesHaveNoElectronicDocument() throws Exception {
        JsonNode s = sell(taxed, null, false);
        assertThat(s.path("electronicDocument").isMissingNode() || s.path("electronicDocument").isNull()).isTrue();
    }

    @Test
    void aSaleIssuesAPosDocumentAndAnInvoiceWhenTheCustomerAsksForIt() throws Exception {
        readyAndEnabled();
        JsonNode pos = doc(sell(taxed, null, false));
        assertThat(pos.get("documentType").asText()).isEqualTo("POS_ELECTRONICO");
        assertThat(pos.get("status").asText()).isEqualTo("ACEPTADO");
        assertThat(pos.get("fullNumber").asText()).isEqualTo("POS2"); // el 1 fue el documento de prueba
        assertThat(pos.get("cufe").asText()).hasSize(96);
        assertThat(pos.get("simulated").asBoolean()).isTrue();
        assertThat(pos.get("customerName").asText()).isEqualTo("Consumidor final");

        // Factura sin datos del cliente: la venta no se hace y se explica qué falta.
        long incomplete = api.post("/api/pos/customers", owner, "{\"name\":\"Sin datos\"}").expect(201).json().get("id").asLong();
        api.post("/api/pos/sales", owner, """
                {"clientRef":"%s","lines":[{"presentationId":%d,"quantity":1}],"payments":[{"method":"EFECTIVO","amount":5000}],
                 "customer":{"customerId":%d},"electronicInvoice":true}""".formatted(UUID.randomUUID(), taxed, incomplete)).expect(400);

        JsonNode inv = doc(sell(taxed, customer("Ana Pérez"), true));
        assertThat(inv.get("documentType").asText()).isEqualTo("FACTURA");
        assertThat(inv.get("status").asText()).isEqualTo("ACEPTADO");
        assertThat(inv.get("fullNumber").asText()).isEqualTo("FE1");
    }

    @Test
    void aLostResponseIsQueriedBeforeRetryingSoNothingIsIssuedTwice() throws Exception {
        readyAndEnabled();
        JsonNode d = doc(sell(taxed, customer("Cliente SIM-CAIDA"), true));
        assertThat(d.get("status").asText()).isEqualTo("DESCONOCIDO");
        long id = d.get("id").asLong();

        JsonNode after = api.post("/api/einvoice/documents/" + id + "/retry", owner, null).expect(200).json();
        assertThat(after.get("status").asText()).isEqualTo("ACEPTADO");
        String ref = api.get("/api/einvoice/documents/" + id, owner).json().get("document").get("id").asText();
        assertThat(ref).isEqualTo(String.valueOf(id));
        JsonNode detail = api.get("/api/einvoice/documents/" + id, owner).json();
        assertThat(detail.get("events").toString()).contains("SIN_RESPUESTA").contains("CONSULTA");
        assertThat(simulator.timesSent(externalRef(id))).isEqualTo(1);
    }

    @Test
    void aRejectionIsCorrectedAndRetriedWithTheSameNumber() throws Exception {
        readyAndEnabled();
        long cust = customer("Cliente SIM-RECHAZO");
        JsonNode d = doc(sell(taxed, cust, true));
        assertThat(d.get("status").asText()).isEqualTo("RECHAZADO");
        assertThat(d.get("lastErrorCode").asText()).isEqualTo("SIM-FAJ43");
        String number = d.get("fullNumber").asText();

        api.put("/api/pos/customers/" + cust, owner, """
                {"name":"Cliente corregido","docType":"CC","docNumber":"12345678","personType":"NATURAL","invoiceEmail":"c@c.com"}""").expect(200);
        JsonNode fixed = api.post("/api/einvoice/documents/" + d.get("id").asLong() + "/retry", owner, null).expect(200).json();
        assertThat(fixed.get("status").asText()).isEqualTo("ACEPTADO");
        assertThat(fixed.get("fullNumber").asText()).isEqualTo(number);
    }

    @Test
    void aSlowProviderLeavesItInProcessAndTheQueryAcceptsIt() throws Exception {
        readyAndEnabled();
        JsonNode d = doc(sell(taxed, customer("Cliente SIM-DEMORA"), true));
        assertThat(d.get("status").asText()).isEqualTo("EN_PROCESO");
        JsonNode after = api.post("/api/einvoice/documents/" + d.get("id").asLong() + "/retry", owner, null).expect(200).json();
        assertThat(after.get("status").asText()).isEqualTo("ACEPTADO");
    }

    @Test
    void voidsAndReturnsGenerateCreditNotesLinkedToTheOriginal() throws Exception {
        readyAndEnabled();
        JsonNode sale = sell(taxed, null, false);
        long original = doc(sale).get("id").asLong();
        api.post("/api/pos/sales/" + sale.get("id").asLong() + "/void", owner, "{\"reason\":\"Error del cajero\"}").expect(200);
        JsonNode notes = api.get("/api/einvoice/documents/" + original, owner).json().get("notes");
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0).get("documentType").asText()).isEqualTo("NOTA_CREDITO");
        assertThat(notes.get(0).get("status").asText()).isEqualTo("ACEPTADO");
        assertThat(notes.get(0).get("total").decimalValue()).isEqualByComparingTo(sale.get("total").decimalValue());

        JsonNode sale2 = sell(taxed, null, false);
        long line = sale2.get("lines").get(0).get("id").asLong();
        api.post("/api/pos/sales/" + sale2.get("id").asLong() + "/returns", owner, """
                {"reason":"Cambio","refundMethod":"EFECTIVO","lines":[{"saleLineId":%d,"quantity":1,"restock":true}]}""".formatted(line)).expect(200);
        JsonNode notes2 = api.get("/api/einvoice/documents/" + doc(sale2).get("id").asLong(), owner).json().get("notes");
        assertThat(notes2).hasSize(1);
        assertThat(notes2.get(0).get("total").decimalValue()).isEqualByComparingTo("3000");
        assertThat(notes2.get(0).get("originalNumber").asText()).isEqualTo(doc(sale2).get("fullNumber").asText());
    }

    @Test
    void missingTaxClassificationBlocksBeforeSendingAndRetryWorksAfterFixing() throws Exception {
        readyAndEnabled();
        // Se le quita la clasificación al producto con IVA 0 después de activar.
        api.put("/api/products/" + zeroTaxProduct, owner, """
                {"name":"Arroz","unit":"UNIDAD","taxRate":0,"taxClass":"",
                 "presentations":[{"id":%d,"name":"Libra","factor":1,"price":2500,"isBase":true}]}""".formatted(zeroTax)).expect(200);
        JsonNode d = doc(sell(zeroTax, null, false));
        assertThat(d.get("status").asText()).isEqualTo("BLOQUEADO");
        assertThat(d.get("lastErrorCode").asText()).isEqualTo("VAL_PRODUCTO");
        assertThat(d.get("help").get("fixTab").asText()).isEqualTo("productos");
        assertThat(d.get("help").get("action").asText()).isEqualTo("RETRY");
        assertThat(d.get("lastErrorMessage").asText()).contains("Arroz");
        int sentBefore = simulator.timesSent(externalRef(d.get("id").asLong()));
        assertThat(sentBefore).isZero();

        api.post("/api/fiscal/products/classify", owner, "{\"productIds\":[%d],\"taxClass\":\"EXCLUIDO\"}".formatted(zeroTaxProduct)).expect(200);
        assertThat(api.post("/api/einvoice/documents/" + d.get("id").asLong() + "/retry", owner, null).expect(200).json()
                .get("status").asText()).isEqualTo("ACEPTADO");
    }

    @Test
    void anOfflineSaleIsNeverRejectedAndKeepsItsRealDate() throws Exception {
        readyAndEnabled();
        long incomplete = api.post("/api/pos/customers", owner, "{\"name\":\"Sin datos\"}").expect(201).json().get("id").asLong();
        Instant at = Instant.now().minus(2, ChronoUnit.HOURS);
        JsonNode s = api.post("/api/pos/sales", owner, """
                {"clientRef":"%s","offline":true,"offlineShiftId":%d,"offlineCreatedAt":"%s","customer":{"customerId":%d},
                 "electronicInvoice":true,"lines":[{"presentationId":%d,"quantity":1}],"payments":[{"method":"EFECTIVO","amount":5000}]}"""
                .formatted(UUID.randomUUID(), shiftId, at, incomplete, taxed)).expect(201).json();
        JsonNode d = doc(s);
        assertThat(d.get("documentType").asText()).isEqualTo("FACTURA");
        assertThat(d.get("status").asText()).isEqualTo("BLOQUEADO");
        assertThat(Instant.parse(d.get("issueDate").asText())).isBefore(Instant.now().minus(1, ChronoUnit.HOURS));
    }

    @Test
    void theSameSaleSentTwiceKeepsOneDocument() throws Exception {
        readyAndEnabled();
        String ref = UUID.randomUUID().toString();
        String body = """
                {"clientRef":"%s","lines":[{"presentationId":%d,"quantity":1}],"payments":[{"method":"EFECTIVO","amount":5000}]}"""
                .formatted(ref, taxed);
        JsonNode a = api.post("/api/pos/sales", owner, body).expect(201).json();
        JsonNode b = api.call("POST", "/api/pos/sales", owner, body).json();
        assertThat(doc(b).get("id").asLong()).isEqualTo(doc(a).get("id").asLong());
        assertThat(api.get("/api/einvoice/documents", owner).json()).hasSize(2); // prueba + venta
    }

    @Test
    void theSimulatorIsNeverEnabledInProduction() throws Exception {
        readyAndEnabled();
        api.put("/api/fiscal/profile", owner, """
                {"personType":"JURIDICA","idType":"NIT","idNumber":"800197268","dv":"4","legalName":"Tienda Prueba SAS",
                 "ivaResponsible":true,"responsibilities":["O-15"],"address":"Calle 1","municipalityCode":"11001",
                 "invoicingEmail":"facturas@prueba.com","registeredAsIssuer":true,"providerAuthorized":true,
                 "providerName":"Simulador","environment":"PRODUCCION"}""").expect(200);
        api.post("/api/einvoice/enable", owner, "{\"enabled\":true}").expect(400);
    }

    @Test
    void anExhaustedRangeBlocksWithAClearReason() throws Exception {
        api.put("/api/fiscal/profile", owner, """
                {"personType":"JURIDICA","idType":"NIT","idNumber":"800197268","dv":"4","legalName":"Tienda Prueba SAS",
                 "ivaResponsible":true,"responsibilities":["O-15"],"address":"Calle 1","municipalityCode":"11001",
                 "invoicingEmail":"facturas@prueba.com","registeredAsIssuer":true,"providerAuthorized":true,"providerName":"Simulador"}""").expect(200);
        resolution("POS_ELECTRONICO", "POS", 1, 2);   // dos números: la prueba y una venta
        api.post("/api/einvoice/test", owner, null).expect(200);
        api.post("/api/einvoice/enable", owner, "{\"enabled\":true}").expect(200);
        assertThat(doc(sell(taxed, null, false)).get("status").asText()).isEqualTo("ACEPTADO");
        JsonNode d = doc(sell(taxed, null, false));
        assertThat(d.get("status").asText()).isEqualTo("BLOQUEADO");
        assertThat(d.get("lastErrorCode").asText()).isEqualTo("CONFIG_RESOLUCION");
    }

    private String externalRef(long documentId) throws Exception {
        JsonNode d = api.get("/api/einvoice/documents/" + documentId, owner).json().get("document");
        long tenant = 0;
        // T{tenant}-S{sale}-{tipo}: se reconstruye con los datos que expone el documento.
        String me = api.get("/api/auth/me", owner).json().get("tenant").get("id").asText();
        tenant = Long.parseLong(me);
        return "T" + tenant + "-S" + d.get("saleId").asLong() + "-" + d.get("documentType").asText();
    }

    @Test
    void problemsCanBeRetriedInBulkAndRaiseAnAlert() throws Exception {
        readyAndEnabled();
        long c1 = customer("Uno SIM-RECHAZO");
        long c2 = customer("Dos SIM-RECHAZO");
        long d1 = doc(sell(taxed, c1, true)).get("id").asLong();
        long d2 = doc(sell(taxed, c2, true)).get("id").asLong();
        JsonNode one = api.get("/api/einvoice/documents/" + d1, owner).json().get("document");
        assertThat(one.get("status").asText()).isEqualTo("RECHAZADO");
        assertThat(one.get("help").get("title").asText()).contains("Rechazado");

        api.post("/api/notifications/scan", owner, null).expect(200);
        assertThat(api.get("/api/notifications", owner).json().get("items").toString()).contains("DOCUMENTOS_CON_ERROR");

        for (long c : new long[]{c1, c2}) {
            api.put("/api/pos/customers/" + c, owner, """
                    {"name":"Corregido %d","docType":"CC","docNumber":"%d","personType":"NATURAL","invoiceEmail":"c@c.com"}"""
                    .formatted(c, 30_000_000 + c)).expect(200);
        }
        JsonNode bulk = api.post("/api/einvoice/documents/retry", owner, "{\"ids\":[%d,%d]}".formatted(d1, d2)).expect(200).json();
        assertThat(bulk.get("accepted").asInt()).isEqualTo(2);
        assertThat(bulk.get("stillWithProblems").asInt()).isZero();
        api.post("/api/einvoice/documents/retry", owner, "{\"ids\":[]}").expect(400);
    }
}
