package com.tiendaposs.distribution;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Lista de precios (tienda, mayorista, especial…). Lo que no tenga precio propio lleva el descuento general. */
@Entity
@Table(name = "price_lists")
public class PriceList {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public String name;
    public BigDecimal discountPct = BigDecimal.ZERO;
    public boolean active = true;
    public Instant createdAt = Instant.now();
}
