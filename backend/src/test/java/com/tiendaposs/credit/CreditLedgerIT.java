package com.tiendaposs.credit;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.support.AbstractIntegrationTest;
import com.tiendaposs.support.Api;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Fiado: el arqueo no cuenta lo fiado, los abonos digitales se verifican y el cuadre de fiados cuadra con los clientes. */
class CreditLedgerIT extends AbstractIntegrationTest {

    Api api;
    String owner;
    long pres;
    long ana;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, json);
        owner = newStore().ownerToken();
        api.put("/api/settings/pos/finance", owner,
                "{\"verifyDigitalPayments\":true,\"creditEnabled\":true,\"defaultCreditLimit\":1000000,\"defaultCreditDays\":15}").expect(200);
        pres = api.post("/api/products", owner, """
                {"name":"Mercado","unit":"UNIDAD","cost":50000,"initialStock":100,
                 "presentations":[{"name":"Unidad","factor":1,"price":100000,"isBase":true}]}""").expect(201).json()
                .get("presentations").get(0).get("id").asLong();
        ana = api.post("/api/pos/customers", owner, "{\"name\":\"Ana\",\"docNumber\":\"123\",\"phone\":\"3000000000\"}").expect(201).json().get("id").asLong();
        long reg = api.get("/api/registers", owner).json().get(0).get("id").asLong();
        api.post("/api/shifts/open", owner, "{\"registerId\":%d,\"openingCash\":50000}".formatted(reg)).expect(200);
    }

    private void sell(int qty, String payments, boolean customer) throws Exception {
        api.post("/api/pos/sales", owner, "{\"clientRef\":\"%s\",\"lines\":[{\"presentationId\":%d,\"quantity\":%d}],\"payments\":[%s]%s}"
                .formatted(UUID.randomUUID(), pres, qty, payments, customer ? ",\"customer\":{\"customerId\":%d}".formatted(ana) : "")).expect(201);
    }

    @Test
    void theCashCountLeavesFiadoOutAndTheLedgerBalances() throws Exception {
        // El ejemplo del dueño: vendí 400.000, 300.000 en efectivo y 100.000 fiado.
        sell(3, "{\"method\":\"EFECTIVO\",\"amount\":300000}", false);
        sell(1, "{\"method\":\"FIADO\",\"amount\":100000}", true);
        JsonNode shift = api.get("/api/shifts/current", owner).json();
        assertThat(shift.get("salesTotal").decimalValue()).isEqualByComparingTo("400000");
        assertThat(shift.get("creditSales").decimalValue()).isEqualByComparingTo("100000");
        assertThat(shift.get("expectedCash").decimalValue()).isEqualByComparingTo("350000"); // base 50.000 + efectivo; el fiado no

        // Abona 50.000 en efectivo: entra a la gaveta.
        api.post("/api/credit/" + ana + "/payments", owner, "{\"amount\":50000,\"method\":\"EFECTIVO\"}").expect(200);
        shift = api.get("/api/shifts/current", owner).json();
        assertThat(shift.get("expectedCash").decimalValue()).isEqualByComparingTo("400000");
        assertThat(shift.get("creditPayments").decimalValue()).isEqualByComparingTo("50000");

        JsonNode l = api.get("/api/credit/ledger", owner).expect(200).json();
        assertThat(l.get("opening").decimalValue()).isEqualByComparingTo("0");
        assertThat(l.get("charged").decimalValue()).isEqualByComparingTo("100000");
        assertThat(l.get("paid").decimalValue()).isEqualByComparingTo("50000");
        assertThat(l.get("paidCash").decimalValue()).isEqualByComparingTo("50000");
        assertThat(l.get("closing").decimalValue()).isEqualByComparingTo("50000");
        assertThat(l.get("balanced").asBoolean()).isTrue();
        assertThat(l.get("customers").get(0).get("name").asText()).isEqualTo("Ana");
    }

    @Test
    void aRejectedDigitalAbonoPutsTheDebtBack() throws Exception {
        sell(1, "{\"method\":\"FIADO\",\"amount\":100000}", true);
        api.post("/api/credit/" + ana + "/payments", owner, "{\"amount\":40000,\"method\":\"NEQUI\",\"reference\":\"N-77\"}").expect(200);
        assertThat(api.get("/api/credit/" + ana, owner).json().get("balance").decimalValue()).isEqualByComparingTo("60000");
        // No entra a la gaveta: es digital.
        assertThat(api.get("/api/shifts/current", owner).json().get("expectedCash").decimalValue()).isEqualByComparingTo("50000");

        JsonNode pending = api.get("/api/payments?status=PENDIENTE", owner).expect(200).json();
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).get("creditPayment").asBoolean()).isTrue();
        assertThat(pending.get(0).get("customerName").asText()).isEqualTo("Ana");
        assertThat(api.get("/api/credit/ledger", owner).json().get("paidPendingVerification").decimalValue()).isEqualByComparingTo("40000");

        long id = pending.get(0).get("id").asLong();
        api.post("/api/payments/" + id + "/reject", owner, "{\"reason\":\"No llegó a Nequi\"}").expect(200);
        assertThat(api.get("/api/credit/" + ana, owner).json().get("balance").decimalValue()).isEqualByComparingTo("100000");
        api.post("/api/payments/" + id + "/replace", owner, "{\"method\":\"EFECTIVO\",\"amount\":40000}").expect(400);

        JsonNode l = api.get("/api/credit/ledger", owner).json();
        assertThat(l.get("rejected").decimalValue()).isEqualByComparingTo("40000");
        assertThat(l.get("closing").decimalValue()).isEqualByComparingTo("100000");
        assertThat(l.get("balanced").asBoolean()).isTrue();
    }

    @Test
    void aConfirmedDigitalAbonoKeepsTheDebtDown() throws Exception {
        sell(1, "{\"method\":\"FIADO\",\"amount\":100000}", true);
        api.post("/api/credit/" + ana + "/payments", owner, "{\"amount\":30000,\"method\":\"DAVIPLATA\"}").expect(200);
        long id = api.get("/api/payments?status=PENDIENTE", owner).json().get(0).get("id").asLong();
        api.post("/api/payments/" + id + "/confirm", owner, "{\"note\":\"Visto en Daviplata\"}").expect(200);
        assertThat(api.get("/api/credit/" + ana, owner).json().get("balance").decimalValue()).isEqualByComparingTo("70000");
        JsonNode l = api.get("/api/credit/ledger", owner).json();
        assertThat(l.get("paidDigital").decimalValue()).isEqualByComparingTo("30000");
        assertThat(l.get("paidPendingVerification").decimalValue()).isEqualByComparingTo("0");
    }
}
