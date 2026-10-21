package com.tiendaposs.restaurant;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.support.AbstractIntegrationTest;
import com.tiendaposs.support.Api;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fase 10 — "Terminado cuando": se abre la mesa 4, el mesero pide 2 platos con modificadores, la cocina los ve y los
 * marca listos, la cuenta se divide entre dos personas con propina y el inventario descuenta los ingredientes de la receta.
 */
class RestaurantIT extends AbstractIntegrationTest {

    Api api;
    String owner;
    long burger;      // presentación de la hamburguesa (receta)
    long burgerId;
    long soda;        // presentación de la gaseosa (bar)
    long beefId;      // ingrediente: carne (kg)
    long term;        // grupo "Término"
    long medio, extraQueso;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, json);
        owner = newStore().ownerToken();
        api.put("/api/settings/pos/modules", owner, "{\"businessType\":\"RESTAURANTE\",\"modules\":[\"RESTAURANTE\",\"CUENTAS_ABIERTAS\",\"COMBOS\"]}").expect(200);
        long platos = api.post("/api/categories", owner, "{\"name\":\"Platos\"}").expect(201).json().get("id").asLong();
        long bebidas = api.post("/api/categories", owner, "{\"name\":\"Bebidas\"}").expect(201).json().get("id").asLong();
        api.put("/api/restaurant/stations", owner, "{\"categories\":{\"%d\":\"COCINA\",\"%d\":\"BAR\"}}".formatted(platos, bebidas)).expect(200);

        beefId = api.post("/api/products", owner, """
                {"name":"Carne molida","unit":"KILOGRAMO","cost":20000,"initialStock":10,
                 "presentations":[{"name":"Kilo","factor":1,"price":30000,"isBase":true}]}""").expect(201).json().get("id").asLong();
        JsonNode b = api.post("/api/products", owner, """
                {"name":"Hamburguesa","unit":"UNIDAD","categoryId":%d,"cost":0,"initialStock":0,"taxRate":0,
                 "presentations":[{"name":"Unidad","factor":1,"price":20000,"isBase":true}]}""".formatted(platos)).expect(201).json();
        burgerId = b.get("id").asLong();
        burger = b.get("presentations").get(0).get("id").asLong();
        // Receta: 150 g de carne con 10 % de merma.
        api.put("/api/products/" + burgerId + "/components", owner,
                "{\"components\":[{\"productId\":%d,\"quantity\":0.15,\"wastePct\":10}]}".formatted(beefId)).expect(200);
        soda = api.post("/api/products", owner, """
                {"name":"Limonada","unit":"UNIDAD","categoryId":%d,"cost":1000,"initialStock":50,"taxRate":0,
                 "presentations":[{"name":"Unidad","factor":1,"price":5000,"isBase":true}]}""".formatted(bebidas)).expect(201).json()
                .get("presentations").get(0).get("id").asLong();
        JsonNode g = api.post("/api/restaurant/modifiers", owner, """
                {"name":"Término","minSelect":1,"maxSelect":1,"productIds":[%d],
                 "options":[{"name":"Término medio"},{"name":"Bien asada"}]}""".formatted(burgerId)).expect(200).json();
        term = g.get("id").asLong();
        medio = g.get("options").get(0).get("id").asLong();
        JsonNode extras = api.post("/api/restaurant/modifiers", owner, """
                {"name":"Adiciones","minSelect":0,"maxSelect":3,"productIds":[%d],
                 "options":[{"name":"Extra queso","price":3000},{"name":"Sin cebolla"}]}""".formatted(burgerId)).expect(200).json();
        extraQueso = extras.get("options").get(0).get("id").asLong();
        long reg = api.get("/api/registers", owner).json().get(0).get("id").asLong();
        api.post("/api/shifts/open", owner, "{\"registerId\":%d,\"openingCash\":50000}".formatted(reg)).expect(200);
    }

    private long table(String name) throws Exception {
        return api.post("/api/restaurant/tables", owner, "{\"name\":\"%s\",\"seats\":4}".formatted(name)).expect(200).json().get("id").asLong();
    }

    @Test
    void theWholeServiceOfTable4() throws Exception {
        long mesa4 = table("Mesa 4");
        long account = api.post("/api/restaurant/tables/" + mesa4 + "/open", owner, "{\"guests\":2}").expect(200).json().get("id").asLong();
        // Abrirla otra vez devuelve la misma cuenta.
        assertThat(api.post("/api/restaurant/tables/" + mesa4 + "/open", owner, "{}").expect(200).json().get("id").asLong()).isEqualTo(account);

        // El término es obligatorio.
        api.post("/api/restaurant/accounts/" + account + "/dishes", owner, "{\"presentationId\":%d,\"quantity\":1}".formatted(burger)).expect(400);
        JsonNode acc = api.post("/api/restaurant/accounts/" + account + "/dishes", owner, """
                {"presentationId":%d,"quantity":1,"modifierIds":[%d,%d],"note":"Para niño"}""".formatted(burger, medio, extraQueso)).expect(200).json();
        api.post("/api/restaurant/accounts/" + account + "/dishes", owner, """
                {"presentationId":%d,"quantity":1,"modifierIds":[%d]}""".formatted(burger, medio)).expect(200);
        acc = api.post("/api/restaurant/accounts/" + account + "/dishes", owner, "{\"presentationId\":%d,\"quantity\":2}".formatted(soda)).expect(200).json();
        JsonNode first = acc.get("lines").get(0);
        assertThat(first.get("unitPrice").decimalValue()).isEqualByComparingTo("23000"); // 20.000 + extra queso
        assertThat(first.get("modifiers").asText()).contains("Término medio").contains("Extra queso");
        assertThat(acc.get("lines")).hasSize(3); // dos hamburguesas distintas no se suman
        assertThat(acc.get("total").decimalValue()).isEqualByComparingTo("53000");

        // La comanda: hamburguesas a cocina y limonadas al bar.
        JsonNode sent = api.post("/api/restaurant/accounts/" + account + "/send", owner, null).expect(200).json();
        assertThat(sent.get("sent").asInt()).isEqualTo(3);
        JsonNode cocina = api.get("/api/restaurant/kitchen?station=COCINA", owner).expect(200).json();
        assertThat(cocina).hasSize(2);
        assertThat(cocina.get(0).get("tableLabel").asText()).isEqualTo("Mesa 4");
        assertThat(cocina.get(0).get("note").asText()).isEqualTo("Para niño");
        assertThat(api.get("/api/restaurant/kitchen?station=BAR", owner).json()).hasSize(1);
        // Enviar otra vez no repite nada.
        assertThat(api.post("/api/restaurant/accounts/" + account + "/send", owner, null).json().get("sent").asInt()).isZero();

        for (JsonNode k : cocina) api.post("/api/restaurant/kitchen/" + k.get("id").asLong() + "/status", owner, "{\"status\":\"LISTO\"}").expect(200);
        JsonNode floor = api.get("/api/restaurant/floor", owner).expect(200).json();
        JsonNode t4 = floor.get("tables").get(0);
        assertThat(t4.get("state").asText()).isEqualTo("LISTO");
        assertThat(t4.get("accounts").get(0).get("ready").asInt()).isEqualTo(2);

        // Precuenta y división: la segunda hamburguesa y una limonada pasan a otra cuenta en la misma mesa.
        api.post("/api/restaurant/accounts/" + account + "/prebill", owner, null).expect(200);
        long l2 = acc.get("lines").get(1).get("id").asLong();
        long l3 = acc.get("lines").get(2).get("id").asLong();
        JsonNode part = api.post("/api/restaurant/accounts/" + account + "/split", owner, """
                {"lines":[{"lineId":%d,"quantity":1},{"lineId":%d,"quantity":1}]}""".formatted(l2, l3)).expect(200).json();
        assertThat(part.get("total").decimalValue()).isEqualByComparingTo("25000");
        assertThat(part.get("tableId").asLong()).isEqualTo(mesa4);
        assertThat(api.get("/api/pos/accounts/" + account, owner).json().get("total").decimalValue()).isEqualByComparingTo("28000");

        // Cada uno paga lo suyo; el primero deja 10 % de propina en efectivo (aparte del total).
        JsonNode s1 = api.post("/api/restaurant/accounts/" + account + "/checkout", owner, """
                {"checkout":{"payments":[{"method":"EFECTIVO","amount":28000}]},"tip":{"amount":2800,"method":"EFECTIVO"}}""").expect(200).json();
        assertThat(s1.get("total").decimalValue()).isEqualByComparingTo("28000");
        assertThat(s1.get("tipAmount").decimalValue()).isEqualByComparingTo("2800");
        api.post("/api/restaurant/accounts/" + part.get("id").asLong() + "/checkout", owner, """
                {"checkout":{"payments":[{"method":"EFECTIVO","amount":25000}]}}""").expect(200);

        JsonNode shift = api.get("/api/shifts/current", owner).json();
        assertThat(shift.get("salesTotal").decimalValue()).isEqualByComparingTo("53000");
        assertThat(shift.get("cashTips").decimalValue()).isEqualByComparingTo("2800");
        assertThat(shift.get("expectedCash").decimalValue()).isEqualByComparingTo("105800"); // 50.000 + 53.000 + propina

        // Receta: 2 hamburguesas × 0,15 kg × 1,10 de merma = 0,33 kg de carne.
        JsonNode beef = api.get("/api/products/" + beefId, owner).json();
        assertThat(beef.get("stock").decimalValue()).isEqualByComparingTo(new BigDecimal("9.67"));
        assertThat(api.get("/api/restaurant/floor", owner).json().get("tables").get(0).get("state").asText()).isEqualTo("LIBRE");
    }

    @Test
    void removingSomethingAlreadyInTheKitchenWarnsTheKitchenAndTablesCanMoveAndMerge() throws Exception {
        long t1 = table("Mesa 1"), t2 = table("Mesa 2"), t3 = table("Mesa 3");
        long a1 = api.post("/api/restaurant/tables/" + t1 + "/open", owner, "{}").json().get("id").asLong();
        JsonNode acc = api.post("/api/restaurant/accounts/" + a1 + "/dishes", owner, """
                {"presentationId":%d,"quantity":1,"modifierIds":[%d]}""".formatted(burger, medio)).json();
        api.post("/api/restaurant/accounts/" + a1 + "/send", owner, null).expect(200);
        api.call("DELETE", "/api/pos/accounts/" + a1 + "/lines/" + acc.get("lines").get(0).get("id").asLong(), owner, null).expect(200);
        JsonNode cocina = api.get("/api/restaurant/kitchen?station=COCINA", owner).json();
        assertThat(cocina).hasSize(2);
        assertThat(cocina.get(1).get("kind").asText()).isEqualTo("ANULAR");

        // Mover la mesa 1 a la 2, y luego unir la 3 a ella.
        api.post("/api/restaurant/accounts/" + a1 + "/move", owner, "{\"tableId\":%d}".formatted(t2)).expect(200);
        long a3 = api.post("/api/restaurant/tables/" + t3 + "/open", owner, "{}").json().get("id").asLong();
        api.post("/api/restaurant/accounts/" + a3 + "/dishes", owner, "{\"presentationId\":%d,\"quantity\":3}".formatted(soda)).expect(200);
        api.post("/api/restaurant/accounts/" + a3 + "/move", owner, "{\"tableId\":%d}".formatted(t2)).expect(409); // ocupada: unir
        JsonNode merged = api.post("/api/restaurant/accounts/" + a3 + "/merge", owner, "{\"intoAccountId\":%d}".formatted(a1)).expect(200).json();
        assertThat(merged.get("accountLabel").asText()).isEqualTo("Mesa 2");
        assertThat(merged.get("total").decimalValue()).isEqualByComparingTo("15000");
        JsonNode floor = api.get("/api/restaurant/floor", owner).json();
        long busy = 0;
        for (JsonNode tb : floor.get("tables")) if (!"LIBRE".equals(tb.get("state").asText())) busy++;
        assertThat(busy).isEqualTo(1);
    }

    @Test
    void theRestaurantModuleMustBeOn() throws Exception {
        api.put("/api/settings/pos/modules", owner, "{\"businessType\":\"TIENDA\",\"modules\":[\"CUENTAS_ABIERTAS\"]}").expect(200);
        api.get("/api/restaurant/floor", owner).expect(409);
    }
}
