package com.tiendaposs.services;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/** Pago de comisiones de un período; queda como gasto de nómina en finanzas. */
@Entity
@Table(name = "staff_payouts")
public class StaffPayout {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long staffId;
    public LocalDate periodFrom;
    public LocalDate periodTo;
    public BigDecimal baseAmount;
    public BigDecimal amount;
    public Long financeEntryId;
    public String paidBy;
    public Instant createdAt = Instant.now();
}
