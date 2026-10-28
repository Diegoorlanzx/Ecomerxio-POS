package com.tiendaposs.remissions;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.support.AbstractIntegrationTest;
import com.tiendaposs.support.Api;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Remisiones: salen del inventario al entregarse, el cliente puede devolver y varias se facturan en una sola venta. */
class RemissionsIT extends AbstractIntegrationTest {

    Api api;
    Store store;
    String owner;
    long presentation;
    long productId;
    long customerId;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, json);
        store = newStore();
        owner = store.ownerToken();
        JsonNode p = api.post("/api/products", owner, """
                {"name":"Cemento","unit":"UNIDAD","cost":20000,"taxRate":19,"initialStock":100,
                 "presentations":[{"name":"Bulto","factor":1,"price":30000,"isBase":true}]}""").expect(201).json();
        productId = p.get("id").asLong();
        for (JsonNode pr : p.get("presentations")) if (pr.get("isBase").asBoolean()) presentation = pr.get("id").asLong();
        customerId = api.post("/api/pos/customers", owner, "{\"name\":\"Constructora Andina\",\"phone\":\"3001234567\"}").expect(201)
                .json().get("id").asLong();
    }

    BigDecimal stock() throws Exception {
        return api.get("/api/products/" + productId, owner).json().get("stock").decimalValue();
    }

    long remission(int bultos) throws Exception {
        JsonNode r = api.post("/api/remissions", owner, """
                {"customerId":%d,"deliveryAddress":"Obra Calle 10","lines":[{"presentationId":%d,"quantity":%d},
                 {"description":"Flete","quantity":1,"unitPrice":10000}]}""".formatted(customerId, presentation, bultos)).expect(201).json();
        return r.get("id").asLong();
    }

    @Test
    void deliveredRemissionsAreInvoicedTogetherWithoutDiscountingStockTwice() throws Exception {
        long a = remission(10);
        JsonNode draft = api.get("/api/remissions/" + a, owner).expect(200).json();
        assertThat(draft.get("code").asText()).isEqualTo("REM-0001");
        assertThat(draft.get("status").asText()).isEqualTo("BORRADOR");
        assertThat(draft.get("customerName").asText()).isEqualTo("Constructora Andina");
        assertThat(draft.get("total").decimalValue()).isEqualByComparingTo("310000");
        // En borrador no mueve inventario.
        assertThat(stock()).isEqualByComparingTo("100");

        JsonNode delivered = api.post("/api/remissions/" + a + "/deliver", owner, "{\"receivedBy\":\"Pedro (maestro de obra)\"}").expect(200).json();
        assertThat(delivered.get("status").asText()).isEqualTo("ENTREGADA");
        assertThat(delivered.get("pendingTotal").decimalValue()).isEqualByComparingTo("310000");
        assertThat(stock()).isEqualByComparingTo("90");
        // Entregada ya no se edita ni se entrega dos veces.
        api.put("/api/remissions/" + a, owner, "{\"customerId\":%d,\"lines\":[{\"presentationId\":%d,\"quantity\":1}]}".formatted(customerId, presentation)).expect(409);
        api.post("/api/remissions/" + a + "/deliver", owner, "{}").expect(409);

        // El cliente devuelve 2 bultos: vuelven al inventario y no se facturan.
        long lineId = delivered.get("lines").get(0).get("id").asLong();
        api.post("/api/remissions/" + a + "/returns", owner, "{\"lines\":[{\"lineId\":%d,\"quantity\":11}],\"reason\":\"Sobró\"}".formatted(lineId)).expect(400);
        JsonNode ret = api.post("/api/remissions/" + a + "/returns", owner, "{\"lines\":[{\"lineId\":%d,\"quantity\":2}],\"reason\":\"Sobró\"}".formatted(lineId)).expect(200).json();
        assertThat(ret.get("lines").get(0).get("pendingQty").decimalValue()).isEqualByComparingTo("8");
        assertThat(ret.get("pendingTotal").decimalValue()).isEqualByComparingTo("250000");
        assertThat(stock()).isEqualByComparingTo("92");

        long b = remission(5);
        api.post("/api/remissions/" + b + "/deliver", owner, "{}").expect(200);
        assertThat(stock()).isEqualByComparingTo("87");

        // Solo se facturan entregadas, y de un mismo cliente.
        long draftC = remission(1);
        String pay = "\"payments\":[{\"method\":\"EFECTIVO\",\"amount\":410000}]";
        api.post("/api/remissions/invoice", owner, "{\"remissionIds\":[%d,%d],%s}".formatted(a, draftC, pay)).expect(409);
        long other = api.post("/api/remissions", owner, "{\"customerName\":\"Otro cliente\",\"lines\":[{\"presentationId\":%d,\"quantity\":1}]}"
                .formatted(presentation)).expect(201).json().get("id").asLong();
        api.post("/api/remissions/" + other + "/deliver", owner, "{}").expect(200);
        api.post("/api/remissions/invoice", owner, "{\"remissionIds\":[%d,%d],%s}".formatted(a, other, pay)).expect(400);

        // Factura las dos en una venta (8 + 5 bultos y dos fletes): la caja no vuelve a descontar el inventario.
        long reg = api.get("/api/registers", owner).expect(200).json().get(0).get("id").asLong();
        api.post("/api/shifts/open", owner, "{\"registerId\":%d,\"openingCash\":0}".formatted(reg)).expect(200);
        BigDecimal before = stock();
        JsonNode sale = api.post("/api/remissions/invoice", owner, "{\"remissionIds\":[%d,%d],%s}".formatted(a, b, pay)).expect(200).json();
        assertThat(sale.get("total").decimalValue()).isEqualByComparingTo("410000");
        assertThat(sale.get("customerId").asLong()).isEqualTo(customerId);
        assertThat(stock()).isEqualByComparingTo(before);
        JsonNode billed = api.get("/api/remissions/" + a, owner).expect(200).json();
        assertThat(billed.get("status").asText()).isEqualTo("FACTURADA");
        assertThat(billed.get("saleNumber").asLong()).isEqualTo(sale.get("number").asLong());
        assertThat(billed.get("invoicedWith").get(0).asText()).isEqualTo("REM-0002");
        api.post("/api/remissions/" + a + "/cancel", owner, "{\"reason\":\"x\"}").expect(409);

        // Anular la venta no devuelve mercancía (sigue donde el cliente) y las remisiones quedan por facturar otra vez.
        api.post("/api/pos/sales/" + sale.get("id").asLong() + "/void", owner, "{\"reason\":\"Error en el pago\"}").expect(200);
        assertThat(stock()).isEqualByComparingTo(before);
        assertThat(api.get("/api/remissions/" + a, owner).json().get("status").asText()).isEqualTo("ENTREGADA");
        JsonNode again = api.post("/api/remissions/invoice", owner, "{\"remissionIds\":[%d,%d],%s}".formatted(a, b, pay)).expect(200).json();
        assertThat(again.get("id").asLong()).isNotEqualTo(sale.get("id").asLong());
        assertThat(api.get("/api/remissions?status=ENTREGADA&customerId=" + customerId, owner).json()).isEmpty();
    }

    @Test
    void cancellingADeliveredRemissionReturnsWhatTheCustomerStillHad() throws Exception {
        long a = remission(10);
        JsonNode d = api.post("/api/remissions/" + a + "/deliver", owner, "{}").expect(200).json();
        api.post("/api/remissions/" + a + "/returns", owner, "{\"lines\":[{\"lineId\":%d,\"quantity\":3}],\"reason\":\"Sobró\"}"
                .formatted(d.get("lines").get(0).get("id").asLong())).expect(200);
        assertThat(stock()).isEqualByComparingTo("93");
        api.post("/api/remissions/" + a + "/cancel", owner, "{\"reason\":\"\"}").expect(400);
        api.post("/api/remissions/" + a + "/cancel", owner, "{\"reason\":\"El cliente no recibió\"}").expect(200);
        assertThat(stock()).isEqualByComparingTo("100");
        assertThat(api.get("/api/remissions?status=ANULADA", owner).json()).hasSize(1);
        // Otra tienda no la ve.
        api.get("/api/remissions/" + a, newStore().ownerToken()).expect(404);
    }

    @Test
    void anAcceptedQuoteIsDispatchedWithARemission() throws Exception {
        JsonNode q = api.post("/api/quotes", owner, """
                {"customerName":"Ferretería Sur","lines":[{"presentationId":%d,"quantity":4,"discountPct":5}]}""".formatted(presentation))
                .expect(201).json();
        long qid = q.get("id").asLong();
        JsonNode r = api.post("/api/quotes/" + qid + "/remission", owner, "{}").expect(201).json();
        assertThat(r.get("quoteCode").asText()).isEqualTo("COT-0001");
        assertThat(r.get("total").decimalValue()).isEqualByComparingTo(q.get("total").decimalValue());
        assertThat(api.get("/api/quotes/" + qid, owner).json().get("status").asText()).isEqualTo("REMITIDA");
        api.post("/api/quotes/" + qid + "/remission", owner, "{}").expect(409);
        // El encargado de inventario no tiene permiso de remisiones.
        String stockKeeper = newEmployee(store, "INVENTARIO");
        api.get("/api/remissions", stockKeeper).expect(403);
    }
}
