package com.tiendaposs.services;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/** Anticipo de una orden: cuenta el día en que se recibe y se descuenta al entregar. */
@Entity
@Table(name = "service_order_deposits")
public class ServiceOrderDeposit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long orderId;
    public BigDecimal amount;
    public String method;
    public String reference;
    public Long shiftId;
    public String userName;
    public Instant createdAt = Instant.now();
}
