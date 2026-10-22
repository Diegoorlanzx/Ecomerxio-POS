package com.tiendaposs.einvoice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tiendaposs.einvoice.EInvoiceProvider.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Fase 16D: el adaptador de Factus contra un servidor falso con las rutas y respuestas de su documentación. */
class FactusProviderTest {

    HttpServer server;
    FactusProvider factus;
    final ObjectMapper json = new ObjectMapper();
    final List<String> calls = new ArrayList<>();
    final List<JsonNode> bodies = new ArrayList<>();
    final AtomicInteger tokens = new AtomicInteger();
    volatile int billStatus = 201;
    volatile String billBody;
    volatile boolean expireOnce;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        factus = new FactusProvider(new FactusProvider.Config("http://127.0.0.1:" + server.getAddress().getPort(), "cid", "secret", "u@x.co", "clave"));
        billBody = """
                {"status":"Created","message":"ok","data":{"bill":{"number":"SETP990000550","cufe":"abc123","is_validated":true,
                 "validated_at":"22-10-2026 05:00:00 PM","errors":{"FAJ44b":"Regla: FAJ44b, Notificación: el nit no está"},
                 "links":{"qr":"https://catalogo-vpfe-hab.dian.gov.co/document/searchqr?documentkey=abc123"}}}}""";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().toString();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        calls.add(ex.getRequestMethod() + " " + path);
        int status;
        String out;
        if (path.equals("/oauth/token")) {
            assertThat(body).contains("client_id=cid");
            tokens.incrementAndGet();
            status = 200;
            out = "{\"token_type\":\"Bearer\",\"expires_in\":3600,\"access_token\":\"T" + tokens.get() + "\",\"refresh_token\":\"R\"}";
        } else if (expireOnce) {
            expireOnce = false;
            status = 401;
            out = "{\"message\":\"Unauthenticated.\"}";
        } else if (path.startsWith("/v2/bills?filter")) {
            status = 200;
            out = "{\"data\":{\"data\":[{\"number\":\"SETP990000550\"}]}}";
        } else if (path.startsWith("/v2/bills/SETP")) {
            status = 200;
            out = billBody;
        } else {
            bodies.add(json.readTree(body));
            status = billStatus;
            out = billBody;
        }
        byte[] b = out.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private Payload payload(String type, Party buyer, String paymentForm, String method) {
        Party issuer = new Party("NIT", "31", "800197268", "4", "Tienda SAS", "JURIDICA", List.of("O-15"), "01", "f@t.co", "Calle 1", "11001");
        Line line = new Line("Gaseosa", new BigDecimal("2"), "94", new BigDecimal("3000"), BigDecimal.ZERO, "IVA", new BigDecimal("19"),
                "GRAVADO", new BigDecimal("958.00"), new BigDecimal("6000.00"));
        return new Payload("T1-S9-FACTURA", type, "PRUEBAS", "FE1", "FE", 1, "18760000001", Instant.now(), issuer, buyer,
                buyer.idNumber().startsWith("2222"), List.of(line), new BigDecimal("958.00"), new BigDecimal("6000.00"),
                "FE7", "cufe-o", "Motivo", paymentForm, "CREDITO".equals(paymentForm) ? LocalDate.of(2026, 11, 6) : null,
                EInvoiceService.NOTA_DEBITO.equals(type) ? "1" : null, method);
    }

    private static Party finalConsumer() {
        return new Party("CC", "13", "222222222222", null, "Consumidor final", "NATURAL", List.of("R-99-PN"), "ZZ", null, null, null);
    }

