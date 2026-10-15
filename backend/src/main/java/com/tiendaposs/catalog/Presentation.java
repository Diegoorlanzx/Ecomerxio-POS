package com.tiendaposs.catalog;

import jakarta.persistence.*;

import java.math.BigDecimal;

/** Una forma de vender el producto: unidad, six pack, caja x24. {@code factor} = unidades base que contiene. */
@Entity
@Table(name = "product_presentations")
public class Presentation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long productId;
    public String name;
    public BigDecimal factor = BigDecimal.ONE;
    public String barcode;
    public BigDecimal price;
    public BigDecimal minPrice = BigDecimal.ZERO;
    public boolean isBase;
    public boolean active = true;
    public int sortOrder;
}
