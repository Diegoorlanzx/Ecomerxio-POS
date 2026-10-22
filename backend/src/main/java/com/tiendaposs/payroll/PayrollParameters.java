package com.tiendaposs.payroll;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/** Fase 16F: valores legales del año (los confirma el dueño o el contador; no se inventan). */
@Entity
@Table(name = "payroll_parameters")
public class PayrollParameters {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public int year;
    public BigDecimal minimumWage;
    public BigDecimal transportAid;
    public BigDecimal monthlyHours;
    public BigDecimal sundaySurchargePct;
    public boolean exonerated;
    public String confirmedBy;
    public Instant updatedAt = Instant.now();
}
