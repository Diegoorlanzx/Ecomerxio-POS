package com.tiendaposs.purchasing;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "purchase_orders")
public class PurchaseOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public long number;
    public Long supplierId;
    /** BORRADOR, POR_APROBAR, APROBADA, PARCIAL, RECIBIDA, CANCELADA. */
    public String status = "BORRADOR";
    public String source = "MANUAL";
    public LocalDate expectedDate;
    public String notes;
    public BigDecimal estimatedTotal = BigDecimal.ZERO;
    public BigDecimal receivedTotal = BigDecimal.ZERO;
    public String createdBy;
    public Instant createdAt = Instant.now();
    public Instant submittedAt;
    public String approvedBy;
    public Instant approvedAt;
    public Instant sentAt;
    public String sentVia;
    public Instant receivedAt;
    public Instant cancelledAt;
    public String cancelReason;
}
