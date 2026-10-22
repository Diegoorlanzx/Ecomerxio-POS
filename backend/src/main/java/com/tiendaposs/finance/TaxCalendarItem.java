package com.tiendaposs.finance;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

/** Una fecha del calendario tributario: solo un recordatorio, no liquida nada (Fase 15). */
@Entity
@Table(name = "tax_calendar_items")
public class TaxCalendarItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public String code;
    public String name;
    public String periodKey;
    public String periodLabel;
    public LocalDate dueDate;
    /** true mientras nadie haya puesto la fecha exacta del calendario oficial. */
    public boolean estimated = true;
    public Instant doneAt;
    public String doneBy;
    public String note;
    public Instant createdAt = Instant.now();
}
