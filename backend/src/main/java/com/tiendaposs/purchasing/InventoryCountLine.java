package com.tiendaposs.purchasing;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "inventory_count_lines")
public class InventoryCountLine {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long countId;
    public Long productId;
    /** Lo que decía el sistema al iniciar el conteo. */
    public BigDecimal theoretical;
    public BigDecimal counted;
    public BigDecimal unitCost = BigDecimal.ZERO;
    public String countedBy;
    public Instant countedAt;
    public boolean unusual;
}
