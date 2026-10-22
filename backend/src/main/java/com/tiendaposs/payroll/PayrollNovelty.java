package com.tiendaposs.payroll;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/** Fase 16F: una novedad del mes de un empleado (horas extra, incapacidad, vacaciones, licencia, bono, deducción...). */
@Entity
@Table(name = "payroll_novelties")
public class PayrollNovelty {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long periodId;
    public Long employeeId;
    public String kind;
    public String code;
    public BigDecimal quantity;
    public BigDecimal percentage;
    public BigDecimal amount;
    public String description;
    public Instant createdAt = Instant.now();
}
