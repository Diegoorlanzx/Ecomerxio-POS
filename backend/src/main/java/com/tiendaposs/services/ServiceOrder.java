package com.tiendaposs.services;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/** Orden de servicio técnico: RECIBIDO → DIAGNOSTICO → ESPERANDO_APROBACION → EN_REPARACION → LISTO → ENTREGADO. */
@Entity
@Table(name = "service_orders")
public class ServiceOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public long number;
    public Long customerId;
    public String customerName;
    public String customerPhone;
    public String device;
    public String brand;
    public String model;
    public String serial;
    public String problem;
    public String accessories;
    public String conditionNotes;
    public String diagnosis;
    public BigDecimal laborPrice = BigDecimal.ZERO;
    public String quoteStatus = "SIN_COTIZAR";
    public String status = "RECIBIDO";
    public Long technicianId;
    public int warrantyDays = 30;
    public LocalDate promisedAt;
    public Long saleId;
    public Instant deliveredAt;
    public String createdBy;
    public Instant createdAt = Instant.now();
    public Instant updatedAt = Instant.now();
}
