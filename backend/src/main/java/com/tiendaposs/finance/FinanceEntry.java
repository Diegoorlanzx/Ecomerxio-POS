package com.tiendaposs.finance;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "finance_entries")
public class FinanceEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public String kind;
    public Long categoryId;
    public String description;
    public BigDecimal amount;
    public LocalDate entryDate;
    public String method;
    /** CAJA, BANCO u OTRO. */
    public String paidFrom;
    public String reference;
    public Long cashMovementId;
    public String createdBy;
    public Instant createdAt = Instant.now();
    public Instant voidedAt;
    public String voidedBy;
}
