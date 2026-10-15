package com.tiendaposs.payments;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "payment_integrations")
public class PaymentIntegration {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public String provider;
    public String publicKey;
    public String secret;
    public boolean active = true;
    public Instant lastEventAt;
    public Instant createdAt = Instant.now();
}
