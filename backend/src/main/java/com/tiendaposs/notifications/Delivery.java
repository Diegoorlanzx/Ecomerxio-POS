package com.tiendaposs.notifications;

import jakarta.persistence.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "notification_deliveries")
public class Delivery {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long notificationId;
    public Long tenantId;
    public String channel;
    public String status;
    public String detail;
    public Instant createdAt = Instant.now();
}
