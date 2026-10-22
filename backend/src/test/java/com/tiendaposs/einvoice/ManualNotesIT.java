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

/** Fase 16E: nota débito (intereses de un fiado), nota crédito manual y sus efectos en cartera y contabilidad. */
class ManualNotesIT extends AbstractIntegrationTest {

    Api api;
    String owner;
    long pres;
    long ana;
    final LocalDate today = LocalDate.now(ZoneId.of("America/Bogota"));

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, json);
        owner = newStore().ownerToken();
        api.put("/api/settings/pos/finance", owner,
                "{\"verifyDigitalPayments\":false,\"creditEnabled\":true,\"defaultCreditLimit\":1000000,\"defaultCreditDays\":15}").expect(200);
        pres = api.post("/api/products", owner, """
                {"name":"Mercado","unit":"UNIDAD","cost":50000,"taxRate":19,"initialStock":100,
                 "presentations":[{"name":"Unidad","factor":1,"price":119000,"isBase":true}]}""").expect(201).json()
                .get("presentations").get(0).get("id").asLong();
        ana = api.post("/api/pos/customers", owner, """
                {"name":"Ana Pérez","docType":"CC","docNumber":"52123456","personType":"NATURAL","invoiceEmail":"ana@correo.com","phone":"3001112233"}""")
                .expect(201).json().get("id").asLong();
        long reg = api.get("/api/registers", owner).json().get(0).get("id").asLong();
        api.post("/api/shifts/open", owner, "{\"registerId\":%d,\"openingCash\":0}".formatted(reg)).expect(200);
    }

    private void enableInvoicing() throws Exception {
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

    private JsonNode sellFiado(boolean invoice) throws Exception {
        return api.post("/api/pos/sales", owner, """
                {"clientRef":"%s","lines":[{"presentationId":%d,"quantity":1}],"payments":[{"method":"FIADO","amount":119000}],
                 "customer":{"customerId":%d},"electronicInvoice":%s}""".formatted(UUID.randomUUID(), pres, ana, invoice)).expect(201).json();
    }

    private double balance() throws Exception {
        return api.get("/api/credit/" + ana, owner).expect(200).json().get("balance").asDouble();
    }

    @Test
    void interestOnAFiadoWithoutInvoicingIsJustACharge() throws Exception {
        long saleId = sellFiado(false).get("id").asLong();
        JsonNode r = api.post("/api/credit/" + ana + "/interest", owner, "{\"saleId\":%d,\"amount\":5000}".formatted(saleId)).expect(200).json();
        assertThat(r.path("debitNoteNumber").isMissingNode() || r.path("debitNoteNumber").isNull()).isTrue();
        assertThat(balance()).isEqualTo(124000);
    }

    @Test
    void debitAndCreditNotesMoveTheFiadoAndTheBooks() throws Exception {
        api.post("/api/accounting/enable", owner, "{\"enabled\":true}").expect(200);
        enableInvoicing();
        JsonNode sale = sellFiado(true);
        long saleId = sale.get("id").asLong();
        long invoiceId = sale.get("electronicDocument").get("id").asLong();
        JsonNode inv = api.get("/api/einvoice/documents/" + invoiceId, owner).json().get("document");
        assertThat(inv.get("status").asText()).isEqualTo("ACEPTADO");
        assertThat(inv.get("remaining").decimalValue()).isEqualByComparingTo("119000");

        // Intereses de mora: nota débito + cargo en la cartera.
        JsonNode r = api.post("/api/credit/" + ana + "/interest", owner, "{\"saleId\":%d,\"amount\":3000}".formatted(saleId)).expect(200).json();
        assertThat(r.get("debitNoteNumber").asText()).isEqualTo("ND1");
        assertThat(balance()).isEqualTo(122000);

        // Rebaja por fuera de la cartera no se puede si no es un medio válido; por la cartera, rebaja la deuda.
        String nc = """
                {"type":"NOTA_CREDITO","concept":"3","description":"Descuento por cliente frecuente","amount":%s,"taxRate":19,
                 "reason":"Acuerdo con el cliente","settlement":"%s"}""";
        api.post("/api/einvoice/documents/" + invoiceId + "/notes", owner, nc.formatted("11900", "BITCOIN")).expect(400);
        JsonNode credit = api.post("/api/einvoice/documents/" + invoiceId + "/notes", owner, nc.formatted("11900", "FIADO")).expect(200).json();
        assertThat(credit.get("fullNumber").asText()).isEqualTo("NC1");
        assertThat(credit.get("taxTotal").decimalValue()).isEqualByComparingTo("1900");
        assertThat(credit.get("status").asText()).isEqualTo("ACEPTADO");
        assertThat(balance()).isEqualTo(110100);

        // Lo que queda del documento: 119.000 + 3.000 − 11.900; no se puede rebajar más que eso.
        inv = api.get("/api/einvoice/documents/" + invoiceId, owner).json().get("document");
        assertThat(inv.get("remaining").decimalValue()).isEqualByComparingTo("110100");
        api.post("/api/einvoice/documents/" + invoiceId + "/notes", owner, nc.formatted("200000", "FIADO")).expect(400);
        // Concepto que no es de nota crédito, o nota de ajuste sobre una factura: no.
        api.post("/api/einvoice/documents/" + invoiceId + "/notes", owner, nc.formatted("1000", "FIADO").replace("\"concept\":\"3\"", "\"concept\":\"1\"")).expect(400);
        api.post("/api/einvoice/documents/" + invoiceId + "/notes", owner,
                nc.formatted("1000", "FIADO").replace("NOTA_CREDITO", "NOTA_AJUSTE_SOPORTE").replace("\"taxRate\":19", "\"taxRate\":0")).expect(400);

        JsonNode journal = api.get("/api/accounting/journal", owner).expect(200).json();
        assertThat(journal.toString()).contains("NOTA_DEBITO").contains("NOTA_CREDITO");
    }
}
