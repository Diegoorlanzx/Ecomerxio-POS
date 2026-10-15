package com.tiendaposs.pos;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "sale_returns")
public class SaleReturn {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long saleId;
    public long number;
    public Long shiftId;
    public String reason;
    public String refundMethod;
    public BigDecimal refundAmount;
    public Long userId;
    public String userName;
    public String authorizedBy;
    public Instant createdAt = Instant.now();
}
