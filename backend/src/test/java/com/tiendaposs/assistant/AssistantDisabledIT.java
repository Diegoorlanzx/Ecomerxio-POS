package com.tiendaposs.assistant;

import com.tiendaposs.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Con el asistente apagado en la plataforma (ASSISTANT_ENABLED=false, el valor por defecto). */
@TestPropertySource(properties = "app.ai.enabled=false")
class AssistantDisabledIT extends AbstractIntegrationTest {

    @Test
    void itsApiIsGoneAndTheAppIsToldButRecommendationsStay() throws Exception {
        Store s = newStore();
        mvc.perform(get("/api/assistant/status").header("Authorization", bearer(s.ownerToken())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(s.ownerToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assistantEnabled").value(false));
        // Las recomendaciones de precio no usan IA: siguen disponibles.
        mvc.perform(get("/api/recommendations").header("Authorization", bearer(s.ownerToken())))
                .andExpect(status().isOk());
    }
}
