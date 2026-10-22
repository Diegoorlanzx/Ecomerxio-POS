package com.tiendaposs.distribution;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Ruta de visitas: su vendedor, los días que se visita y la comisión del vendedor. */
@Entity
@Table(name = "routes")
public class SalesRoute {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public String name;
    public Long sellerUserId;
    public String sellerName;
    /** 1 = lunes … 7 = domingo, separados por coma. */
    public String visitDays = "";
    public BigDecimal commissionPct = BigDecimal.ZERO;
    public boolean active = true;
    public Instant createdAt = Instant.now();
}
