package com.tiendaposs.online;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "delivery_zones")
public class DeliveryZone {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public String name;
    public BigDecimal fee = BigDecimal.ZERO;
    public BigDecimal minOrder = BigDecimal.ZERO;
    public boolean active = true;
    public int sortOrder;
}
