package com.tiendaposs.offline;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.support.AbstractIntegrationTest;
import com.tiendaposs.support.Api;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** S2: una operación con Idempotency-Key se hace una sola vez aunque la app la reenvíe. */
class IdempotencyIT extends AbstractIntegrationTest {

    Api api;
    Store store;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, json);
        store = newStore();
    }

    private MvcResult send(String token, String key, String path, String body) throws Exception {
        return mvc.perform(post(path).header("Authorization", bearer(token)).header(IdempotencyFilter.HEADER, key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private int categories(String token) throws Exception {
        return api.get("/api/categories", token).expect(200).json().size();
    }

    @Test
    void theSameKeyCreatesOnceAndReplaysTheSameResponse() throws Exception {
        int before = categories(store.ownerToken());
        String key = UUID.randomUUID().toString();
        MvcResult first = send(store.ownerToken(), key, "/api/categories", "{\"name\":\"Granel\"}");
        MvcResult again = send(store.ownerToken(), key, "/api/categories", "{\"name\":\"Granel\"}");
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(again.getResponse().getStatus()).isEqualTo(201);
        assertThat(again.getResponse().getHeader("Idempotent-Replay")).isEqualTo("true");
        JsonNode a = json.readTree(first.getResponse().getContentAsString());
        JsonNode b = json.readTree(again.getResponse().getContentAsString());
        assertThat(b.get("id").asLong()).isEqualTo(a.get("id").asLong());
        assertThat(categories(store.ownerToken())).isEqualTo(before + 1);
    }

    @Test
    void aFailedOperationIsNotStoredAndCanBeRetriedWithTheSameKey() throws Exception {
        String key = UUID.randomUUID().toString();
        assertThat(send(store.ownerToken(), key, "/api/categories", "{\"name\":\"\"}").getResponse().getStatus()).isEqualTo(400);
        MvcResult ok = send(store.ownerToken(), key, "/api/categories", "{\"name\":\"Aseo\"}");
        assertThat(ok.getResponse().getStatus()).isEqualTo(201);
        assertThat(ok.getResponse().getHeader("Idempotent-Replay")).isNull();
    }

    @Test
    void keysAreSeparatePerStoreAndRequestsWithoutKeyWorkAsBefore() throws Exception {
        Store other = newStore();
        String key = UUID.randomUUID().toString();
        send(store.ownerToken(), key, "/api/categories", "{\"name\":\"Bebidas\"}");
        MvcResult theirs = send(other.ownerToken(), key, "/api/categories", "{\"name\":\"Bebidas\"}");
        assertThat(theirs.getResponse().getHeader("Idempotent-Replay")).isNull();
        int before = categories(store.ownerToken());
        api.post("/api/categories", store.ownerToken(), "{\"name\":\"Sin llave 1\"}").expect(201);
        api.post("/api/categories", store.ownerToken(), "{\"name\":\"Sin llave 2\"}").expect(201);
        assertThat(categories(store.ownerToken())).isEqualTo(before + 2);
    }

    @Test
    void aSaleSentTwiceWithTheSameKeyIsChargedOnce() throws Exception {
        String owner = store.ownerToken();
        long pres = api.post("/api/products", owner, """
                {"name":"Arroz","unit":"KILOGRAMO","cost":3000,"initialStock":10,
                 "presentations":[{"name":"Kilo","factor":1,"price":4000,"isBase":true}]}""").expect(201).json()
                .get("presentations").get(0).get("id").asLong();
        long reg = api.get("/api/registers", owner).json().get(0).get("id").asLong();
        String shiftKey = UUID.randomUUID().toString();
        String open = "{\"registerId\":%d,\"openingCash\":0}".formatted(reg);
        assertThat(send(owner, shiftKey, "/api/shifts/open", open).getResponse().getStatus()).isEqualTo(200);
        assertThat(send(owner, shiftKey, "/api/shifts/open", open).getResponse().getStatus()).isEqualTo(200); // no "ya hay turno abierto"

        String saleKey = UUID.randomUUID().toString();
        String sale = """
                {"lines":[{"presentationId":%d,"quantity":0.5}],"payments":[{"method":"EFECTIVO","amount":2000}]}""".formatted(pres);
        JsonNode s1 = json.readTree(send(owner, saleKey, "/api/pos/sales", sale).getResponse().getContentAsString());
        JsonNode s2 = json.readTree(send(owner, saleKey, "/api/pos/sales", sale).getResponse().getContentAsString());
        assertThat(s2.get("id").asLong()).isEqualTo(s1.get("id").asLong());
        JsonNode product = api.get("/api/products?size=50", owner).json().get("items").get(0);
        assertThat(product.get("stock").decimalValue()).isEqualByComparingTo("9.5");
    }
}
