package com.tiendaposs.restaurant;

import jakarta.persistence.*;

/** Salón o zona del restaurante (Terraza, Segundo piso). */
@Entity
@Table(name = "dining_areas")
public class DiningArea {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public String name;
    public int sortOrder;
    public boolean active = true;
}
