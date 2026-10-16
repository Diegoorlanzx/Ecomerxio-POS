package com.tiendaposs.online;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "online_order_lines")
public class OnlineOrderLine {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long orderId;
    public Long productId;
    public Long presentationId;
    public String productName;
    public String presentationName;
    public BigDecimal factor;
    public BigDecimal quantity;
    public BigDecimal unitPrice;
    public BigDecimal lineTotal;
    public int sortOrder;
}
