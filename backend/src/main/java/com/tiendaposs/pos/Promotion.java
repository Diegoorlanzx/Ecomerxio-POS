package com.tiendaposs.pos;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "promotions")
public class Promotion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public String name;
    /** PORCENTAJE, PRECIO_POR_CANTIDAD (N por $X) o LLEVA_PAGA (lleva N, paga M). */
    public String type;
    public Long productId;
    public Long categoryId;
    public BigDecimal percent;
    public BigDecimal minQty;
    public BigDecimal bundleQty;
    public BigDecimal bundlePrice;
    public BigDecimal payQty;
    public java.time.LocalDate startsOn;
    public java.time.LocalDate endsOn;
    public boolean active = true;
    public Instant createdAt = Instant.now();
}
