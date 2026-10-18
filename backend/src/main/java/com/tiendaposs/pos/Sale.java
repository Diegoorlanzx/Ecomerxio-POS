package com.tiendaposs.pos;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "sales")
public class Sale {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long number;
    /** ABIERTA (cuenta abierta), EN_ESPERA (venta guardada), PAGADA, ANULADA. */
    public String status;
    public String accountLabel;
    public Long shiftId;
    public Long registerId;
    public Long userId;
    public String userName;
    public Long customerId;
    public String customerName;
    public String customerDoc;
    public BigDecimal subtotal = BigDecimal.ZERO;
    public BigDecimal discountTotal = BigDecimal.ZERO;
    public BigDecimal taxTotal = BigDecimal.ZERO;
    public BigDecimal total = BigDecimal.ZERO;
    public BigDecimal costTotal = BigDecimal.ZERO;
    public BigDecimal paidTotal = BigDecimal.ZERO;
    public BigDecimal changeAmount = BigDecimal.ZERO;
    public BigDecimal refundedTotal = BigDecimal.ZERO;
    public BigDecimal saleDiscountPct;
    public BigDecimal saleDiscountAmount;
    public String discountAuthorizedBy;
    public String notes;
    public String clientRef;
    public boolean offline;
    /** El cliente pidió factura electrónica a su nombre. */
    public boolean invoiceRequested;
    public String reviewFlags;
    public Instant reviewedAt;
    public Instant createdAt = Instant.now();
    public Instant completedAt;
    public Instant voidedAt;
    public String voidedBy;
    public String voidReason;
    public String voidAuthorizedBy;
    @Version
    public int version;
}
