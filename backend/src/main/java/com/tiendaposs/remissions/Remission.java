package com.tiendaposs.remissions;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Remisión: la mercancía sale con el cliente y se factura después (una o varias juntas). No es factura. */
@Entity
@Table(name = "remissions")
public class Remission {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public long number;
    /** BORRADOR, ENTREGADA, FACTURADA o ANULADA. */
    public String status;
    public Long customerId;
    public String customerName;
    public String customerDocType;
    public String customerDoc;
    public String customerPhone;
    public String deliveryAddress;
    public LocalDate issueDate;
    public String notes;
    public boolean showPrices = true;
    public BigDecimal subtotal = BigDecimal.ZERO;
    public BigDecimal discountTotal = BigDecimal.ZERO;
    public BigDecimal taxTotal = BigDecimal.ZERO;
    public BigDecimal total = BigDecimal.ZERO;
    public boolean pricesIncludeTax = true;
    public Long quoteId;
    public Long saleId;
    public String createdBy;
    public Instant createdAt = Instant.now();
    public Instant updatedAt = Instant.now();
    public Instant deliveredAt;
    public String deliveredBy;
    public String receivedBy;
    public String receivedDoc;
    public Instant invoicedAt;
    public Instant cancelledAt;
    public String cancelReason;
    @Version
    public int version;

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    /** REM-0007. */
    public String code() {
        return "REM-" + String.format("%04d", number);
    }
}
