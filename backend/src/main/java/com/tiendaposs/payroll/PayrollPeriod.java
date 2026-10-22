package com.tiendaposs.payroll;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

/** Fase 16F: la nómina de un mes (BORRADOR mientras se cargan novedades; APROBADA queda fija). */
@Entity
@Table(name = "payroll_periods")
public class PayrollPeriod {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public int year;
    public int month;
    public String status = "BORRADOR";
    public LocalDate paymentDate;
    public String approvedBy;
    public Instant approvedAt;
    public Instant createdAt = Instant.now();
}
