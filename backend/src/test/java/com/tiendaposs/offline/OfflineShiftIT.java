package com.tiendaposs.offline;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.support.AbstractIntegrationTest;
import com.tiendaposs.support.Api;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** S3: la caja hecha sin internet llega con su hora real y no se rechaza por lo que ya pasó en el mostrador. */
class OfflineShiftIT extends AbstractIntegrationTest {

    Api api;
    String owner;
    long register;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, json);
        owner = newStore().ownerToken();
        register = api.get("/api/registers", owner).json().get(0).get("id").asLong();
    }

    @Test
    void openMovementAndCloseKeepTheTimeTheyHappened() throws Exception {
        Instant opened = Instant.now().minus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        JsonNode s = api.post("/api/shifts/open", owner,
                "{\"registerId\":%d,\"openingCash\":10000,\"offlineAt\":\"%s\"}".formatted(register, opened)).expect(200).json();
        assertThat(Instant.parse(s.get("openedAt").asText())).isEqualTo(opened);

        // Sin internet un gasto mayor que el esperado se registra igual (ya salió el dinero): no se pierde.
        Instant spent = opened.plus(1, ChronoUnit.HOURS);
        JsonNode m = api.post("/api/shifts/" + s.get("id").asLong() + "/movements", owner,
                "{\"type\":\"GASTO\",\"amount\":15000,\"reason\":\"Hielo\",\"offline\":true,\"offlineAt\":\"%s\"}".formatted(spent)).expect(200).json();
        assertThat(Instant.parse(m.get("createdAt").asText())).isEqualTo(spent);
        // Con internet, el mismo gasto sí se valida contra el efectivo esperado.
        api.post("/api/shifts/" + s.get("id").asLong() + "/movements", owner,
                "{\"type\":\"GASTO\",\"amount\":15000,\"reason\":\"Hielo\"}").expect(400);

        Instant closed = opened.plus(2, ChronoUnit.HOURS);
        JsonNode c = api.post("/api/shifts/" + s.get("id").asLong() + "/close", owner,
                "{\"countedCash\":-5000,\"closeNotes\":\"x\",\"offlineAt\":\"%s\"}".formatted(closed)).json();
        // Contado negativo no vale; con contado 0 la diferencia es +5.000 frente a −5.000 esperados.
        c = api.post("/api/shifts/" + s.get("id").asLong() + "/close", owner,
                "{\"countedCash\":0,\"closeNotes\":\"Faltó registrar un ingreso\",\"offlineAt\":\"%s\"}".formatted(closed)).expect(200).json();
        assertThat(Instant.parse(c.get("closedAt").asText())).isEqualTo(closed);
        assertThat(c.get("expectedCash").decimalValue()).isEqualByComparingTo("-5000");
    }

    @Test
    void aClockInTheFutureOrTooOldIsIgnored() throws Exception {
        Instant future = Instant.now().plus(2, ChronoUnit.DAYS);
        JsonNode s = api.post("/api/shifts/open", owner,
                "{\"registerId\":%d,\"openingCash\":0,\"offlineAt\":\"%s\"}".formatted(register, future)).expect(200).json();
        assertThat(Instant.parse(s.get("openedAt").asText())).isBefore(Instant.now().plusSeconds(5));
        assertThat(OfflineTime.when(Instant.now().minus(90, ChronoUnit.DAYS))).isAfter(Instant.now().minusSeconds(5));
    }
}
