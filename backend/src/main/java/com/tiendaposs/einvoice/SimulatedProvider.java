package com.tiendaposs.einvoice;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Proveedor de mentira para construir y probar todo el flujo antes de contratar uno real. Sus documentos NO tienen
 * validez ante la DIAN y solo se usan en el ambiente de pruebas.
 *
 * Acepta todo por defecto. Para demostraciones y pruebas, según el nombre del comprador:
 * - contiene {@code SIM-RECHAZO}: rechaza con un error de identificación del adquiriente.
 * - contiene {@code SIM-DEMORA}: queda "en proceso" y se acepta al consultarlo.
 * - contiene {@code SIM-CAIDA}: lo recibe y lo acepta, pero la respuesta "se pierde" (no hay respuesta); al consultarlo
 *   aparece aceptado. Sirve para comprobar que nunca se emite dos veces.
 */
@Component
public class SimulatedProvider implements EInvoiceProvider {

    /** Lo que el "proveedor" recibió, por referencia externa (se pierde al reiniciar: como si nunca lo hubiera recibido). */
    private final Map<String, Result> received = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> sendCount = new ConcurrentHashMap<>();

    @Override
    public String code() {
        return "SIMULADO";
    }

    @Override
    public boolean simulated() {
        return true;
    }

    @Override
    public Result send(Payload d) throws ProviderUnavailableException {
        sendCount.computeIfAbsent(d.externalRef(), k -> new AtomicInteger()).incrementAndGet();
        // Idempotente por referencia, como debe serlo uno real: reenviar lo mismo devuelve el mismo resultado.
        Result existing = received.get(d.externalRef());
        if (existing != null && existing.outcome() != Outcome.REJECTED) return existing;

        String buyer = d.buyer() == null || d.buyer().name() == null ? "" : d.buyer().name().toUpperCase();
        if (buyer.contains("SIM-RECHAZO")) {
            Result r = new Result(Outcome.REJECTED, track(), null, null,
                    List.of(new ProviderError("SIM-FAJ43", "Rechazo simulado: el número de identificación del adquiriente no es válido")));
            received.put(d.externalRef(), r);
            return r;
        }
        Result accepted = new Result(Outcome.ACCEPTED, track(), cufe(d), qr(d), List.of());
        if (buyer.contains("SIM-DEMORA")) {
            received.put(d.externalRef(), accepted);
            return new Result(Outcome.PROCESSING, accepted.trackId(), null, null, List.of());
        }
        received.put(d.externalRef(), accepted);
        if (buyer.contains("SIM-CAIDA")) throw new ProviderUnavailableException("Simulación: el proveedor no respondió a tiempo");
        return accepted;
    }

    @Override
    public Result query(String externalRef) {
        Result r = received.get(externalRef);
        return r == null ? Result.notFound() : r;
    }

    /** Para las pruebas: cuántas veces se envió una referencia. */
    public int timesSent(String externalRef) {
        AtomicInteger n = sendCount.get(externalRef);
        return n == null ? 0 : n.get();
    }

    private static String track() {
        return "SIM-" + UUID.randomUUID();
    }

    /** Un código con la forma de un CUFE (SHA-384 de los datos del documento). Simulado: no es verificable ante la DIAN. */
    private static String cufe(Payload d) {
        String base = d.fullNumber() + d.issueDate() + d.total() + d.issuer().idNumber()
                + (d.buyer() == null ? "" : d.buyer().idNumber()) + d.environment();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-384").digest(base.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String qr(Payload d) {
        return "DOCUMENTO SIMULADO SIN VALIDEZ|" + d.fullNumber() + "|" + d.issueDate() + "|" + d.total();
    }
}
