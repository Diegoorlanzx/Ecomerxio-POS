package com.tiendaposs.restaurant;

import jakarta.persistence.*;

import java.math.BigDecimal;

/** Opción de un modificador ("Término medio", "Extra queso" con precio). */
@Entity
@Table(name = "modifier_options")
public class ModifierOption {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long groupId;
    public String name;
    public BigDecimal price = BigDecimal.ZERO;
    public int sortOrder;
    public boolean active = true;
}
