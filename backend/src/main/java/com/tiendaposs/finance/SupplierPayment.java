package com.tiendaposs.finance;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "supplier_payments")
public class SupplierPayment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long supplierId;
    public BigDecimal amount;
    public String method;
    public String paidFrom;
    public String reference;
    public String note;
    public LocalDate paidOn;
    public Long cashMovementId;
    public String userName;
    public Instant createdAt = Instant.now();
}
