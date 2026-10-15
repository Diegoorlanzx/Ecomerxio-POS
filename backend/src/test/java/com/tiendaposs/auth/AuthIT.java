package com.tiendaposs.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthIT extends AbstractIntegrationTest {

    @MockBean
    MailService mail;

    @Test
    void registerCreatesStoreWithOwnerAndEightRoles() throws Exception {
        Store s = newStore();
        JsonNode me = body(mvc.perform(get("/api/auth/me").header("Authorization", bearer(s.ownerToken())))
                .andExpect(status().isOk()));
        assertThat(me.get("owner").asBoolean()).isTrue();
        assertThat(me.get("roles").get(0).asText()).isEqualTo("Dueño");
        assertThat(me.get("permissions").size()).isEqualTo(com.tiendaposs.security.Permission.values().length);
        assertThat(me.get("tenant").get("currency").asText()).isEqualTo("COP");

        JsonNode roles = body(mvc.perform(get("/api/roles").header("Authorization", bearer(s.ownerToken()))));
        assertThat(roles.size()).isEqualTo(8);
    }

    @Test
    void duplicateEmailAndWeakPasswordAreRejectedPerField() throws Exception {
        Store s = newStore();
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"storeName":"Otra","ownerName":"X","email":"%s","password":"Clave1234"}""".formatted(s.ownerEmail())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.email").exists());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"storeName":"Otra","ownerName":"X","email":"%s","password":"corta"}""".formatted(unique("x") + "@p.com")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.password").exists());
    }

    @Test
    void fiveWrongPasswordsLockTheAccount() throws Exception {
        Store s = newStore();
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"%s\",\"password\":\"mala12345\"}".formatted(s.ownerEmail())))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"Clave1234\"}".formatted(s.ownerEmail())))
                .andExpect(status().isTooManyRequests());
        // El intento fallido y el bloqueo quedan en el historial de accesos.
        JsonNode audit = body(mvc.perform(get("/api/audit?sensitiveOnly=true").header("Authorization", bearer(s.ownerToken()))));
        assertThat(audit.get("items").findValuesAsText("action")).contains("LOGIN_FALLIDO", "CUENTA_BLOQUEADA");
    }

    @Test
    void unknownEmailGivesSameErrorAsWrongPassword() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"noexiste@nada.com\",\"password\":\"Clave1234\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Correo o contraseña incorrectos"));
    }

    @Test
    void logoutEverywhereInvalidatesExistingTokens() throws Exception {
        Store s = newStore();
        String second = login(s.ownerEmail(), "Clave1234");
        mvc.perform(post("/api/auth/logout-all").header("Authorization", bearer(s.ownerToken())))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(second))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(s.ownerToken()))).andExpect(status().isUnauthorized());
    }

    @Test
    void passwordRecoveryFlowWithSingleUseLink() throws Exception {
        Store s = newStore();
        mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\"}".formatted(s.ownerEmail()))).andExpect(status().isOk());
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq(s.ownerEmail()), anyString(), bodyCaptor.capture());
        String token = bodyCaptor.getValue().replaceAll("(?s).*token=([A-Za-z0-9_-]+).*", "$1");

        mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"%s\",\"password\":\"NuevaClave99\"}".formatted(token))).andExpect(status().isNoContent());
        // Reusar el enlace falla y la contraseña nueva funciona; el token viejo queda inválido.
        mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"%s\",\"password\":\"OtraClave99\"}".formatted(token))).andExpect(status().isBadRequest());
        login(s.ownerEmail(), "NuevaClave99");
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(s.ownerToken()))).andExpect(status().isUnauthorized());
    }

    @Test
    void forgotPasswordDoesNotRevealUnknownEmails() throws Exception {
        mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"fantasma@nada.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void changePasswordRequiresCurrentOne() throws Exception {
        Store s = newStore();
        mvc.perform(post("/api/auth/change-password").header("Authorization", bearer(s.ownerToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"equivocada1\",\"newPassword\":\"Nueva12345\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.currentPassword").exists());
        mvc.perform(post("/api/auth/change-password").header("Authorization", bearer(s.ownerToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Clave1234\",\"newPassword\":\"Nueva12345\"}"))
                .andExpect(status().isNoContent());
        login(s.ownerEmail(), "Nueva12345");
    }

    @Test
    void anonymousRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer basura")).andExpect(status().isUnauthorized());
    }
}
