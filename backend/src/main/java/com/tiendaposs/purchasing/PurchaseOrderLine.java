package com.tiendaposs.purchasing;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "purchase_order_lines")
public class PurchaseOrderLine {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long orderId;
    public Long productId;
    public String packName;
    public BigDecimal packFactor;
    public BigDecimal packsOrdered;
    public BigDecimal costPerPack;
    /** En unidades base. */
    public BigDecimal unitsReceived = BigDecimal.ZERO;
    public BigDecimal unitsRejected = BigDecimal.ZERO;
    /** Por qué se pide (sugerencia) o nota de la línea. */
    public String reason;

    public BigDecimal unitsOrdered() {
        return packsOrdered.multiply(packFactor);
    }
}
