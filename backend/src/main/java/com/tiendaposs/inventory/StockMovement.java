package com.tiendaposs.inventory;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "stock_movements")
public class StockMovement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long productId;
    public String type;
    /** Con signo, en unidades base: positivo entra, negativo sale. */
    public BigDecimal quantity;
    public BigDecimal stockAfter;
    public BigDecimal unitCost;
    public String referenceType;
    public Long referenceId;
    public String reason;
    public Long userId;
    public String userName;
    public Instant createdAt = Instant.now();
}