    @Test
    void anAcceptedInvoiceTakesFactusNumberAndIgnoresNotifications() throws Exception {
        Result r = factus.send(payload(EInvoiceService.FACTURA, finalConsumer(), "CONTADO", "EFECTIVO"));
        assertThat(r.outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(r.issuedNumber()).isEqualTo("SETP990000550");
        assertThat(r.cufe()).isEqualTo("abc123");
        assertThat(r.qrData()).startsWith("https://catalogo-vpfe");
        assertThat(calls).contains("POST /oauth/token", "POST /v2/bills/validate");

        JsonNode b = bodies.get(0);
        assertThat(b.get("reference_code").asText()).isEqualTo("T1-S9-FACTURA");
        assertThat(b.get("customer").get("identification").asText()).isEqualTo("222222222222");
        assertThat(b.get("customer").get("legal_organization_code").asText()).isEqualTo("2");
        assertThat(b.get("payment_details").get(0).get("payment_method_code").asText()).isEqualTo("10");
        JsonNode item = b.get("items").get(0);
        assertThat(item.get("price").asText()).isEqualTo("2521.00"); // (6000 − 958) / 2, sin IVA
        assertThat(item.get("taxes").get(0).get("rate").asText()).isEqualTo("19.00");

        // El token se reutiliza.
        factus.send(payload(EInvoiceService.FACTURA, finalConsumer(), "CONTADO", "EFECTIVO"));
        assertThat(tokens.get()).isEqualTo(1);
    }

    @Test
    void notesAndCreditGoToTheirEndpointsWithTheirFields() throws Exception {
        Party ana = new Party("CC", "13", "52123456", null, "Ana", "NATURAL", List.of(), "ZZ", "a@a.co", null, "11001");
        factus.send(payload(EInvoiceService.NOTA_DEBITO, ana, "CREDITO", "FIADO"));
        JsonNode nd = bodies.get(0);
        assertThat(calls).contains("POST /v2/debit-notes/validate");
        assertThat(nd.get("bill_number").asText()).isEqualTo("FE7");
        assertThat(nd.get("correction_concept_code").asText()).isEqualTo("1");
        assertThat(nd.get("payment_details").get(0).get("payment_form").asText()).isEqualTo("2");
        assertThat(nd.get("payment_details").get(0).get("due_date").asText()).isEqualTo("2026-11-06");

        factus.send(payload(EInvoiceService.NOTA_AJUSTE_SOPORTE, ana, "CONTADO", null));
        assertThat(calls).contains("POST /v2/adjustment-notes/validate");
        assertThat(bodies.get(1).get("support_document_number").asText()).isEqualTo("FE7");
        assertThat(bodies.get(1).has("provider")).isTrue();
    }

    @Test
    void validationErrorsAreRejectionsAndOutagesAreUnknown() throws Exception {
        billStatus = 422;
        billBody = "{\"message\":\"Los datos no son válidos\",\"errors\":{\"customer.identification\":[\"El campo es obligatorio\"]}}";
        Result r = factus.send(payload(EInvoiceService.FACTURA, finalConsumer(), "CONTADO", "TARJETA"));
        assertThat(r.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(r.errors().get(0).code()).isEqualTo("customer.identification");

        billStatus = 503;
        assertThatThrownBy(() -> factus.send(payload(EInvoiceService.FACTURA, finalConsumer(), "CONTADO", "TARJETA")))
                .isInstanceOf(ProviderUnavailableException.class);

        billStatus = 402;
        assertThat(factus.send(payload(EInvoiceService.FACTURA, finalConsumer(), "CONTADO", null)).errors().get(0).code()).isEqualTo("FACTUS_402");
    }

    @Test
    void anExpiredTokenIsRenewedOnceAndBillsAreQueriedByReference() throws Exception {
        factus.send(payload(EInvoiceService.FACTURA, finalConsumer(), "CONTADO", "EFECTIVO"));
        expireOnce = true;
        Result q = factus.query("T1-S9-FACTURA");
        assertThat(q.outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(tokens.get()).isEqualTo(2);
        assertThat(calls).anyMatch(c -> c.startsWith("GET /v2/bills?filter%5Breference_code%5D=") || c.startsWith("GET /v2/bills?filter[reference_code]="));
        // Notas: no hay búsqueda por referencia; se reenvía con la misma (Factus no la duplica).
        assertThat(factus.query("T1-NC-D4").outcome()).isEqualTo(Outcome.NOT_FOUND);
    }
}
