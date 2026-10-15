package com.tiendaposs.notifications;

import jakarta.persistence.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "notification_rules")
@IdClass(RuleKey.class)
public class Rule {
    @Id
    public Long tenantId;
    @Id
    public String type;
    public boolean enabled = true;
    public BigDecimal threshold;
    public boolean email;
    public boolean webhook;
    public boolean whatsapp;
}
