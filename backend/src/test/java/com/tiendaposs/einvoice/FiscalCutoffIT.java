package com.tiendaposs.einvoice;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.support.AbstractIntegrationTest;
import com.tiendaposs.support.Api;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Fase 16B: paso de POS normal a POS con factura con fecha de corte. */
class FiscalCutoffIT extends AbstractIntegrationTest {

    Api api;
    Store store;
    String owner;
    long pres;
    final LocalDate today = LocalDate.now(ZoneId.of("America/Bogota"));

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, json);
        store = newStore();
        owner = store.ownerToken();
        pres = api.post("/api/products", owner, """
                {"name":"Gaseosa","unit":"UNIDAD","cost":2000,"taxRate":19,"initialStock":100,
                 "presentations":[{"name":"Unidad","factor":1,"price":3000,"isBase":true}]}""").expect(201).json()
                .get("presentations").get(0).get("id").asLong();
        long reg = api.get("/api/registers", owner).json().get(0).get("id").asLong();
        api.post("/api/shifts/open", owner, "{\"registerId\":%d,\"openingCash\":0}".formatted(reg)).expect(200);
        api.put("/api/fiscal/profile", owner, """
                {"personType":"JURIDICA","idType":"NIT","idNumber":"800197268","dv":"4","legalName":"Tienda Prueba SAS",
                 "ivaResponsible":true,"responsibilities":["O-15"],"address":"Calle 1","municipalityCode":"11001",
                 "municipalityName":"Bogotá","invoicingEmail":"facturas@prueba.com","registeredAsIssuer":true,
                 "providerAuthorized":true,"providerName":"Simulador"}""").expect(200);
        for (String[] r : new String[][]{{"POS_ELECTRONICO", "POS"}, {"FACTURA", "FE"}}) {
            api.post("/api/fiscal/resolutions", owner, """
                    {"documentType":"%s","resolutionNumber":"18761","prefix":"%s","rangeFrom":1,"rangeTo":1000,
                     "validFrom":"%s","validTo":"%s"}""".formatted(r[0], r[1], today.minusDays(5), today.plusYears(1))).expect(200);
        }
        api.post("/api/einvoice/test", owner, null).expect(200);
    }

    private JsonNode sell() throws Exception {
        return api.post("/api/pos/sales", owner, """
                {"clientRef":"%s","lines":[{"presentationId":%d,"quantity":1}],"payments":[{"method":"EFECTIVO","amount":3000}]}"""
                .formatted(UUID.randomUUID(), pres)).expect(201).json();
    }

    private long customer() throws Exception {
        return api.post("/api/pos/customers", owner, """
                {"name":"Ana","docType":"CC","docNumber":"%d","personType":"NATURAL","invoiceEmail":"a@a.com"}"""
                .formatted(10_000_000 + (long) (Math.random() * 80_000_000))).expect(201).json().get("id").asLong();
    }

    @Test
    void salesBeforeTheCutoffAreNeverInvoicedAndStayHiddenUntilTheOwnerAsks() throws Exception {
        JsonNode old = sell();
        Thread.sleep(20);

        JsonNode preview = api.get("/api/einvoice/activation-preview", owner).expect(200).json();
        assertThat(preview.get("salesLast30Days").asLong()).isEqualTo(1);
        assertThat(preview.get("estimatedDocumentsPerMonth").asLong()).isEqualTo(1);
        assertThat(preview.get("salesBeforeCutoff").asLong()).isEqualTo(1);
        assertThat(preview.get("cutoffWillMove").asBoolean()).isTrue();

        JsonNode summary = api.post("/api/einvoice/enable", owner, "{\"enabled\":true}").expect(200).json();
        assertThat(summary.get("fiscalStartAt").isNull()).isFalse();
        assertThat(api.get("/api/auth/me", owner).json().get("fiscalStartAt").isNull()).isFalse();
        JsonNode fresh = sell();
        assertThat(fresh.get("electronicDocument").isNull()).isFalse();

        // Ventas: por defecto solo las de después del corte; el dueño puede pedir las anteriores, el cajero no.
        assertThat(api.get("/api/pos/sales", owner).json().get("total").asLong()).isEqualTo(1);
        assertThat(api.get("/api/pos/sales?includePrevious=true", owner).json().get("total").asLong()).isEqualTo(2);
        String cashier = newEmployee(store, "SUPERVISOR");
        assertThat(api.get("/api/pos/sales?includePrevious=true", cashier).json().get("total").asLong()).isEqualTo(1);

        // Analítica: hoy cuenta solo lo de después del corte.
        JsonNode rep = api.get("/api/analytics/report?from=" + today + "&to=" + today, owner).expect(200).json();
        assertThat(rep.get("current").get("transactions").asLong()).isEqualTo(1);

        // Nunca se factura una venta anterior al corte.
        api.post("/api/einvoice/sales/" + old.get("id").asLong() + "/reinvoice", owner, "{\"customerId\":%d}".formatted(customer())).expect(400);

        // El dueño puede mostrarlas en informes.
        api.post("/api/einvoice/cutoff-view", owner, "{\"hidePreCutoff\":false}").expect(200);
        assertThat(api.get("/api/pos/sales", owner).json().get("total").asLong()).isEqualTo(2);
        rep = api.get("/api/analytics/report?from=" + today + "&to=" + today, owner).expect(200).json();
        assertThat(rep.get("current").get("transactions").asLong()).isEqualTo(2);
    }

    @Test
    void onlyTheOwnerWithHisPasswordTurnsInvoicingOff() throws Exception {
        api.post("/api/einvoice/enable", owner, "{\"enabled\":true}").expect(200);
        String first = api.get("/api/einvoice/summary", owner).json().get("fiscalStartAt").asText();
        api.post("/api/einvoice/enable", owner, "{\"enabled\":false}").expect(400);
        api.post("/api/einvoice/enable", owner, "{\"enabled\":false,\"password\":\"otra\"}").expect(400);
        String admin = newEmployee(store, "ADMINISTRADOR");
        api.post("/api/einvoice/enable", admin, "{\"enabled\":false,\"password\":\"Clave1234\"}").expect(403);
        api.post("/api/einvoice/enable", owner, "{\"enabled\":false,\"password\":\"Clave1234\"}").expect(200);
        // Volver a activar en el mismo ambiente no mueve el corte.
        JsonNode again = api.post("/api/einvoice/enable", owner, "{\"enabled\":true}").expect(200).json();
        assertThat(again.get("fiscalStartAt").asText()).isEqualTo(first);
    }
}
