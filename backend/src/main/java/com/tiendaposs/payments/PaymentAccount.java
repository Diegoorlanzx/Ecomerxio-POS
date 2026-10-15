package com.tiendaposs.payments;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "payment_accounts")
public class PaymentAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public String method;
    public String label;
    public String holder;
    /** Número de cuenta, celular o llave Bre-B. */
    public String accountRef;
    public String qrImage;
    public boolean active = true;
    public Instant createdAt = Instant.now();
}
