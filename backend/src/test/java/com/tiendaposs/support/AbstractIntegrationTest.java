package com.tiendaposs.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Un solo PostgreSQL embebido para toda la suite; el contexto de Spring se reutiliza entre clases. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    protected static final EmbeddedPostgres PG;
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    static {
        try {
            PG = EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:" + PG.getPort() + "/postgres");
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "postgres");
        r.add("app.rate-limit.enabled", () -> "false");
        r.add("app.scheduler.enabled", () -> "false");
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    protected static String unique(String prefix) {
        return prefix + SEQ.incrementAndGet();
    }

    /** Tienda nueva con su dueño; devuelve el token del dueño. */
    protected Store newStore() throws Exception {
        String email = unique("dueno") + "@prueba.com";
        JsonNode res = body(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"storeName":"Tienda %s","ownerName":"Dueño Prueba","email":"%s","password":"Clave1234","acceptTerms":true,"acceptPrivacy":true}
                """.formatted(email, email))));
        return new Store(res.get("token").asText(), res.get("me").get("tenant").get("id").asLong(), email);
    }

    public record Store(String ownerToken, long tenantId, String ownerEmail) {
    }

    protected long roleId(String token, String systemCode) throws Exception {
        for (JsonNode r : body(mvc.perform(get("/api/roles").header("Authorization", "Bearer " + token)))) {
            if (systemCode.equals(r.path("systemCode").asText())) return r.get("id").asLong();
        }
        throw new IllegalStateException("rol no encontrado " + systemCode);
    }

    /** Crea un empleado con el rol indicado y devuelve su token. */
    protected String newEmployee(Store store, String systemCode) throws Exception {
        String email = unique("emp") + "@prueba.com";
        long rid = roleId(store.ownerToken(), systemCode);
        mvc.perform(post("/api/users").header("Authorization", "Bearer " + store.ownerToken())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"fullName":"Empleado %s","email":"%s","password":"Clave1234","roleIds":[%d]}
                        """.formatted(systemCode, email, rid)));
        return login(email, "Clave1234");
    }

    protected String login(String email, String password) throws Exception {
        return body(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))).get("token").asText();
    }

    protected JsonNode body(ResultActions r) throws Exception {
        String s = r.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return s.isEmpty() ? json.createObjectNode() : json.readTree(s);
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }
}
