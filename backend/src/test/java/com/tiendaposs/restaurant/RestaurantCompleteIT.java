package com.tiendaposs.restaurant;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.support.AbstractIntegrationTest;
import com.tiendaposs.support.Api;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Restaurante completo: roles de mesero y cocina, dividir en partes iguales, gaseosa a elección, domicilios y pedidos
 * por QR de la mesa que llegan a la cocina, reservas de mesa y rentabilidad de combos repartida.
 */
class RestaurantCompleteIT extends AbstractIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    Api api;
    Store store;
    String owner;
    long platos, bebidas;
    long burger, burgerId;     // hamburguesa (presentación y producto)
    long cola, colaId;         // Coca-Cola (bar)
    long juiceId;              // jugo (para la bebida a elección)
    long term, medio;          // término obligatorio
    long bebida, optCola, optJugo; // "Bebida a elección" que descuenta del inventario

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, json);
        store = newStore();
        owner = store.ownerToken();
        jdbc.update("UPDATE tenants SET plan = 'PRO' WHERE id = ?", store.tenantId());
        api.put("/api/settings/pos/modules", owner,
                "{\"businessType\":\"RESTAURANTE\",\"modules\":[\"RESTAURANTE\",\"CUENTAS_ABIERTAS\",\"COMBOS\",\"TIENDA_ONLINE\"]}").expect(200);
        platos = api.post("/api/categories", owner, "{\"name\":\"Platos\"}").expect(201).json().get("id").asLong();
        bebidas = api.post("/api/categories", owner, "{\"name\":\"Bebidas\"}").expect(201).json().get("id").asLong();
        api.put("/api/restaurant/stations", owner, "{\"categories\":{\"%d\":\"COCINA\",\"%d\":\"BAR\"}}".formatted(platos, bebidas)).expect(200);
        JsonNode b = api.post("/api/products", owner, """
                {"name":"Hamburguesa","unit":"UNIDAD","categoryId":%d,"cost":8000,"initialStock":0,"trackStock":false,"taxRate":0,
                 "presentations":[{"name":"Unidad","factor":1,"price":20000,"isBase":true}]}""".formatted(platos)).expect(201).json();
        burgerId = b.get("id").asLong();
        burger = b.get("presentations").get(0).get("id").asLong();
        JsonNode c = api.post("/api/products", owner, """
                {"name":"Coca-Cola","unit":"UNIDAD","categoryId":%d,"cost":1500,"initialStock":30,"taxRate":0,
                 "presentations":[{"name":"Unidad","factor":1,"price":4000,"isBase":true}]}""".formatted(bebidas)).expect(201).json();
        colaId = c.get("id").asLong();
        cola = c.get("presentations").get(0).get("id").asLong();
        juiceId = api.post("/api/products", owner, """
                {"name":"Jugo de mora","unit":"UNIDAD","categoryId":%d,"cost":1200,"initialStock":20,"taxRate":0,
                 "presentations":[{"name":"Unidad","factor":1,"price":5000,"isBase":true}]}""".formatted(bebidas)).expect(201).json().get("id").asLong();
        JsonNode g = api.post("/api/restaurant/modifiers", owner, """
                {"name":"Término","minSelect":1,"maxSelect":1,"productIds":[%d],
                 "options":[{"name":"Término medio"},{"name":"Bien asada"}]}""".formatted(burgerId)).expect(200).json();
        term = g.get("id").asLong();
        medio = g.get("options").get(0).get("id").asLong();
        JsonNode bg = api.post("/api/restaurant/modifiers", owner, """
                {"name":"Bebida a elección","minSelect":0,"maxSelect":1,"productIds":[%d],
                 "options":[{"name":"Coca-Cola","price":0,"productId":%d,"productQty":1},
                            {"name":"Jugo de mora","price":1000,"productId":%d,"productQty":1}]}""".formatted(burgerId, colaId, juiceId)).expect(200).json();
        bebida = bg.get("id").asLong();
        optCola = bg.get("options").get(0).get("id").asLong();
        optJugo = bg.get("options").get(1).get("id").asLong();
        assertThat(bg.get("options").get(0).get("productName").asText()).isEqualTo("Coca-Cola");
        long reg = api.get("/api/registers", owner).json().get(0).get("id").asLong();
        api.post("/api/shifts/open", owner, "{\"registerId\":%d,\"openingCash\":0}".formatted(reg)).expect(200);
    }

    private long table(String name) throws Exception {
        return api.post("/api/restaurant/tables", owner, "{\"name\":\"%s\",\"seats\":4}".formatted(name)).expect(200).json().get("id").asLong();
    }

    private BigDecimal stock(long id) throws Exception {
        return api.get("/api/products/" + id, owner).expect(200).json().get("stock").decimalValue();
    }

    @Test
    void waiterServesOnlyHisTablesNeverChargesAndTheKitchenOnlySeesTheBoard() throws Exception {
        // Encender el restaurante creó los roles de mesero y cocina.
        String mesero = newEmployee(store, "MESERO");
        String otroMesero = newEmployee(store, "MESERO");
        String cocina = newEmployee(store, "COCINA");
        String cajero = newEmployee(store, "CAJERO");
        long mesa1 = table("Mesa 1");

        assertThat(api.get("/api/restaurant/floor", mesero).expect(200).json().get("tables")).hasSize(1);
        assertThat(api.get("/api/pos/catalog", mesero).expect(200).json().size()).isGreaterThan(0);
        long acc = api.post("/api/restaurant/tables/" + mesa1 + "/open", mesero, "{\"guests\":2}").expect(200).json().get("id").asLong();
        api.post("/api/restaurant/accounts/" + acc + "/dishes", mesero,
                "{\"presentationId\":%d,\"quantity\":1,\"modifierIds\":[%d]}".formatted(burger, medio)).expect(200);
        api.post("/api/restaurant/accounts/" + acc + "/send", mesero, null).expect(200);
        api.post("/api/restaurant/accounts/" + acc + "/prebill", mesero, null).expect(200);
        api.get("/api/pos/sales/" + acc + "/receipt", mesero).expect(200);

        // El mesero no cobra, no vende en mostrador y no abre cuentas sin mesa.
        api.post("/api/restaurant/accounts/" + acc + "/checkout", mesero,
                "{\"checkout\":{\"payments\":[{\"method\":\"EFECTIVO\",\"amount\":20000}]}}").expect(403);
        api.post("/api/pos/accounts", mesero, "{\"label\":\"Barra\"}").expect(403);
        // Otro mesero ve la mesa en el plano pero no la toca.
        JsonNode floor = api.get("/api/restaurant/floor", otroMesero).expect(200).json();
        assertThat(floor.get("tables").get(0).get("accounts").get(0).get("mine").asBoolean()).isFalse();
        api.post("/api/restaurant/accounts/" + acc + "/dishes", otroMesero,
                "{\"presentationId\":%d,\"quantity\":1}".formatted(cola)).expect(403);
        api.get("/api/pos/accounts/" + acc, otroMesero).expect(403);

        // La cocina solo ve su tablero.
        JsonNode board = api.get("/api/restaurant/kitchen?station=COCINA", cocina).expect(200).json();
        assertThat(board).hasSize(1);
        api.post("/api/restaurant/kitchen/" + board.get(0).get("id").asLong() + "/status", cocina, "{\"status\":\"LISTO\"}").expect(200);
        api.get("/api/restaurant/floor", cocina).expect(403);
        api.get("/api/pos/catalog", cocina).expect(403);

        // La caja cobra la mesa del mesero.
        long reg = api.get("/api/registers", owner).json().get(0).get("id").asLong();
        api.post("/api/shifts/open", cajero, "{\"registerId\":%d,\"openingCash\":0}".formatted(reg));
        api.post("/api/restaurant/accounts/" + acc + "/checkout", owner,
                "{\"checkout\":{\"payments\":[{\"method\":\"EFECTIVO\",\"amount\":20000}]}}").expect(200);
    }

    @Test
    void splitInEqualPartsKeepsTheTotalAndTheInventory() throws Exception {
        long mesa = table("Mesa 7");
        long acc = api.post("/api/restaurant/tables/" + mesa + "/open", owner, "{\"guests\":3}").json().get("id").asLong();
        api.post("/api/restaurant/accounts/" + acc + "/dishes", owner, "{\"presentationId\":%d,\"quantity\":1}".formatted(cola)).expect(200);
        api.post("/api/restaurant/accounts/" + acc + "/dishes", owner,
                "{\"presentationId\":%d,\"quantity\":2,\"modifierIds\":[%d]}".formatted(burger, medio)).expect(200);
        api.post("/api/restaurant/accounts/" + acc + "/split-equal", owner, "{\"parts\":1}").expect(400);
        JsonNode first = api.post("/api/restaurant/accounts/" + acc + "/split-equal", owner, "{\"parts\":3}").expect(200).json();

        JsonNode tbl = api.get("/api/restaurant/floor", owner).json().get("tables").get(0);
        assertThat(tbl.get("accounts")).hasSize(3);
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal colas = BigDecimal.ZERO;
        for (JsonNode a : tbl.get("accounts")) {
            JsonNode s = api.get("/api/pos/accounts/" + a.get("id").asLong(), owner).json();
            sum = sum.add(s.get("total").decimalValue());
            for (JsonNode l : s.get("lines")) if (l.get("presentationId").asLong() == cola) colas = colas.add(l.get("quantity").decimalValue());
            // Cada parte vale lo mismo, salvo unos pesos: las cantidades van con 3 decimales (0,666 de hamburguesa).
            assertThat(s.get("total").decimalValue().subtract(new BigDecimal("14666.67")).abs()).isLessThan(new BigDecimal("50"));
        }
        assertThat(sum).isEqualByComparingTo("44000");
        assertThat(colas).isEqualByComparingTo("1"); // 0,333 + 0,333 + 0,334
        assertThat(first.get("id").asLong()).isEqualTo(acc);

        for (JsonNode a : tbl.get("accounts")) {
            long id = a.get("id").asLong();
            BigDecimal total = api.get("/api/pos/accounts/" + id, owner).json().get("total").decimalValue();
            api.post("/api/restaurant/accounts/" + id + "/checkout", owner,
                    "{\"checkout\":{\"payments\":[{\"method\":\"EFECTIVO\",\"amount\":%s}]}}".formatted(total.toPlainString())).expect(200);
        }
        assertThat(stock(colaId)).isEqualByComparingTo("29");
    }

    @Test
    void theDrinkOfChoiceComesOutOfTheInventoryAndCountsAsCost() throws Exception {
        long mesa = table("Mesa 2");
        long acc = api.post("/api/restaurant/tables/" + mesa + "/open", owner, "{}").json().get("id").asLong();
        JsonNode s = api.post("/api/restaurant/accounts/" + acc + "/dishes", owner,
                "{\"presentationId\":%d,\"quantity\":2,\"modifierIds\":[%d,%d]}".formatted(burger, medio, optJugo)).expect(200).json();
        assertThat(s.get("lines").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("21000");
        api.post("/api/restaurant/accounts/" + acc + "/dishes", owner,
                "{\"presentationId\":%d,\"quantity\":1,\"modifierIds\":[%d,%d]}".formatted(burger, medio, optCola)).expect(200);
        JsonNode sale = api.post("/api/restaurant/accounts/" + acc + "/checkout", owner,
                "{\"checkout\":{\"payments\":[{\"method\":\"EFECTIVO\",\"amount\":62000}]}}").expect(200).json();
        assertThat(stock(juiceId)).isEqualByComparingTo("18");
        assertThat(stock(colaId)).isEqualByComparingTo("29");
        // Costo: 3 hamburguesas × 8.000 + 2 jugos × 1.200 + 1 Coca-Cola × 1.500.
        assertThat(sale.get("costTotal").decimalValue()).isEqualByComparingTo("27900");

        // Anular la venta devuelve las bebidas.
        api.post("/api/pos/sales/" + sale.get("id").asLong() + "/void", owner, "{\"reason\":\"Prueba de anulación\"}").expect(200);
        assertThat(stock(juiceId)).isEqualByComparingTo("20");
        assertThat(stock(colaId)).isEqualByComparingTo("30");
    }

    private String enableOnline(boolean tableOrders) throws Exception {
        String slug = api.get("/api/online-store", owner).expect(200).json().get("slug").asText();
        api.put("/api/online-store", owner, """
                {"enabled":true,"slug":"%s","mode":"PEDIDOS","whatsapp":"3001234567","showStock":false,"deliveryEnabled":true,
                 "pickupEnabled":true,"minOrder":0,"payMethods":["CONTRA_ENTREGA"],"schedule":[],"paused":false,"prepMinutes":25,
                 "showBadge":true,"zones":[],"tableOrders":%s}""".formatted(slug, tableOrders)).expect(200);
        api.put("/api/online-store/products", owner, "[{\"productId\":%d,\"visible\":true},{\"productId\":%d,\"visible\":true}]"
                .formatted(burgerId, colaId)).expect(200);
        return slug;
    }

    @Test
    void deliveryOrdersGoToTheKitchenAndBecomeReadyWhenTheKitchenFinishes() throws Exception {
        String slug = enableOnline(false);
        JsonNode pub = api.get("/api/public/stores/" + slug, null).expect(200).json();
        JsonNode burgerPub = null;
        for (JsonNode p : pub.get("products")) if (p.get("id").asLong() == burgerId) burgerPub = p;
        assertThat(burgerPub.get("modifierGroups")).hasSize(2);

        // Sin el término obligatorio no se puede pedir.
        api.post("/api/public/stores/" + slug + "/orders", null, """
                {"name":"Ana","phone":"3005551234","fulfillment":"RECOGER","payMethod":"CONTRA_ENTREGA",
                 "items":[{"presentationId":%d,"quantity":1}]}""".formatted(burger)).expect(400);
        JsonNode placed = api.post("/api/public/stores/" + slug + "/orders", null, """
                {"name":"Ana","phone":"3005551234","fulfillment":"RECOGER","payMethod":"CONTRA_ENTREGA",
                 "items":[{"presentationId":%d,"quantity":1,"modifierIds":[%d,%d],"note":"Sin salsas"},
                          {"presentationId":%d,"quantity":2}]}""".formatted(burger, medio, optJugo, cola)).expect(201).json();
        assertThat(placed.get("total").decimalValue()).isEqualByComparingTo("29000"); // 21.000 + 2 × 4.000

        long orderId = api.get("/api/online-orders", owner).expect(200).json().get(0).get("id").asLong();
        api.post("/api/online-orders/" + orderId + "/accept", owner, null).expect(200);
        JsonNode cocina = api.get("/api/restaurant/kitchen?station=COCINA", owner).expect(200).json();
        assertThat(cocina).hasSize(1);
        assertThat(cocina.get(0).get("tableLabel").asText()).startsWith("Para llevar #");
        assertThat(cocina.get(0).get("modifiers").asText()).contains("Término medio").contains("Jugo de mora");
        assertThat(cocina.get(0).get("note").asText()).isEqualTo("Sin salsas");
        JsonNode bar = api.get("/api/restaurant/kitchen?station=BAR", owner).json();
        assertThat(bar).hasSize(1);

        api.post("/api/restaurant/kitchen/" + cocina.get(0).get("id").asLong() + "/status", owner, "{\"status\":\"PREPARANDO\"}").expect(200);
        assertThat(api.get("/api/online-orders/" + orderId, owner).json().get("status").asText()).isEqualTo("PREPARANDO");
        api.post("/api/restaurant/kitchen/" + cocina.get(0).get("id").asLong() + "/status", owner, "{\"status\":\"LISTO\"}").expect(200);
        api.post("/api/restaurant/kitchen/" + bar.get(0).get("id").asLong() + "/status", owner, "{\"status\":\"LISTO\"}").expect(200);
        assertThat(api.get("/api/online-orders/" + orderId, owner).json().get("status").asText()).isEqualTo("LISTO");

        // Al entregarlo se cobra y el jugo elegido sale del inventario.
        api.post("/api/online-orders/" + orderId + "/deliver", owner, "{\"payments\":[{\"method\":\"EFECTIVO\",\"amount\":29000}]}").expect(200);
        assertThat(stock(juiceId)).isEqualByComparingTo("19");
        assertThat(stock(colaId)).isEqualByComparingTo("28");
        assertThat(api.get("/api/restaurant/kitchen?station=COCINA", owner).json().get(0).get("status").asText()).isEqualTo("ENTREGADO");
    }

    @Test
    void ordersFromTheTableQrGoToTheWaiterAndThenToTheTableAccount() throws Exception {
        long mesa = table("Mesa 5");
        String slug = enableOnline(false);
        String token = api.get("/api/restaurant/floor", owner).json().get("tables").get(0).get("qrToken").asText();
        // Sin activar los pedidos desde la mesa, el QR no sirve.
        api.get("/api/public/stores/" + slug + "/tables/" + token, null).expect(404);
        enableOnline(true);
        assertThat(api.get("/api/public/stores/" + slug + "/tables/" + token, null).expect(200).json().get("name").asText()).isEqualTo("Mesa 5");
        api.get("/api/public/stores/" + slug + "/tables/nada", null).expect(404);

        // Desde la mesa no se pide celular ni forma de pago.
        JsonNode placed = api.post("/api/public/stores/" + slug + "/orders", null, """
                {"name":"Carlos","fulfillment":"MESA","tableToken":"%s",
                 "items":[{"presentationId":%d,"quantity":1,"modifierIds":[%d]}]}""".formatted(token, burger, medio)).expect(201).json();
        assertThat(placed.get("tableLabel").asText()).isEqualTo("Mesa 5");
        assertThat(placed.get("payLabel").asText()).isEqualTo("Paga en la mesa");

        String mesero = newEmployee(store, "MESERO");
        JsonNode pending = api.get("/api/restaurant/table-orders", mesero).expect(200).json();
        assertThat(pending).hasSize(1);
        api.post("/api/restaurant/table-orders/" + pending.get(0).get("id").asLong() + "/accept", mesero, null).expect(200);
        assertThat(api.get("/api/restaurant/table-orders", mesero).json()).isEmpty();

        JsonNode t = api.get("/api/restaurant/floor", mesero).json().get("tables").get(0);
        assertThat(t.get("state").asText()).isEqualTo("OCUPADA");
        long acc = t.get("accounts").get(0).get("id").asLong();
        JsonNode account = api.get("/api/pos/accounts/" + acc, mesero).expect(200).json();
        assertThat(account.get("total").decimalValue()).isEqualByComparingTo("20000");
        assertThat(account.get("lines").get(0).get("sentQty").decimalValue()).isEqualByComparingTo("1"); // ya en la cocina
        assertThat(api.get("/api/restaurant/kitchen?station=COCINA", owner).json().get(0).get("tableLabel").asText()).isEqualTo("Mesa 5");

        // Un pedido de broma se descarta.
        api.post("/api/public/stores/" + slug + "/orders", null, """
                {"name":"X","fulfillment":"MESA","tableToken":"%s","items":[{"presentationId":%d,"quantity":5}]}""".formatted(token, cola)).expect(201);
        long joke = api.get("/api/restaurant/table-orders", mesero).json().get(0).get("id").asLong();
        api.post("/api/restaurant/table-orders/" + joke + "/reject", mesero, "{\"reason\":\"No lo pidió nadie\"}").expect(200);
        // Un QR nuevo invalida el viejo.
        api.post("/api/restaurant/tables/" + mesa + "/qr", owner, null).expect(200);
        api.get("/api/public/stores/" + slug + "/tables/" + token, null).expect(404);
    }

    @Test
    void reservationsShowOnTheFloorAndOpenTheTableWhenTheyArrive() throws Exception {
        long mesa = table("Mesa 9");
        Instant at = Instant.now().plus(Duration.ofHours(1));
        JsonNode r = api.post("/api/restaurant/reservations", owner, """
                {"name":"Familia Gómez","phone":"3001112233","people":6,"reservedAt":"%s","durationMin":90,"tableId":%d}"""
                .formatted(at, mesa)).expect(200).json();
        // La misma mesa no se reserva dos veces a la misma hora.
        api.post("/api/restaurant/reservations", owner, """
                {"name":"Otro","people":2,"reservedAt":"%s","tableId":%d}""".formatted(at.plus(Duration.ofMinutes(30)), mesa)).expect(400);
        JsonNode floor = api.get("/api/restaurant/floor", owner).json();
        assertThat(floor.get("reservations").get(String.valueOf(mesa)).get("name").asText()).isEqualTo("Familia Gómez");
        assertThat(api.get("/api/restaurant/reservations", owner).expect(200).json()).hasSize(1);

        api.post("/api/restaurant/reservations/" + r.get("id").asLong() + "/status", owner, "{\"status\":\"CONFIRMADA\"}").expect(200);
        JsonNode acc = api.post("/api/restaurant/reservations/" + r.get("id").asLong() + "/seat", owner, "{}").expect(200).json();
        assertThat(acc.get("tableId").asLong()).isEqualTo(mesa);
        assertThat(acc.get("guests").asInt()).isEqualTo(6);
        floor = api.get("/api/restaurant/floor", owner).json();
        assertThat(floor.get("reservations").has(String.valueOf(mesa))).isFalse();
        assertThat(floor.get("tables").get(0).get("state").asText()).isEqualTo("OCUPADA");
    }

    @Test
    void combosCanBeSplitIntoTheirProductsInTheProfitabilityReport() throws Exception {
        JsonNode combo = api.post("/api/products", owner, """
                {"name":"Combo hamburguesa + Coca-Cola","unit":"UNIDAD","categoryId":%d,"cost":0,"initialStock":0,"taxRate":0,
                 "presentations":[{"name":"Unidad","factor":1,"price":21600,"isBase":true}]}""".formatted(platos)).expect(201).json();
        long comboId = combo.get("id").asLong();
        api.put("/api/products/" + comboId + "/components", owner,
                "{\"components\":[{\"productId\":%d,\"quantity\":1},{\"productId\":%d,\"quantity\":1}]}".formatted(burgerId, colaId)).expect(200);
        api.post("/api/pos/sales", owner, """
                {"lines":[{"presentationId":%d,"quantity":1}],"payments":[{"method":"EFECTIVO","amount":21600}]}"""
                .formatted(combo.get("presentations").get(0).get("id").asLong())).expect(201);

        JsonNode plain = api.get("/api/analytics/profitability", owner).expect(200).json();
        assertThat(plain.get("products")).hasSize(1);
        assertThat(plain.get("products").get(0).get("productId").asLong()).isEqualTo(comboId);

        JsonNode split = api.get("/api/analytics/profitability?splitCombos=true", owner).expect(200).json();
        assertThat(split.get("products")).hasSize(2);
        BigDecimal burgerRev = null, colaRev = null;
        for (JsonNode p : split.get("products")) {
            if (p.get("productId").asLong() == burgerId) burgerRev = p.get("revenueEx").decimalValue();
            if (p.get("productId").asLong() == colaId) colaRev = p.get("revenueEx").decimalValue();
        }
        // 21.600 repartido por precio: 20.000 / 24.000 y 4.000 / 24.000.
        assertThat(burgerRev).isEqualByComparingTo("18000");
        assertThat(colaRev).isEqualByComparingTo("3600");
        assertThat(split.get("revenueEx").decimalValue()).isEqualByComparingTo(plain.get("revenueEx").decimalValue());
    }
}
