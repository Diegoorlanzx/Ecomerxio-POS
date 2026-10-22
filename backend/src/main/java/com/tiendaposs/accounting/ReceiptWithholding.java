package com.tiendaposs.accounting;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/** Una retención practicada en una compra (retención en la fuente, ReteIVA o ReteICA). */
@Entity
@Table(name = "receipt_withholdings")
public class ReceiptWithholding {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long receiptId;
    /** RETEFUENTE, RETEIVA o RETEICA. */
    public String kind;
    public BigDecimal base;
    /** Porcentaje: 2.5 = 2,5 %. */
    public BigDecimal rate;
    public BigDecimal amount;
    /** false = propuesta del sistema; true = la revisó una persona. */
    public boolean confirmed;
    public Instant createdAt = Instant.now();
}
