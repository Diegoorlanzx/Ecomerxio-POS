package com.tiendaposs.catalog;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "categories")
public class Category {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long parentId;
    public String name;
    public String icon;
    /** Estación donde se prepara (COCINA, BAR); null = no va a cocina. */
    public String station;
    public int sortOrder;
    public boolean active = true;
    public Instant createdAt = Instant.now();
}
