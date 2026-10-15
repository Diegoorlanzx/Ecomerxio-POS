package com.tiendaposs.pos;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "customers")
public class Customer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public String name;
    public String docType;
    public String docNumber;
    public String phone;
    public String email;
    public String address;
    public String notes;
    public boolean active = true;
    public Instant createdAt = Instant.now();
}
