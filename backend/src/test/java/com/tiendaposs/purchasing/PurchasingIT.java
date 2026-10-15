package com.tiendaposs.purchasing;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.support.AbstractIntegrationTest;
import com.tiendaposs.support.Api;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PurchasingIT extends AbstractIntegrationTest {

    Api api;
    Store store;
    String owner;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, json);
        store = newStore();
        owner = store.ownerToken();
    }

    JsonNode product(String name, int price, int cost, int stock, int stockMin) throws Exception {
        return api.post("/api/products", owner, """
                {"name":"%s","unit":"UNIDAD","cost":%d,"initialStock":%d,"stockMin":%d,
                 "presentations":[{"name":"Unidad","factor":1,"price":%d,"isBase":true}]}
                """.formatted(name, cost, stock, stockMin, price)).expect(201).json();
    }

    long base(JsonNode p) {
        return p.get("presentations").get(0).get("id").asLong();
    }

    JsonNode supplier(String name, Integer lead) throws Exception {
        return api.post("/api/suppliers", owner, """
                {"name":"%s","contactName":"Carlos","whatsapp":"3001234567","email":"pedidos@prov.test","creditDays":15,
                 "orderDays":"lun,jue","leadTimeDays":%s}""".formatted(name, lead == null ? "null" : lead)).expect(201).json();
    }

    void catalog(long supplierId, long productId, String pack, int factor, int costPerPack, int min, int multiple) throws Exception {
        api.post("/api/suppliers/" + supplierId + "/catalog", owner, """
                {"productId":%d,"packName":"%s","packFactor":%d,"costPerPack":%d,"minPacks":%d,"multiplePacks":%d}
                """.formatted(productId, pack, factor, costPerPack, min, multiple)).expect(200);
    }

    BigDecimal stock(long productId) throws Exception {
        return api.get("/api/products/" + productId, owner).json().get("stock").decimalValue();
    }

    void sell(long presentationId, int qty) throws Exception {
        api.post("/api/pos/sales", owner, """
                {"clientRef":"%s","lines":[{"presentationId":%d,"quantity":%d}],"payments":[{"method":"EFECTIVO","amount":99999999}]}
                """.formatted(UUID.randomUUID(), presentationId, qty)).expect(201);
    }

    void openShift() throws Exception {
        long reg = api.get("/api/registers", owner).json().get(0).get("id").asLong();
        api.post("/api/shifts/open", owner, "{\"registerId\":%d,\"openingCash\":0}".formatted(reg)).expect(200);
    }

    @Test
    void supplierCatalogPrimaryAndComparison() throws Exception {
        JsonNode p = product("Cerveza Águila", 3500, 2400, 10, 5);
        long pid = p.get("id").asLong();
        long a = supplier("Bavaria", 2).get("id").asLong();
        long b = supplier("Distribuidora El Mono", null).get("id").asLong();
        api.post("/api/suppliers", owner, "{\"name\":\"bavaria\"}").expect(400);
        api.post("/api/suppliers", owner, "{\"name\":\"X\",\"orderDays\":\"FUNDAY\"}").expect(400);
        catalog(a, pid, "Caja x24", 24, 55200, 1, 1);
        catalog(b, pid, "Six pack", 6, 14400, 2, 2);
        JsonNode comp = api.get("/api/products/" + pid + "/suppliers", owner).expect(200).json();
        assertThat(comp.get(0).get("supplierName").asText()).isEqualTo("Bavaria"); // 2.300 por unidad
        assertThat(comp.get(0).get("unitCost").decimalValue()).isEqualByComparingTo("2300");
        assertThat(comp.get(0).get("isPrimary").asBoolean()).isTrue(); // el primero queda como principal
        long itemB = comp.get(1).get("id").asLong();
        api.post("/api/supplier-products/" + itemB + "/primary", owner, null).expect(204);
        JsonNode after = api.get("/api/products/" + pid + "/suppliers", owner).json();
        assertThat(after.findValuesAsText("isPrimary")).containsExactly("false", "true");
        JsonNode sup = api.get("/api/suppliers/" + a, owner).expect(200).json();
        assertThat(sup.get("orderDays").asText()).isEqualTo("LUN,JUE");
        assertThat(sup.get("metrics").get("effectiveLeadDays").decimalValue()).isEqualByComparingTo("2");
        // Otra tienda no lo ve.
        api.get("/api/suppliers/" + a, newStore().ownerToken()).expect(404);
    }

    @Test
    void orderApprovalSendAndPartialReceptionUpdateStockCostAndSupplier() throws Exception {
        JsonNode p = product("Gaseosa 400", 3200, 1800, 4, 6);
        long pid = p.get("id").asLong();
        long s = supplier("Postobón", 3).get("id").asLong();
        catalog(s, pid, "Paca x12", 12, 21600, 1, 1);
        String compras = newEmployee(store, "COMPRAS");
        JsonNode o = api.post("/api/purchases", compras, """
                {"supplierId":%d,"lines":[{"productId":%d,"packs":2}]}""".formatted(s, pid)).expect(201).json();
        assertThat(o.get("estimatedTotal").decimalValue()).isEqualByComparingTo("43200");
        assertThat(o.get("lines").get(0).get("unitsOrdered").decimalValue()).isEqualByComparingTo("24");
        long id = o.get("id").asLong();
        // Compras no puede aprobar: queda por aprobar.
        assertThat(api.post("/api/purchases/" + id + "/submit", compras, null).expect(200).json().get("status").asText()).isEqualTo("POR_APROBAR");
        api.post("/api/purchases/" + id + "/approve", compras, null).expect(403);
        JsonNode alerts = api.get("/api/inventory/alerts", owner).json();
        assertThat(alerts.findValuesAsText("type")).contains("PEDIDO_POR_APROBAR");
        // El dueño modifica la cantidad y aprueba.
        api.put("/api/purchases/" + id, owner, "{\"supplierId\":%d,\"lines\":[{\"productId\":%d,\"packs\":3}]}".formatted(s, pid)).expect(200);
        JsonNode ap = api.post("/api/purchases/" + id + "/approve", owner, null).expect(200).json();
        assertThat(ap.get("status").asText()).isEqualTo("APROBADA");
        assertThat(ap.get("expectedDate").asText()).isEqualTo(LocalDate.now().plusDays(3).toString());
        JsonNode sent = api.post("/api/purchases/" + id + "/send", owner, "{\"via\":\"WHATSAPP\"}").expect(200).json();
        assertThat(sent.get("whatsappUrl").asText()).startsWith("https://wa.me/573001234567?text=");
        assertThat(sent.get("message").asText()).contains("3 Paca x12");
        // Ya en camino: no se vuelve a sugerir.
        JsonNode sug = api.get("/api/purchases/suggestions", owner).expect(200).json();
        assertThat(sug.toString()).doesNotContain("\"productId\":" + pid + ",");
        long lineId = ap.get("lines").get(0).get("id").asLong();
        // Llegan 24 de 36 a 2.000 c/u (subió), 12 faltan.
        JsonNode rec = api.post("/api/purchases/" + id + "/receive", owner, """
                {"invoiceNumber":"FV-991","lines":[{"orderLineId":%d,"unitsReceived":24,"unitsRejected":0,"unitCost":2000}]}
                """.formatted(lineId)).expect(200).json();
        assertThat(rec.get("status").asText()).isEqualTo("PARCIAL");
        assertThat(stock(pid)).isEqualByComparingTo("28");
        // Costo promedio: (4*1800 + 24*2000)/28 = 1971.43
        assertThat(api.get("/api/products/" + pid, owner).json().get("cost").decimalValue()).isEqualByComparingTo("1971.4286");
        JsonNode cat = api.get("/api/suppliers/" + s + "/catalog", owner).json();
        assertThat(cat.get(0).get("costPerPack").decimalValue()).isEqualByComparingTo("24000");
        assertThat(api.get("/api/suppliers/" + s + "/price-history", owner).json().get(0).get("source").asText()).isEqualTo("COMPRA");
        // El resto llega incompleto: 10 buenas y 2 faltantes -> cerrada.
        JsonNode fin = api.post("/api/purchases/" + id + "/receive", owner, """
                {"lines":[{"orderLineId":%d,"unitsReceived":10,"unitsRejected":2,"rejectReason":"FALTANTE"}]}
                """.formatted(lineId)).expect(200).json();
        assertThat(fin.get("status").asText()).isEqualTo("RECIBIDA");
        assertThat(fin.get("lines").get(0).get("unitsRejected").decimalValue()).isEqualByComparingTo("2");
        assertThat(fin.get("receipts")).hasSize(2);
        JsonNode m = api.get("/api/suppliers/" + s, owner).json().get("metrics");
        assertThat(m.get("fillRatePct").decimalValue()).isEqualByComparingTo("94.4"); // 34 de 36
        // 24 x 2.000 (precio de la factura) + 10 x 1.800 (sin costo digitado: se usa el pactado en la orden).
        assertThat(m.get("balance").decimalValue()).isEqualByComparingTo("66000");
        assertThat(m.get("onTimePct").decimalValue()).isEqualByComparingTo("100.0");
        api.post("/api/purchases/" + id + "/receive", owner, "{\"lines\":[{\"orderLineId\":%d,\"unitsReceived\":1}]}".formatted(lineId)).expect(400);
    }

    @Test
    void lateOrdersRaiseAlerts() throws Exception {
        JsonNode p = product("Maní", 2500, 1400, 20, 0);
        long s = supplier("Moto", 1).get("id").asLong();
        long id = api.post("/api/purchases", owner, "{\"supplierId\":%d,\"expectedDate\":\"%s\",\"lines\":[{\"productId\":%d,\"packs\":5,\"costPerPack\":1400}]}"
                .formatted(s, LocalDate.now().minusDays(2), p.get("id").asLong())).expect(201).json().get("id").asLong();
        api.post("/api/purchases/" + id + "/submit", owner, null).expect(200);
        assertThat(api.get("/api/purchases/late", owner).json()).hasSize(1);
        assertThat(api.get("/api/inventory/alerts", owner).json().findValuesAsText("type")).contains("PEDIDO_RETRASADO");
        api.post("/api/purchases/" + id + "/cancel", owner, "{\"reason\":\"\"}").expect(400);
        assertThat(api.post("/api/purchases/" + id + "/cancel", owner, "{\"reason\":\"No llegó\"}").expect(200).json().get("status").asText()).isEqualTo("CANCELADA");
    }

    @Test
    void lotsAreSoldFirstExpiredFirstAndCanBeWrittenOff() throws Exception {
        JsonNode p = api.post("/api/products", owner, """
                {"name":"Yogurt","unit":"UNIDAD","cost":1500,"trackLots":true,
                 "presentations":[{"name":"Unidad","factor":1,"price":2500,"isBase":true}]}""").expect(201).json();
        long pid = p.get("id").asLong();
        long s = supplier("Alpina", 1).get("id").asLong();
        api.post("/api/receipts", owner, """
                {"supplierId":%d,"lines":[{"productId":%d,"unitsReceived":10,"unitCost":1500,"lotCode":"L-LEJOS","expiryDate":"%s"},
                                          {"productId":%d,"unitsReceived":5,"unitCost":1500,"lotCode":"L-PRONTO","expiryDate":"%s"}]}
                """.formatted(s, pid, LocalDate.now().plusDays(60), pid, LocalDate.now().plusDays(5))).expect(201);
        // No se recibe mercancía ya vencida.
        api.post("/api/receipts", owner, """
                {"supplierId":%d,"lines":[{"productId":%d,"unitsReceived":1,"expiryDate":"%s"}]}""".formatted(s, pid, LocalDate.now().minusDays(1))).expect(400);
        openShift();
        sell(base(p), 3);
        JsonNode lots = api.get("/api/products/" + pid + "/lots", owner).expect(200).json();
        assertThat(lots.get(0).get("lotCode").asText()).isEqualTo("L-PRONTO");
        assertThat(lots.get(0).get("remaining").decimalValue()).isEqualByComparingTo("2");
        JsonNode expiring = api.get("/api/inventory/expiring", owner).expect(200).json();
        assertThat(expiring).hasSize(1);
        api.post("/api/lots/" + lots.get(0).get("id").asLong() + "/write-off", owner, "{\"reason\":\"Se dañó la nevera\"}").expect(200);
        assertThat(stock(pid)).isEqualByComparingTo("10");
        assertThat(api.get("/api/inventory/expiring", owner).json()).isEmpty();
    }

    @Test
    void blindCountAdjustsOnlyTheDifferenceAndFlagsUnusual() throws Exception {
        JsonNode a = product("Cigarrillos", 11500, 9800, 20, 0);
        JsonNode b = product("Chicles", 500, 200, 50, 0);
        String inv = newEmployee(store, "INVENTARIO");
        JsonNode c = api.post("/api/counts", inv, "{\"name\":\"Conteo de viernes\"}").expect(201).json();
        long id = c.get("id").asLong();
        assertThat(c.get("totalLines").asInt()).isEqualTo(2);
        // Durante el conteo se vende 1 cigarrillo.
        openShift();
        sell(base(a), 1);
        api.post("/api/counts/" + id + "/entries", inv, "{\"productId\":%d,\"counted\":10,\"add\":false}".formatted(a.get("id").asLong())).expect(200);
        api.post("/api/counts/" + id + "/entries", inv, "{\"productId\":%d,\"counted\":30,\"add\":false}".formatted(b.get("id").asLong())).expect(200);
        api.post("/api/counts/" + id + "/entries", inv, "{\"productId\":%d,\"counted\":19,\"add\":true}".formatted(b.get("id").asLong())).expect(200);
        // Un cajero no ve el teórico en un conteo a ciegas.
        String cajero = newEmployee(store, "CAJERO");
        api.get("/api/counts/" + id, cajero).expect(403);
        JsonNode closed = api.post("/api/counts/" + id + "/close", owner, null).expect(200).json();
        assertThat(closed.get("status").asText()).isEqualTo("CERRADO");
        // Cigarrillos: teórico 20, contado 10 -> -10 aplicado sobre el actual 19 = 9.
        assertThat(stock(a.get("id").asLong())).isEqualByComparingTo("9");
        assertThat(stock(b.get("id").asLong())).isEqualByComparingTo("49");
        assertThat(closed.get("unusualLines").asInt()).isEqualTo(1);
        assertThat(closed.get("shrinkageValue").decimalValue()).isEqualByComparingTo("98200");
        assertThat(api.get("/api/inventory/alerts", owner).json().findValuesAsText("type")).contains("DIFERENCIA_INUSUAL");
        JsonNode byUser = api.get("/api/inventory/adjustments-by-user", owner).expect(200).json();
        assertThat(byUser.get(0).get("userName").asText()).isEqualTo("Dueño Prueba");
    }

    @Test
    void insightsAndReplenishmentSuggestion() throws Exception {
        JsonNode fast = product("Águila lata", 3500, 2400, 10, 5);
        JsonNode slow = product("Licor raro", 90000, 60000, 3, 0);
        long s = supplier("Bavaria", 3).get("id").asLong();
        catalog(s, fast.get("id").asLong(), "Six pack", 6, 14400, 1, 1);
        openShift();
        sell(base(fast), 6);
        JsonNode ins = api.get("/api/inventory/insights", owner).expect(200).json();
        JsonNode f = null, sl = null;
        for (JsonNode x : ins.get("products")) {
            if (x.get("productId").asLong() == fast.get("id").asLong()) f = x;
            if (x.get("productId").asLong() == slow.get("id").asLong()) sl = x;
        }
        assertThat(f.get("soldWindow").decimalValue()).isEqualByComparingTo("6");
        // d = 0,6*(6/7) + 0,4*(6/30) = 0,594
        assertThat(f.get("dailyVelocity").decimalValue()).isEqualByComparingTo("0.59");
        assertThat(f.get("coverageDays").decimalValue()).isEqualByComparingTo("6.7");
        assertThat(f.get("abc").asText()).isEqualTo("A");
        assertThat(f.get("flags").toString()).contains("BAJO_MINIMO");
        assertThat(sl.get("abc").asText()).isEqualTo("C");
        assertThat(ins.get("summary").get("totalValue").decimalValue()).isEqualByComparingTo(new BigDecimal(4 * 2400 + 3 * 60000));
        JsonNode sug = api.get("/api/purchases/suggestions", owner).expect(200).json();
        JsonNode group = sug.get("suppliers").get(0);
        assertThat(group.get("supplierName").asText()).isEqualTo("Bavaria");
        JsonNode line = group.get("lines").get(0);
        assertThat(line.get("productName").asText()).isEqualTo("Águila lata");
        assertThat(line.get("packName").asText()).isEqualTo("Six pack");
        assertThat(line.get("packs").decimalValue()).isEqualByComparingTo("1");
        assertThat(line.get("estimatedCost").decimalValue()).isEqualByComparingTo("14400");
        assertThat(line.get("reason").asText()).contains("al día").contains("Six pack");
        // Crear la orden desde la sugerencia.
        api.post("/api/purchases", owner, "{\"supplierId\":%d,\"source\":\"SUGERIDA\",\"lines\":[{\"productId\":%d,\"packs\":1,\"reason\":\"x\"}]}"
                .formatted(s, fast.get("id").asLong())).expect(201);
    }

    @Test
    void supplierReturnTakesStockOut() throws Exception {
        JsonNode p = product("Pan tajado", 6000, 4000, 10, 0);
        long s = supplier("Bimbo", 1).get("id").asLong();
        api.post("/api/suppliers/" + s + "/returns", owner, "{\"productId\":%d,\"quantity\":3,\"reason\":\"Venía mohoso\"}"
                .formatted(p.get("id").asLong())).expect(201);
        assertThat(stock(p.get("id").asLong())).isEqualByComparingTo("7");
        assertThat(api.get("/api/suppliers/" + s + "/returns", owner).json().get(0).get("reason").asText()).isEqualTo("Venía mohoso");
    }

    @Test
    void inventoryParamsValidation() throws Exception {
        String body = """
                {"analysisDays":30,"noRotationDays":30,"excessDays":90,"expiryAlertDays":15,"defaultLeadDays":3,"reviewDays":7,
                 "unusualDiffPct":10,"unusualDiffValue":50000,"abcAPct":80,"abcBPct":%s,"purchaseBudget":2000000}""";
        api.put("/api/settings/pos/inventory", owner, body.formatted("70")).expect(400);
        JsonNode st = api.put("/api/settings/pos/inventory", owner, body.formatted("95")).expect(200).json();
        assertThat(st.get("purchaseBudget").decimalValue()).isEqualByComparingTo("2000000");
        assertThat(api.get("/api/purchases/suggestions", owner).json().get("budgetLeft").decimalValue()).isEqualByComparingTo("2000000");
    }
}
