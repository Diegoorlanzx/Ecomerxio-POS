package com.tiendaposs.online;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/** Pedido hecho desde la tienda en línea. Al entregarse se cobra como una venta normal (saleId). */
@Entity
@Table(name = "online_orders")
public class OnlineOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long number;
    public String token;
    public String status = "NUEVO";
    public Long customerId;
    public String customerName;
    public String phone;
    public String email;
    /** DOMICILIO o RECOGER. */
    public String fulfillment;
    public Long zoneId;
    public String zoneName;
    public String address;
    public String notes;
    public String payMethod;
    public BigDecimal subtotal = BigDecimal.ZERO;
    public BigDecimal deliveryFee = BigDecimal.ZERO;
    public BigDecimal total = BigDecimal.ZERO;
    public Long saleId;
    public String cancelReason;
    public String handledBy;
    public Instant acceptedAt;
    public Instant readyAt;
    public Instant dispatchedAt;
    public Instant deliveredAt;
    public Instant cancelledAt;
    public Instant createdAt = Instant.now();
    public Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
