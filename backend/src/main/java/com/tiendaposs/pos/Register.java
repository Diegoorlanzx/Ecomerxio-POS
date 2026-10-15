package com.tiendaposs.pos;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "registers")
public class Register {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public String name;
    /** Sucursal de la caja (hoy todas en la principal). */
    public Long branchId;
    public boolean active = true;
    public Instant createdAt = Instant.now();
}
