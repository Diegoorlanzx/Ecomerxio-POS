package com.tiendaposs.accounting;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "accounting_settings")
public class AccountingSettings {
    @Id
    public Long tenantId;
    public boolean enabled;
    /** Desde esta fecha se contabiliza; nada hacia atrás. */
    public LocalDate startDate;
    public Instant createdAt = Instant.now();
    public Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
