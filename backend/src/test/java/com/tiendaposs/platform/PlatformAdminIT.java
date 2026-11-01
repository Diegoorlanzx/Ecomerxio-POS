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

    @Test
    void catalogoDePlanesYPrecioQueConservaCadaTienda() throws Exception {
        Store antes = newStore();
        call(patch("/api/platform/stores/" + antes.tenantId() + "/plan"), "{\"plan\":\"TIENDA\"}");
        JsonNode pub = body(mvc.perform(get("/api/public/plans")).andExpect(status().isOk()));
        assertThat(pub.findValuesAsText("code")).containsExactly("TIENDA", "NEGOCIO", "PRO");
        assertThat(pub.get(0).get("priceMonthly").asLong()).isEqualTo(39_900);
        assertThat(pub.get(2).hasNonNull("maxUsers")).isFalse(); // sin límite
        try {
            JsonNode upd = call(put("/api/platform/plans/TIENDA"), "{\"priceMonthly\":45000,\"priceAnnual\":450000,\"maxRegisters\":2,\"maxUsers\":4}");
            assertThat(upd.get("priceMonthly").asLong()).isEqualTo(45_000);
            assertThat(upd.get("stores").asLong()).isPositive();
            JsonNode tienda = body(mvc.perform(get("/api/public/plans"))).get(0);
            assertThat(tienda.get("priceMonthly").asLong()).isEqualTo(45_000);
            assertThat(tienda.get("maxRegisters").asInt()).isEqualTo(2);

            // La tienda que ya estaba en el plan conserva su precio; los topes nuevos sí le aplican.
            JsonNode st = body(mvc.perform(get("/api/subscription").header("Authorization", bearer(antes.ownerToken()))).andExpect(status().isOk()));
            assertThat(st.at("/plans/0/priceMonthly").asLong()).isEqualTo(39_900);
            assertThat(st.at("/plans/0/priceAnnual").asLong()).isEqualTo(399_000);
            JsonNode me = body(mvc.perform(get("/api/auth/me").header("Authorization", bearer(antes.ownerToken()))));
            assertThat(me.toString()).contains("\"maxRegisters\":2");
            JsonNode summary = null;
            for (JsonNode n : call(get("/api/platform/stores"), null)) if (n.get("id").asLong() == antes.tenantId()) summary = n;
            assertThat(summary.get("planPriceMonthly").asLong()).isEqualTo(39_900);

            // Quien entra al plan desde ahora paga el precio nuevo.
            Store despues = newStore();
            call(patch("/api/platform/stores/" + despues.tenantId() + "/plan"), "{\"plan\":\"TIENDA\"}");
            JsonNode st2 = body(mvc.perform(get("/api/subscription").header("Authorization", bearer(despues.ownerToken()))));
            assertThat(st2.at("/plans/0/priceMonthly").asLong()).isEqualTo(45_000);

            mvc.perform(put("/api/platform/plans/TIENDA").header("X-Platform-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"priceMonthly\":-1,\"priceAnnual\":450000}")).andExpect(status().isBadRequest());
            mvc.perform(put("/api/platform/plans/OTRO").header("X-Platform-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"priceMonthly\":1,\"priceAnnual\":1}")).andExpect(status().isNotFound());
            mvc.perform(put("/api/platform/plans/TIENDA").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"priceMonthly\":1,\"priceAnnual\":1}")).andExpect(status().isUnauthorized());
        } finally {
            call(put("/api/platform/plans/TIENDA"), "{\"priceMonthly\":39900,\"priceAnnual\":399000,\"maxRegisters\":1,\"maxUsers\":3}");
        }
    }

    @Test
    void facturacionSoloConElInterruptorDeLaPlataforma() throws Exception {
        Store s = newStore();
        long id = s.tenantId();
        assertThat(body(mvc.perform(get("/api/auth/me").header("Authorization", bearer(s.ownerToken())))).at("/tenant/einvoiceAddon").asBoolean()).isFalse();

        mvc.perform(put("/api/fiscal/profile").header("Authorization", bearer(s.ownerToken())).contentType(MediaType.APPLICATION_JSON).content("""
                {"personType":"JURIDICA","idType":"NIT","idNumber":"800197268","dv":"4","legalName":"Tienda Prueba SAS",
                 "ivaResponsible":true,"responsibilities":["O-15"],"address":"Calle 1","municipalityCode":"11001",
                 "municipalityName":"Bogotá","invoicingEmail":"facturas@prueba.com","registeredAsIssuer":true,
                 "providerAuthorized":true,"providerName":"Simulador"}""")).andExpect(status().isOk());
        mvc.perform(post("/api/einvoice/enable").header("Authorization", bearer(s.ownerToken())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isForbidden());

        assertThat(call(patch("/api/platform/stores/" + id + "/einvoice"), "{\"enabled\":true}").get("einvoiceAddon").asBoolean()).isTrue();
        assertThat(body(mvc.perform(get("/api/auth/me").header("Authorization", bearer(s.ownerToken())))).at("/tenant/einvoiceAddon").asBoolean()).isTrue();
        // Con el interruptor ya no es "no incluida": lo que falta es completar el asistente.
        mvc.perform(post("/api/einvoice/enable").header("Authorization", bearer(s.ownerToken())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isBadRequest());

        JsonNode off = call(patch("/api/platform/stores/" + id + "/einvoice"), "{\"enabled\":false}");
        assertThat(off.get("einvoiceAddon").asBoolean()).isFalse();
        assertThat(off.get("invoicingEnabled").asBoolean()).isFalse();
        assertThat(call(get("/api/platform/stores/" + id), null).get("history").toString()).contains("FACTURACION_HABILITADA", "FACTURACION_RETIRADA");
    }

    private int upload(Store s, String kind, byte[] data) throws Exception {
        return mvc.perform(post("/api/einvoice/request/documents").header("Authorization", bearer(s.ownerToken()))
                .header("X-Doc-Kind", kind).header("X-Filename", "rut%20tienda.pdf")
                .contentType(MediaType.APPLICATION_OCTET_STREAM).content(data)).andReturn().getResponse().getStatus();
    }

    @Test
    void solicitudDeFacturacionConDocumentos() throws Exception {
        Store s = newStore();
        long id = s.tenantId();
        byte[] pdf = "%PDF-1.4 documento de prueba".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        // Sin activar no se emite ni la prueba.
        mvc.perform(post("/api/einvoice/test").header("Authorization", bearer(s.ownerToken()))).andExpect(status().isBadRequest());

        assertThat(upload(s, "RUT", "hola".getBytes())).isEqualTo(400); // no es PDF/JPG/PNG
        assertThat(upload(s, "RUT", pdf)).isEqualTo(200);
        JsonNode v = body(mvc.perform(get("/api/einvoice/request").header("Authorization", bearer(s.ownerToken()))));
        assertThat(v.get("canSubmit").asBoolean()).isFalse(); // faltan datos fiscales y la cédula
        mvc.perform(post("/api/einvoice/request/submit").header("Authorization", bearer(s.ownerToken())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"acceptedTerms\":true}")).andExpect(status().isBadRequest());

        mvc.perform(put("/api/fiscal/profile").header("Authorization", bearer(s.ownerToken())).contentType(MediaType.APPLICATION_JSON).content("""
                {"personType":"NATURAL","idType":"NIT","idNumber":"71654321","dv":"4","legalName":"Jorge Restrepo","firstNames":"Jorge","lastNames":"Restrepo",
                 "ivaResponsible":false,"responsibilities":["R-99-PN"],"address":"Calle 1","municipalityCode":"05001",
                 "municipalityName":"Medellín","invoicingEmail":"facturas@prueba.com"}""")).andExpect(status().isOk());
        assertThat(upload(s, "CEDULA", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3})).isEqualTo(200);
        v = body(mvc.perform(get("/api/einvoice/request").header("Authorization", bearer(s.ownerToken()))));
        assertThat(v.get("missing").size()).as(v.toString()).isZero();
        assertThat(v.get("canSubmit").asBoolean()).isTrue();
        assertThat(body(mvc.perform(post("/api/einvoice/request/submit").header("Authorization", bearer(s.ownerToken())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"acceptedTerms\":true}")).andExpect(status().isOk())).get("status").asText()).isEqualTo("ENVIADA");
        assertThat(upload(s, "RUT", pdf)).isEqualTo(400); // en revisión no se cambian documentos

        // Ecomerxio la ve, abre un documento y pide corregir.
        assertThat(call(get("/api/platform/einvoice-requests"), null).toString()).contains("\"tenantId\":" + id, "ENVIADA");
        JsonNode detail = call(get("/api/platform/stores/" + id + "/einvoice-request"), null);
        assertThat(detail.at("/fiscalProfile/legal_name").asText()).isEqualTo("Jorge Restrepo");
        long rutId = 0;
        for (JsonNode d : detail.get("documents")) if ("RUT".equals(d.get("kind").asText())) rutId = d.get("id").asLong();
        byte[] got = mvc.perform(get("/api/platform/stores/" + id + "/einvoice-request/documents/" + rutId).header("X-Platform-Key", KEY))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(got).isEqualTo(pdf);
        mvc.perform(get("/api/einvoice/request").header("X-Platform-Key", KEY)).andExpect(status().isUnauthorized());

        call(patch("/api/platform/stores/" + id + "/einvoice-request"), "{\"status\":\"CORREGIR\",\"comment\":\"El RUT está vencido\"}");
        v = body(mvc.perform(get("/api/einvoice/request").header("Authorization", bearer(s.ownerToken()))));
        assertThat(v.get("status").asText()).isEqualTo("CORREGIR");
        assertThat(v.get("adminComment").asText()).isEqualTo("El RUT está vencido");
        assertThat(upload(s, "RUT", pdf)).isEqualTo(200);
        mvc.perform(post("/api/einvoice/request/submit").header("Authorization", bearer(s.ownerToken())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"acceptedTerms\":true}")).andExpect(status().isOk());

        call(patch("/api/platform/stores/" + id + "/einvoice-request"), "{\"status\":\"APROBADA\"}");
        call(patch("/api/platform/stores/" + id + "/einvoice"), "{\"enabled\":true}");
        v = body(mvc.perform(get("/api/einvoice/request").header("Authorization", bearer(s.ownerToken()))));
        assertThat(v.get("status").asText()).isEqualTo("ACTIVA");
        assertThat(v.get("einvoiceAddon").asBoolean()).isTrue();
    }
}
