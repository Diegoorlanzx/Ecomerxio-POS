package com.tiendaposs.einvoice;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Proveedor tecnológico autorizado por la DIAN. Ecomerxio POS no firma ni transmite: arma el documento y se lo entrega
 * al proveedor. Hoy solo existe {@link SimulatedProvider}; el real se agrega implementando esta interfaz (adaptador).
 */
public interface EInvoiceProvider {

    /** Código que se guarda en cada documento (SIMULADO, DATAICO, ...). */
    String code();

    /** true si sus documentos no tienen validez (el simulador): nunca se usa en producción. */
    boolean simulated();

    /** Envía el documento. Lanza {@link ProviderUnavailableException} si no hay respuesta (no se sabe si llegó). */
    Result send(Payload document) throws ProviderUnavailableException;

    /** Estado de un documento por su referencia externa; NOT_FOUND si el proveedor nunca lo recibió. */
    Result query(String externalRef) throws ProviderUnavailableException;

    enum Outcome { ACCEPTED, REJECTED, PROCESSING, NOT_FOUND }

    record ProviderError(String code, String message) {
    }

    /** issuedNumber: el número que puso el proveedor (Factus numera con su rango); null = se conserva el nuestro. */
    record Result(Outcome outcome, String trackId, String cufe, String qrData, List<ProviderError> errors, String issuedNumber) {
        public Result(Outcome outcome, String trackId, String cufe, String qrData, List<ProviderError> errors) {
            this(outcome, trackId, cufe, qrData, errors, null);
        }

        static Result notFound() {
            return new Result(Outcome.NOT_FOUND, null, null, null, List.of());
        }
    }

    class ProviderUnavailableException extends Exception {
        public ProviderUnavailableException(String message) {
            super(message);
        }
    }

    /** tributeCode: 01 si es responsable de IVA, ZZ si no aplica. */
    record Party(String idType, String idTypeCode, String idNumber, String dv, String name, String personType,
                 List<String> responsibilities, String tributeCode, String email, String address, String municipalityCode) {
    }

    record Line(String description, BigDecimal quantity, String unitCode, BigDecimal unitPrice, BigDecimal discount,
                String taxName, BigDecimal taxRate, String taxClass, BigDecimal tax, BigDecimal total) {
    }

    record Payload(String externalRef, String documentType, String environment, String fullNumber, String prefix, long number,
                   String resolutionNumber, Instant issueDate, Party issuer, Party buyer, boolean finalConsumer, List<Line> lines,
                   BigDecimal taxTotal, BigDecimal total, String originalNumber, String originalCufe, String reason,
                   /** CONTADO o CREDITO (fiado) con su vencimiento. */ String paymentForm, java.time.LocalDate dueDate,
                   /** Fase 16E: concepto DIAN de la nota (null si no es nota manual). */ String noteConcept,
                   /** Fase 16D: medio de pago principal (EFECTIVO, TARJETA, FIADO...). */ String paymentMethod) {
    }
}
