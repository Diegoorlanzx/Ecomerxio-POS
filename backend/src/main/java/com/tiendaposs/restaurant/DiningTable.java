package com.tiendaposs.restaurant;

import jakarta.persistence.*;

/** Mesa. Ocupada = tiene una cuenta abierta (o varias, si se dividió la cuenta). */
@Entity
@Table(name = "dining_tables")
public class DiningTable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long areaId;
    public String name;
    public int seats = 4;
    public int sortOrder;
    public boolean active = true;
}
