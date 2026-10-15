package com.tiendaposs.pos;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.support.AbstractIntegrationTest;
import com.tiendaposs.support.Api;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Flujos de punto de venta de punta a punta contra la base de datos real. */
class PosIT extends AbstractIntegrationTest {

    Api api;
    Store store;
    String owner;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, json);
        store = newStore();
        owner = store.ownerToken();
    }

    // ---------------- ayudas ----------------

    /** Crea un producto con unidad base y, opcionalmente, una caja; devuelve el producto. */
    JsonNode product(String name, int price, int cost, int stock, String barcode, Integer boxFactor, Integer boxPrice, int minPrice) throws Exception {
        String box = boxFactor == null ? "" : """
                ,{"name":"Caja x%d","factor":%d,"price":%d,"isBase":false}""".formatted(boxFactor, boxFactor, boxPrice);
        return api.post("/api/products", owner, """
                {"name":"%s","unit":"UNIDAD","cost":%d,"initialStock":%d,"stockMin":5,
                 "presentations":[{"name":"Unidad","factor":1,"barcode":%s,"price":%d,"minPrice":%d,"isBase":true}%s]}
                """.formatted(name, cost, stock, barcode == null ? "null" : "\"" + barcode + "\"", price, minPrice, box)).expect(201).json();
    }

    long base(JsonNode p) {
        for (JsonNode pr : p.get("presentations")) if (pr.get("isBase").asBoolean()) return pr.get("id").asLong();
        throw new IllegalStateException();
    }

    long pres(JsonNode p, String name) {
        for (JsonNode pr : p.get("presentations")) if (pr.get("name").asText().equals(name)) return pr.get("id").asLong();
        throw new IllegalStateException();
    }

    long register(String token) throws Exception {
        return api.get("/api/registers", token).expect(200).json().get(0).get("id").asLong();
    }

    JsonNode openShift(String token, int cash) throws Exception {
        return api.post("/api/shifts/open", token, "{\"registerId\":%d,\"openingCash\":%d}".formatted(register(token), cash)).expect(200).json();
    }

    BigDecimal stock(long productId) throws Exception {
        return new BigDecimal(api.get("/api/products/" + productId, owner).expect(200).json().get("stock").asText());
    }

    String sale(String lines, String payments) {
        return "{\"clientRef\":\"%s\",\"lines\":[%s],\"payments\":[%s]}".formatted(UUID.randomUUID(), lines, payments);
    }

    String line(long presentationId, String qty) {
        return "{\"presentationId\":%d,\"quantity\":%s}".formatted(presentationId, qty);
    }

    String pinFor(String token) throws Exception {
        api.post("/api/auth/pin", token, "{\"currentPassword\":\"Clave1234\",\"pin\":\"4826\"}").expect(204);
        return "4826";
    }

    String approval(String requesterToken, long approverId, String pin, String permission) throws Exception {
        return api.post("/api/pos/approvals", requesterToken, "{\"approverId\":%d,\"secret\":\"%s\",\"permission\":\"%s\"}"
                .formatted(approverId, pin, permission)).expect(200).json().get("token").asText();
    }

    long ownerId() throws Exception {
        return api.get("/api/auth/me", owner).json().get("id").asLong();
    }

    // ---------------- catálogo e inventario ----------------

    @Test
    void productWithPresentationsBarcodeAndMargin() throws Exception {
        JsonNode p = product("Cerveza Águila", 3500, 2400, 48, "7702004003508", 24, 78000, 3200);
        assertThat(p.get("sku").asText()).startsWith("P");
        assertThat(p.get("stock").decimalValue()).isEqualByComparingTo("48");
        assertThat(p.get("presentations")).hasSize(2);
        assertThat(p.get("presentations").get(0).get("marginPct").decimalValue()).isEqualByComparingTo("31.4");
        JsonNode byCode = api.get("/api/products/barcode/7702004003508", owner).expect(200).json();
        assertThat(byCode.get("id").asLong()).isEqualTo(p.get("id").asLong());
        // Código repetido en otro producto: error claro.
        api.post("/api/products", owner, """
                {"name":"Otra","unit":"UNIDAD","presentations":[{"name":"Unidad","factor":1,"barcode":"7702004003508","price":100,"isBase":true}]}
                """).expect(400);
        // El cajero no ve costos.
        String cajero = newEmployee(store, "CAJERO");
        JsonNode seen = api.get("/api/products/" + p.get("id").asLong(), cajero).expect(200).json();
        assertThat(seen.has("cost")).isFalse();
    }

    @Test
    void priceChangesAreLoggedAndNeedPermission() throws Exception {
        JsonNode p = product("Gaseosa", 3000, 1800, 10, null, null, null, 0);
        long id = p.get("id").asLong();
        long baseId = base(p);
        String body = """
                {"name":"Gaseosa","unit":"UNIDAD","priceChangeReason":"Subió el proveedor",
                 "presentations":[{"id":%d,"name":"Unidad","factor":1,"price":3200,"isBase":true}]}""".formatted(baseId);
        String inv = newEmployee(store, "INVENTARIO");
        api.put("/api/products/" + id, inv, body).expect(403);
        api.put("/api/products/" + id, owner, body).expect(200);
        JsonNode hist = api.get("/api/products/" + id + "/price-history", owner).expect(200).json();
        assertThat(hist.get(0).get("field").asText()).isEqualTo("PRECIO");
        assertThat(hist.get(0).get("newValue").decimalValue()).isEqualByComparingTo("3200");
        assertThat(hist.get(0).get("reason").asText()).isEqualTo("Subió el proveedor");
    }

    @Test
    void adjustmentsNeedReasonAndNegativeStockIsBlocked() throws Exception {
        JsonNode p = product("Hielo", 6000, 3800, 3, null, null, null, 0);
        long id = p.get("id").asLong();
        api.post("/api/inventory/adjust", owner, "{\"productId\":%d,\"mode\":\"ENTRADA\",\"quantity\":10,\"reason\":\"\"}".formatted(id)).expect(400);
        api.post("/api/inventory/adjust", owner, "{\"productId\":%d,\"mode\":\"ENTRADA\",\"quantity\":10,\"unitCost\":4200,\"reason\":\"Llegó pedido\"}".formatted(id)).expect(200);
        assertThat(stock(id)).isEqualByComparingTo("13");
        // Costo promedio: (3*3800 + 10*4200)/13 = 4107.69
        assertThat(api.get("/api/products/" + id, owner).json().get("cost").decimalValue()).isEqualByComparingTo("4107.6923");
        api.post("/api/inventory/adjust", owner, "{\"productId\":%d,\"mode\":\"SALIDA\",\"quantity\":50,\"reason\":\"x\"}".formatted(id)).expect(409);
        api.post("/api/inventory/adjust", owner, "{\"productId\":%d,\"mode\":\"CONTEO\",\"quantity\":11,\"reason\":\"Conteo semanal\"}".formatted(id)).expect(200);
        assertThat(stock(id)).isEqualByComparingTo("11");
        JsonNode movs = api.get("/api/inventory/movements?productId=" + id, owner).expect(200).json().get("items");
        assertThat(movs.findValuesAsText("type")).containsExactly("AJUSTE_SALIDA", "AJUSTE_ENTRADA", "INICIAL");
        assertThat(movs.get(0).get("userName").asText()).isEqualTo("Dueño Prueba");
        String cajero = newEmployee(store, "CAJERO");
        api.post("/api/inventory/adjust", cajero, "{\"productId\":%d,\"mode\":\"ENTRADA\",\"quantity\":1,\"reason\":\"x\"}".formatted(id)).expect(403);
    }

    @Test
    void csvImportCreatesUpdatesAndReportsErrors() throws Exception {
        String csv = """
                sku;nombre;categoria;subcategoria;marca;unidad;codigo_barras;precio;costo;stock;presentaciones
                CER-1;Poker lata;Cervezas;Nacionales;Bavaria;UNIDAD;770111;3.500;2400;24;Six pack:6:19000:
                CER-2;Club Colombia;Cervezas;;Bavaria;UNIDAD;;4000;2900;12;
                ;Sin precio;Snacks;;;;;;;;
                """;
        JsonNode r = api.post("/api/products/import", owner, java.util.Map.of("csv", csv)).expect(200).json();
        assertThat(r.get("created").asInt()).isEqualTo(2);
        assertThat(r.get("errors")).hasSize(1);
        // Reimportar actualiza precio y stock (queda como movimiento de importación).
        String csv2 = "sku;nombre;precio;stock\nCER-1;Poker lata;3600;30\n";
        JsonNode r2 = api.post("/api/products/import", owner, java.util.Map.of("csv", csv2)).expect(200).json();
        assertThat(r2.get("updated").asInt()).isEqualTo(1);
        JsonNode list = api.get("/api/products?q=poker", owner).expect(200).json().get("items");
        assertThat(list.get(0).get("stock").decimalValue()).isEqualByComparingTo("30");
        assertThat(list.get(0).get("presentations")).hasSize(2);
        assertThat(list.get(0).get("presentations").get(0).get("price").decimalValue()).isEqualByComparingTo("3600");
        JsonNode cats = api.get("/api/categories", owner).json();
        assertThat(cats.findValuesAsText("name")).contains("Cervezas", "Nacionales");
        // La fila con error se deshizo completa: no dejó creada su categoría.
        assertThat(cats.findValuesAsText("name")).doesNotContain("Snacks");
        // Filtrar por la categoría padre incluye la subcategoría.
        long cervezas = 0;
        for (JsonNode c : cats) if (c.get("name").asText().equals("Cervezas")) cervezas = c.get("id").asLong();
        assertThat(api.get("/api/products?categoryId=" + cervezas, owner).json().get("total").asInt()).isEqualTo(2);
        String exported = api.get("/api/products/export", owner).expect(200).raw();
        assertThat(exported).contains("CER-1;Poker lata;Cervezas;Nacionales").contains("Six pack:6:19000");
    }

    @Test
    void bulkPriceUpdateWithRounding() throws Exception {
        JsonNode a = product("A", 1000, 500, 1, null, null, null, 0);
        JsonNode b = product("B", 2350, 500, 1, null, null, null, 0);
        api.post("/api/products/bulk", owner, "{\"productIds\":[%d,%d],\"action\":\"PRECIO_PORCENTAJE\",\"value\":10,\"roundTo\":50}"
                .formatted(a.get("id").asLong(), b.get("id").asLong())).expect(200);
        assertThat(api.get("/api/products/" + a.get("id").asLong(), owner).json().get("presentations").get(0).get("price").decimalValue())
                .isEqualByComparingTo("1100");
        assertThat(api.get("/api/products/" + b.get("id").asLong(), owner).json().get("presentations").get(0).get("price").decimalValue())
                .isEqualByComparingTo("2600");
    }

    // ---------------- caja y ventas ----------------

    @Test
    void cannotSellWithoutOpenShift() throws Exception {
        JsonNode p = product("Mani", 2000, 1000, 10, null, null, null, 0);
        api.post("/api/pos/sales", owner, sale(line(base(p), "1"), "{\"method\":\"EFECTIVO\",\"amount\":2000}")).expect(409);
    }

    @Test
    void cashSaleWithChangeDeductsStockAndFeedsShift() throws Exception {
        JsonNode p = product("Águila", 3500, 2400, 48, null, 24, 78000, 0);
        openShift(owner, 200000);
        JsonNode s = api.post("/api/pos/sales", owner, sale(line(base(p), "2") + "," + line(pres(p, "Caja x24"), "1"),
                "{\"method\":\"EFECTIVO\",\"amount\":100000}")).expect(201).json();
        assertThat(s.get("number").asLong()).isEqualTo(1);
        assertThat(s.get("total").decimalValue()).isEqualByComparingTo("85000");
        assertThat(s.get("changeAmount").decimalValue()).isEqualByComparingTo("15000");
        assertThat(s.get("costTotal").decimalValue()).isEqualByComparingTo(new BigDecimal(26 * 2400));
        assertThat(stock(p.get("id").asLong())).isEqualByComparingTo("22");
        JsonNode shift = api.get("/api/shifts/current", owner).expect(200).json();
        assertThat(shift.get("expectedCash").decimalValue()).isEqualByComparingTo("285000");
        assertThat(shift.get("salesCount").asInt()).isEqualTo(1);
    }

    @Test
    void mixedPaymentsAndValidation() throws Exception {
        JsonNode p = product("Ron", 48000, 30000, 5, null, null, null, 0);
        openShift(owner, 0);
        api.post("/api/pos/sales", owner, sale(line(base(p), "1"), "{\"method\":\"EFECTIVO\",\"amount\":20000}")).expect(400);
        api.post("/api/pos/sales", owner, sale(line(base(p), "1"), "{\"method\":\"NEQUI\",\"amount\":50000}")).expect(400);
        JsonNode s = api.post("/api/pos/sales", owner, sale(line(base(p), "1"),
                "{\"method\":\"EFECTIVO\",\"amount\":20000},{\"method\":\"NEQUI\",\"amount\":28000,\"reference\":\"M-9281\"}")).expect(201).json();
        assertThat(s.get("payments")).hasSize(2);
        assertThat(s.get("payments").get(1).get("reference").asText()).isEqualTo("M-9281");
        JsonNode shift = api.get("/api/shifts/current", owner).json();
        assertThat(shift.get("expectedCash").decimalValue()).isEqualByComparingTo("20000");
        assertThat(shift.get("byMethod").findValuesAsText("method")).contains("EFECTIVO", "NEQUI");
    }

    @Test
    void sameClientRefIsNotChargedTwice() throws Exception {
        JsonNode p = product("Chicle", 500, 200, 10, null, null, null, 0);
        openShift(owner, 0);
        String body = sale(line(base(p), "1"), "{\"method\":\"EFECTIVO\",\"amount\":500}");
        long a = api.post("/api/pos/sales", owner, body).expect(201).json().get("id").asLong();
        long b = api.post("/api/pos/sales", owner, body).expect(201).json().get("id").asLong();
        assertThat(a).isEqualTo(b);
        assertThat(stock(p.get("id").asLong())).isEqualByComparingTo("9");
    }

    @Test
    void discountsOverLimitNeedSupervisorPin() throws Exception {
        JsonNode p = product("Whisky", 100000, 60000, 5, null, null, null, 85000);
        String cajero = newEmployee(store, "CAJERO");
        openShift(cajero, 0);
        // 5% está dentro del límite (10%).
        api.post("/api/pos/sales", cajero, sale("{\"presentationId\":%d,\"quantity\":1,\"discountPct\":5}".formatted(base(p)),
                "{\"method\":\"EFECTIVO\",\"amount\":95000}")).expect(201);
        // 12% supera el límite: pide autorización.
        String over = sale("{\"presentationId\":%d,\"quantity\":1,\"discountPct\":12}".formatted(base(p)), "{\"method\":\"EFECTIVO\",\"amount\":88000}");
        Api.Res denied = api.post("/api/pos/sales", cajero, over).expect(403);
        assertThat(denied.json().get("fields").get("approval").asText()).isEqualTo("POS_DESCUENTO_ESPECIAL");
        // El dueño pone su PIN; un PIN errado no sirve.
        String pin = pinFor(owner);
        api.post("/api/pos/approvals", cajero, "{\"approverId\":%d,\"secret\":\"0000\",\"permission\":\"POS_DESCUENTO_ESPECIAL\"}"
                .formatted(ownerId())).expect(401);
        String token = approval(cajero, ownerId(), pin, "POS_DESCUENTO_ESPECIAL");
        String withApproval = over.replace("\"lines\"", "\"approvals\":[\"" + token + "\"],\"lines\"");
        JsonNode s = api.post("/api/pos/sales", cajero, withApproval).expect(201).json();
        assertThat(s.get("discountAuthorizedBy").asText()).isEqualTo("Dueño Prueba");
        // Bajo el precio mínimo (85.000) necesita otro permiso.
        String below = sale("{\"presentationId\":%d,\"quantity\":1,\"unitPrice\":80000}".formatted(base(p)), "{\"method\":\"EFECTIVO\",\"amount\":80000}");
        assertThat(api.post("/api/pos/sales", cajero, below).expect(403).json().get("fields").get("approval").asText())
                .isEqualTo("POS_DESCUENTO_ESPECIAL");
    }

    @Test
    void promotionsApplyAutomatically() throws Exception {
        JsonNode p = product("Pony Malta", 3000, 1500, 20, null, null, null, 0);
        api.post("/api/pos/promotions", owner, "{\"name\":\"3x2 Pony\",\"type\":\"LLEVA_PAGA\",\"productId\":%d,\"bundleQty\":3,\"payQty\":2}"
                .formatted(p.get("id").asLong())).expect(201);
        openShift(owner, 0);
        JsonNode s = api.post("/api/pos/sales", owner, sale(line(base(p), "3"), "{\"method\":\"EFECTIVO\",\"amount\":6000}")).expect(201).json();
        assertThat(s.get("total").decimalValue()).isEqualByComparingTo("6000");
        assertThat(s.get("lines").get(0).get("promoName").asText()).isEqualTo("3x2 Pony");
    }

    @Test
    void voidRestoresStockOnlyWhileShiftIsOpen() throws Exception {
        JsonNode p = product("Cigarrillos", 11500, 9800, 10, null, null, null, 0);
        String cajero = newEmployee(store, "CAJERO");
        JsonNode shift = openShift(cajero, 0);
        long saleId = api.post("/api/pos/sales", cajero, sale(line(base(p), "2"), "{\"method\":\"EFECTIVO\",\"amount\":23000}"))
                .expect(201).json().get("id").asLong();
        assertThat(stock(p.get("id").asLong())).isEqualByComparingTo("8");
        api.post("/api/pos/sales/" + saleId + "/void", cajero, "{\"reason\":\"Error de digitación\"}").expect(403);
        String token = approval(cajero, ownerId(), pinFor(owner), "POS_ANULAR");
        JsonNode v = api.post("/api/pos/sales/" + saleId + "/void", cajero,
                "{\"reason\":\"Error de digitación\",\"approvals\":[\"" + token + "\"]}").expect(200).json();
        assertThat(v.get("status").asText()).isEqualTo("ANULADA");
        assertThat(v.get("voidAuthorizedBy").asText()).isEqualTo("Dueño Prueba");
        assertThat(stock(p.get("id").asLong())).isEqualByComparingTo("10");
        JsonNode sum = api.get("/api/shifts/" + shift.get("id").asLong(), cajero).json();
        assertThat(sum.get("expectedCash").decimalValue()).isEqualByComparingTo("0");
        assertThat(sum.get("voidedCount").asInt()).isEqualTo(1);
    }

    @Test
    void partialReturnRefundsCashAndRestocksOnlyGoodUnits() throws Exception {
        JsonNode p = product("Papas", 5200, 3000, 10, null, null, null, 0);
        JsonNode shift = openShift(owner, 50000);
        JsonNode s = api.post("/api/pos/sales", owner, sale(line(base(p), "3"), "{\"method\":\"EFECTIVO\",\"amount\":15600}")).expect(201).json();
        long lineId = s.get("lines").get(0).get("id").asLong();
        JsonNode r = api.post("/api/pos/sales/" + s.get("id").asLong() + "/returns", owner, """
                {"lines":[{"saleLineId":%d,"quantity":1,"restock":true,"condition":"BUENO"}],"reason":"No le gustó","refundMethod":"EFECTIVO"}
                """.formatted(lineId)).expect(200).json();
        assertThat(r.get("refundedTotal").decimalValue()).isEqualByComparingTo("5200");
        api.post("/api/pos/sales/" + s.get("id").asLong() + "/returns", owner, """
                {"lines":[{"saleLineId":%d,"quantity":1,"restock":true,"condition":"DANADO"}],"reason":"Paquete roto","refundMethod":"EFECTIVO"}
                """.formatted(lineId)).expect(200);
        // 10 - 3 + 1 (bueno) = 8; el dañado no vuelve.
        assertThat(stock(p.get("id").asLong())).isEqualByComparingTo("8");
        api.post("/api/pos/sales/" + s.get("id").asLong() + "/returns", owner, """
                {"lines":[{"saleLineId":%d,"quantity":2,"restock":true}],"reason":"x","refundMethod":"EFECTIVO"}
                """.formatted(lineId)).expect(400);
        JsonNode sum = api.get("/api/shifts/" + shift.get("id").asLong(), owner).json();
        assertThat(sum.get("expectedCash").decimalValue()).isEqualByComparingTo(new BigDecimal(50000 + 15600 - 10400));
        // Con devoluciones ya no se puede anular completa.
        api.post("/api/pos/sales/" + s.get("id").asLong() + "/void", owner, "{\"reason\":\"x\"}").expect(400);
    }

    @Test
    void cashMovementsAndCloseWithDifferenceNeedsNote() throws Exception {
        openShift(owner, 100000);
        long id = api.get("/api/shifts/current", owner).json().get("id").asLong();
        api.post("/api/shifts/" + id + "/movements", owner, "{\"type\":\"GASTO\",\"amount\":15000,\"reason\":\"Bolsas\"}").expect(200);
        api.post("/api/shifts/" + id + "/movements", owner, "{\"type\":\"RETIRO\",\"amount\":50000,\"reason\":\"Alivio de caja\"}").expect(200);
        api.post("/api/shifts/" + id + "/movements", owner, "{\"type\":\"INGRESO\",\"amount\":10000,\"reason\":\"Sencillo\"}").expect(200);
        api.post("/api/shifts/" + id + "/movements", owner, "{\"type\":\"RETIRO\",\"amount\":999999,\"reason\":\"x\"}").expect(400);
        JsonNode s = api.get("/api/shifts/" + id, owner).json();
        assertThat(s.get("expectedCash").decimalValue()).isEqualByComparingTo("45000");
        // Faltan 2.000: exige explicación.
        api.post("/api/shifts/" + id + "/close", owner, "{\"countDetail\":\"20000x2,1000x3\"}").expect(400);
        JsonNode closed = api.post("/api/shifts/" + id + "/close", owner,
                "{\"countDetail\":\"20000x2,1000x3\",\"closeNotes\":\"Se dio mal un cambio\"}").expect(200).json();
        assertThat(closed.get("difference").decimalValue()).isEqualByComparingTo("-2000");
        assertThat(closed.get("status").asText()).isEqualTo("CLOSED");
        assertThat(api.get("/api/shifts/current", owner).status()).isEqualTo(204);
        JsonNode report = api.get("/api/shifts/report", owner).expect(200).json();
        assertThat(report.get(0).get("shortages").decimalValue()).isEqualByComparingTo("2000");
    }

    @Test
    void oneOpenShiftPerRegisterAndPerUser() throws Exception {
        openShift(owner, 0);
        String sup = newEmployee(store, "SUPERVISOR");
        api.post("/api/shifts/open", sup, "{\"registerId\":%d,\"openingCash\":0}".formatted(register(sup))).expect(409);
        api.post("/api/shifts/open", owner, "{\"registerId\":%d,\"openingCash\":0}".formatted(register(owner))).expect(409);
    }

    // ---------------- cuentas abiertas ----------------

    @Test
    void openAccountsDeductStockOnlyWhenCharged() throws Exception {
        JsonNode p = product("Cerveza Poker", 3500, 2300, 30, null, null, null, 0);
        openShift(owner, 0);
        JsonNode acc = api.post("/api/pos/accounts", owner, "{\"label\":\"Mesa del fondo\"}").expect(201).json();
        long id = acc.get("id").asLong();
        api.post("/api/pos/accounts", owner, "{\"label\":\"mesa del FONDO\"}").expect(400);
        api.post("/api/pos/accounts/" + id + "/lines", owner, "{\"line\":" + line(base(p), "2") + "}").expect(200);
        JsonNode after = api.post("/api/pos/accounts/" + id + "/lines", owner, "{\"line\":" + line(base(p), "3") + "}").expect(200).json();
        assertThat(after.get("lines")).hasSize(1);
        assertThat(after.get("lines").get(0).get("quantity").decimalValue()).isEqualByComparingTo("5");
        assertThat(after.get("total").decimalValue()).isEqualByComparingTo("17500");
        assertThat(stock(p.get("id").asLong())).isEqualByComparingTo("30");
        JsonNode list = api.get("/api/pos/accounts", owner).expect(200).json();
        assertThat(list.get(0).get("itemCount").asInt()).isEqualTo(5);
        JsonNode paid = api.post("/api/pos/accounts/" + id + "/checkout", owner, "{\"payments\":[{\"method\":\"EFECTIVO\",\"amount\":20000}]}")
                .expect(200).json();
        assertThat(paid.get("status").asText()).isEqualTo("PAGADA");
        assertThat(paid.get("changeAmount").decimalValue()).isEqualByComparingTo("2500");
        assertThat(stock(p.get("id").asLong())).isEqualByComparingTo("25");
        assertThat(api.get("/api/pos/accounts", owner).json()).isEmpty();
        api.post("/api/pos/accounts/" + id + "/checkout", owner, "{\"payments\":[{\"method\":\"EFECTIVO\",\"amount\":20000}]}").expect(409);
    }

    @Test
    void openAccountsLimitIsEnforced() throws Exception {
        JsonNode st = api.get("/api/settings/pos", owner).expect(200).json();
        ((com.fasterxml.jackson.databind.node.ObjectNode) st).put("openAccountsLimit", 2);
        ((com.fasterxml.jackson.databind.node.ObjectNode) st).set("paymentMethods", json.readTree("[\"EFECTIVO\",\"NEQUI\"]"));
        api.put("/api/settings/pos", owner, st).expect(200);
        api.post("/api/pos/accounts", owner, "{\"label\":\"Uno\"}").expect(201);
        api.post("/api/pos/accounts", owner, "{\"label\":\"Dos\"}").expect(201);
        Api.Res third = api.post("/api/pos/accounts", owner, "{\"label\":\"Tres\"}").expect(409);
        assertThat(third.json().get("message").asText()).contains("2 cuentas abiertas");
    }

    @Test
    void cancellingAccountWithItemsNeedsVoidPermission() throws Exception {
        JsonNode p = product("Aguardiente", 48000, 36000, 5, null, null, null, 0);
        String cajero = newEmployee(store, "CAJERO");
        long id = api.post("/api/pos/accounts", cajero, "{\"label\":\"Don Pedro\"}").expect(201).json().get("id").asLong();
        api.post("/api/pos/accounts/" + id + "/lines", cajero, "{\"line\":" + line(base(p), "1") + "}").expect(200);
        api.post("/api/pos/accounts/" + id + "/cancel", cajero, "{\"reason\":\"Se fue\"}").expect(403);
        api.post("/api/pos/accounts/" + id + "/cancel", owner, "{\"reason\":\"Se fue sin pagar\"}").expect(204);
        assertThat(stock(p.get("id").asLong())).isEqualByComparingTo("5");
    }

    @Test
    void holdAndRecoverSale() throws Exception {
        JsonNode p = product("Agua", 2000, 900, 10, null, null, null, 0);
        JsonNode h = api.post("/api/pos/holds", owner, "{\"label\":\"Señora del bolso\",\"lines\":[" + line(base(p), "2") + "]}").expect(201).json();
        assertThat(api.get("/api/pos/holds", owner).json()).hasSize(1);
        JsonNode rec = api.post("/api/pos/holds/" + h.get("id").asLong() + "/recover", owner, null).expect(200).json();
        assertThat(rec.get("lines").get(0).get("quantity").decimalValue()).isEqualByComparingTo("2");
        assertThat(api.get("/api/pos/holds", owner).json()).isEmpty();
        assertThat(stock(p.get("id").asLong())).isEqualByComparingTo("10");
    }

    // ---------------- sin internet ----------------

    @Test
    void offlineSaleIsAcceptedEvenWithoutStockAndFlagged() throws Exception {
        JsonNode p = product("Gaseosa 400", 3200, 1800, 1, null, null, null, 0);
        JsonNode shift = openShift(owner, 0);
        String body = """
                {"clientRef":"%s","offline":true,"offlineShiftId":%d,"offlineCreatedAt":"2026-01-01T15:00:00Z",
                 "lines":[{"presentationId":%d,"quantity":3,"unitPrice":3000}],"payments":[{"method":"EFECTIVO","amount":9000}]}
                """.formatted(UUID.randomUUID(), shift.get("id").asLong(), base(p));
        JsonNode s = api.post("/api/pos/sales", owner, body).expect(201).json();
        assertThat(s.get("offline").asBoolean()).isTrue();
        assertThat(s.get("total").decimalValue()).isEqualByComparingTo("9000");
        assertThat(s.get("reviewFlags").toString()).contains("STOCK_NEGATIVO", "PRECIO_DIFERENTE");
        assertThat(stock(p.get("id").asLong())).isEqualByComparingTo("-2");
        JsonNode review = api.get("/api/pos/sales?onlyReview=true", owner).expect(200).json();
        assertThat(review.get("total").asInt()).isEqualTo(1);
        api.post("/api/pos/sales/" + s.get("id").asLong() + "/reviewed", owner, null).expect(200);
        assertThat(api.get("/api/pos/sales?onlyReview=true", owner).json().get("total").asInt()).isEqualTo(0);
    }

    @Test
    void storesCannotSeeEachOthersSales() throws Exception {
        JsonNode p = product("Bon Bon Bum", 500, 200, 10, null, null, null, 0);
        openShift(owner, 0);
        long saleId = api.post("/api/pos/sales", owner, sale(line(base(p), "1"), "{\"method\":\"EFECTIVO\",\"amount\":500}"))
                .expect(201).json().get("id").asLong();
        Store other = newStore();
        api.get("/api/pos/sales/" + saleId, other.ownerToken()).expect(404);
        api.get("/api/products/" + p.get("id").asLong(), other.ownerToken()).expect(404);
        api.post("/api/shifts/open", other.ownerToken(), "{\"registerId\":%d,\"openingCash\":0}".formatted(register(owner))).expect(400);
        api.post("/api/pos/sales", other.ownerToken(), sale(line(base(p), "1"), "{\"method\":\"EFECTIVO\",\"amount\":500}")).expect(409);
    }

    @Test
    void receiptHasStoreDataAndSaleDetail() throws Exception {
        JsonNode p = product("Halls", 1000, 500, 10, null, null, null, 0);
        openShift(owner, 0);
        long id = api.post("/api/pos/sales", owner, sale(line(base(p), "1"), "{\"method\":\"EFECTIVO\",\"amount\":1000}"))
                .expect(201).json().get("id").asLong();
        JsonNode r = api.get("/api/pos/sales/" + id + "/receipt", owner).expect(200).json();
        assertThat(r.get("storeName").asText()).startsWith("Tienda");
        assertThat(r.get("widthMm").asInt()).isEqualTo(80);
        assertThat(r.get("sale").get("lines")).hasSize(1);
    }
}
