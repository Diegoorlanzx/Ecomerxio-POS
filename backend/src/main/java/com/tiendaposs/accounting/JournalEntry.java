package com.tiendaposs.accounting;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Comprobante contable (partida doble) enlazado a la operación que lo originó. */
@Entity
@Table(name = "journal_entries")
public class JournalEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public long number;
    public String entryType;
    public LocalDate entryDate;
    public String sourceType;
    public Long sourceId;
    public Long reversalOf;
    public String description;
    public BigDecimal total = BigDecimal.ZERO;
    /** Fecha real de la operación cuando el comprobante se movió porque su mes estaba cerrado. */
    public LocalDate originalDate;
    public String createdBy;
    public Instant createdAt = Instant.now();
}
