package com.tiendaposs.counter;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.support.AbstractIntegrationTest;
import com.tiendaposs.support.Api;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Tipos de negocio, fase 4: turnos del mostrador (cafetería) e historial por placa en el taller. */
class BusinessTypesPhase4IT extends AbstractIntegrationTest {

    private String register(String type) throws Exception {
        String email = unique("f4") + "@prueba.com";
        return body(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"storeName":"Negocio %s","ownerName":"Dueño","email":"%s","password":"Clave1234","businessType":"%s",
                 "acceptTerms":true,"acceptPrivacy":true}""".formatted(type, email, type))).andExpect(status().isCreated())).get("token").asText();
    }

    private long product(Api api, String owner, String name) throws Exception {
        return api.post("/api/products", owner, """
                {"name":"%s","unit":"UNIDAD","cost":1000,"initialStock":50,
                 "presentations":[{"name":"Unidad","factor":1,"price":3000,"isBase":true}]}""".formatted(name)).expect(201).json()
                .get("presentations").get(0).get("id").asLong();
    }

    private JsonNode sell(Api api, String owner, long pres) throws Exception {
        return api.post("/api/pos/sales", owner, """
                {"clientRef":"%s","lines":[{"presentationId":%d,"quantity":2}],"payments":[{"method":"EFECTIVO","amount":99999}]}"""
                .formatted(UUID.randomUUID(), pres)).expect(201).json();
    }

    private void openShift(Api api, String owner) throws Exception {
        long reg = api.get("/api/registers", owner).json().get(0).get("id").asLong();
        api.post("/api/shifts/open", owner, "{\"registerId\":%d,\"openingCash\":0}".formatted(reg)).expect(200);
    }

    @Test
    void cafeteriaDaTurnosYLosMueveHastaEntregar() throws Exception {
        Api api = new Api(mvc, json);
        String owner = register("CAFETERIA");
        openShift(api, owner);
        long cafe = product(api, owner, "Café con leche");
        JsonNode s1 = sell(api, owner, cafe);
        JsonNode s2 = sell(api, owner, cafe);
        assertThat(s1.get("counterNumber").asInt()).isEqualTo(1);
        assertThat(s2.get("counterNumber").asInt()).isEqualTo(2);

        JsonNode board = api.get("/api/counter/orders", owner).expect(200).json();
        assertThat(board).hasSize(2);
        assertThat(board.get(0).get("items").asText()).isEqualTo("2 Café con leche");
        board = api.post("/api/counter/orders/" + s1.get("id").asLong() + "/status", owner, "{\"status\":\"LISTO\"}").expect(200).json();
        assertThat(board.get(0).get("status").asText()).isEqualTo("LISTO");
        api.post("/api/counter/orders/" + s1.get("id").asLong() + "/status", owner, "{\"status\":\"ENTREGADO\"}").expect(200);
        api.post("/api/counter/orders/" + s1.get("id").asLong() + "/status", owner, "{\"status\":\"OTRO\"}").expect(400);

        // Una tienda sin el módulo no recibe turnos.
        String tienda = register("TIENDA");
        openShift(api, tienda);
        assertThat(sell(api, tienda, product(api, tienda, "Gaseosa")).has("counterNumber")).isFalse();
        api.get("/api/counter/orders", tienda).expect(409);
    }

    @Test
    void tallerMuestraElHistorialPorPlaca() throws Exception {
        Api api = new Api(mvc, json);
        String owner = register("TALLER");
        for (String placa : new String[]{"ABC123", "abc-123", "XYZ987"}) {
            api.post("/api/service-orders", owner, """
                    {"customerName":"Pedro","customerPhone":"3101234567","device":"Moto","serial":"%s","problem":"Cambio de aceite"}""".formatted(placa))
                    .expect(200);
        }
        JsonNode h = api.get("/api/service-orders/history?serial=ABC 123", owner).expect(200).json();
        assertThat(h).hasSize(2);
        assertThat(api.get("/api/service-orders", owner).json().get(0).get("serial").asText()).isNotBlank();
    }
}
