package com.tiendaposs.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiendaposs.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Cuenta Wompi de la PLATAFORMA (la misma de Ecomerxio: "Soluciones Digitales para Negocios") para cobrar la suscripción
 * del POS. Apagada mientras falte alguna llave (BILLING_ENABLED + WOMPI_*). La llave privada y el secreto de integridad nunca
 * salen del servidor.
 *
 * <p>No usa el aviso (webhook) de Wompi: la cuenta solo admite una URL de eventos y ya la tiene Ecomerxio. El pago se confirma
 * consultando la transacción a Wompi al cerrar la ventana, con «Verificar mi pago» y con una revisión periódica.</p>
 */
@Component
public class WompiBillingGateway {

    private static final Logger log = LoggerFactory.getLogger(WompiBillingGateway.class);

    public record WidgetParams(String publicKey, String currency, long amountInCents, String reference, String integritySignature) {
    }

    public record TransactionInfo(String id, String reference, long amountInCents, String status, String statusMessage) {
    }

    private final boolean enabled;
    private final String baseUrl;
    private final String publicKey;
    private final String privateKey;
    private final String integritySecret;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public WompiBillingGateway(@Value("${app.billing.enabled:false}") boolean enabled,
                               @Value("${app.billing.wompi-base-url:https://production.wompi.co/v1}") String baseUrl,
                               @Value("${app.billing.wompi-public-key:}") String publicKey,
                               @Value("${app.billing.wompi-private-key:}") String privateKey,
                               @Value("${app.billing.wompi-integrity-secret:}") String integritySecret,
                               ObjectMapper json) {
        this.enabled = enabled && !publicKey.isBlank() && !privateKey.isBlank() && !integritySecret.isBlank() && !baseUrl.isBlank();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.publicKey = publicKey;
        this.privateKey = privateKey;
        this.integritySecret = integritySecret;
        this.json = json;
        if (enabled && !this.enabled) {
            log.warn("BILLING_ENABLED=true pero faltan llaves de Wompi de la plataforma; el pago de la suscripción sigue apagado");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "El pago en línea de la suscripción aún no está activo. Escríbenos y te ayudamos a pagar.");
        }
    }

    /** Datos firmados para abrir el widget: SHA-256 de referencia + monto + moneda + secreto de integridad, en ese orden. */
    public WidgetParams widgetParams(String reference, long amountInCents) {
        requireEnabled();
        return new WidgetParams(publicKey, "COP", amountInCents, reference, sha256(reference + amountInCents + "COP" + integritySecret));
    }

    /** Estado real de una transacción, preguntado a Wompi (lo que diga el navegador no cuenta como prueba de pago). */
    public TransactionInfo fetchTransaction(String transactionId) {
        requireEnabled();
        JsonNode data = get("/transactions/" + URLEncoder.encode(transactionId, StandardCharsets.UTF_8)).path("data");
        return info(data, transactionId, null);
    }

    public List<TransactionInfo> fetchByReference(String reference) {
        requireEnabled();
        List<TransactionInfo> found = new ArrayList<>();
        for (JsonNode tx : get("/transactions?reference=" + URLEncoder.encode(reference, StandardCharsets.UTF_8)).path("data")) {
            found.add(info(tx, null, reference));
        }
        return found;
    }

    private static TransactionInfo info(JsonNode d, String id, String reference) {
        return new TransactionInfo(d.path("id").asText(id), d.path("reference").asText(reference), d.path("amount_in_cents").asLong(0),
                d.path("status").asText("ERROR"), d.path("status_message").asText(null));
    }

    private JsonNode get(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + privateKey).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + res.statusCode());
            }
            return json.readTree(res.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable(e);
        } catch (Exception e) {
            throw unavailable(e);
        }
    }

    private ApiException unavailable(Exception e) {
        log.warn("Consulta a Wompi falló: {}", e.toString());
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "No pudimos consultar el pago en Wompi. Intenta de nuevo en un momento.");
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
