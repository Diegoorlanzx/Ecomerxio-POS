package com.tiendaposs.purchasing;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "supplier_returns")
public class SupplierReturn {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long supplierId;
    public Long productId;
    public BigDecimal quantity;
    public BigDecimal unitCost;
    public String reason;
    public String userName;
    public Instant createdAt = Instant.now();
}
