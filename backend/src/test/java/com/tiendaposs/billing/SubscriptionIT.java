package com.tiendaposs.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Pago de la suscripción del POS con Wompi (la pasarela simulada): firma, aplicación idempotente, monto y tienda ajena. */
class SubscriptionIT extends AbstractIntegrationTest {

    @MockitoBean
    WompiBillingGateway wompi;

    private JsonNode start(Store s, String plan, String period) throws Exception {
        when(wompi.isEnabled()).thenReturn(true);
        when(wompi.widgetParams(anyString(), anyLong())).thenAnswer(a ->
                new WompiBillingGateway.WidgetParams("pub_test_x", "COP", a.getArgument(1), a.getArgument(0), "firma"));
        return body(mvc.perform(post("/api/subscription/checkout").header("Authorization", bearer(s.ownerToken()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"%s\",\"period\":\"%s\"}".formatted(plan, period)))
                .andExpect(status().isOk()));
    }

    private JsonNode confirm(Store s, String txId) throws Exception {
        return body(mvc.perform(post("/api/subscription/confirm").header("Authorization", bearer(s.ownerToken()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"transactionId\":\"%s\"}".formatted(txId))));
    }

    @Test
    void pagoAprobadoActivaElPlanUnaSolaVez() throws Exception {
        Store s = newStore();
        JsonNode st = body(mvc.perform(get("/api/subscription").header("Authorization", bearer(s.ownerToken()))).andExpect(status().isOk()));
        assertThat(st.get("state").asText()).isEqualTo("PRUEBA");

        JsonNode w = start(s, "TIENDA", "MENSUAL").get("widget");
        String ref = w.get("reference").asText();
        assertThat(ref).startsWith("POSS-" + s.tenantId() + "-TIENDA-M-3990000-");
        assertThat(w.get("amountInCents").asLong()).isEqualTo(3_990_000L);

        when(wompi.fetchTransaction("tx-1")).thenReturn(new WompiBillingGateway.TransactionInfo("tx-1", ref, 3_990_000L, "APPROVED", null));
        JsonNode r = confirm(s, "tx-1");
        assertThat(r.get("status").asText()).isEqualTo("APLICADO");
        assertThat(r.at("/subscription/state").asText()).isEqualTo("ACTIVA");
        assertThat(r.at("/subscription/plan").asText()).isEqualTo("TIENDA");
        String paidUntil = r.at("/subscription/paidUntil").asText();

        // La misma transacción otra vez (o por la revisión periódica) no suma otro mes.
        assertThat(confirm(s, "tx-1").get("status").asText()).isEqualTo("YA_APLICADO");
        when(wompi.fetchByReference(ref)).thenReturn(List.of(new WompiBillingGateway.TransactionInfo("tx-1", ref, 3_990_000L, "APPROVED", null)));
        assertThat(body(mvc.perform(post("/api/subscription/verify").header("Authorization", bearer(s.ownerToken())))).at("/subscription/paidUntil").asText())
                .isEqualTo(paidUntil);

        JsonNode me = body(mvc.perform(get("/api/auth/me").header("Authorization", bearer(s.ownerToken()))));
        assertThat(me.at("/tenant/subscriptionState").asText()).isEqualTo("ACTIVA");
    }

    @Test
    void rechazaMontoAlteradoYTiendaAjena() throws Exception {
        Store s = newStore();
        Store other = newStore();
        String ref = start(s, "PRO", "ANUAL").at("/widget/reference").asText();
        assertThat(ref).contains("-PRO-A-119900000-");

        when(wompi.fetchTransaction("tx-barato")).thenReturn(new WompiBillingGateway.TransactionInfo("tx-barato", ref, 100_00L, "APPROVED", null));
        mvc.perform(post("/api/subscription/confirm").header("Authorization", bearer(s.ownerToken()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"transactionId\":\"tx-barato\"}")).andExpect(status().isBadRequest());

        when(wompi.fetchTransaction("tx-ajena")).thenReturn(new WompiBillingGateway.TransactionInfo("tx-ajena", ref, 119_900_000L, "APPROVED", null));
        mvc.perform(post("/api/subscription/confirm").header("Authorization", bearer(other.ownerToken()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"transactionId\":\"tx-ajena\"}")).andExpect(status().isBadRequest());

        when(wompi.fetchTransaction("tx-no")).thenReturn(new WompiBillingGateway.TransactionInfo("tx-no", ref, 119_900_000L, "DECLINED", "Fondos insuficientes"));
        JsonNode r = confirm(s, "tx-no");
        assertThat(r.get("status").asText()).isEqualTo("RECHAZADO");
        assertThat(r.at("/subscription/state").asText()).isEqualTo("PRUEBA");
    }

    @Test
    void soloElDuenoPaga() throws Exception {
        Store s = newStore();
        String cajero = newEmployee(s, "CAJERO");
        mvc.perform(get("/api/subscription").header("Authorization", bearer(cajero))).andExpect(status().isForbidden());
        mvc.perform(post("/api/subscription/checkout").header("Authorization", bearer(cajero))
                .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"PRO\",\"period\":\"MENSUAL\"}")).andExpect(status().isForbidden());
    }
}
