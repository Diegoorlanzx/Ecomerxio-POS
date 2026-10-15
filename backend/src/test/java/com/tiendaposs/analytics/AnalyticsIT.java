package com.tiendaposs.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.support.AbstractIntegrationTest;
import com.tiendaposs.support.Api;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Fase 4: dashboard, analítica de ventas y rentabilidad por producto. */
class AnalyticsIT extends AbstractIntegrationTest {

    Api api;
    Store store;
    String owner;
    long shiftId;
    long presA, presB, idA;
    final LocalDate today = LocalDate.now(ZoneId.of("America/Bogota"));

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, json);
        store = newStore();
        owner = store.ownerToken();
        long cat = api.post("/api/categories", owner, "{\"name\":\"Snacks\"}").expect(201).json().get("id").asLong();
        JsonNode a = api.post("/api/products", owner, """
                {"name":"Papas Margarita","brand":"Margarita","categoryId":%d,"unit":"UNIDAD","cost":6000,"initialStock":100,
                 "presentations":[{"name":"Unidad","factor":1,"price":10000,"isBase":true}]}""".formatted(cat)).expect(201).json();
        JsonNode b = api.post("/api/products", owner, """
                {"name":"Gaseosa 400","brand":"Postobón","unit":"UNIDAD","cost":4500,"initialStock":100,
                 "presentations":[{"name":"Unidad","factor":1,"price":5000,"isBase":true}]}""").expect(201).json();
        idA = a.get("id").asLong();
        presA = a.get("presentations").get(0).get("id").asLong();
        presB = b.get("presentations").get(0).get("id").asLong();
        long reg = api.get("/api/registers", owner).json().get(0).get("id").asLong();
        shiftId = api.post("/api/shifts/open", owner, "{\"registerId\":%d,\"openingCash\":0}".formatted(reg)).expect(200).json().get("id").asLong();
    }

    JsonNode sell(String token, long pres, int qty, String method, int amount) throws Exception {
        return api.post("/api/pos/sales", token, """
                {"clientRef":"%s","lines":[{"presentationId":%d,"quantity":%d}],"payments":[{"method":"%s","amount":%d,"reference":"R%s"}]}
                """.formatted(UUID.randomUUID(), pres, qty, method, amount, UUID.randomUUID().toString().substring(0, 8))).expect(201).json();
    }

    /** Venta hecha "sin internet" hace `daysAgo` días (así se crean ventas del período anterior). */
    void sellPast(long pres, int qty, int amount, int daysAgo) throws Exception {
        Instant at = Instant.now().minus(daysAgo, ChronoUnit.DAYS);
        api.post("/api/pos/sales", owner, """
                {"clientRef":"%s","offline":true,"offlineShiftId":%d,"offlineCreatedAt":"%s",
                 "lines":[{"presentationId":%d,"quantity":%d}],"payments":[{"method":"EFECTIVO","amount":%d}]}
                """.formatted(UUID.randomUUID(), shiftId, at, pres, qty, amount)).expect(201);
    }

    String range() {
        return "?from=" + today.minusDays(2) + "&to=" + today;
    }

    void baseSales() throws Exception {
        sell(owner, presA, 3, "EFECTIVO", 30000);
        sell(owner, presB, 10, "EFECTIVO", 50000);
        String cajero = newEmployee(store, "CAJERO");
        long reg2 = api.post("/api/registers", owner, "{\"name\":\"Caja 2\"}").expect(200).json().get("id").asLong();
        api.post("/api/shifts/open", cajero, "{\"registerId\":%d,\"openingCash\":0}".formatted(reg2)).expect(200);
        sell(cajero, presA, 1, "NEQUI", 10000);
        // Período anterior (3 a 5 días atrás): se vendieron 10 papas.
        sellPast(presA, 10, 100000, 4);
    }

    @Test
    void salesReportKpisGroupsComparisonAndDeclining() throws Exception {
        baseSales();
        // El pago de una venta hecha sin internet conserva la fecha real del cobro.
        LocalDate past = today.minusDays(4);
        assertThat(api.get("/api/payments?from=" + past + "&to=" + past, owner).expect(200).json()).hasSize(1);
        JsonNode r = api.get("/api/analytics/report" + range(), owner).expect(200).json();
        JsonNode k = r.get("current");
        assertThat(k.get("sales").decimalValue()).isEqualByComparingTo("90000");
        assertThat(k.get("transactions").asInt()).isEqualTo(3);
        assertThat(k.get("units").decimalValue()).isEqualByComparingTo("14");
        assertThat(k.get("avgTicket").decimalValue()).isEqualByComparingTo("30000");
        assertThat(k.get("grossProfit").decimalValue()).isEqualByComparingTo("21000"); // (40.000-24.000) + (50.000-45.000)
        assertThat(k.get("marginPct").decimalValue()).isEqualByComparingTo("23.3");
        assertThat(r.get("previous").get("sales").decimalValue()).isEqualByComparingTo("100000");
        assertThat(r.get("changes").get("sales").decimalValue()).isEqualByComparingTo("-10.0");

        JsonNode products = r.get("products");
        assertThat(products.get(0).get("name").asText()).isEqualTo("Gaseosa 400");
        assertThat(products.get(1).get("profit").decimalValue()).isEqualByComparingTo("16000");
        assertThat(products.get(1).get("marginPct").decimalValue()).isEqualByComparingTo("40.0");
        assertThat(products.get(1).get("changePct").decimalValue()).isEqualByComparingTo("-60.0");
        assertThat(r.get("declining").findValuesAsText("name")).containsExactly("Papas Margarita");

        assertThat(r.get("categories").findValuesAsText("label")).containsExactly("Sin categoría", "Snacks");
        assertThat(r.get("categories").get(1).get("sharePct").decimalValue()).isEqualByComparingTo("44.4");
        assertThat(r.get("brands").findValuesAsText("label")).containsExactly("Postobón", "Margarita");
        assertThat(r.get("employees")).hasSize(2);
        assertThat(r.get("shifts")).hasSize(2);
        assertThat(r.get("methods").get(0).get("key").asText()).isEqualTo("EFECTIVO");
        assertThat(r.get("methods").get(0).get("sales").decimalValue()).isEqualByComparingTo("80000");
        int tx = 0;
        for (JsonNode h : r.get("hours")) tx += h.get("transactions").asInt();
        assertThat(tx).isEqualTo(3);
        assertThat(r.get("days")).hasSize(3);
        assertThat(r.get("months")).hasSize(12);
        assertThat(r.get("bestWeekday").asText()).isNotBlank();

        // Una devolución reduce la venta original.
        JsonNode sale = api.get("/api/pos/sales?size=10", owner).json();
        long saleB = 0, lineB = 0;
        for (JsonNode s : sale.get("items")) {
            JsonNode d = api.get("/api/pos/sales/" + s.get("id").asLong(), owner).json();
            if (d.get("lines").get(0).get("productName").asText().equals("Gaseosa 400")) {
                saleB = d.get("id").asLong();
                lineB = d.get("lines").get(0).get("id").asLong();
            }
        }
        api.post("/api/pos/sales/" + saleB + "/returns", owner, """
                {"reason":"Mal estado","refundMethod":"EFECTIVO","lines":[{"saleLineId":%d,"quantity":2,"restock":false,"condition":"DANADO"}]}
                """.formatted(lineB)).expect(200);
        JsonNode r2 = api.get("/api/analytics/report" + range(), owner).json().get("current");
        assertThat(r2.get("sales").decimalValue()).isEqualByComparingTo("80000");
        assertThat(r2.get("units").decimalValue()).isEqualByComparingTo("12");
        assertThat(r2.get("refunds").decimalValue()).isEqualByComparingTo("10000");

        Api.Res csv = api.get("/api/analytics/export" + range(), owner).expect(200);
        assertThat(csv.raw()).contains("Informe de ventas").contains("Papas Margarita").contains("Medio de pago");
    }

    @Test
    void profitabilityQuadrantsDiscountsAndExpenseAllocation() throws Exception {
        baseSales();
        api.post("/api/finance/entries", owner, "{\"kind\":\"GASTO\",\"description\":\"Luz\",\"amount\":7000,\"method\":\"TRANSFERENCIA\",\"paidFrom\":\"BANCO\"}").expect(200);
        JsonNode p = api.get("/api/analytics/profitability" + range(), owner).expect(200).json();
        assertThat(p.get("revenueEx").decimalValue()).isEqualByComparingTo("90000");
        assertThat(p.get("grossProfit").decimalValue()).isEqualByComparingTo("21000");
        assertThat(p.get("expenses").decimalValue()).isEqualByComparingTo("7000");
        assertThat(p.get("netProfit").decimalValue()).isEqualByComparingTo("14000");
        JsonNode first = p.get("products").get(0);
        assertThat(first.get("name").asText()).isEqualTo("Papas Margarita");
        assertThat(first.get("profitPerUnit").decimalValue()).isEqualByComparingTo("4000");
        assertThat(first.get("allocatedExpenses").decimalValue()).isEqualByComparingTo("3111.11");
        assertThat(first.get("quadrant").asText()).isEqualTo("NICHO_RENTABLE");
        assertThat(p.get("products").get(1).get("quadrant").asText()).isEqualTo("VOLUMEN_BAJO_MARGEN");
        assertThat(p.get("products").get(1).get("marginPct").decimalValue()).isEqualByComparingTo("10.0");
        assertThat(p.get("categories")).hasSize(2);

        // Evolución de precio y costo.
        JsonNode prod = api.get("/api/products/" + idA, owner).json();
        api.put("/api/products/" + idA, owner, """
                {"name":"Papas Margarita","unit":"UNIDAD","priceChangeReason":"Ajuste","presentations":[{"id":%d,"name":"Unidad","factor":1,"price":11000,"isBase":true}]}
                """.formatted(prod.get("presentations").get(0).get("id").asLong())).expect(200);
        JsonNode ev = api.get("/api/analytics/products/" + idA + "/evolution", owner).expect(200).json();
        assertThat(ev.get("currentPrice").decimalValue()).isEqualByComparingTo("11000");
        assertThat(ev.get("priceChanges").findValuesAsText("field")).contains("PRECIO");
        assertThat(ev.get("purchaseCosts")).isNotEmpty();
        assertThat(ev.get("months").get(11).get("units").decimalValue()).isPositive();

        assertThat(api.get("/api/analytics/export?type=rentabilidad" + range().replace('?', '&'), owner).expect(200).raw())
                .contains("NICHO_RENTABLE");
    }

    @Test
    void dashboardShowsTodayPendingAndAlerts() throws Exception {
        sell(owner, presA, 2, "EFECTIVO", 20000);
        sell(owner, presB, 1, "NEQUI", 5000);
        JsonNode d = api.get("/api/dashboard", owner).expect(200).json();
        JsonNode todayStat = d.get("periods").get(0);
        assertThat(todayStat.get("key").asText()).isEqualTo("dia");
        assertThat(todayStat.get("sales").decimalValue()).isEqualByComparingTo("25000");
        assertThat(todayStat.get("transactions").asInt()).isEqualTo(2);
        assertThat(todayStat.get("grossProfit").decimalValue()).isEqualByComparingTo("8500");
        assertThat(d.get("periods")).hasSize(4);
        assertThat(d.get("cash").get(0).get("expectedCash").decimalValue()).isEqualByComparingTo("20000");
        assertThat(d.get("methodsToday").findValuesAsText("key")).contains("EFECTIVO", "NEQUI");
        assertThat(d.get("topProducts").get(0).get("name").asText()).isEqualTo("Papas Margarita");
        assertThat(d.get("pending").get("paymentsToVerify").asInt()).isEqualTo(1);
        assertThat(d.get("todayByHour")).isNotEmpty();
        assertThat(d.get("monthFinance").get("grossProfit").decimalValue()).isPositive();
    }

    @Test
    void permissionsAndSettings() throws Exception {
        sell(owner, presA, 1, "EFECTIVO", 10000);
        String cajero = newEmployee(store, "CAJERO");
        api.get("/api/dashboard", cajero).expect(403);
        api.get("/api/analytics/report", cajero).expect(403);
        String sup = newEmployee(store, "SUPERVISOR");
        JsonNode r = api.get("/api/analytics/report", sup).expect(200).json();
        assertThat(r.get("costs").asBoolean()).isFalse();
        assertThat(r.get("current").has("grossProfit")).isFalse();
        api.get("/api/analytics/profitability", sup).expect(403);
        assertThat(api.get("/api/dashboard", sup).expect(200).json().has("monthFinance")).isFalse();

        api.put("/api/settings/pos/analytics", owner, "{\"decliningPct\":20,\"lowMarginPct\":40,\"highMarginPct\":30,\"rankingSize\":10,\"allocateExpenses\":true}").expect(400);
        JsonNode s = api.put("/api/settings/pos/analytics", owner, "{\"decliningPct\":25,\"lowMarginPct\":10,\"highMarginPct\":35,\"rankingSize\":5,\"allocateExpenses\":false}").expect(200).json();
        assertThat(s.get("rankingSize").asInt()).isEqualTo(5);
        assertThat(api.get("/api/analytics/profitability", owner).json().get("products").get(0).has("allocatedExpenses")).isFalse();
        api.get("/api/analytics/report?from=2026-05-01&to=2026-04-01", owner).expect(400);
    }
}
