package com.tiendaposs.restaurant;

import jakarta.persistence.*;

/** Grupo de modificadores ("Término de la carne", "Adiciones"): cuántas opciones se pueden o se deben elegir. */
@Entity
@Table(name = "modifier_groups")
public class ModifierGroup {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public String name;
    public int minSelect;
    public int maxSelect = 1;
    public int sortOrder;
    public boolean active = true;
}
