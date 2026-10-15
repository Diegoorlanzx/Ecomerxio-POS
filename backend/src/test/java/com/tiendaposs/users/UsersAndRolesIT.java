package com.tiendaposs.users;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UsersAndRolesIT extends AbstractIntegrationTest {

    @Test
    void cashierCannotManageUsersRolesOrSeeAudit() throws Exception {
        Store s = newStore();
        String cajero = newEmployee(s, "CAJERO");
        mvc.perform(get("/api/users").header("Authorization", bearer(cajero))).andExpect(status().isForbidden());
        mvc.perform(post("/api/roles").header("Authorization", bearer(cajero)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Hack\",\"permissions\":[\"CONFIG_EDITAR\"]}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/audit").header("Authorization", bearer(cajero))).andExpect(status().isForbidden());
        mvc.perform(put("/api/store").header("Authorization", bearer(cajero)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\",\"currency\":\"COP\",\"timezone\":\"America/Bogota\"}")).andExpect(status().isForbidden());

        JsonNode me = body(mvc.perform(get("/api/auth/me").header("Authorization", bearer(cajero))));
        assertThat(me.get("permissions").toString()).contains("POS_VENDER").doesNotContain("COSTOS_VER");
    }

    @Test
    void adminCannotEscalateToOwnerOrGrantPermissionsTheyLack() throws Exception {
        Store s = newStore();
        String admin = newEmployee(s, "ADMINISTRADOR");
        long ownerRole = roleId(s.ownerToken(), "DUENO");
        mvc.perform(post("/api/users").header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Colado","email":"%s","password":"Clave1234","roleIds":[%d]}
                                """.formatted(unique("c") + "@p.com", ownerRole)))
                .andExpect(status().isForbidden());
        // El administrador no tiene ROLES_GESTIONAR ni CONFIG_EDITAR: no puede crear roles.
        mvc.perform(post("/api/roles").header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Super\",\"permissions\":[\"CONFIG_EDITAR\"]}")).andExpect(status().isForbidden());
        // Sí puede crear un cajero.
        long cajeroRole = roleId(s.ownerToken(), "CAJERO");
        mvc.perform(post("/api/users").header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Cajera Nueva","email":"%s","password":"Clave1234","roleIds":[%d]}
                                """.formatted(unique("c") + "@p.com", cajeroRole)))
                .andExpect(status().isCreated());
    }

    @Test
    void customRoleWithMultiplePermissionsAppliesToEmployee() throws Exception {
        Store s = newStore();
        JsonNode role = body(mvc.perform(post("/api/roles").header("Authorization", bearer(s.ownerToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Cajero de noche\",\"description\":\"Turno nocturno\",\"permissions\":[\"POS_VENDER\",\"CAJA_OPERAR\",\"AUDITORIA_VER\"]}"))
                .andExpect(status().isCreated()));
        long roleId = role.get("id").asLong();
        String email = unique("noche") + "@p.com";
        mvc.perform(post("/api/users").header("Authorization", bearer(s.ownerToken())).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fullName":"Nocturno","email":"%s","password":"Clave1234","roleIds":[%d]}
                        """.formatted(email, roleId))).andExpect(status().isCreated());
        String token = login(email, "Clave1234");
        mvc.perform(get("/api/audit").header("Authorization", bearer(token))).andExpect(status().isOk());
        mvc.perform(get("/api/users").header("Authorization", bearer(token))).andExpect(status().isOk());
        mvc.perform(post("/api/users").header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fullName":"Otro","email":"%s","password":"Clave1234","roleIds":[%d]}
                        """.formatted(unique("o") + "@p.com", roleId))).andExpect(status().isForbidden());

        // Rol asignado: no se puede borrar. Nombre repetido: error en el campo.
        mvc.perform(delete("/api/roles/" + roleId).header("Authorization", bearer(s.ownerToken())))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/roles").header("Authorization", bearer(s.ownerToken())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"cajero de NOCHE\",\"permissions\":[]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.name").exists());
        mvc.perform(post("/api/roles").header("Authorization", bearer(s.ownerToken())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Raro\",\"permissions\":[\"NO_EXISTE\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void permissionChangesApplyImmediatelyWithoutNewLogin() throws Exception {
        Store s = newStore();
        String cajero = newEmployee(s, "CAJERO");
        long cajeroRole = roleId(s.ownerToken(), "CAJERO");
        mvc.perform(get("/api/audit").header("Authorization", bearer(cajero))).andExpect(status().isForbidden());
        mvc.perform(put("/api/roles/" + cajeroRole).header("Authorization", bearer(s.ownerToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Cajero\",\"permissions\":[\"POS_VENDER\",\"AUDITORIA_VER\"]}")).andExpect(status().isOk());
        mvc.perform(get("/api/audit").header("Authorization", bearer(cajero))).andExpect(status().isOk());
        // Restablecer devuelve los permisos de fábrica.
        mvc.perform(post("/api/roles/" + cajeroRole + "/reset").header("Authorization", bearer(s.ownerToken())))
                .andExpect(status().isOk());
        mvc.perform(get("/api/audit").header("Authorization", bearer(cajero))).andExpect(status().isForbidden());
    }

    @Test
    void ownerRoleIsNotEditableAndPredefinedRolesNotDeletable() throws Exception {
        Store s = newStore();
        long owner = roleId(s.ownerToken(), "DUENO");
        long auditor = roleId(s.ownerToken(), "AUDITOR");
        mvc.perform(put("/api/roles/" + owner).header("Authorization", bearer(s.ownerToken()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Dueño\",\"permissions\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/roles/" + auditor).header("Authorization", bearer(s.ownerToken())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deactivatedUserIsLoggedOutAndCannotLogIn() throws Exception {
        Store s = newStore();
        String email = unique("temp") + "@p.com";
        long rid = roleId(s.ownerToken(), "CAJERO");
        JsonNode u = body(mvc.perform(post("/api/users").header("Authorization", bearer(s.ownerToken()))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"fullName":"Temporal","email":"%s","password":"Clave1234","roleIds":[%d]}
                        """.formatted(email, rid))));
        String token = login(email, "Clave1234");
        mvc.perform(post("/api/users/" + u.get("id").asLong() + "/deactivate").header("Authorization", bearer(s.ownerToken())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(token))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"Clave1234\"}".formatted(email))).andExpect(status().isForbidden());
        // El historial se conserva: el usuario sigue en la lista, desactivado.
        JsonNode list = body(mvc.perform(get("/api/users").header("Authorization", bearer(s.ownerToken()))));
        assertThat(list.toString()).contains(email);
    }

    @Test
    void lastOwnerCannotBeDemotedOrDeactivated() throws Exception {
        Store s = newStore();
        JsonNode users = body(mvc.perform(get("/api/users").header("Authorization", bearer(s.ownerToken()))));
        long ownerId = users.get(0).get("id").asLong();
        long cajeroRole = roleId(s.ownerToken(), "CAJERO");
        mvc.perform(put("/api/users/" + ownerId).header("Authorization", bearer(s.ownerToken()))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"fullName":"Dueño","email":"%s","roleIds":[%d]}""".formatted(s.ownerEmail(), cajeroRole)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/users/" + ownerId + "/deactivate").header("Authorization", bearer(s.ownerToken())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void storesAreIsolatedFromEachOther() throws Exception {
        Store a = newStore();
        Store b = newStore();
        JsonNode usersA = body(mvc.perform(get("/api/users").header("Authorization", bearer(a.ownerToken()))));
        long ownerA = usersA.get(0).get("id").asLong();
        long roleA = roleId(a.ownerToken(), "CAJERO");

        JsonNode usersB = body(mvc.perform(get("/api/users").header("Authorization", bearer(b.ownerToken()))));
        assertThat(usersB.toString()).doesNotContain(a.ownerEmail());
        mvc.perform(post("/api/users/" + ownerA + "/deactivate").header("Authorization", bearer(b.ownerToken())))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/roles/" + roleA).header("Authorization", bearer(b.ownerToken()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\",\"permissions\":[]}"))
                .andExpect(status().isNotFound());
        // Tampoco puede asignar a sus empleados un rol de otra tienda.
        mvc.perform(post("/api/users").header("Authorization", bearer(b.ownerToken())).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"X","email":"%s","password":"Clave1234","roleIds":[%d]}
                                """.formatted(unique("x") + "@p.com", roleA)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sensitiveActionsAreAudited() throws Exception {
        Store s = newStore();
        newEmployee(s, "SUPERVISOR");
        JsonNode audit = body(mvc.perform(get("/api/audit?sensitiveOnly=true").header("Authorization", bearer(s.ownerToken()))));
        assertThat(audit.get("items").findValuesAsText("action")).contains("USUARIO_CREADO");
        assertThat(audit.get("items").get(0).get("userName").asText()).isEqualTo("Dueño Prueba");
    }

    @Test
    void storeSettingsUpdateAndValidation() throws Exception {
        Store s = newStore();
        mvc.perform(put("/api/store").header("Authorization", bearer(s.ownerToken())).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Cigarrería El Paisa","taxId":"900123456-7","address":"Cra 10 # 20-30","city":"Medellín",
                                 "whatsapp":"3001234567","currency":"COP","timezone":"America/Bogota",
                                 "logo":"data:image/png;base64,iVBORw0KGgo="}"""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Cigarrería El Paisa"));
        mvc.perform(put("/api/store").header("Authorization", bearer(s.ownerToken())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"currency\":\"COP\",\"timezone\":\"Marte/Base\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.timezone").exists());
        mvc.perform(put("/api/store").header("Authorization", bearer(s.ownerToken())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"currency\":\"COP\",\"timezone\":\"America/Bogota\",\"logo\":\"javascript:alert(1)\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.logo").exists());
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(s.ownerToken())))
                .andExpect(jsonPath("$.tenant.name").value("Cigarrería El Paisa"));
    }
}
