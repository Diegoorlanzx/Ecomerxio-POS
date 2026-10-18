package com.tiendaposs.fiscal;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

/** Resolución de numeración autorizada: prefijo, rango y vigencia de un tipo de documento. */
@Entity
@Table(name = "numbering_resolutions")
public class NumberingResolution {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public String documentType;
    public String resolutionNumber;
    public LocalDate resolutionDate;
    public String prefix;
    public long rangeFrom;
    public long rangeTo;
    /** Siguiente consecutivo a usar; la Fase 13B lo toma de forma atómica. */
    public long nextNumber;
    public LocalDate validFrom;
    public LocalDate validTo;
    public String environment = "PRUEBAS";
    public String technicalKey;
    public boolean active = true;
    public Instant createdAt = Instant.now();
    public Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    /** Números ya usados del rango. */
    public long used() {
        return nextNumber - rangeFrom;
    }

    public long size() {
        return rangeTo - rangeFrom + 1;
    }

    public long remaining() {
        return Math.max(0, rangeTo - nextNumber + 1);
    }
}
