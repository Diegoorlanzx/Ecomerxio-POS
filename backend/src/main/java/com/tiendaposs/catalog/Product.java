package com.tiendaposs.catalog;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "products")
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long categoryId;
    public String sku;
    public String name;
    public String description;
    public String brand;
    public String photo;
    public String unit = "UNIDAD";
    public boolean trackStock = true;
    /** En unidades base. */
    public BigDecimal stock = BigDecimal.ZERO;
    /** Costo por unidad base (promedio ponderado o último, según la configuración). */
    public BigDecimal cost = BigDecimal.ZERO;
    public BigDecimal lastCost = BigDecimal.ZERO;
    public BigDecimal stockMin = BigDecimal.ZERO;
    public BigDecimal stockMax = BigDecimal.ZERO;
    public BigDecimal safetyStock = BigDecimal.ZERO;
    public String taxName = "IVA";
    public BigDecimal taxRate = BigDecimal.ZERO;
    public BigDecimal fixedTax = BigDecimal.ZERO;
    /** ACTIVE o DISCONTINUED. "Agotado" se deduce del stock. */
    public String status = "ACTIVE";
    public boolean favorite;
    public Instant createdAt = Instant.now();
    public Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    /** AGOTADO, BAJO, NORMAL o EXCESO (para chips y alertas). */
    public String stockStatus() {
        if (!trackStock) return "NORMAL";
        if (stock.signum() <= 0) return "AGOTADO";
        if (stockMin.signum() > 0 && stock.compareTo(stockMin) <= 0) return "BAJO";
        if (stockMax.signum() > 0 && stock.compareTo(stockMax) > 0) return "EXCESO";
        return "NORMAL";
    }
}
