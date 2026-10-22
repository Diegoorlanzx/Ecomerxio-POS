package com.tiendaposs.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.support.AbstractIntegrationTest;
import com.tiendaposs.support.Api;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Fase 8: perfiles de negocio, módulos, variantes, combos, seriales y etiquetas de báscula. */
class CatalogAdvancedIT extends AbstractIntegrationTest {

    Api api;
    Store store;
    String owner;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, json);
        store = newStore();
        owner = store.ownerToken();
        modules("MIXTO", "VARIANTES", "COMBOS", "SERIALES", "BASCULA", "LOTES", "FIADO", "CUENTAS_ABIERTAS", "COMPRAS");
        long reg = api.get("/api/registers", owner).expect(200).json().get(0).get("id").asLong();
        api.post("/api/shifts/open", owner, "{\"registerId\":%d,\"openingCash\":100000}".formatted(reg)).expect(200);
    }

    // ---------------- ayudas ----------------

    JsonNode modules(String type, String... mods) throws Exception {
        return api.put("/api/settings/pos/modules", owner, "{\"businessType\":\"%s\",\"modules\":[%s]}"
                .formatted(type, String.join(",", List.of(mods).stream().map(m -> "\"" + m + "\"").toList()))).expect(200).json();
    }

    JsonNode product(String name, String unit, int price, int cost, int stock, String extra) throws Exception {
        return api.post("/api/products", owner, """
                {"name":"%s","unit":"%s","cost":%d,"initialStock":%d%s,
                 "presentations":[{"name":"Unidad","factor":1,"price":%d,"isBase":true}]}
                """.formatted(name, unit, cost, stock, extra == null ? "" : "," + extra, price)).expect(201).json();
    }

    long base(JsonNode p) {
        for (JsonNode pr : p.get("presentations")) if (pr.get("isBase").asBoolean()) return pr.get("id").asLong();
        throw new IllegalStateException();
    }

    JsonNode get(long id) throws Exception {
        return api.get("/api/products/" + id, owner).expect(200).json();
    }

    BigDecimal stock(long id) throws Exception {
        return get(id).get("stock").decimalValue();
    }

    String sale(String lines) {
        return "{\"clientRef\":\"%s\",\"lines\":[%s],\"payments\":[{\"method\":\"EFECTIVO\",\"amount\":5000000}]}".formatted(UUID.randomUUID(), lines);
    }

    String line(long presentationId, String qty, String... serials) {
        String ss = serials.length == 0 ? "" : ",\"serials\":[" + String.join(",", List.of(serials).stream().map(x -> "\"" + x + "\"").toList()) + "]";
        return "{\"presentationId\":%d,\"quantity\":%s%s}".formatted(presentationId, qty, ss);
    }

    static String ean13(String first12) {
        int sum = 0;
        for (int i = 0; i < 12; i++) sum += (first12.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        return first12 + (10 - sum % 10) % 10;
    }

    // ---------------- perfiles y módulos ----------------

    @Test
    void businessProfileSetsModulesAndServerEnforcesThem() throws Exception {
        String email = unique("moda") + "@prueba.com";
        JsonNode reg = json.readTree(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"storeName":"Boutique","ownerName":"Dueña","email":"%s","password":"Clave1234","businessType":"MODA","acceptTerms":true,"acceptPrivacy":true}
                """.formatted(email))).andReturn().getResponse().getContentAsString());
        String moda = reg.get("token").asText();
        JsonNode m = api.get("/api/settings/pos/modules", moda).expect(200).json();
        assertThat(m.get("businessType").asText()).isEqualTo("MODA");
        List<String> enabled = new ArrayList<>();
        m.get("enabled").forEach(x -> enabled.add(x.asText()));
        assertThat(enabled).contains("VARIANTES", "COMBOS", "COMPRAS").doesNotContain("FIADO", "SERIALES", "CUENTAS_ABIERTAS");
        assertThat(m.get("profiles").size()).isEqualTo(8);
        // Sin fiado no aparece como medio de pago.
        JsonNode st = api.get("/api/settings/pos", moda).expect(200).json();
        assertThat(st.get("creditEnabled").asBoolean()).isFalse();
        assertThat(st.get("paymentMethods").asText()).doesNotContain("FIADO");
        // Módulos apagados: el servidor los rechaza.
        api.post("/api/pos/accounts", moda, "{\"label\":\"Mesa 1\"}").expect(409);
        JsonNode p = api.post("/api/products", moda, """
                {"name":"Celular","unit":"UNIDAD","trackSerials":true,"presentations":[{"name":"Unidad","factor":1,"price":100,"isBase":true}]}
                """).expect(409).json();
        assertThat(p.get("message").asText()).contains("Seriales");
        // Un módulo "próximamente" o inventado no se puede activar.
        api.put("/api/settings/pos/modules", moda, "{\"businessType\":\"MODA\",\"modules\":[\"SERVICIOS\"]}").expect(400);
        api.put("/api/settings/pos/modules", moda, "{\"businessType\":\"PANADERIA\",\"modules\":[]}").expect(400);
        // Activar fiado lo deja como medio de pago.
        api.put("/api/settings/pos/modules", moda, "{\"businessType\":\"MODA\",\"modules\":[\"VARIANTES\",\"FIADO\"]}").expect(200);
        assertThat(api.get("/api/settings/pos", moda).json().get("paymentMethods").asText()).contains("FIADO");
        // Un cajero no cambia los módulos.
        api.put("/api/settings/pos/modules", newEmployee(store, "CAJERO"), "{\"businessType\":\"MIXTO\",\"modules\":[]}").expect(403);
    }

    // ---------------- variantes ----------------

    @Test
    void variantMatrixCreatesSellableChildrenWithTheirOwnStock() throws Exception {
        JsonNode parent = api.post("/api/products", owner, """
                {"sku":"CAM01","name":"Camiseta básica","unit":"UNIDAD","cost":18000,
                 "presentations":[{"name":"Unidad","factor":1,"price":39900,"isBase":true}]}""").expect(201).json();
        long pid = parent.get("id").asLong();
        JsonNode dto = api.put("/api/products/" + pid + "/variants", owner, """
                {"options":[{"name":"Talla","values":["S","M"]},{"name":"Color","values":["Negro","Blanco"]}],
                 "variants":[
                   {"values":{"Talla":"S","Color":"Negro"},"barcode":"7700000000011","price":39900,"initialStock":3},
                   {"values":{"Talla":"S","Color":"Blanco"},"price":39900,"initialStock":2},
                   {"values":{"Talla":"M","Color":"Negro"},"price":41900,"initialStock":5},
                   {"values":{"Talla":"M","Color":"Blanco"},"price":41900,"cost":19000,"initialStock":0}]}""").expect(200).json();
        assertThat(dto.get("productType").asText()).isEqualTo("PADRE");
        assertThat(dto.get("trackStock").asBoolean()).isFalse();
        assertThat(dto.get("variants")).hasSize(4);
        assertThat(dto.get("stock").decimalValue()).isEqualByComparingTo("10");
        JsonNode sNegro = dto.get("variants").get(0);
        assertThat(sNegro.get("label").asText()).isEqualTo("S / Negro");
        assertThat(sNegro.get("sku").asText()).isEqualTo("CAM01-S-NEGR");
        assertThat(sNegro.get("barcode").asText()).isEqualTo("7700000000011");
        long childId = sNegro.get("id").asLong();
        JsonNode child = get(childId);
        assertThat(child.get("name").asText()).isEqualTo("Camiseta básica · S / Negro");
        assertThat(child.get("parentName").asText()).isEqualTo("Camiseta básica");
        assertThat(child.get("variantValues").get("Color").asText()).isEqualTo("Negro");

        // La caja recibe el padre sin presentaciones y las variantes con su padre.
        JsonNode cat = api.get("/api/pos/catalog", owner).expect(200).json();
        int children = 0;
        for (JsonNode x : cat) {
            if (x.get("id").asLong() == pid) assertThat(x.get("presentations")).isEmpty();
            if (x.has("parentId") && x.get("parentId").asLong() == pid) children++;
        }
        assertThat(children).isEqualTo(4);
        // El padre no se vende; la variante sí, y descuenta su propio stock. El código de barras encuentra la variante.
        api.post("/api/pos/sales", owner, sale(line(base(parent), "1"))).expect(400);
        assertThat(api.get("/api/products/barcode/7700000000011", owner).expect(200).json().get("id").asLong()).isEqualTo(childId);
        api.post("/api/pos/sales", owner, sale(line(base(child), "2"))).expect(201);
        assertThat(stock(childId)).isEqualByComparingTo("1");
        assertThat(stock(pid)).isEqualByComparingTo("8");
        // El inventario no se ajusta en el padre.
        api.post("/api/inventory/adjust", owner, "{\"productId\":%d,\"mode\":\"ENTRADA\",\"quantity\":3,\"reason\":\"x\"}".formatted(pid)).expect(400);

        // La lista principal muestra solo el padre; buscar por el código de una variante lo encuentra.
        JsonNode top = api.get("/api/products?level=TOP&q=7700000000011", owner).expect(200).json();
        assertThat(top.get("total").asInt()).isEqualTo(1);
        assertThat(top.get("items").get(0).get("id").asLong()).isEqualTo(pid);
        assertThat(api.get("/api/products?level=ITEMS&q=camiseta", owner).json().get("total").asInt()).isEqualTo(4);

        // Quitar una talla la descontinúa; agregar un color crea las variantes nuevas.
        JsonNode dto2 = api.put("/api/products/" + pid + "/variants", owner, """
                {"options":[{"name":"Talla","values":["S","M"]},{"name":"Color","values":["Negro","Blanco","Rojo"]}],
                 "variants":[
                   {"id":%d,"values":{"Talla":"S","Color":"Negro"},"barcode":"7700000000011","price":37900},
                   {"values":{"Talla":"M","Color":"Negro"},"price":41900},
                   {"values":{"Talla":"M","Color":"Blanco"},"price":41900},
                   {"values":{"Talla":"M","Color":"Rojo"},"price":41900,"initialStock":4}]}""".formatted(childId)).expect(200).json();
        assertThat(dto2.get("variants")).hasSize(5);
        long discontinued = 0;
        for (JsonNode v : dto2.get("variants")) if (v.get("status").asText().equals("DISCONTINUED")) discontinued++;
        assertThat(discontinued).isEqualTo(1);
        assertThat(get(childId).get("presentations").get(0).get("price").decimalValue()).isEqualByComparingTo("37900");
        // Valores que no están en la lista: error claro.
        api.put("/api/products/" + pid + "/variants", owner, """
                {"options":[{"name":"Talla","values":["S"]}],"variants":[{"values":{"Talla":"XL"},"price":1}]}""").expect(400);
        // Renombrar el padre renombra sus variantes.
        api.put("/api/products/" + pid, owner, """
                {"sku":"CAM01","name":"Camiseta premium","unit":"UNIDAD",
                 "presentations":[{"id":%d,"name":"Unidad","factor":1,"price":39900,"isBase":true}]}""".formatted(base(parent))).expect(200);
        assertThat(get(childId).get("name").asText()).isEqualTo("Camiseta premium · S / Negro");
    }

    // ---------------- combos ----------------

    @Test
    void comboDiscountsComponentsAndReturnsPutThemBack() throws Exception {
        JsonNode cafe = product("Café", "UNIDAD", 3000, 800, 20, null);
        JsonNode pan = product("Pan", "UNIDAD", 1000, 300, 20, null);
        JsonNode combo = product("Desayuno", "UNIDAD", 6000, 0, 0, null);
        long cid = combo.get("id").asLong();
        JsonNode dto = api.put("/api/products/" + cid + "/components", owner, """
                {"components":[{"productId":%d,"quantity":1},{"productId":%d,"quantity":2}]}"""
                .formatted(cafe.get("id").asLong(), pan.get("id").asLong())).expect(200).json();
        assertThat(dto.get("productType").asText()).isEqualTo("COMBO");
        assertThat(dto.get("components")).hasSize(2);
        assertThat(dto.get("cost").decimalValue()).isEqualByComparingTo("1400");
        // No se anidan combos ni se mete el mismo producto.
        api.put("/api/products/" + cafe.get("id").asLong() + "/components", owner,
                "{\"components\":[{\"productId\":%d,\"quantity\":1}]}".formatted(cid)).expect(400);

        JsonNode s = api.post("/api/pos/sales", owner, sale(line(base(combo), "3"))).expect(201).json();
        assertThat(stock(cafe.get("id").asLong())).isEqualByComparingTo("17");
        assertThat(stock(pan.get("id").asLong())).isEqualByComparingTo("14");
        JsonNode l = s.get("lines").get(0);
        assertThat(l.get("unitCost").decimalValue()).isEqualByComparingTo("1400");
        assertThat(l.get("components")).hasSize(2);
        assertThat(s.get("costTotal").decimalValue()).isEqualByComparingTo("4200");
        // Devolver un combo en buen estado regresa sus componentes.
        api.post("/api/pos/sales/" + s.get("id").asLong() + "/returns", owner, """
                {"lines":[{"saleLineId":%d,"quantity":1,"restock":true,"condition":"BUENO"}],"reason":"No lo quiso","refundMethod":"EFECTIVO"}
                """.formatted(l.get("id").asLong())).expect(200);
        assertThat(stock(cafe.get("id").asLong())).isEqualByComparingTo("18");
        assertThat(stock(pan.get("id").asLong())).isEqualByComparingTo("16");
        // Anular otra venta del combo devuelve todo.
        JsonNode s2 = api.post("/api/pos/sales", owner, sale(line(base(combo), "2"))).expect(201).json();
        assertThat(stock(pan.get("id").asLong())).isEqualByComparingTo("12");
        api.post("/api/pos/sales/" + s2.get("id").asLong() + "/void", owner, "{\"reason\":\"Error\"}").expect(200);
        assertThat(stock(pan.get("id").asLong())).isEqualByComparingTo("16");
        // La demanda del componente incluye lo vendido en combos (inventario inteligente).
        JsonNode ins = api.get("/api/inventory/insights", owner).expect(200).json();
        assertThat(ins.toString()).contains("Pan");
    }

    // ---------------- seriales ----------------

    @Test
    void serialsTrackEachUnitFromEntryToWarranty() throws Exception {
        api.post("/api/products", owner, """
                {"name":"Celular X","unit":"UNIDAD","trackSerials":true,"initialStock":2,
                 "presentations":[{"name":"Unidad","factor":1,"price":900000,"isBase":true}]}""").expect(400);
        JsonNode p = product("Celular X", "UNIDAD", 900000, 600000, 0, "\"trackSerials\":true,\"warrantyMonths\":12");
        long id = p.get("id").asLong();
        assertThat(p.get("trackSerials").asBoolean()).isTrue();
        api.post("/api/products/" + id + "/serials", owner,
                "{\"serials\":[\"IMEI001\",\"imei002\",\"IMEI003\"],\"unitCost\":610000,\"reason\":\"Inventario inicial\"}").expect(201);
        assertThat(stock(id)).isEqualByComparingTo("3");
        api.post("/api/products/" + id + "/serials", owner, "{\"serials\":[\"IMEI002\"],\"reason\":\"x\"}").expect(400);
        // El stock no se ajusta a mano: va por serial.
        api.post("/api/inventory/adjust", owner, "{\"productId\":%d,\"mode\":\"ENTRADA\",\"quantity\":1,\"reason\":\"x\"}".formatted(id)).expect(400);
        assertThat(api.get("/api/products/" + id + "/serials/available", owner).expect(200).json()).hasSize(3);

        // Vender exige un serial disponible por unidad.
        api.post("/api/pos/sales", owner, sale(line(base(p), "1"))).expect(400);
        api.post("/api/pos/sales", owner, sale(line(base(p), "1", "NOEXISTE"))).expect(400);
        String body = "{\"clientRef\":\"%s\",\"customer\":{\"name\":\"Ana Pérez\",\"docNumber\":\"1010\"},\"lines\":[%s],\"payments\":[{\"method\":\"EFECTIVO\",\"amount\":900000}]}"
                .formatted(UUID.randomUUID(), line(base(p), "1", "imei001"));
        JsonNode s = api.post("/api/pos/sales", owner, body).expect(201).json();
        assertThat(s.get("lines").get(0).get("serials").get(0).asText()).isEqualTo("IMEI001");
        assertThat(stock(id)).isEqualByComparingTo("2");
        api.post("/api/pos/sales", owner, sale(line(base(p), "1", "IMEI001"))).expect(400);

        // Garantía: se encuentra por serial o por cliente.
        JsonNode w = api.get("/api/serials?q=imei001", owner).expect(200).json();
        assertThat(w.get(0).get("status").asText()).isEqualTo("VENDIDO");
        assertThat(w.get(0).get("customerName").asText()).isEqualTo("Ana Pérez");
        assertThat(w.get(0).get("inWarranty").asBoolean()).isTrue();
        assertThat(w.get(0).get("saleNumber").asLong()).isEqualTo(s.get("number").asLong());
        assertThat(api.get("/api/serials?q=ana p", owner).expect(200).json()).hasSize(1);

        // Devolución: indica el serial; en buen estado vuelve al stock.
        long lineId = s.get("lines").get(0).get("id").asLong();
        api.post("/api/pos/sales/" + s.get("id").asLong() + "/returns", owner, """
                {"lines":[{"saleLineId":%d,"quantity":1,"restock":true,"condition":"BUENO","serials":["IMEI002"]}],"reason":"x","refundMethod":"EFECTIVO"}
                """.formatted(lineId)).expect(400);
        api.post("/api/pos/sales/" + s.get("id").asLong() + "/returns", owner, """
                {"lines":[{"saleLineId":%d,"quantity":1,"restock":true,"condition":"BUENO","serials":["IMEI001"]}],"reason":"Cambio","refundMethod":"EFECTIVO"}
                """.formatted(lineId)).expect(200);
        assertThat(stock(id)).isEqualByComparingTo("3");
        assertThat(api.get("/api/serials?q=imei001", owner).json().get(0).get("status").asText()).isEqualTo("EN_STOCK");

        // Sin internet la venta nunca se rechaza: un serial raro queda marcado para revisión.
        String off = "{\"clientRef\":\"%s\",\"offline\":true,\"lines\":[%s],\"payments\":[{\"method\":\"EFECTIVO\",\"amount\":900000}]}"
                .formatted(UUID.randomUUID(), line(base(p), "1", "IMEI999"));
        JsonNode so = api.post("/api/pos/sales", owner, off).expect(201).json();
        assertThat(so.get("reviewFlags").toString()).contains("SERIAL_INVALIDO");
        // Una venta sin internet de hace 10 días: la garantía cuenta desde el día de la venta, no desde la sincronización.
        java.time.Instant tenDays = java.time.Instant.now().minus(java.time.Duration.ofDays(10));
        String off2 = "{\"clientRef\":\"%s\",\"offline\":true,\"offlineCreatedAt\":\"%s\",\"lines\":[%s],\"payments\":[{\"method\":\"EFECTIVO\",\"amount\":900000}]}"
                .formatted(UUID.randomUUID(), tenDays, line(base(p), "1", "IMEI002"));
        api.post("/api/pos/sales", owner, off2).expect(201);
        JsonNode w2 = api.get("/api/serials?q=imei002", owner).expect(200).json().get(0);
        assertThat(w2.get("warrantyUntil").asText())
                .isEqualTo(tenDays.atZone(java.time.ZoneId.of("America/Bogota")).toLocalDate().plusMonths(12).toString());

        // Baja de un serial dañado.
        long serialId = api.get("/api/serials?q=imei003", owner).json().get(0).get("id").asLong();
        BigDecimal before = stock(id);
        api.post("/api/serials/" + serialId + "/write-off", owner, "{\"reason\":\"Pantalla rota\"}").expect(200);
        assertThat(stock(id)).isEqualByComparingTo(before.subtract(BigDecimal.ONE));

        // Recepción de compra: un serial por unidad.
        long sup = api.post("/api/suppliers", owner, "{\"name\":\"Mayorista Cel\"}").expect(201).json().get("id").asLong();
        api.post("/api/receipts", owner, """
                {"supplierId":%d,"lines":[{"productId":%d,"unitsReceived":2,"unitCost":600000,"serials":["IMEI010"]}]}""".formatted(sup, id)).expect(400);
        api.post("/api/receipts", owner, """
                {"supplierId":%d,"lines":[{"productId":%d,"unitsReceived":2,"unitCost":600000,"serials":["IMEI010","IMEI011"]}]}""".formatted(sup, id)).expect(201);
        assertThat(api.get("/api/products/" + id + "/serials/available", owner).json().toString()).contains("IMEI010", "IMEI011");
    }

    // ---------------- báscula ----------------

    @Test
    void scaleLabelsGiveProductAndWeightOrPrice() throws Exception {
        JsonNode p = product("Queso campesino", "KILOGRAMO", 18000, 11000, 10, "\"plu\":\"00123\"");
        assertThat(p.get("plu").asText()).isEqualTo("123");
        // Otro producto no puede usar el mismo PLU.
        api.post("/api/products", owner, """
                {"name":"Otro","unit":"KILOGRAMO","plu":"123","presentations":[{"name":"Kilo","factor":1,"price":1,"isBase":true}]}""").expect(400);
        String code = ean13("200012301250");
        JsonNode r = api.get("/api/products/scale/" + code, owner).expect(200).json();
        assertThat(r.get("productId").asLong()).isEqualTo(p.get("id").asLong());
        assertThat(r.get("quantity").decimalValue()).isEqualByComparingTo("1.25");
        assertThat(r.get("total").decimalValue()).isEqualByComparingTo("22500");
        // Dígito de control malo o prefijo que no es de balanza.
        String bad = code.substring(0, 12) + ((code.charAt(12) - '0' + 1) % 10);
        api.get("/api/products/scale/" + bad, owner).expect(400);
        api.get("/api/products/scale/" + ean13("770001230125"), owner).expect(400);
        // Modo precio: la etiqueta trae el valor a cobrar.
        api.put("/api/settings/pos/modules", owner, """
                {"businessType":"FRUVER_CARNICERIA","modules":["BASCULA"],"scaleMode":"PRECIO","scalePluDigits":5}""").expect(200);
        JsonNode r2 = api.get("/api/products/scale/" + ean13("200012309000"), owner).expect(200).json();
        assertThat(r2.get("total").decimalValue()).isEqualByComparingTo("9000");
        assertThat(r2.get("quantity").decimalValue()).isEqualByComparingTo("0.5");
        // Con el módulo apagado no se leen etiquetas.
        modules("TIENDA", "LOTES");
        api.get("/api/products/scale/" + code, owner).expect(409);
    }
}
