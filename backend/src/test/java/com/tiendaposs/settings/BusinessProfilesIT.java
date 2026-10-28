package com.tiendaposs.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Cada tipo de negocio enciende al registrarse justo los módulos de su perfil: una droguería no trae mesas ni cocina. */
class BusinessProfilesIT extends AbstractIntegrationTest {

    @Test
    void cadaTipoNuevoEnciendeSoloSusModulos() throws Exception {
        for (String code : List.of("SUPERMERCADO", "DROGUERIA", "FERRETERIA", "PAPELERIA", "LICORERA", "PANADERIA", "CAFETERIA",
                "LAVANDERIA", "TALLER", "VETERINARIA", "AGRO", "OPTICA", "GIMNASIO")) {
            ModulesService.Profile profile = ModulesService.profile(code);
            String email = unique("tipo") + "@prueba.com";
            JsonNode me = body(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                    {"storeName":"Negocio %s","ownerName":"Dueño","email":"%s","password":"Clave1234","businessType":"%s",
                     "acceptTerms":true,"acceptPrivacy":true}""".formatted(code, email, code))).andExpect(status().isCreated())).get("me");
            assertThat(me.get("businessType").asText()).isEqualTo(code);
            Set<String> modules = new HashSet<>();
            me.get("modules").forEach(m -> modules.add(m.asText()));
            assertThat(modules).as(code).containsExactlyInAnyOrderElementsOf(profile.modules());
            assertThat(modules).as(code).doesNotContain("RESTAURANTE", "DISTRIBUCION");
        }
    }
}
