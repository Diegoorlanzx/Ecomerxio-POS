package com.tiendaposs.purchasing;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "inventory_counts")
public class InventoryCount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public long number;
    public String name;
    /** ABIERTO, CERRADO o ANULADO. */
    public String status = "ABIERTO";
    /** Conteo a ciegas: quien cuenta no ve lo que dice el sistema. */
    public boolean blind = true;
    public Long categoryId;
    public String createdBy;
    public Instant createdAt = Instant.now();
    public String closedBy;
    public Instant closedAt;
    public String notes;
}
