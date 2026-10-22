package com.tiendaposs.accounting;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.support.AbstractIntegrationTest;
import com.tiendaposs.support.Api;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Fase 14 (resto): compras con retenciones, pagos, gastos, abonos, inventario, caja, notas, cierres y terceros. */
class AccountingOperationsIT extends AbstractIntegrationTest {

    Api api;
    Store store;
    String owner;
    long pres, productId, supplier, ana, shiftId;
    final LocalDate today = LocalDate.now(ZoneId.of("America/Bogota"));

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, json);
        store = newStore();
        owner = store.ownerToken();
        api.put("/api/settings/pos/finance", owner,
                "{\"verifyDigitalPayments\":false,\"creditEnabled\":true,\"defaultCreditLimit\":1000000,\"defaultCreditDays\":15}").expect(200);
        JsonNode p = api.post("/api/products", owner, """
                {"name":"Arroz","unit":"UNIDAD","cost":1000,"initialStock":0,
                 "presentations":[{"name":"Unidad","factor":1,"price":2000,"isBase":true}]}""").expect(201).json();
        pres = p.get("presentations").get(0).get("id").asLong();
        productId = p.get("id").asLong();
        supplier = api.post("/api/suppliers", owner, """
                {"name":"Molinos SAS","taxId":"900123456","creditDays":30,"incomeTaxDeclarant":true}""").expect(201).json().get("id").asLong();
        ana = api.post("/api/pos/customers", owner, "{\"name\":\"Ana\",\"docNumber\":\"52123456\"}").expect(201).json().get("id").asLong();
        long reg = api.get("/api/registers", owner).json().get(0).get("id").asLong();
        shiftId = api.post("/api/shifts/open", owner, "{\"registerId\":%d,\"openingCash\":50000}".formatted(reg)).expect(200).json().get("id").asLong();
    }

    private void enable(LocalDate start) throws Exception {
        api.post("/api/accounting/enable", owner, "{\"enabled\":true,\"startDate\":\"%s\"}".formatted(start)).expect(200);
    }

    private void retentions() throws Exception {
        // UVT de prueba 1.000: la base de 27 UVT es 27.000.
        api.put("/api/accounting/withholdings/settings", owner, """
                {"uvt":1000,"retentionAgent":true,"purchasesBaseUvt":27,"purchasesRateDeclarant":2.5,"purchasesRateOther":3.5,
                 "reteivaAgent":false,"reteivaRate":15,"reteicaPerMil":null}""").expect(200);
    }

    private JsonNode receive(int units, int cost) throws Exception {
        return api.post("/api/receipts", owner, """
                {"supplierId":%d,"invoiceNumber":"FV-%d","lines":[{"productId":%d,"unitsReceived":%d,"unitCost":%d}]}"""
                .formatted(supplier, units, productId, units, cost)).expect(201).json();
    }

    private List<JsonNode> journal(String type) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode e : api.get("/api/accounting/journal?from=" + today.minusYears(2) + "&to=" + today + "&limit=500", owner).json()) {
            if (type == null || type.equals(e.get("entryType").asText())) out.add(e);
        }
        return out;
    }

    private BigDecimal line(JsonNode entry, String account, String side) {
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode l : entry.get("lines")) if (l.get("accountCode").asText().equals(account)) sum = sum.add(l.get(side).decimalValue());
        return sum;
    }

    private void assertBalanced() throws Exception {
        JsonNode tb = api.get("/api/accounting/trial-balance?from=" + today.minusYears(2) + "&to=" + today, owner).expect(200).json();
        assertThat(tb.get("balanced").asBoolean()).isTrue();
        JsonNode st = api.get("/api/accounting/statements?from=" + today.withDayOfYear(1) + "&to=" + today, owner).expect(200).json();
        assertThat(st.get("balanced").asBoolean()).isTrue();
    }

    @Test
    void purchasesWithWithholdingPaymentsAndTheirAdjustment() throws Exception {
        enable(today);
        retentions();
        JsonNode rec = receive(100, 1000); // 100.000: supera la base de 27.000 → retención 2,5 % = 2.500
        long recId = rec.get("id").asLong();
        JsonNode w = api.get("/api/accounting/receipts/" + recId + "/withholdings", owner).expect(200).json();
        assertThat(w.get("withheld").decimalValue()).isEqualByComparingTo("2500");
        assertThat(w.get("payable").decimalValue()).isEqualByComparingTo("97500");
        assertThat(w.get("items").get(0).get("confirmed").asBoolean()).isFalse();

        JsonNode buy = journal("COMPRA").get(0);
        assertThat(line(buy, "1435", "debit")).isEqualByComparingTo("100000");
        assertThat(line(buy, "2365", "credit")).isEqualByComparingTo("2500");
        assertThat(line(buy, "2205", "credit")).isEqualByComparingTo("97500");
        assertThat(buy.get("lines").get(0).get("thirdPartyId").asText()).isEqualTo("900123456");

        // Una compra pequeña no lleva retención.
        receive(10, 1000);
        assertThat(api.get("/api/finance/payables?supplierId=" + supplier, owner).json().get("total").decimalValue()).isEqualByComparingTo("107500");

        // El contador la sube al 3 %: se ajusta la diferencia (500).
        w = api.put("/api/accounting/receipts/" + recId + "/withholdings", owner,
                "{\"items\":[{\"kind\":\"RETEFUENTE\",\"base\":100000,\"rate\":3}]}").expect(200).json();
        assertThat(w.get("withheld").decimalValue()).isEqualByComparingTo("3000");
        JsonNode adj = journal("AJUSTE_RETENC").get(0);
        assertThat(line(adj, "2205", "debit")).isEqualByComparingTo("500");
        assertThat(line(adj, "2365", "credit")).isEqualByComparingTo("500");

        // Pago desde el banco.
        api.post("/api/finance/supplier-payments", owner, """
                {"supplierId":%d,"amount":97000,"method":"TRANSFERENCIA","paidFrom":"BANCO"}""".formatted(supplier)).expect(200);
        JsonNode pay = journal("PAGO_PROVEEDOR").get(0);
        assertThat(line(pay, "2205", "debit")).isEqualByComparingTo("97000");
        assertThat(line(pay, "1110", "credit")).isEqualByComparingTo("97000");
        // No se puede retener más de lo que falta por pagar.
        api.put("/api/accounting/receipts/" + recId + "/withholdings", owner,
                "{\"items\":[{\"kind\":\"RETEFUENTE\",\"base\":100000,\"rate\":20}]}").expect(400);

        JsonNode report = api.get("/api/accounting/withholdings/report?from=" + today.withDayOfMonth(1) + "&to=" + today, owner).expect(200).json();
        assertThat(report.get("totals").get("RETEFUENTE").decimalValue()).isEqualByComparingTo("3000");

        // Terceros: el proveedor con su saldo (debe 10.000 de la compra pequeña).
        JsonNode third = null;
        for (JsonNode t : api.get("/api/accounting/third-parties", owner).expect(200).json()) if (t.get("id").asText().equals("900123456")) third = t;
        assertThat(third).isNotNull();
        assertThat(third.get("kind").asText()).isEqualTo("PROVEEDOR");
        assertThat(third.get("balance").decimalValue()).isEqualByComparingTo("-13000"); // 10.000 por pagar + 3.000 retenido (pasivos)
        assertThat(api.get("/api/accounting/third-parties/900123456/ledger", owner).expect(200).json()).hasSizeGreaterThan(4);
        assertBalanced();
    }

    @Test
    void expensesFiadoInventoryAndCashDifferencesAreAccounted() throws Exception {
        enable(today);
        // Gasto de arriendo desde el banco: va a 5120 por el nombre de la categoría.
        long arriendo = 0;
        for (JsonNode c : api.get("/api/finance/categories", owner).json()) if (c.get("name").asText().equals("Arriendo")) arriendo = c.get("id").asLong();
        JsonNode exp = api.post("/api/finance/entries", owner, """
                {"kind":"GASTO","categoryId":%d,"description":"Arriendo octubre","amount":800000,"method":"TRANSFERENCIA","paidFrom":"BANCO"}"""
                .formatted(arriendo)).expect(200).json();
        JsonNode g = journal("GASTO").get(0);
        assertThat(line(g, "5120", "debit")).isEqualByComparingTo("800000");
        assertThat(line(g, "1110", "credit")).isEqualByComparingTo("800000");
        api.post("/api/finance/entries/" + exp.get("id").asLong() + "/void", owner, "{\"reason\":\"Duplicado\"}").expect(200);
        assertThat(line(journal("REVERSA").get(0), "1110", "debit")).isEqualByComparingTo("800000");

        receive(10, 1000);
        // Venta fiada y abono en efectivo: el abono baja Clientes (1305).
        api.post("/api/pos/sales", owner, """
                {"clientRef":"%s","lines":[{"presentationId":%d,"quantity":5}],"payments":[{"method":"FIADO","amount":10000}],
                 "customer":{"customerId":%d}}""".formatted(UUID.randomUUID(), pres, ana)).expect(201);
        api.post("/api/credit/" + ana + "/payments", owner, "{\"amount\":4000,\"method\":\"EFECTIVO\"}").expect(200);
        JsonNode ab = journal("ABONO_FIADO").get(0);
        assertThat(line(ab, "1105", "debit")).isEqualByComparingTo("4000");
        assertThat(line(ab, "1305", "credit")).isEqualByComparingTo("4000");
        assertThat(ab.get("lines").get(0).get("thirdPartyId").asText()).isEqualTo("52123456");

        // Baja por daño de 2 unidades a 1.000.
        api.post("/api/inventory/adjust", owner, "{\"productId\":%d,\"mode\":\"DANADO\",\"quantity\":2,\"reason\":\"Se mojó\"}".formatted(productId)).expect(200);
        JsonNode inv = journal("INVENTARIO").get(0);
        assertThat(line(inv, "5310", "debit")).isEqualByComparingTo("2000");
        assertThat(line(inv, "1435", "credit")).isEqualByComparingTo("2000");

        // Faltante de caja al cerrar: esperado 54.000, contado 53.000.
        api.post("/api/shifts/" + shiftId + "/close", owner, "{\"countedCash\":53000,\"closeNotes\":\"Faltó un billete\"}").expect(200);
        JsonNode cash = journal("DIFERENCIA_CAJA").get(0);
        assertThat(line(cash, "5195", "debit")).isEqualByComparingTo("1000");
        assertThat(line(cash, "1105", "credit")).isEqualByComparingTo("1000");
        assertBalanced();
    }

    @Test
    void closedMonthsMoveLateOperationsAndManualNotesMustBalance() throws Exception {
        YearMonth last = YearMonth.from(today).minusMonths(1);
        enable(last.atDay(1));
        // Nota de saldos iniciales: no cuadra → no; cuadra → sí.
        api.post("/api/accounting/notes", owner, """
                {"date":"%s","description":"Saldos iniciales","lines":[{"accountCode":"1105","debit":500000},{"accountCode":"3115","credit":400000}]}"""
                .formatted(last.atDay(1))).expect(400);
        JsonNode note = api.post("/api/accounting/notes", owner, """
                {"date":"%s","description":"Saldos iniciales","lines":[{"accountCode":"1105","debit":500000},{"accountCode":"3115","credit":500000}]}"""
                .formatted(last.atDay(1))).expect(201).json();
        assertThat(note.get("entryType").asText()).isEqualTo("NOTA");

        // No se cierra el mes en curso; el pasado sí.
        api.post("/api/accounting/periods/" + YearMonth.from(today) + "/close", owner, null).expect(400);
        JsonNode periods = api.post("/api/accounting/periods/" + last + "/close", owner, null).expect(200).json();
        assertThat(periods.get("months").get(1).get("closed").asBoolean()).isTrue();

        // En el mes cerrado no entran notas; un gasto con esa fecha se registra el 1.º del mes abierto.
        api.post("/api/accounting/notes", owner, """
                {"date":"%s","description":"Tarde","lines":[{"accountCode":"5195","debit":1000},{"accountCode":"1105","credit":1000}]}"""
                .formatted(last.atDay(10))).expect(400);
        api.post("/api/finance/entries", owner, """
                {"kind":"GASTO","description":"Factura de luz de %s","amount":90000,"method":"TRANSFERENCIA","paidFrom":"BANCO","entryDate":"%s"}"""
                .formatted(last, last.atDay(20))).expect(200);
        JsonNode late = journal("GASTO").get(0);
        assertThat(late.get("entryDate").asText()).isEqualTo(YearMonth.from(today).atDay(1).toString());
        assertThat(late.get("originalDate").asText()).isEqualTo(last.atDay(20).toString());

        // Reabrir: solo el dueño y con motivo.
        api.post("/api/accounting/periods/" + last + "/reopen", newEmployee(store, "ADMINISTRADOR"), "{\"reason\":\"x\"}").expect(403);
        api.post("/api/accounting/periods/" + last + "/reopen", owner, "{\"reason\":\"\"}").expect(400);
        api.post("/api/accounting/periods/" + last + "/reopen", owner, "{\"reason\":\"Falta una factura\"}").expect(200);

        // La nota se reversa (no se borra).
        JsonNode rev = api.post("/api/accounting/entries/" + note.get("id").asLong() + "/reverse", owner, "{\"reason\":\"Mal digitada\"}").expect(200).json();
        assertThat(rev.get("reversalOf").asLong()).isEqualTo(note.get("id").asLong());
        api.post("/api/accounting/entries/" + note.get("id").asLong() + "/reverse", owner, "{\"reason\":\"otra vez\"}").expect(400);
        assertBalanced();
    }

    @Test
    void theYearCloseSendsResultsToEquity() throws Exception {
        int year = today.getYear() - 1;
        enable(LocalDate.of(year, 1, 1));
        api.post("/api/accounting/notes", owner, """
                {"date":"%s","description":"Ingreso del año pasado","lines":[{"accountCode":"1105","debit":50000},{"accountCode":"4295","credit":50000}]}"""
                .formatted(LocalDate.of(year, 3, 15))).expect(201);
        api.post("/api/accounting/notes", owner, """
                {"date":"%s","description":"Gasto del año pasado","lines":[{"accountCode":"5195","debit":20000},{"accountCode":"1105","credit":20000}]}"""
                .formatted(LocalDate.of(year, 6, 1))).expect(201);
        api.post("/api/accounting/years/" + today.getYear() + "/close", owner, null).expect(400); // el año en curso no
        api.post("/api/accounting/years/" + year + "/close", owner, null).expect(400);             // faltan meses por cerrar
        for (int m = 1; m <= 12; m++) api.post("/api/accounting/periods/" + YearMonth.of(year, m) + "/close", owner, null).expect(200);
        api.post("/api/accounting/years/" + year + "/close", owner, null).expect(200);

        JsonNode close = journal("CIERRE").get(0);
        assertThat(close.get("entryDate").asText()).isEqualTo(year + "-12-31");
        assertThat(line(close, "4295", "debit")).isEqualByComparingTo("50000");
        assertThat(line(close, "5195", "credit")).isEqualByComparingTo("20000");
        assertThat(line(close, "3605", "credit")).isEqualByComparingTo("30000");
        // El estado de resultados del año cerrado sigue mostrando su utilidad; un mes de ese año ya no se reabre.
        JsonNode st = api.get("/api/accounting/statements?from=" + year + "-01-01&to=" + year + "-12-31", owner).json();
        assertThat(st.get("netIncome").decimalValue()).isEqualByComparingTo("30000");
        assertThat(st.get("balanced").asBoolean()).isTrue();
        api.post("/api/accounting/periods/" + YearMonth.of(year, 5) + "/reopen", owner, "{\"reason\":\"probar\"}").expect(400);
        api.post("/api/accounting/years/" + year + "/close", owner, null).expect(400);
    }
}
