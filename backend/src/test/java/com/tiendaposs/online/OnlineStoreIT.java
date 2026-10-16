package com.tiendaposs.online;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.support.AbstractIntegrationTest;
import com.tiendaposs.support.Api;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Fase 9: tienda en línea, catálogo público y pedidos que llegan al POS. */
class OnlineStoreIT extends AbstractIntegrationTest {

    static final String PNG = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    Api api;
    Store store;
    String owner;
    String slug;
    long zoneId;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, json);
        store = newStore();
        owner = store.ownerToken();
        long reg = api.get("/api/registers", owner).expect(200).json().get(0).get("id").asLong();
        api.post("/api/shifts/open", owner, "{\"registerId\":%d,\"openingCash\":50000}".formatted(reg)).expect(200);
    }

    // ---------------- ayudas ----------------

    JsonNode product(String name, int price, int stock, String extra) throws Exception {
        return api.post("/api/products", owner, """
                {"name":"%s","unit":"UNIDAD","cost":%d,"initialStock":%d%s,
                 "presentations":[{"name":"Unidad","factor":1,"price":%d,"isBase":true}]}
                """.formatted(name, price / 2, stock, extra == null ? "" : "," + extra, price)).expect(201).json();
    }

    long base(JsonNode p) {
        for (JsonNode pr : p.get("presentations")) if (pr.get("isBase").asBoolean()) return pr.get("id").asLong();
        throw new IllegalStateException();
    }

    BigDecimal stock(long id) throws Exception {
        return api.get("/api/products/" + id, owner).expect(200).json().get("stock").decimalValue();
    }

    JsonNode enable(String extra) throws Exception {
        JsonNode mods = api.get("/api/settings/pos/modules", owner).json();
        StringBuilder list = new StringBuilder("\"TIENDA_ONLINE\"");
        mods.get("enabled").forEach(m -> list.append(",\"").append(m.asText()).append('"'));
        api.put("/api/settings/pos/modules", owner, "{\"businessType\":\"TIENDA\",\"modules\":[" + list + "]}").expect(200);
        slug = api.get("/api/online-store", owner).expect(200).json().get("slug").asText();
        JsonNode cfg = api.put("/api/online-store", owner, """
                {"enabled":true,"slug":"%s","mode":"PEDIDOS","welcome":"Domicilios en el barrio","whatsapp":"3001234567",
                 "showStock":false,"deliveryEnabled":true,"pickupEnabled":true,"minOrder":5000,
                 "payMethods":["CONTRA_ENTREGA","NEQUI"],"schedule":[],"paused":false,"prepMinutes":25,"showBadge":true,
                 "zones":[{"name":"Prado","fee":3000,"minOrder":10000,"active":true},{"name":"Centro","fee":5000,"active":true}]%s}
                """.formatted(slug, extra == null ? "" : "," + extra)).expect(200).json();
        zoneId = cfg.get("zones").get(0).get("id").asLong();
        return cfg;
    }

    String order(String fulfillment, Long zone, String items) {
        return """
                {"name":"Ana Pérez","phone":"300 555 1234","email":"ana@correo.test","fulfillment":"%s",%s"address":"Calle 50 # 10-20",
                 "notes":"Timbre dañado","payMethod":"CONTRA_ENTREGA","items":[%s]}
                """.formatted(fulfillment, zone == null ? "" : "\"zoneId\":" + zone + ",", items);
    }

    String item(long presentationId, int qty) {
        return "{\"presentationId\":%d,\"quantity\":%d}".formatted(presentationId, qty);
    }

    // ---------------- pruebas ----------------

    @Test
    void publicCatalogShowsOnlyPublishedProductsWithOnlinePrices() throws Exception {
        JsonNode cerveza = product("Cerveza Águila", 3500, 24, "\"photo\":\"" + PNG + "\"");
        JsonNode oculto = product("Producto interno", 1000, 5, null);
        // Sin el módulo no se puede publicar.
        String s0 = api.get("/api/online-store", owner).expect(200).json().get("slug").asText();
        assertThat(s0).startsWith("tienda-");
        api.put("/api/online-store", owner, """
                {"enabled":true,"slug":"%s","mode":"PEDIDOS","payMethods":["CONTRA_ENTREGA"],"deliveryEnabled":true,"pickupEnabled":true,
                 "prepMinutes":30,"schedule":[]}""".formatted(s0)).expect(409);
        api.get("/api/public/stores/" + s0, null).expect(404);
        enable(null);
        api.put("/api/online-store/products", owner, """
                [{"productId":%d,"visible":false},{"productId":%d,"onlinePrice":3300}]""".formatted(oculto.get("id").asLong(), cerveza.get("id").asLong())).expect(200);

        JsonNode pub = api.get("/api/public/stores/" + slug, null).expect(200).json();
        assertThat(pub.get("deliveryEnabled").asBoolean()).isTrue();
        assertThat(pub.get("zones")).hasSize(2);
        assertThat(pub.get("open").asBoolean()).isTrue();
        assertThat(pub.toString()).doesNotContain("Producto interno");
        JsonNode p = pub.get("products").get(0);
        assertThat(p.get("name").asText()).isEqualTo("Cerveza Águila");
        assertThat(p.get("presentations").get(0).get("price").decimalValue()).isEqualByComparingTo("3300");
        assertThat(p.get("available").asBoolean()).isTrue();
        assertThat(p.has("stock")).isFalse(); // la tienda no muestra existencias exactas
        // La foto viaja aparte (catálogo liviano) y se sirve como imagen.
        String photoUrl = p.get("photoUrl").asText();
        var res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(photoUrl)).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getContentType()).isEqualTo("image/png");
        // Otra tienda no puede quitarle la dirección.
        Store other = newStore();
        api.put("/api/online-store", other.ownerToken(), """
                {"enabled":false,"slug":"%s","mode":"CARTA","payMethods":[],"prepMinutes":30}""".formatted(slug)).expect(400);
        // Apagarla la quita de internet.
        api.put("/api/online-store", owner, """
                {"enabled":false,"slug":"%s","mode":"PEDIDOS","payMethods":["CONTRA_ENTREGA"],"deliveryEnabled":true,"pickupEnabled":true,"prepMinutes":30}"""
                .formatted(slug)).expect(200);
        api.get("/api/public/stores/" + slug, null).expect(404);
    }

    @Test
    void orderGoesFromPhoneToPosAndIsChargedAsASale() throws Exception {
        JsonNode cerveza = product("Cerveza Águila", 3500, 10, null);
        JsonNode papas = product("Papas Margarita", 2500, 6, null);
        enable(null);
        long cid = cerveza.get("id").asLong();
        // Pedido mínimo de la zona Prado: 10.000.
        api.post("/api/public/stores/" + slug + "/orders", null, order("DOMICILIO", zoneId, item(base(cerveza), 2))).expect(400);
        api.post("/api/public/stores/" + slug + "/orders", null, order("DOMICILIO", null, item(base(cerveza), 4))).expect(400); // falta la zona
        api.post("/api/public/stores/" + slug + "/orders", null, order("DOMICILIO", zoneId, item(base(cerveza), 50))).expect(409); // no alcanza
        JsonNode placed = api.post("/api/public/stores/" + slug + "/orders", null,
                order("DOMICILIO", zoneId, item(base(cerveza), 4) + "," + item(base(papas), 1))).expect(201).json();
        assertThat(placed.get("subtotal").decimalValue()).isEqualByComparingTo("16500");
        assertThat(placed.get("deliveryFee").decimalValue()).isEqualByComparingTo("3000");
        assertThat(placed.get("total").decimalValue()).isEqualByComparingTo("19500");
        assertThat(placed.get("status").asText()).isEqualTo("NUEVO");
        assertThat(placed.get("whatsappUrl").asText()).startsWith("https://wa.me/573001234567?text=");
        String token = placed.get("token").asText();
        assertThat(api.get("/api/public/orders/" + token, null).expect(200).json().get("number").asLong()).isEqualTo(1);
        api.get("/api/public/orders/no-existe", null).expect(404);

        // Llega a la bandeja del POS.
        assertThat(api.get("/api/online-orders/counts", owner).expect(200).json().get("nuevos").asLong()).isEqualTo(1);
        JsonNode o = api.get("/api/online-orders", owner).expect(200).json().get(0);
        long oid = o.get("id").asLong();
        assertThat(o.get("customerName").asText()).isEqualTo("Ana Pérez");
        assertThat(api.get("/api/notifications", owner).expect(200).raw()).contains("Pedido en línea #1");

        // Aceptar aparta las 4 cervezas: el mostrador solo puede vender 6.
        api.post("/api/online-orders/" + oid + "/accept", owner, null).expect(200);
        assertThat(api.get("/api/products/" + cid, owner).json().get("reserved").decimalValue()).isEqualByComparingTo("4");
        String sale7 = "{\"clientRef\":\"%s\",\"lines\":[{\"presentationId\":%d,\"quantity\":7}],\"payments\":[{\"method\":\"EFECTIVO\",\"amount\":30000}]}"
                .formatted(UUID.randomUUID(), base(cerveza));
        JsonNode err = api.post("/api/pos/sales", owner, sale7).expect(409).json();
        assertThat(err.get("message").asText()).contains("apartadas");
        // El catálogo de la caja muestra lo disponible.
        for (JsonNode x : api.get("/api/pos/catalog", owner).json()) {
            if (x.get("id").asLong() == cid) assertThat(x.get("stock").decimalValue()).isEqualByComparingTo("6");
        }
        api.post("/api/online-orders/" + oid + "/status/LISTO", owner, null).expect(200);
        api.post("/api/online-orders/" + oid + "/status/EN_CAMINO", owner, null).expect(200);
        assertThat(api.get("/api/public/orders/" + token, null).json().get("status").asText()).isEqualTo("EN_CAMINO");

        // Entregar y cobrar: queda como una venta normal, con el domicilio como línea.
        JsonNode sale = api.post("/api/online-orders/" + oid + "/deliver", owner,
                "{\"payments\":[{\"method\":\"EFECTIVO\",\"amount\":20000}]}").expect(200).json();
        assertThat(sale.get("total").decimalValue()).isEqualByComparingTo("19500");
        assertThat(sale.get("changeAmount").decimalValue()).isEqualByComparingTo("500");
        assertThat(sale.get("lines")).hasSize(3);
        assertThat(sale.get("lines").get(2).get("productName").asText()).isEqualTo("Domicilio");
        assertThat(sale.get("customerName").asText()).isEqualTo("Ana Pérez");
        assertThat(sale.get("notes").asText()).isEqualTo("Pedido en línea #1");
        assertThat(stock(cid)).isEqualByComparingTo("6");
        assertThat(api.get("/api/products/" + cid, owner).json().get("reserved").decimalValue()).isEqualByComparingTo("0");
        JsonNode done = api.get("/api/online-orders/" + oid, owner).json();
        assertThat(done.get("status").asText()).isEqualTo("ENTREGADO");
        assertThat(done.get("saleId").asLong()).isEqualTo(sale.get("id").asLong());
        // Cobrar dos veces no duplica la venta.
        api.post("/api/online-orders/" + oid + "/deliver", owner, "{\"payments\":[{\"method\":\"EFECTIVO\",\"amount\":20000}]}").expect(400);

        // Cancelar devuelve lo apartado.
        JsonNode second = api.post("/api/public/stores/" + slug + "/orders", null, order("RECOGER", null, item(base(papas), 3))).expect(201).json();
        assertThat(second.get("deliveryFee").decimalValue()).isEqualByComparingTo("0");
        long oid2 = api.get("/api/online-orders", owner).json().get(0).get("id").asLong();
        api.post("/api/online-orders/" + oid2 + "/status/EN_CAMINO", owner, null).expect(400); // primero se acepta
        api.post("/api/online-orders/" + oid2 + "/accept", owner, null).expect(200);
        api.post("/api/online-orders/" + oid2 + "/status/EN_CAMINO", owner, null).expect(400); // recoge en tienda
        assertThat(api.get("/api/products/" + papas.get("id").asLong(), owner).json().get("reserved").decimalValue()).isEqualByComparingTo("3");
        api.post("/api/online-orders/" + oid2 + "/cancel", owner, "{\"reason\":\"El cliente no contestó\"}").expect(200);
        assertThat(api.get("/api/products/" + papas.get("id").asLong(), owner).json().get("reserved").decimalValue()).isEqualByComparingTo("0");
        assertThat(api.get("/api/public/orders/" + second.get("token").asText(), null).json().get("status").asText()).isEqualTo("CANCELADO");
        // El cliente quedó registrado una sola vez.
        assertThat(api.get("/api/pos/customers?q=Ana", owner).json()).hasSize(1);
    }

    @Test
    void onlinePriceIsRespectedEvenWithPromotions() throws Exception {
        JsonNode pony = product("Pony Malta", 2000, 20, null);
        api.post("/api/pos/promotions", owner, "{\"name\":\"3x2 Pony\",\"type\":\"LLEVA_PAGA\",\"productId\":%d,\"bundleQty\":3,\"payQty\":2}"
                .formatted(pony.get("id").asLong())).expect(201);
        enable(null);
        JsonNode placed = api.post("/api/public/stores/" + slug + "/orders", null, order("RECOGER", null, item(base(pony), 3))).expect(201).json();
        long oid = api.get("/api/online-orders", owner).json().get(0).get("id").asLong();
        JsonNode sale = api.post("/api/online-orders/" + oid + "/deliver", owner, "{\"payments\":[{\"method\":\"EFECTIVO\",\"amount\":6000}]}").expect(200).json();
        assertThat(sale.get("total").decimalValue()).isEqualByComparingTo(placed.get("total").decimalValue());
    }

    @Test
    void scheduleMenuModeAndPauseStopOrders() throws Exception {
        JsonNode p = product("Pan", 1000, 50, null);
        int today = LocalDate.now(ZoneId.of("America/Bogota")).getDayOfWeek().getValue();
        StringBuilder sch = new StringBuilder();
        for (int d = 1; d <= 7; d++) {
            if (d > 1) sch.append(',');
            sch.append("{\"day\":%d,\"open\":\"07:00\",\"close\":\"19:00\",\"closed\":%s}".formatted(d, d == today));
        }
        enable(null);
        api.put("/api/online-store", owner, """
                {"enabled":true,"slug":"%s","mode":"PEDIDOS","payMethods":["CONTRA_ENTREGA"],"deliveryEnabled":false,"pickupEnabled":true,
                 "prepMinutes":30,"schedule":[%s]}""".formatted(slug, sch)).expect(200);
        JsonNode pub = api.get("/api/public/stores/" + slug, null).expect(200).json();
        assertThat(pub.get("open").asBoolean()).isFalse();
        assertThat(pub.get("statusText").asText()).contains("Abrimos mañana a las 7:00 a. m.");
        JsonNode err = api.post("/api/public/stores/" + slug + "/orders", null, order("RECOGER", null, item(base(p), 1))).expect(409).json();
        assertThat(err.get("message").asText()).contains("Abrimos");
        // Modo carta: se ve, pero no se pide.
        api.put("/api/online-store", owner, """
                {"enabled":true,"slug":"%s","mode":"CARTA","payMethods":[],"deliveryEnabled":false,"pickupEnabled":true,"prepMinutes":30,"schedule":[]}"""
                .formatted(slug)).expect(200);
        api.get("/api/public/stores/" + slug, null).expect(200);
        api.post("/api/public/stores/" + slug + "/orders", null, order("RECOGER", null, item(base(p), 1))).expect(400);
        // Pausa: "no estamos recibiendo pedidos".
        api.put("/api/online-store", owner, """
                {"enabled":true,"slug":"%s","mode":"PEDIDOS","payMethods":["CONTRA_ENTREGA"],"deliveryEnabled":false,"pickupEnabled":true,
                 "prepMinutes":30,"schedule":[],"paused":true,"pausedMessage":"Volvemos en 20 minutos"}""".formatted(slug)).expect(200);
        JsonNode e2 = api.post("/api/public/stores/" + slug + "/orders", null, order("RECOGER", null, item(base(p), 1))).expect(409).json();
        assertThat(e2.get("message").asText()).isEqualTo("Volvemos en 20 minutos");
        // Solo recoger: no se acepta domicilio.
        api.put("/api/online-store", owner, """
                {"enabled":true,"slug":"%s","mode":"PEDIDOS","payMethods":["CONTRA_ENTREGA"],"deliveryEnabled":false,"pickupEnabled":true,
                 "prepMinutes":30,"schedule":[]}""".formatted(slug)).expect(200);
        api.post("/api/public/stores/" + slug + "/orders", null, order("DOMICILIO", null, item(base(p), 1))).expect(400);
        // El comprobante trae la dirección de la tienda en línea para su QR.
        JsonNode s = api.post("/api/pos/sales", owner, "{\"clientRef\":\"%s\",\"lines\":[{\"presentationId\":%d,\"quantity\":1}],\"payments\":[{\"method\":\"EFECTIVO\",\"amount\":1000}]}"
                .formatted(UUID.randomUUID(), base(p))).expect(201).json();
        assertThat(api.get("/api/pos/sales/" + s.get("id").asLong() + "/receipt", owner).json().get("onlineSlug").asText()).isEqualTo(slug);
    }
}
