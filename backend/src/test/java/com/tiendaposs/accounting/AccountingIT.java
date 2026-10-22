package com.tiendaposs.accounting;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.support.AbstractIntegrationTest;
import com.tiendaposs.support.Api;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Fase 14 (primer bloque): asientos automáticos cuadrados, pendientes por contabilizar y balance de prueba. */
class AccountingIT extends AbstractIntegrationTest {

    Api api;
    Store store;
    String owner;
    long taxed;   // IVA 19 %, precio 3.000 (incluye IVA), costo 2.000

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, json);
        store = newStore();
        owner = store.ownerToken();
        taxed = api.post("/api/products", owner, """
                {"name":"Gaseosa","unit":"UNIDAD","cost":2000,"taxRate":19,"initialStock":100,
                 "presentations":[{"name":"Unidad","factor":1,"price":3000,"isBase":true}]}""").expect(201).json()
                .get("presentations").get(0).get("id").asLong();
        long reg = api.get("/api/registers", owner).json().get(0).get("id").asLong();
        api.post("/api/shifts/open", owner, "{\"registerId\":%d,\"openingCash\":0}".formatted(reg)).expect(200);
    }

    private JsonNode sell(int qty) throws Exception {
        return api.post("/api/pos/sales", owner, """
                {"clientRef":"%s","lines":[{"presentationId":%d,"quantity":%d}],"payments":[{"method":"EFECTIVO","amount":%d}]}"""
                .formatted(UUID.randomUUID(), taxed, qty, qty * 3000)).expect(201).json();
    }

    private void enable() throws Exception {
        JsonNode st = api.post("/api/accounting/enable", owner, "{\"enabled\":true}").expect(200).json();
        assertThat(st.get("enabled").asBoolean()).isTrue();
    }

    /** Saldo final por cuenta en el balance de prueba de hoy. */
    private Map<String, BigDecimal> balances(JsonNode tb) {
        Map<String, BigDecimal> m = new HashMap<>();
        for (JsonNode r : tb.get("rows")) m.put(r.get("code").asText(), r.get("closing").decimalValue());
        return m;
    }

    @Test
    void nothingIsPostedUntilEnabledAndThenThePucIsSeeded() throws Exception {
        sell(1);
        api.post("/api/accounting/enable", owner, "{\"enabled\":false}").expect(200);
        assertThat(api.get("/api/accounting/journal", owner).json()).isEmpty();
        enable();
        assertThat(api.get("/api/accounting/accounts", owner).json().size()).isGreaterThanOrEqualTo(20);
        assertThat(api.get("/api/accounting/map", owner).json().toString()).contains("PAY:EFECTIVO").contains("1105");
        assertThat(api.get("/api/accounting/journal", owner).json()).isEmpty(); // no se contabiliza hacia atrás
    }

    @Test
    void aSalePostsRevenueTaxAndCostAndTheTrialBalanceBalances() throws Exception {
        enable();
        JsonNode sale = sell(2);
        JsonNode journal = api.get("/api/accounting/journal", owner).expect(200).json();
        assertThat(journal).hasSize(1);
        JsonNode e = journal.get(0);
        assertThat(e.get("entryType").asText()).isEqualTo("VENTA");
        assertThat(e.get("saleNumber").asLong()).isEqualTo(sale.get("number").asLong());

        JsonNode tb = api.get("/api/accounting/trial-balance", owner).expect(200).json();
        assertThat(tb.get("balanced").asBoolean()).isTrue();
        Map<String, BigDecimal> b = balances(tb);
        assertThat(b.get("1105")).isEqualByComparingTo("6000");                                     // caja
        assertThat(b.get("2408")).isEqualByComparingTo(sale.get("taxTotal").decimalValue());          // IVA por pagar
        assertThat(b.get("4135").add(b.get("2408"))).isEqualByComparingTo("6000");                  // ingreso + IVA = total
        assertThat(b.get("6135")).isEqualByComparingTo("4000");                                     // costo
        assertThat(b.get("1435")).isEqualByComparingTo("-4000");                                    // salida de inventario
    }

    @Test
    void aVoidReversesTheOriginalEntryExactly() throws Exception {
        enable();
        JsonNode sale = sell(1);
        api.post("/api/pos/sales/" + sale.get("id").asLong() + "/void", owner, "{\"reason\":\"Error\"}").expect(200);
        JsonNode journal = api.get("/api/accounting/journal", owner).json();
        assertThat(journal).hasSize(2);
        assertThat(journal.get(0).get("entryType").asText()).isEqualTo("ANULACION");
        assertThat(journal.get(0).get("reversalOf").asLong()).isEqualTo(journal.get(1).get("id").asLong());
        JsonNode tb = api.get("/api/accounting/trial-balance", owner).json();
        assertThat(tb.get("balanced").asBoolean()).isTrue();
        balances(tb).values().forEach(v -> assertThat(v).isEqualByComparingTo("0"));
    }

    @Test
    void aReturnPostsSalesReturnsAndPutsTheCostBackInInventory() throws Exception {
        enable();
        JsonNode sale = sell(2);
        long line = sale.get("lines").get(0).get("id").asLong();
        api.post("/api/pos/sales/" + sale.get("id").asLong() + "/returns", owner, """
                {"reason":"Cambio","refundMethod":"EFECTIVO","lines":[{"saleLineId":%d,"quantity":1,"restock":true}]}""".formatted(line)).expect(200);
        JsonNode journal = api.get("/api/accounting/journal", owner).json();
        assertThat(journal.get(0).get("entryType").asText()).isEqualTo("DEVOLUCION");
        JsonNode tb = api.get("/api/accounting/trial-balance", owner).json();
        assertThat(tb.get("balanced").asBoolean()).isTrue();
        Map<String, BigDecimal> b = balances(tb);
        assertThat(b.get("1105")).isEqualByComparingTo("3000");     // 6.000 cobrados − 3.000 devueltos
        assertThat(b.get("4175")).isPositive();                     // devoluciones en ventas
        assertThat(b.get("6135")).isEqualByComparingTo("2000");     // costo de lo que se quedó vendido
        assertThat(b.get("1435")).isEqualByComparingTo("-2000");
    }

    @Test
    void aMissingAccountLeavesTheSalePendingInsteadOfInventingOne() throws Exception {
        enable();
        api.put("/api/accounting/map", owner, "{\"TAX:IVA\":\"\"}").expect(200);
        JsonNode sale = sell(1);
        assertThat(api.get("/api/accounting/journal", owner).json()).isEmpty();
        JsonNode pending = api.get("/api/accounting/pending", owner).expect(200).json();
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).get("reason").asText()).contains("IVA");
        assertThat(pending.get(0).get("sourceId").asLong()).isEqualTo(sale.get("id").asLong());

        api.put("/api/accounting/map", owner, "{\"TAX:IVA\":\"2408\"}").expect(200);
        JsonNode r = api.post("/api/accounting/pending/process", owner, null).expect(200).json();
        assertThat(r.get("posted").asInt()).isEqualTo(1);
        assertThat(api.get("/api/accounting/pending", owner).json()).isEmpty();
        assertThat(api.get("/api/accounting/trial-balance", owner).json().get("balanced").asBoolean()).isTrue();
        // Una cuenta que no existe no se puede poner en el mapa.
        api.put("/api/accounting/map", owner, "{\"TAX:IVA\":\"9999\"}").expect(400);
    }

    @Test
    void accountsWithMovementsKeepTheirCodeAndCashiersCannotSeeAccounting() throws Exception {
        enable();
        sell(1);
        long caja = 0;
        for (JsonNode a : api.get("/api/accounting/accounts", owner).json()) {
            if (a.get("code").asText().equals("1105")) {
                caja = a.get("id").asLong();
                assertThat(a.get("hasMovements").asBoolean()).isTrue();
            }
        }
        api.put("/api/accounting/accounts/" + caja, owner, "{\"code\":\"1106\",\"name\":\"Caja\",\"nature\":\"DEBITO\"}").expect(400);
        api.put("/api/accounting/accounts/" + caja, owner, "{\"code\":\"1105\",\"name\":\"Caja general\",\"nature\":\"DEBITO\"}").expect(200);
        api.post("/api/accounting/accounts", owner, "{\"code\":\"110510\",\"name\":\"Caja menor\",\"nature\":\"DEBITO\"}").expect(200);

        String cashier = newEmployee(store, "CAJERO");
        api.get("/api/accounting/status", cashier).expect(403);
        String accountant = newEmployee(store, "CONTADOR");
        api.get("/api/accounting/trial-balance", accountant).expect(200);
    }

    @Test
    void statementsBalanceAndTheSiigoExportHasOneRowPerLine() throws Exception {
        enable();
        JsonNode sale = sell(2);
        long line = sale.get("lines").get(0).get("id").asLong();
        api.post("/api/pos/sales/" + sale.get("id").asLong() + "/returns", owner, """
                {"reason":"Cambio","refundMethod":"EFECTIVO","lines":[{"saleLineId":%d,"quantity":1,"restock":true}]}""".formatted(line)).expect(200);

        JsonNode st = api.get("/api/accounting/statements", owner).expect(200).json();
        assertThat(st.get("notice").asText()).contains("pendiente de revisión del contador");
        assertThat(st.get("balanced").asBoolean()).isTrue();
        assertThat(st.get("assets").get("total").decimalValue()).isEqualByComparingTo("1000");          // caja 3.000 − inventario 2.000
        assertThat(st.get("costs").get("total").decimalValue()).isEqualByComparingTo("2000");
        assertThat(st.get("netIncome").decimalValue()).isEqualByComparingTo(st.get("accumulatedResult").decimalValue());
        assertThat(st.get("netIncome").decimalValue()).isEqualByComparingTo(st.get("income").get("total").decimalValue().subtract(new BigDecimal("2000")));

        String csv = api.get("/api/accounting/export/siigo", owner).expect(200).raw();
        String[] rows = csv.split("\r\n");
        assertThat(rows[0]).startsWith("Tipo de comprobante;Consecutivo comprobante;Fecha de elaboración;Código cuenta contable");
        int lines = 0;
        for (JsonNode e : api.get("/api/accounting/journal", owner).json()) lines += e.get("lines").size();
        assertThat(rows).hasSize(lines + 1);
        assertThat(csv).contains(";4135;").contains(";1105;");
    }
}
