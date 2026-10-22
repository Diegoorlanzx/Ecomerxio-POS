package com.tiendaposs.distribution;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Pedido de preventa: PENDIENTE → ALISTADO → DESPACHADO → ENTREGADO / PARCIAL / DEVUELTO (o ANULADO). */
@Entity
@Table(name = "presale_orders")
public class PresaleOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public long number;
    public String clientRef;
    public Long customerId;
    public String customerName;
    public Long routeId;
    public Long sellerUserId;
    public String sellerName;
    public String status = "PENDIENTE";
    public String notes;
    public BigDecimal total = BigDecimal.ZERO;
    public Instant takenAt = Instant.now();
    public Instant syncedAt = Instant.now();
    public boolean offline;
    public Instant pickedAt;
    public Long dispatchId;
    public Instant deliveredAt;
    public Long saleId;
    public BigDecimal deliveredTotal = BigDecimal.ZERO;
    public BigDecimal collectedTotal = BigDecimal.ZERO;
    public BigDecimal creditTotal = BigDecimal.ZERO;
    public String returnReason;
}
