package com.tiendaposs.purchasing;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "supplier_products")
public class SupplierProduct {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long supplierId;
    public Long productId;
    /** Cómo lo vende el proveedor: "Caja x24" con packFactor 24 unidades base. */
    public String packName = "Unidad";
    public BigDecimal packFactor = BigDecimal.ONE;
    public BigDecimal costPerPack = BigDecimal.ZERO;
    public BigDecimal minPacks = BigDecimal.ONE;
    public BigDecimal multiplePacks = BigDecimal.ONE;
    public String supplierSku;
    public boolean isPrimary;
    public Instant updatedAt = Instant.now();

    public BigDecimal unitCost() {
        return packFactor.signum() == 0 ? costPerPack : costPerPack.divide(packFactor, 4, java.math.RoundingMode.HALF_UP);
    }
}
