package com.tiendaposs.accounting;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

/** Operación que no se pudo contabilizar porque falta una cuenta en el mapa. */
@Entity
@Table(name = "accounting_pending")
public class PendingEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public String sourceType;
    public Long sourceId;
    public LocalDate entryDate;
    public String description;
    public String reason;
    public Instant createdAt = Instant.now();
    public Instant resolvedAt;
}
