package com.tiendaposs.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.support.AbstractIntegrationTest;
import com.tiendaposs.support.Api;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Vencida la prueba: 5 días de gracia con aviso, luego solo lectura; y los topes de cajas y usuarios del plan. */
class SubscriptionEnforcementIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    Api api;
    Store store;
    String owner;

    static final String PRODUCT = """
            {"name":"Arroz","unit":"UNIDAD","presentations":[{"name":"Unidad","factor":1,"price":3000,"isBase":true}]}""";

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, json);
        store = newStore();
        owner = store.ownerToken();
    }

    void trialEnded(int daysAgo) {
        jdbc.update("UPDATE tenants SET trial_ends_at = ?, paid_until = NULL WHERE id = ?",
                Timestamp.from(Instant.now().minus(daysAgo, ChronoUnit.DAYS)), store.tenantId());
    }

    @Test
    void duringTheGraceDaysEverythingStillWorks() throws Exception {
        trialEnded(2);
        JsonNode t = api.get("/api/auth/me", owner).expect(200).json().get("tenant");
        assertThat(t.get("subscriptionState").asText()).isEqualTo("VENCIDA");
        assertThat(t.get("readOnly").asBoolean()).isFalse();
        assertThat(t.has("readOnlyFrom")).isTrue();
        api.post("/api/products", owner, PRODUCT).expect(201);
    }

    @Test
    void afterTheGraceDaysTheStoreIsReadOnlyUntilItPays() throws Exception {
        api.post("/api/products", owner, PRODUCT).expect(201);
        trialEnded(6);
        assertThat(api.get("/api/auth/me", owner).json().get("tenant").get("readOnly").asBoolean()).isTrue();

        // No puede vender ni cambiar nada…
        api.post("/api/products", owner, PRODUCT.replace("Arroz", "Frijol")).expect(402);
        long reg = api.get("/api/registers", owner).expect(200).json().get(0).get("id").asLong();
        api.post("/api/shifts/open", owner, "{\"registerId\":%d,\"openingCash\":0}".formatted(reg)).expect(402);
        // …pero sí consultar, ver su suscripción y aceptar textos legales.
        assertThat(api.get("/api/products", owner).expect(200).json().get("total").asInt()).isEqualTo(1);
        assertThat(api.get("/api/subscription", owner).expect(200).json().get("readOnly").asBoolean()).isTrue();
        assertThat(api.post("/api/legal/accept", owner, "{}").status()).isNotEqualTo(402);

        // Pagó (o soporte registró el pago): vuelve a todo.
        jdbc.update("UPDATE tenants SET paid_until = ? WHERE id = ?", Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)), store.tenantId());
        api.post("/api/products", owner, PRODUCT.replace("Arroz", "Frijol")).expect(201);
    }

    @Test
    void courtesyStoresAreNeverBlocked() throws Exception {
        jdbc.update("UPDATE tenants SET trial_ends_at = NULL, paid_until = NULL WHERE id = ?", store.tenantId());
        api.post("/api/products", owner, PRODUCT).expect(201);
    }

    @Test
    void usersCannotExceedThePlan() throws Exception {
        jdbc.update("UPDATE tenants SET plan = 'TIENDA' WHERE id = ?", store.tenantId());
        // Tienda: 3 usuarios activos, contando al dueño.
        newEmployee(store, "CAJERO");
        newEmployee(store, "CAJERO");
        long cajero = roleId(owner, "CAJERO");
        Api.Res r = api.post("/api/users", owner, """
                {"fullName":"Cuarto","email":"%s@prueba.com","password":"Clave1234","roleIds":[%d]}""".formatted(unique("cuarto"), cajero)).expect(403);
        assertThat(r.json().get("fields").get("planLimit").asText()).isEqualTo("USERS");
        assertThat(r.json().get("message").asText()).contains("3 usuarios");

        // Desactivar uno libera el cupo; reactivarlo con el cupo lleno no se puede.
        JsonNode list = api.get("/api/users", owner).expect(200).json();
        long firstEmployee = 0;
        for (JsonNode u : list) if (!u.get("owner").asBoolean()) { firstEmployee = u.get("id").asLong(); break; }
        api.post("/api/users/" + firstEmployee + "/deactivate", owner, null).expect(200);
        api.post("/api/users", owner, """
                {"fullName":"Reemplazo","email":"%s@prueba.com","password":"Clave1234","roleIds":[%d]}""".formatted(unique("reemplazo"), cajero)).expect(201);
        api.post("/api/users/" + firstEmployee + "/activate", owner, null).expect(403);

        // Con un plan mayor, sí.
        jdbc.update("UPDATE tenants SET plan = 'NEGOCIO' WHERE id = ?", store.tenantId());
        api.post("/api/users/" + firstEmployee + "/activate", owner, null).expect(200);
    }

    @Test
    void registersCannotExceedThePlan() throws Exception {
        jdbc.update("UPDATE tenants SET plan = 'TIENDA' WHERE id = ?", store.tenantId());
        Api.Res r = api.post("/api/registers", owner, "{\"name\":\"Caja 2\"}").expect(403);
        assertThat(r.json().get("fields").get("planLimit").asText()).isEqualTo("REGISTERS");
        jdbc.update("UPDATE tenants SET plan = 'PRO' WHERE id = ?", store.tenantId());
        api.post("/api/registers", owner, "{\"name\":\"Caja 2\"}").expect(200);
        api.post("/api/registers", owner, "{\"name\":\"Caja 3\"}").expect(200);
    }
}
