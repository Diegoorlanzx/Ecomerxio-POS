package com.tiendaposs.catalog;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "price_history")
public class PriceHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long productId;
    public Long presentationId;
    /** PRECIO, PRECIO_MINIMO o COSTO. */
    public String field;
    public BigDecimal oldValue;
    public BigDecimal newValue;
    public String reason;
    public Long userId;
    public String userName;
    public Instant createdAt = Instant.now();
}
