package com.tiendaposs.einvoice;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.support.AbstractIntegrationTest;
import com.tiendaposs.support.Api;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Fase 16A: modo fiscal, cliente con y sin factura, refacturar y fiado como crédito en la factura. */
class InvoicingModesIT extends AbstractIntegrationTest {

    Api api;
    Store store;
    String owner;
    long pres;
    final LocalDate today = LocalDate.now(ZoneId.of("America/Bogota"));

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, json);
        store = newStoreWithEinvoice();
        owner = store.ownerToken();
        api.put("/api/settings/pos/finance", owner,
                "{\"verifyDigitalPayments\":false,\"creditEnabled\":true,\"defaultCreditLimit\":1000000,\"defaultCreditDays\":15}").expect(200);
        pres = api.post("/api/products", owner, """
                {"name":"Gaseosa","unit":"UNIDAD","cost":2000,"taxRate":19,"initialStock":100,
                 "presentations":[{"name":"Unidad","factor":1,"price":3000,"isBase":true}]}""").expect(201).json()
                .get("presentations").get(0).get("id").asLong();
        long reg = api.get("/api/registers", owner).json().get(0).get("id").asLong();
        api.post("/api/shifts/open", owner, "{\"registerId\":%d,\"openingCash\":0}".formatted(reg)).expect(200);
        api.put("/api/fiscal/profile", owner, """
                {"personType":"JURIDICA","idType":"NIT","idNumber":"800197268","dv":"4","legalName":"Tienda Prueba SAS",
                 "ivaResponsible":true,"responsibilities":["O-15"],"address":"Calle 1","municipalityCode":"11001",
                 "municipalityName":"Bogotá","invoicingEmail":"facturas@prueba.com","registeredAsIssuer":true,
                 "providerAuthorized":true,"providerName":"Simulador"}""").expect(200);
        for (String[] r : new String[][]{{"POS_ELECTRONICO", "POS"}, {"FACTURA", "FE"}}) {
            api.post("/api/fiscal/resolutions", owner, """
                    {"documentType":"%s","resolutionNumber":"18761","prefix":"%s","rangeFrom":1,"rangeTo":1000,
                     "validFrom":"%s","validTo":"%s"}""".formatted(r[0], r[1], today.minusDays(5), today.plusYears(1))).expect(200);
        }
        api.post("/api/einvoice/test", owner, null).expect(200);
        api.post("/api/einvoice/enable", owner, "{\"enabled\":true}").expect(200);
    }

    private long customer(String name, String preference) throws Exception {
        return api.post("/api/pos/customers", owner, """
                {"name":"%s","docType":"CC","docNumber":"%d","personType":"NATURAL","invoiceEmail":"cliente@correo.com",
                 "phone":"3001234567","invoicePreference":"%s"}"""
                .formatted(name, 10_000_000 + (long) (Math.random() * 80_000_000), preference)).expect(201).json().get("id").asLong();
    }

    private Api.Res sell(String token, Long customerId, boolean invoice, String method, String approvals) throws Exception {
        String cust = customerId == null ? "" : ",\"customer\":{\"customerId\":%d}".formatted(customerId);
        String appr = approvals == null ? "" : ",\"approvals\":[\"%s\"]".formatted(approvals);
        return api.post("/api/pos/sales", token, """
                {"clientRef":"%s","lines":[{"presentationId":%d,"quantity":2}],"payments":[{"method":"%s","amount":6000}]%s%s,
                 "electronicInvoice":%s}""".formatted(UUID.randomUUID(), pres, method, cust, appr, invoice));
    }

    private JsonNode docOf(JsonNode sale) throws Exception {
        JsonNode d = sale.path("electronicDocument");
        if (d.isMissingNode() || d.isNull()) return null;
        return api.get("/api/einvoice/documents/" + d.get("id").asLong(), owner).expect(200).json().get("document");
    }

    @Test
    void onDemandModeOnlyInvoicesWhenTheCustomerAsks() throws Exception {
        assertThat(api.post("/api/einvoice/mode", owner, "{\"mode\":\"A_PEDIDO\"}").expect(200).json().get("mode").asText()).isEqualTo("A_PEDIDO");
        assertThat(api.get("/api/auth/me", owner).json().get("invoicingMode").asText()).isEqualTo("A_PEDIDO");

        // Venta común: comprobante interno, no gasta documento.
        assertThat(docOf(sell(owner, null, false, "EFECTIVO", null).expect(201).json())).isNull();
        // El cliente la pide: factura a su nombre.
        JsonNode inv = docOf(sell(owner, customer("Ana", "PREGUNTAR"), true, "EFECTIVO", null).expect(201).json());
        assertThat(inv.get("documentType").asText()).isEqualTo("FACTURA");
        assertThat(inv.get("customerName").asText()).isEqualTo("Ana");

        // Modo obligado: toda venta lleva documento (consumidor final si no la pide).
        api.post("/api/einvoice/mode", owner, "{\"mode\":\"OBLIGADO\"}").expect(200);
        JsonNode cf = docOf(sell(owner, null, false, "EFECTIVO", null).expect(201).json());
        assertThat(cf.get("customerName").asText()).isEqualTo("Consumidor final");
        api.post("/api/einvoice/mode", owner, "{\"mode\":\"OTRO\"}").expect(400);
    }

    @Test
    void aCustomerWhoAlwaysAsksIsInvoicedAndSkippingItNeedsAPin() throws Exception {
        long siempre = customer("Ferretería Siempre", "SIEMPRE");
        String cashier = newEmployee(store, "CAJERO");
        long reg2 = api.post("/api/registers", owner, "{\"name\":\"Caja 2\"}").expect(200).json().get("id").asLong();
        api.post("/api/shifts/open", cashier, "{\"registerId\":%d,\"openingCash\":0}".formatted(reg2)).expect(200);

        // El cajero no marcó la factura: se pide autorización de un supervisor.
        JsonNode denied = sell(cashier, siempre, false, "EFECTIVO", null).expect(403).json();
        assertThat(denied.get("fields").get("approval").asText()).isEqualTo("FACTURA_OMITIR");

        // Con PIN del dueño se cobra sin factura (a consumidor final en modo obligado) y queda quién autorizó.
        api.post("/api/auth/pin", owner, "{\"currentPassword\":\"Clave1234\",\"pin\":\"4826\"}").expect(204);
        long ownerId = api.get("/api/auth/me", owner).json().get("id").asLong();
        String token = api.post("/api/pos/approvals", cashier, "{\"approverId\":%d,\"secret\":\"4826\",\"permission\":\"FACTURA_OMITIR\"}"
                .formatted(ownerId)).expect(200).json().get("token").asText();
        JsonNode skipped = docOf(sell(cashier, siempre, false, "EFECTIVO", token).expect(201).json());
        assertThat(skipped.get("customerName").asText()).isEqualTo("Consumidor final");
        assertThat(api.get("/api/audit?limit=20", owner).json().toString()).contains("FACTURA_OMITIDA");

        // Marcado: factura a su nombre.
        JsonNode inv = docOf(sell(cashier, siempre, true, "EFECTIVO", null).expect(201).json());
        assertThat(inv.get("documentType").asText()).isEqualTo("FACTURA");

        // "Nunca": no se le factura a su nombre.
        long nunca = customer("Vecino Nunca", "NUNCA");
        sell(owner, nunca, true, "EFECTIVO", null).expect(400);
        assertThat(docOf(sell(owner, nunca, false, "EFECTIVO", null).expect(201).json()).get("customerName").asText()).isEqualTo("Consumidor final");
    }

    @Test
    void aFinalConsumerSaleIsReinvoicedWithACreditNoteAndANewInvoice() throws Exception {
        JsonNode sale = sell(owner, null, false, "EFECTIVO", null).expect(201).json();
        JsonNode first = docOf(sale);
        assertThat(first.get("status").asText()).isEqualTo("ACEPTADO");
        long saleId = sale.get("id").asLong();
        long ana = customer("Ana Pérez", "PREGUNTAR");

        JsonNode inv = api.post("/api/einvoice/sales/" + saleId + "/reinvoice", owner, "{\"customerId\":%d}".formatted(ana)).expect(200).json();
        assertThat(inv.get("documentType").asText()).isEqualTo("FACTURA");
        assertThat(inv.get("customerName").asText()).isEqualTo("Ana Pérez");
        assertThat(inv.get("status").asText()).isEqualTo("ACEPTADO");

        JsonNode old = api.get("/api/einvoice/documents/" + first.get("id").asLong(), owner).json();
        assertThat(old.get("document").get("superseded").asBoolean()).isTrue();
        assertThat(old.get("document").get("replacedByNumber").asText()).isEqualTo(inv.get("fullNumber").asText());
        JsonNode note = old.get("notes").get(0);
        assertThat(note.get("documentType").asText()).isEqualTo("NOTA_CREDITO");
        assertThat(note.get("customerName").asText()).isEqualTo("Consumidor final"); // la nota va a quien recibió el documento
        assertThat(note.get("status").asText()).isEqualTo("ACEPTADO");

        // El comprobante de la venta ya muestra la factura nueva; no se puede refacturar al mismo cliente.
        assertThat(api.get("/api/pos/sales/" + saleId, owner).json().get("electronicDocument").get("fullNumber").asText())
                .isEqualTo(inv.get("fullNumber").asText());
        api.post("/api/einvoice/sales/" + saleId + "/reinvoice", owner, "{\"customerId\":%d}".formatted(ana)).expect(400);
        // Y anular la venta anula la factura vigente (no la reemplazada).
        String pin = "4826";
        api.post("/api/auth/pin", owner, "{\"currentPassword\":\"Clave1234\",\"pin\":\"%s\"}".formatted(pin)).expect(204);
        api.post("/api/pos/sales/" + saleId + "/void", owner, "{\"reason\":\"Error de cobro\"}").expect(200);
        JsonNode invDetail = api.get("/api/einvoice/documents/" + inv.get("id").asLong(), owner).json();
        assertThat(invDetail.get("notes").size()).isEqualTo(1);
    }

    @Test
    void theReceiptDesignKeepsTheInvoiceLockAndShowsTheFiadoBalance() throws Exception {
        String profile = """
                {"preset":"MINIMO","productNames":false,"quantities":false,"unitPrice":false,"lineTotal":false,"taxes":false,
                 "customer":false,"legalData":false,"saleNumber":false,"dateTime":false,"groupAsVarios":true,"creditBalance":true}""";
        String body = """
                {"config":{"printMode":"SIEMPRE","copies":2,"fontSize":"L","returnPolicy":"Cambios hasta 8 días",
                 "profiles":{"INTERNO":%s,"FACTURA":%s,"PRECUENTA":%s,"FIADO":%s}}}""".formatted(profile, profile, profile, profile);
        JsonNode saved = api.put("/api/settings/pos/receipt", owner, body).expect(200).json();
        JsonNode cfg = json.readTree(saved.get("receiptConfig").asText());
        assertThat(cfg.get("profiles").get("INTERNO").get("productNames").asBoolean()).isFalse();
        // La factura impresa no puede quitar el detalle, el comprador ni los impuestos.
        JsonNode inv = cfg.get("profiles").get("FACTURA");
        assertThat(inv.get("productNames").asBoolean()).isTrue();
        assertThat(inv.get("taxes").asBoolean()).isTrue();
        assertThat(inv.get("customer").asBoolean()).isTrue();
        assertThat(inv.get("groupAsVarios").asBoolean()).isFalse();
        api.put("/api/settings/pos/receipt", owner, body.replace("\"copies\":2", "\"copies\":9")).expect(400);
        api.put("/api/settings/pos/receipt", owner, body.replace("\"fontSize\":\"L\"", "\"fontSize\":\"L\",\"otra\":1")).expect(400);

        long ana = customer("Ana Recibo", "PREGUNTAR");
        long saleId = sell(owner, ana, false, "FIADO", null).expect(201).json().get("id").asLong();
        JsonNode receipt = api.get("/api/pos/sales/" + saleId + "/receipt", owner).expect(200).json();
        assertThat(receipt.get("config").get("printMode").asText()).isEqualTo("SIEMPRE");
        assertThat(receipt.get("creditBalance").decimalValue()).isEqualByComparingTo("6000");
    }

    @Test
    void aFiadoGoesOnTheInvoiceAsCreditWithItsDueDate() throws Exception {
        long ana = customer("Ana Fiado", "PREGUNTAR");
        JsonNode inv = docOf(sell(owner, ana, true, "FIADO", null).expect(201).json());
        assertThat(inv.get("paymentForm").asText()).isEqualTo("CREDITO");
        assertThat(inv.get("dueDate").asText()).isEqualTo(today.plusDays(15).toString());
        JsonNode cash = docOf(sell(owner, ana, true, "EFECTIVO", null).expect(201).json());
        assertThat(cash.get("paymentForm").asText()).isEqualTo("CONTADO");
        assertThat(cash.path("dueDate").isMissingNode() || cash.path("dueDate").isNull()).isTrue();
    }
}
