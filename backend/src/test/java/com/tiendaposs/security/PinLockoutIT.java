package com.tiendaposs.security;

import com.tiendaposs.support.AbstractIntegrationTest;
import com.tiendaposs.support.Api;
import org.junit.jupiter.api.Test;

/** Un cajero no puede adivinar el PIN del supervisor probando combinaciones: tras 5 errores queda bloqueado 15 minutos. */
class PinLockoutIT extends AbstractIntegrationTest {

    @Test
    void wrongSupervisorPinsLockTheApproval() throws Exception {
        Api api = new Api(mvc, json);
        Store store = newStore();
        String owner = store.ownerToken();
        api.post("/api/auth/pin", owner, "{\"currentPassword\":\"Clave1234\",\"pin\":\"4826\"}").expect(204);
        long ownerId = api.get("/api/auth/me", owner).json().get("id").asLong();
        String cajero = newEmployee(store, "CAJERO");
        String body = "{\"approverId\":%d,\"secret\":\"%s\",\"permission\":\"POS_ANULAR\"}";

        for (int i = 0; i < 5; i++) api.post("/api/pos/approvals", cajero, body.formatted(ownerId, "000" + i)).expect(401);
        // Bloqueado: ni siquiera el PIN correcto pasa (si no, el bloqueo no serviría contra quien prueba todos).
        api.post("/api/pos/approvals", cajero, body.formatted(ownerId, "4826")).expect(429);
    }

    @Test
    void aCorrectPinClearsEarlierMistakes() throws Exception {
        Api api = new Api(mvc, json);
        Store store = newStore();
        String owner = store.ownerToken();
        api.post("/api/auth/pin", owner, "{\"currentPassword\":\"Clave1234\",\"pin\":\"4826\"}").expect(204);
        long ownerId = api.get("/api/auth/me", owner).json().get("id").asLong();
        String cajero = newEmployee(store, "CAJERO");
        String body = "{\"approverId\":%d,\"secret\":\"%s\",\"permission\":\"POS_ANULAR\"}";

        for (int i = 0; i < 4; i++) api.post("/api/pos/approvals", cajero, body.formatted(ownerId, "000" + i)).expect(401);
        api.post("/api/pos/approvals", cajero, body.formatted(ownerId, "4826")).expect(200);
        api.post("/api/pos/approvals", cajero, body.formatted(ownerId, "9999")).expect(401);
        api.post("/api/pos/approvals", cajero, body.formatted(ownerId, "4826")).expect(200);
    }
}
