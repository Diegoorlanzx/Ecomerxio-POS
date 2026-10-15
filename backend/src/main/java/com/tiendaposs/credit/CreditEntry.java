package com.tiendaposs.credit;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "credit_entries")
public class CreditEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long customerId;
    /** CARGO (venta fiada), ABONO o AJUSTE. */
    public String type;
    public BigDecimal amount;
    /** Solo cargos: cuánto se ha abonado. */
    public BigDecimal paidAmount = BigDecimal.ZERO;
    public Long saleId;
    public String method;
    public String reference;
    public Long shiftId;
    public LocalDate dueDate;
    public String note;
    public String userName;
    public String authorizedBy;
    public Instant createdAt = Instant.now();
}
