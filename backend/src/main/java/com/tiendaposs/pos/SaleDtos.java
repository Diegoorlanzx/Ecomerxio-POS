package com.tiendaposs.pos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class SaleDtos {
    private SaleDtos() {
    }

    public record LineRequest(
            @NotNull Long presentationId,
            @NotNull @DecimalMin(value = "0.001", message = "la cantidad debe ser mayor que cero") BigDecimal quantity,
            @DecimalMin(value = "0", message = "no puede ser negativo") BigDecimal unitPrice,
            @DecimalMin(value = "0", message = "no puede ser negativo") @DecimalMax(value = "100", message = "máximo 100") BigDecimal discountPct) {
    }

    public record CustomerRequest(Long customerId, @Size(max = 120) String name, @Size(max = 10) String docType,
                                  @Size(max = 30) String docNumber, @Size(max = 30) String phone,
                                  @Email(message = "no es un correo válido") String email) {
    }

    public record PaymentRequest(
            @NotBlank String method,
            @NotNull @DecimalMin(value = "0.01", message = "debe ser mayor que cero") BigDecimal amount,
            @Size(max = 80, message = "máximo 80 caracteres") String reference) {
    }

    public record CheckoutRequest(
            @Size(max = 64) String clientRef,
            @NotNull @Size(min = 1, message = "agrega al menos un producto") List<@Valid LineRequest> lines,
            BigDecimal saleDiscountPct,
            BigDecimal saleDiscountAmount,
            @Valid CustomerRequest customer,
            @NotNull @Size(min = 1, message = "registra al menos un pago") List<@Valid PaymentRequest> payments,
            @Size(max = 500) String notes,
            List<String> approvals,
            boolean offline,
            Instant offlineCreatedAt,
            Long offlineShiftId) {
    }

    /** Cobro de una cuenta abierta: las líneas ya están guardadas en el servidor. */
    public record AccountCheckoutRequest(
            BigDecimal saleDiscountPct,
            BigDecimal saleDiscountAmount,
            @Valid CustomerRequest customer,
            @NotNull @Size(min = 1, message = "registra al menos un pago") List<@Valid PaymentRequest> payments,
            @Size(max = 500) String notes,
            List<String> approvals) {
    }

    public record AccountRequest(@NotBlank(message = "ponle un nombre a la cuenta") @Size(max = 60, message = "máximo 60 caracteres") String label,
                                 @Valid CustomerRequest customer, @Size(max = 500) String notes) {
    }

    public record AddLineRequest(@NotNull @Valid LineRequest line, List<String> approvals) {
    }

    public record QuantityRequest(@NotNull @DecimalMin(value = "0.001", message = "la cantidad debe ser mayor que cero") BigDecimal quantity) {
    }

    public record HoldRequest(@Size(max = 60) String label, @NotNull @Size(min = 1, message = "la venta está vacía") List<@Valid LineRequest> lines,
                              @Valid CustomerRequest customer, @Size(max = 500) String notes) {
    }

    public record VoidRequest(@NotBlank(message = "explica por qué se anula") @Size(max = 300) String reason, List<String> approvals) {
    }

    public record ReturnLineRequest(@NotNull Long saleLineId,
                                    @NotNull @DecimalMin(value = "0.001", message = "debe ser mayor que cero") BigDecimal quantity,
                                    boolean restock,
                                    @Pattern(regexp = "BUENO|DANADO", message = "estado no válido") String condition) {
    }

    public record ReturnRequest(@NotNull @Size(min = 1, message = "elige qué se devuelve") List<@Valid ReturnLineRequest> lines,
                                @NotBlank(message = "explica el motivo") @Size(max = 300) String reason,
                                @NotBlank String refundMethod,
                                List<String> approvals) {
    }

    // ---------------- Respuestas ----------------

    public record LineDto(Long id, Long productId, Long presentationId, String productName, String presentationName,
                          BigDecimal factor, BigDecimal quantity, BigDecimal listPrice, BigDecimal unitPrice,
                          String priceAuthorizedBy, BigDecimal discountPct, String promoName, BigDecimal gross,
                          BigDecimal discount, String taxName, BigDecimal taxRate, BigDecimal tax, BigDecimal lineTotal,
                          BigDecimal unitCost, BigDecimal returnedQty, String addedBy) {
    }

    public record PaymentDto(Long id, String method, BigDecimal amount, BigDecimal received, BigDecimal changeGiven,
                             String reference, String status, String userName, Instant createdAt) {
    }

    public record ReturnDto(Long id, long number, String reason, String refundMethod, BigDecimal refundAmount,
                            String userName, String authorizedBy, Instant createdAt, List<ReturnLineDto> lines) {
    }

    public record ReturnLineDto(Long saleLineId, String productName, BigDecimal quantity, BigDecimal amount,
                                boolean restock, String condition) {
    }

    public record SaleDto(Long id, Long number, String status, String accountLabel, Long shiftId, Long registerId,
                          Long userId, String userName, Long customerId, String customerName, String customerDoc,
                          BigDecimal subtotal, BigDecimal discountTotal, BigDecimal taxTotal, BigDecimal total,
                          BigDecimal costTotal, BigDecimal paidTotal, BigDecimal changeAmount, BigDecimal refundedTotal,
                          BigDecimal saleDiscountPct, BigDecimal saleDiscountAmount, String discountAuthorizedBy,
                          String notes, String clientRef, boolean offline, List<String> reviewFlags, Instant reviewedAt,
                          Instant createdAt, Instant completedAt, Instant voidedAt, String voidedBy, String voidReason,
                          String voidAuthorizedBy, List<LineDto> lines, List<PaymentDto> payments, List<ReturnDto> returns) {
    }

    public record SaleSummaryDto(Long id, Long number, String status, String userName, String customerName,
                                 BigDecimal total, BigDecimal refundedTotal, List<String> methods, int itemCount,
                                 boolean offline, List<String> reviewFlags, boolean reviewed, Instant completedAt) {
    }

    public record AccountSummaryDto(Long id, String label, String userName, String customerName, BigDecimal total,
                                    int itemCount, Instant createdAt, int version) {
    }
}
