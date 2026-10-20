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
    /** Venta pagada; null si el pago es un abono de fiado (ver creditEntryId). */
    public Long saleId;
    /** Abono de fiado que este pago respalda (abonos por medios digitales, para verificarlos). */
    public Long creditEntryId;
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
