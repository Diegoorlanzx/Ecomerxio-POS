package com.tiendaposs.distribution;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.support.AbstractIntegrationTest;
import com.tiendaposs.support.Api;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fase 12 — "Terminado cuando": un vendedor toma 3 pedidos sin internet, se sincronizan, se alistan y se despachan con
 * remisión, uno se entrega parcial, y el cierre de ruta cuadra el dinero.
 */
class DistributionIT extends AbstractIntegrationTest {

    Api api;
    String owner;
    long arroz, aceite, arrozId;
    long route;
    long[] customers = new long[3];

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, json);
        owner = newStore().ownerToken();
        api.put("/api/settings/pos/modules", owner, "{\"businessType\":\"DISTRIBUIDORA\",\"modules\":[\"DISTRIBUCION\",\"FIADO\"]}").expect(200);
        JsonNode a = api.post("/api/products", owner, """
                {"name":"Arroz 500 g","unit":"UNIDAD","cost":2500,"initialStock":100,"taxRate":0,
                 "presentations":[{"name":"Unidad","factor":1,"price":4000,"isBase":true}]}""").expect(201).json();
        arrozId = a.get("id").asLong();
        arroz = a.get("presentations").get(0).get("id").asLong();
        aceite = api.post("/api/products", owner, """
                {"name":"Aceite 1 L","unit":"UNIDAD","cost":7000,"initialStock":50,"taxRate":0,
                 "presentations":[{"name":"Unidad","factor":1,"price":10000,"isBase":true}]}""").expect(201).json()
                .get("presentations").get(0).get("id").asLong();
        // Lista "Tiendas": arroz a 3.500 desde 10 unidades y 5 % menos en lo demás.
        long list = api.post("/api/distribution/price-lists", owner, """
                {"name":"Tiendas","discountPct":5,"items":[{"presentationId":%d,"minQty":10,"price":3500}]}""".formatted(arroz))
                .expect(200).json().get("id").asLong();
        long me = api.get("/api/auth/me", owner).json().get("id").asLong();
        int today = LocalDate.now().getDayOfWeek().getValue();
        route = api.post("/api/distribution/routes", owner, """
                {"name":"Ruta Norte","sellerUserId":%d,"visitDays":[%d],"commissionPct":2}""".formatted(me, today)).expect(200).json().get("id").asLong();
        for (int i = 0; i < 3; i++) {
            customers[i] = api.post("/api/pos/customers", owner, "{\"name\":\"Tienda %d\",\"phone\":\"30000000%d\"}".formatted(i + 1, i))
                    .expect(201).json().get("id").asLong();
            api.put("/api/distribution/customers/" + customers[i], owner,
                    "{\"routeId\":%d,\"priceListId\":%d,\"routeOrder\":%d}".formatted(route, list, i)).expect(200);
        }
        long reg = api.get("/api/registers", owner).json().get(0).get("id").asLong();
        api.post("/api/shifts/open", owner, "{\"registerId\":%d,\"openingCash\":20000}".formatted(reg)).expect(200);
    }

    private JsonNode order(int i, String ref, int arrozQty, int aceiteQty) throws Exception {
        String taken = Instant.now().minus(2, ChronoUnit.HOURS).toString();
        return api.post("/api/distribution/orders", owner, """
                {"clientRef":"%s","customerId":%d,"offline":true,"takenAt":"%s",
                 "lines":[{"presentationId":%d,"quantity":%d},{"presentationId":%d,"quantity":%d}]}"""
                .formatted(ref, customers[i], taken, arroz, arrozQty, aceite, aceiteQty)).expect(200).json();
    }

    @Test
    void presaleToRouteClose() throws Exception {
        // Lo que el vendedor carga en el celular: sus clientes de hoy con la lista de precios.
        JsonNode seller = api.get("/api/distribution/seller", owner).expect(200).json();
        assertThat(seller.get("customers")).hasSize(3);
        assertThat(seller.get("customers").get(0).get("today").asBoolean()).isTrue();

        // Tres pedidos tomados sin internet que llegan al volver la señal (uno se reenvía: no se duplica).
        JsonNode o1 = order(0, "cel-1", 10, 2);
        JsonNode o2 = order(1, "cel-2", 10, 1);
        JsonNode o3 = order(2, "cel-3", 4, 1);
        assertThat(order(0, "cel-1", 10, 2).get("id").asLong()).isEqualTo(o1.get("id").asLong());
        assertThat(o1.get("offline").asBoolean()).isTrue();
        // Arroz x10 a 3.500 (escalón) + aceite con 5 % = 9.500.
        assertThat(o1.get("total").decimalValue()).isEqualByComparingTo("54000");
        assertThat(o3.get("lines").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("3800"); // 4 arroz: 5 % sobre 4.000
        assertThat(o3.get("total").decimalValue()).isEqualByComparingTo("24700");
        assertThat(api.get("/api/distribution/orders?status=PENDIENTE", owner).json()).hasSize(3);

        // Alistamiento consolidado y despacho.
        String ids = "[%d,%d,%d]".formatted(o1.get("id").asLong(), o2.get("id").asLong(), o3.get("id").asLong());
        JsonNode pick = api.post("/api/distribution/pick", owner, "{\"orderIds\":" + ids + "}").expect(200).json();
        JsonNode arrozLine = pick.get("lines").get(1).get("productName").asText().startsWith("Arroz") ? pick.get("lines").get(1) : pick.get("lines").get(0);
        assertThat(arrozLine.get("quantity").decimalValue()).isEqualByComparingTo("24");
        JsonNode d = api.post("/api/distribution/dispatches", owner,
                "{\"orderIds\":%s,\"driverName\":\"Pedro\",\"vehicle\":\"NPR placa ABC123\",\"routeId\":%d}".formatted(ids, route)).expect(200).json();
        long dispatch = d.get("id").asLong();
        assertThat(d.get("orders")).hasSize(3);
        // La remisión se arma con los datos del pedido.
        JsonNode rem = api.get("/api/distribution/orders/" + o1.get("id").asLong(), owner).json();
        assertThat(rem.get("status").asText()).isEqualTo("DESPACHADO");
        assertThat(rem.get("dispatchId").asLong()).isEqualTo(dispatch);

        // Pedido 1: entregado completo y pagado en efectivo.
        api.post("/api/distribution/orders/%d/deliver".formatted(o1.get("id").asLong()), owner,
                "{\"payments\":[{\"method\":\"EFECTIVO\",\"amount\":54000}]}").expect(200);
        // Pedido 2: el cliente recibe solo 5 arroces; paga en efectivo lo entregado.
        long arrozLine2 = o2.get("lines").get(0).get("id").asLong();
        api.post("/api/distribution/orders/%d/deliver".formatted(o2.get("id").asLong()), owner,
                "{\"lines\":[{\"lineId\":%d,\"deliveredQty\":5}],\"payments\":[{\"method\":\"EFECTIVO\",\"amount\":27000}]}".formatted(arrozLine2))
                .expect(400); // falta el motivo
        JsonNode p2 = api.post("/api/distribution/orders/%d/deliver".formatted(o2.get("id").asLong()), owner, """
                {"lines":[{"lineId":%d,"deliveredQty":5}],"payments":[{"method":"EFECTIVO","amount":27000}],"returnReason":"No le cabía en la bodega"}"""
                .formatted(arrozLine2)).expect(200).json();
        assertThat(p2.get("status").asText()).isEqualTo("PARCIAL");
        // 5 arroces al precio pactado (3.500) + 1 aceite (9.500) = 27.000.
        assertThat(p2.get("deliveredTotal").decimalValue()).isEqualByComparingTo("27000");
        // Pedido 3: queda a crédito (cartera).
        JsonNode p3 = api.post("/api/distribution/orders/%d/deliver".formatted(o3.get("id").asLong()), owner, "{}").expect(200).json();
        assertThat(p3.get("creditTotal").decimalValue()).isEqualByComparingTo("24700");

        // Inventario: solo salió lo entregado (10 + 5 + 4 = 19 arroces).
        assertThat(api.get("/api/products/" + arrozId, owner).json().get("stock").decimalValue()).isEqualByComparingTo("81");

        // Cierre de ruta: el conductor trae 81.000 en efectivo y cuadra.
        JsonNode closed = api.post("/api/distribution/dispatches/%d/close".formatted(dispatch), owner, "{\"cashCounted\":81000}").expect(200).json();
        assertThat(closed.get("cashExpected").decimalValue()).isEqualByComparingTo("81000");
        assertThat(closed.get("difference").decimalValue()).isEqualByComparingTo("0");
        assertThat(closed.get("credit").decimalValue()).isEqualByComparingTo("24700");
        assertThat(closed.get("returned").decimalValue()).isEqualByComparingTo("17500"); // 5 arroces devueltos
        // El efectivo entra a la caja de quien cierra.
        assertThat(api.get("/api/shifts/current", owner).json().get("expectedCash").decimalValue()).isEqualByComparingTo("101000");
        api.post("/api/distribution/dispatches/%d/close".formatted(dispatch), owner, "{\"cashCounted\":81000}").expect(409);

        // Reporte: ventas del vendedor y efectividad de la ruta.
        JsonNode rep = api.get("/api/distribution/report", owner).expect(200).json();
        assertThat(rep.get("sellers").get(0).get("delivered").decimalValue()).isEqualByComparingTo("105700");
        assertThat(rep.get("sellers").get(0).get("commission").decimalValue()).isEqualByComparingTo("2114");
        assertThat(rep.get("routes").get(0).get("orders").asLong()).isEqualTo(3);
    }

    @Test
    void routeCloseNeedsEveryOrder() throws Exception {
        JsonNode o1 = order(0, "x-1", 1, 1);
        api.post("/api/distribution/pick", owner, "{\"orderIds\":[%d]}".formatted(o1.get("id").asLong())).expect(200);
        long d = api.post("/api/distribution/dispatches", owner, "{\"orderIds\":[%d],\"driverName\":\"Ana\"}".formatted(o1.get("id").asLong()))
                .expect(200).json().get("id").asLong();
        api.post("/api/distribution/dispatches/%d/close".formatted(d), owner, "{\"cashCounted\":0}").expect(409);
        JsonNode c = api.post("/api/distribution/dispatches/%d/close".formatted(d), owner, "{\"cashCounted\":0,\"returnPending\":true}").expect(200).json();
        assertThat(c.get("orders").get(0).get("status").asText()).isEqualTo("DEVUELTO");
        // Visita sin pedido para la efectividad.
        api.post("/api/distribution/visits", owner, "{\"customerId\":%d,\"result\":\"CERRADO\"}".formatted(customers[1])).expect(200);
    }

    @Test
    void moduleRequired() throws Exception {
        String other = newStore().ownerToken();
        api.get("/api/distribution/seller", other).expect(409);
    }
}
