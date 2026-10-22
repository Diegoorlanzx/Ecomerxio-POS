package com.tiendaposs.payroll;

import jakarta.persistence.*;

import java.math.BigDecimal;

/** Fase 16F: liquidación de un empleado en un mes; el detalle (con claves de la nómina electrónica) va en JSON. */
@Entity
@Table(name = "payslips")
public class Payslip {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long periodId;
    public Long employeeId;
    public int daysWorked;
    public BigDecimal contributionBase;
    public BigDecimal totalEarned;
    public BigDecimal totalDeductions;
    public BigDecimal netPay;
    public BigDecimal employerCost;
    public BigDecimal provisions;
    @Column(columnDefinition = "text")
    public String detail;
    /** Se eliminó ante la DIAN con la nota de ajuste. */
    public boolean deletedBeforeDian;
}
