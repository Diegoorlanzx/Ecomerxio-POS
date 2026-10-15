package com.tiendaposs.pos;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payments")
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long saleId;
    public Long shiftId;
    public String method;
    public BigDecimal amount;
    public BigDecimal received;
    public BigDecimal changeGiven = BigDecimal.ZERO;
    public String reference;
    public String status = "CONFIRMADO";
    public String verifiedBy;
    public Instant verifiedAt;
    public String verificationNote;
    public String providerTxId;
    public Instant reconciledAt;
    /** La misma referencia ya se había usado en otro pago del mismo medio. */
    public boolean duplicateRef;
    public Long userId;
    public String userName;
    public Instant createdAt = Instant.now();
}
