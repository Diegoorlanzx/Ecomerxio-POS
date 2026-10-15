package com.tiendaposs.payments;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "payment_events")
public class PaymentEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long paymentId;
    public String type;
    public String detail;
    public String userName;
    public Instant createdAt = Instant.now();
}
