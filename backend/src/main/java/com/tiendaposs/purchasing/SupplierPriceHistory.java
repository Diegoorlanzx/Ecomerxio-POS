package com.tiendaposs.purchasing;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "supplier_price_history")
public class SupplierPriceHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long supplierId;
    public Long productId;
    public BigDecimal oldCostPerUnit;
    public BigDecimal newCostPerUnit;
    public String source;
    public Long referenceId;
    public String userName;
    public Instant createdAt = Instant.now();
}
