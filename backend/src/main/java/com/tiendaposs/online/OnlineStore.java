package com.tiendaposs.online;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/** Vitrina en línea de una tienda (una por tienda). */
@Entity
@Table(name = "online_stores")
public class OnlineStore {
    @Id
    public Long tenantId;
    public boolean enabled;
    public String slug;
    /** PEDIDOS o CARTA (solo ver). */
    public String mode = "PEDIDOS";
    public String welcome;
    public String whatsapp;
    public boolean showStock;
    public boolean deliveryEnabled = true;
    public boolean pickupEnabled = true;
    public BigDecimal minOrder = BigDecimal.ZERO;
    public String payMethods = "CONTRA_ENTREGA,NEQUI,TRANSFERENCIA";
    public String schedule;
    public boolean paused;
    public String pausedMessage;
    public int prepMinutes = 30;
    public boolean showBadge = true;
    public Instant createdAt = Instant.now();
    public Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public java.util.List<String> payMethodList() {
        return java.util.Arrays.stream(payMethods.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
