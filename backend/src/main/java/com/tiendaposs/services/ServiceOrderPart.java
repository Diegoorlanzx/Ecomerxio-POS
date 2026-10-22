package com.tiendaposs.services;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/** Repuesto usado en una orden: sale del inventario al entregar. */
@Entity
@Table(name = "service_order_parts")
public class ServiceOrderPart {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long orderId;
    public Long presentationId;
    public String name;
    public BigDecimal quantity;
    public BigDecimal unitPrice;
}
