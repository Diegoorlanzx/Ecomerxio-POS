package com.tiendaposs.notifications;

import jakarta.persistence.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "notification_channels")
public class Channels {
    @Id
    public Long tenantId;
    public String emailTo;
    public String webhookUrl;
    public String webhookSecret;
    public String whatsappPhoneId;
    public String whatsappToken;
    public String whatsappTo;
    public int dailyReportHour = 21;
    public Instant lastScanAt;
    public Instant updatedAt = Instant.now();
}
