package com.tiendaposs.purchasing;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "goods_receipts")
public class GoodsReceipt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public long number;
    public Long supplierId;
    public Long orderId;
    public String invoiceNumber;
    public BigDecimal total = BigDecimal.ZERO;
    public BigDecimal paidAmount = BigDecimal.ZERO;
    /** Retenciones practicadas al proveedor (Fase 14): no se le pagan, se declaran. */
    public BigDecimal withheld = BigDecimal.ZERO;
    public LocalDate dueDate;
    public Boolean onTime;
    public String notes;
    public String receivedBy;
    public Instant receivedAt = Instant.now();

    /** Lo que todavía se le debe al proveedor. */
    public BigDecimal payable() {
        return total.subtract(withheld == null ? BigDecimal.ZERO : withheld).subtract(paidAmount);
    }
}
