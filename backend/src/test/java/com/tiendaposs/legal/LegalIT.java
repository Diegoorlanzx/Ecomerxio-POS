package com.tiendaposs.legal;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.support.AbstractIntegrationTest;
import com.tiendaposs.support.Api;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Aceptación de términos, licencia, servicio, encargo y privacidad: obligatoria, con prueba y por versión. */
class LegalIT extends AbstractIntegrationTest {

    @Test
    void registrationRequiresBothAcceptancesAndLeavesProof() throws Exception {
        Api api = new Api(mvc, json);
        String body = """
                {"storeName":"Sin aceptar","ownerName":"X","email":"%s","password":"Clave1234"%s}""";
        JsonNode r1 = api.post("/api/auth/register", null, body.formatted(unique("l") + "@p.com", "")).expect(400).json();
        assertThat(r1.get("fields").has("acceptTerms")).isTrue();
        JsonNode r2 = api.post("/api/auth/register", null, body.formatted(unique("l") + "@p.com", ",\"acceptTerms\":true")).expect(400).json();
        assertThat(r2.get("fields").has("acceptPrivacy")).isTrue();

        Store s = newStore();
        JsonNode me = api.get("/api/auth/me", s.ownerToken()).expect(200).json();
        assertThat(me.get("legalPending")).isEmpty();
        JsonNode st = api.get("/api/legal/status", s.ownerToken()).expect(200).json();
        assertThat(st.get("history")).hasSize(5);
        assertThat(st.get("history").get(0).get("context").asText()).isEqualTo("REGISTRO");
        assertThat(st.get("history").get(0).get("version").asText()).isEqualTo(LegalService.VERSION);
    }

    @Test
    void employeesAuthorizeTheirOwnDataOnFirstLogin() throws Exception {
        Api api = new Api(mvc, json);
        Store s = newStore();
        String emp = newEmployee(s, "CAJERO");
        JsonNode me = api.get("/api/auth/me", emp).expect(200).json();
        assertThat(me.get("legalPending").get(0).asText()).isEqualTo("PRIVACIDAD");
        api.post("/api/legal/accept", emp, "{\"documents\":[\"PRIVACIDAD\"],\"version\":\"1999-01-01\"}").expect(409);
        api.post("/api/legal/accept", emp, "{\"documents\":[],\"version\":\"%s\"}".formatted(LegalService.VERSION)).expect(400);
        JsonNode ok = api.post("/api/legal/accept", emp, "{\"documents\":[\"PRIVACIDAD\"],\"version\":\"%s\"}".formatted(LegalService.VERSION))
                .expect(200).json();
        assertThat(ok.get("pending")).isEmpty();
        assertThat(ok.get("history")).isEmpty(); // el cajero no ve las aceptaciones de los demás
        assertThat(api.get("/api/legal/status", s.ownerToken()).json().get("history")).hasSize(6);
    }
}
