package com.tiendaposs.inventory;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "product_lots")
public class ProductLot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long productId;
    public String lotCode;
    public LocalDate expiryDate;
    public BigDecimal quantity;
    public BigDecimal remaining;
    public Long receiptId;
    public Instant createdAt = Instant.now();
}
