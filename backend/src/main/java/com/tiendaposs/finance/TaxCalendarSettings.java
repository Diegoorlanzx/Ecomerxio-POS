package com.tiendaposs.finance;

import jakarta.persistence.*;

import java.time.Instant;

/** Qué obligaciones tiene el negocio, para armar su calendario de recordatorios (Fase 15). */
@Entity
@Table(name = "tax_calendar_settings")
public class TaxCalendarSettings {
    @Id
    public Long tenantId;
    /** NINGUNO, BIMESTRAL o CUATRIMESTRAL. */
    public String ivaPeriod = "NINGUNO";
    public boolean simpleRegime;
    public boolean retentionAgent;
    /** NINGUNO, BIMESTRAL o ANUAL. */
    public String icaPeriod = "NINGUNO";
    /** NINGUNO, NATURAL o JURIDICA. */
    public String incomeTax = "NINGUNO";
    public boolean mercantileRegistry = true;
    public int reminderDays = 7;
    /** false = todavía no lo ha revisado nadie (se muestran las sugerencias). */
    public boolean configured;
    public Instant updatedAt = Instant.now();
}
