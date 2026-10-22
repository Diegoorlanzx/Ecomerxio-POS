package com.tiendaposs.services;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/** Historial de una orden (cada cambio de estado, con nota). */
@Entity
@Table(name = "service_order_events")
public class ServiceOrderEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long orderId;
    public String status;
    public String note;
    public String userName;
    public Instant createdAt = Instant.now();
}
