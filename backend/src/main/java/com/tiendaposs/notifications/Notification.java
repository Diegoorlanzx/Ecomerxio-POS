package com.tiendaposs.notifications;

import jakarta.persistence.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "notifications")
public class Notification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public String type;
    public String severity;
    public String title;
    public String body;
    public String link;
    public String permission;
    public String dedupKey;
    public Instant createdAt = Instant.now();
}
