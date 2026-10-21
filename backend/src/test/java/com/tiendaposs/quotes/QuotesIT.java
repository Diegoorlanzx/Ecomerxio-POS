package com.tiendaposs.quotes;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiendaposs.fiscal.Nit;
import com.tiendaposs.support.AbstractIntegrationTest;
import com.tiendaposs.support.Api;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Cotizaciones, prefacturas (validación antes de facturar) y el enlace público del cliente. */
class QuotesIT extends AbstractIntegrationTest {

    Api api;
    Store store;
    String owner;
    long presentation;
    long productId;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, json);
        store = newStore();
        owner = store.ownerToken();
        JsonNode p = api.post("/api/products", owner, """
                {"name":"Taladro","unit":"UNIDAD","cost":6000,"taxRate":19,"initialStock":10,
                 "presentations":[{"name":"Unidad","factor":1,"price":10000,"isBase":true}]}""").expect(201).json();
        productId = p.get("id").asLong();
        for (JsonNode pr : p.get("presentations")) if (pr.get("isBase").asBoolean()) presentation = pr.get("id").asLong();
    }

    String quoteBody(String customer, int qty, int discount) {
        return """
                {%s"lines":[{"presentationId":%d,"quantity":%d,"discountPct":%d},
                            {"description":"Instalación","quantity":1,"unitPrice":20000,"taxRate":19}]}"""
                .formatted(customer, presentation, qty, discount);
    }

    static List<String> codes(JsonNode validation, String severity) {
        List<String> out = new ArrayList<>();
        validation.get("issues").forEach(i -> {
            if (severity.equals(i.get("severity").asText())) out.add(i.get("code").asText());
        });
        return out;
    }

    @Test
    void quoteIsAcceptedByLinkThenValidatedAsPrefacturaAndCharged() throws Exception {
        // Cotización: 3 taladros con 5 % + una línea libre. Precios con IVA incluido (ajuste por defecto).
        JsonNode q = api.post("/api/quotes", owner, quoteBody("\"customerName\":\"Ferretería El Tornillo\",", 3, 5)).expect(201).json();
        assertThat(q.get("code").asText()).isEqualTo("COT-0001");
        assertThat(q.get("status").asText()).isEqualTo("BORRADOR");
        assertThat(q.get("total").decimalValue()).isEqualByComparingTo("48500");
        assertThat(LocalDate.parse(q.get("validUntil").asText())).isAfter(LocalDate.now().plusDays(13));
        long id = q.get("id").asLong();
        String token = q.get("publicToken").asText();

        // El cliente la ve sin cuenta y la acepta; no se puede aceptar dos veces.
        JsonNode pub = api.get("/api/public/quotes/" + token, null).expect(200).json();
        assertThat(pub.get("storeName").asText()).startsWith("Tienda");
        assertThat(pub.get("lines")).hasSize(2);
        api.post("/api/quotes/" + id + "/sent", owner, "{}").expect(200);
        JsonNode acc = api.post("/api/public/quotes/" + token + "/decision", null, "{\"accepted\":true,\"name\":\"Carlos Ruiz\"}").expect(200).json();
        assertThat(acc.get("status").asText()).isEqualTo("ACEPTADA");
        api.post("/api/public/quotes/" + token + "/decision", null, "{\"accepted\":false,\"name\":\"Otro\"}").expect(409);
        // La tienda recibe el aviso en la campana.
        assertThat(api.get("/api/notifications", owner).expect(200).raw()).contains("Aceptaron la cotización COT-0001");

        // Pasa a prefactura: la cotización queda convertida y la prefactura arranca en borrador con errores de datos.
        JsonNode pf = api.post("/api/quotes/" + id + "/prefactura", owner, "{}").expect(201).json();
        long pfId = pf.get("id").asLong();
        assertThat(pf.get("code").asText()).isEqualTo("PF-0001");
        assertThat(pf.get("sourceCode").asText()).isEqualTo("COT-0001");
        assertThat(api.get("/api/quotes/" + id, owner).expect(200).json().get("status").asText()).isEqualTo("CONVERTIDA");
        assertThat(codes(pf.get("validation"), "ERROR")).contains("CLIENTE_DOC", "CLIENTE_CORREO", "CLIENTE_PERSONA");
        api.post("/api/quotes/" + pfId + "/approve", owner, "{\"acceptWarnings\":true}").expect(400);

        // NIT con DV equivocado: error que dice cuál es el correcto.
        String nit = "900123456";
        char good = Nit.dv(nit);
        char bad = good == '1' ? '2' : '1';
        String customer = """
                "customerName":"Ferretería El Tornillo S.A.S.","customerDocType":"NIT","customerDoc":"%s","customerDv":"%s",
                "customerPersonType":"JURIDICA","customerEmail":"facturas@tornillo.test","customerMunicipality":"05001",""";
        JsonNode wrong = api.put("/api/quotes/" + pfId, owner, quoteBody(customer.formatted(nit, bad), 3, 5)).expect(200).json();
        JsonNode dvIssue = null;
        for (JsonNode i : wrong.get("validation").get("issues")) if ("CLIENTE_DV".equals(i.get("code").asText())) dvIssue = i;
        assertThat(dvIssue).isNotNull();
        assertThat(dvIssue.get("message").asText()).contains("debería ser " + good);

        // El cliente reporta un error desde el enlace: bloquea la aprobación hasta que se corrija y se guarde.
        api.post("/api/public/quotes/" + pf.get("publicToken").asText() + "/check", null, "{\"correct\":false}").expect(400);
        api.post("/api/public/quotes/" + pf.get("publicToken").asText() + "/check", null,
                "{\"correct\":false,\"note\":\"El NIT termina en " + good + "\"}").expect(200);
        assertThat(codes(api.get("/api/quotes/" + pfId + "/validation", owner).json(), "ERROR")).contains("CLIENTE_REPORTO");

        JsonNode fixed = api.put("/api/quotes/" + pfId, owner, quoteBody(customer.formatted(nit, good), 3, 5)).expect(200).json();
        assertThat(fixed.hasNonNull("clientCheck")).isFalse();
        JsonNode v = fixed.get("validation");
        assertThat(v.get("errors").asInt()).as(v.toString()).isZero();
        assertThat(codes(v, "WARNING")).contains("SIN_FACTURACION");

        // Las advertencias se aceptan expresamente; al aprobar, la ficha del cliente queda con los datos revisados.
        api.post("/api/quotes/" + pfId + "/approve", owner, "{\"acceptWarnings\":false}").expect(400);
        JsonNode approved = api.post("/api/quotes/" + pfId + "/approve", owner, "{\"acceptWarnings\":true}").expect(200).json();
        assertThat(approved.get("status").asText()).isEqualTo("APROBADA");
        assertThat(approved.get("approvedWarnings")).isNotEmpty();
        long customerId = approved.get("customerId").asLong();
        JsonNode cust = api.get("/api/pos/customers?q=" + nit, owner).expect(200).json().get(0);
        assertThat(cust.get("id").asLong()).isEqualTo(customerId);
        assertThat(cust.get("dv").asText()).isEqualTo(String.valueOf(good));

        // Cobro en la caja: venta con los precios pactados y el inventario descontado.
        api.post("/api/quotes/" + pfId + "/charge", owner, "{\"payments\":[{\"method\":\"EFECTIVO\",\"amount\":48500}]}").expect(409);
        long reg = api.get("/api/registers", owner).expect(200).json().get(0).get("id").asLong();
        api.post("/api/shifts/open", owner, "{\"registerId\":%d,\"openingCash\":50000}".formatted(reg)).expect(200);
        JsonNode sale = api.post("/api/quotes/" + pfId + "/charge", owner, "{\"payments\":[{\"method\":\"EFECTIVO\",\"amount\":48500}]}").expect(200).json();
        assertThat(sale.get("total").decimalValue()).isEqualByComparingTo("48500");
        assertThat(sale.get("customerId").asLong()).isEqualTo(customerId);
        assertThat(sale.get("lines").get(1).get("productName").asText()).isEqualTo("Instalación");
        assertThat(api.get("/api/products/" + productId, owner).json().get("stock").decimalValue()).isEqualByComparingTo("7");
        JsonNode done = api.get("/api/quotes/" + pfId, owner).expect(200).json();
        assertThat(done.get("status").asText()).isEqualTo("FACTURADA");
        assertThat(done.get("saleNumber").asLong()).isEqualTo(sale.get("number").asLong());
        api.put("/api/quotes/" + pfId, owner, quoteBody(customer.formatted(nit, good), 1, 0)).expect(409);
    }

    @Test
    void discountsFollowTheSamePermissionsAsTheRegister() throws Exception {
        String cashier = newEmployee(store, "CAJERO");
        String c = "\"customerName\":\"Ana\",";
        api.post("/api/quotes", cashier, quoteBody(c, 1, 5)).expect(201);
        api.post("/api/quotes", cashier, quoteBody(c, 1, 15)).expect(403); // supera el máximo de 10 %
        api.post("/api/quotes", owner, quoteBody(c, 1, 15)).expect(201);
        // Una cotización necesita destinatario y una vigencia que no esté vencida.
        api.post("/api/quotes", owner, quoteBody("", 1, 0)).expect(400);
        api.post("/api/quotes", owner, quoteBody(c + "\"validUntil\":\"2020-01-01\",", 1, 0)).expect(400);
        // El cajero no aprueba prefacturas.
        JsonNode pf = api.post("/api/quotes?kind=PREFACTURA", cashier, quoteBody(c, 1, 0)).expect(201).json();
        api.post("/api/quotes/" + pf.get("id").asLong() + "/approve", cashier, "{\"acceptWarnings\":true}").expect(403);
        // Otra tienda no ve el documento.
        api.get("/api/quotes/" + pf.get("id").asLong(), newStore().ownerToken()).expect(404);
    }

    @Test
    void prefacturaFlagsMissingStockAndAnnulledLinksDisappear() throws Exception {
        JsonNode pf = api.post("/api/quotes?kind=PREFACTURA", owner, quoteBody("\"customerName\":\"Ana\",", 50, 0)).expect(201).json();
        assertThat(codes(pf.get("validation"), "ERROR")).contains("STOCK");
        String token = pf.get("publicToken").asText();
        api.post("/api/quotes/" + pf.get("id").asLong() + "/cancel", owner, "{\"reason\":\"\"}").expect(400);
        api.post("/api/quotes/" + pf.get("id").asLong() + "/cancel", owner, "{\"reason\":\"Cliente desistió\"}").expect(200);
        api.get("/api/public/quotes/" + token, null).expect(404);
        api.get("/api/public/quotes/inventado-inventado-inventado", null).expect(404);
        JsonNode list = api.get("/api/quotes?kind=PREFACTURA&status=ANULADA", owner).expect(200).json();
        assertThat(list).hasSize(1);
    }
}
