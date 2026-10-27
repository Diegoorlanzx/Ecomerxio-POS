package com.tiendaposs.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** API del Super Admin de Ecomerxio sobre las tiendas del POS: llave, listado, plan, prueba, pago manual, suspensión y soporte. */
@TestPropertySource(properties = "app.platform.admin-key=llave-de-prueba-123")
class PlatformAdminIT extends AbstractIntegrationTest {

    private static final String KEY = "llave-de-prueba-123";

    private JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, String body) throws Exception {
        req.header("X-Platform-Key", KEY);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        return body(mvc.perform(req).andExpect(status().isOk()));
    }

    @Test
    void sinLlaveONiConSesionDeUsuarioNoEntra() throws Exception {
        Store s = newStore();
        mvc.perform(get("/api/platform/stores")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/platform/stores").header("X-Platform-Key", "otra")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/platform/stores").header("Authorization", bearer(s.ownerToken()))).andExpect(status().isUnauthorized());
    }

    @Test
    void gestionaUnaTienda() throws Exception {
        Store s = newStore();
        long id = s.tenantId();

        JsonNode list = call(get("/api/platform/stores"), null);
        JsonNode mine = null;
        for (JsonNode n : list) if (n.get("id").asLong() == id) mine = n;
        assertThat(mine).isNotNull();
        assertThat(mine.get("ownerEmail").asText()).isEqualTo(s.ownerEmail());
        assertThat(mine.get("subscriptionState").asText()).isEqualTo("PRUEBA");

        assertThat(call(patch("/api/platform/stores/" + id + "/plan"), "{\"plan\":\"PRO\"}").get("plan").asText()).isEqualTo("PRO");
        String trial = call(patch("/api/platform/stores/" + id + "/trial"), "{\"days\":10}").get("trialEndsAt").asText();
        assertThat(trial).isNotBlank();

        JsonNode paid = call(post("/api/platform/stores/" + id + "/payments"), "{\"plan\":\"NEGOCIO\",\"period\":\"ANUAL\",\"note\":\"Transferencia Bancolombia\"}");
        assertThat(paid.get("subscriptionState").asText()).isEqualTo("ACTIVA");
        assertThat(paid.get("billingPeriod").asText()).isEqualTo("ANUAL");

        JsonNode detail = call(get("/api/platform/stores/" + id), null);
        assertThat(detail.get("users").size()).isEqualTo(1);
        assertThat(detail.get("history").toString()).contains("Transferencia Bancolombia", "PRUEBA_EXTENDIDA", "PLAN_CAMBIADO");

        // Soporte entra como el dueño con un código de un solo uso que canjea el POS.
        String url = call(post("/api/platform/stores/" + id + "/impersonate"), null).get("url").asText();
        String code = url.substring(url.indexOf("code=") + 5);
        JsonNode session = body(mvc.perform(post("/api/auth/handoff/redeem").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"%s\"}".formatted(code))).andExpect(status().isOk()));
        assertThat(session.at("/me/tenant/id").asLong()).isEqualTo(id);

        // Suspendida: el dueño ya no puede usar la app; reactivada, vuelve a entrar.
        call(patch("/api/platform/stores/" + id + "/status"), "{\"status\":\"SUSPENDED\",\"reason\":\"prueba\"}");
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(s.ownerToken()))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/platform/stores/" + id + "/impersonate").header("X-Platform-Key", KEY)).andExpect(status().isBadRequest());
        call(patch("/api/platform/stores/" + id + "/status"), "{\"status\":\"ACTIVE\"}");
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(s.ownerToken()))).andExpect(status().isOk());
    }
}
