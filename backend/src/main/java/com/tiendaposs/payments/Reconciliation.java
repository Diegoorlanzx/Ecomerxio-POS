package com.tiendaposs.payments;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "reconciliations")
public class Reconciliation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public String method;
    public String fileName;
    public LocalDate dateFrom;
    public LocalDate dateTo;
    public int totalRows;
    public int matched;
    public int inconsistent;
    public int unmatched;
    public String createdBy;
    public Instant createdAt = Instant.now();
}
