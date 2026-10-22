package com.tiendaposs.services;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/** Cita: AGENDADA → CONFIRMADA → ATENDIDA (o NO_ASISTIO / CANCELADA). Al cobrarla queda una cuenta en la caja. */
@Entity
@Table(name = "appointments")
public class Appointment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long staffId;
    public Long customerId;
    public String customerName;
    public String customerPhone;
    public Long serviceId;
    public String serviceName;
    public BigDecimal price = BigDecimal.ZERO;
    public Instant startAt;
    public Instant endAt;
    public String status = "AGENDADA";
    public String source = "CAJA";
    public String notes;
    public Long saleId;
    public Instant reminderSentAt;
    public String createdBy;
    public Instant createdAt = Instant.now();
}
