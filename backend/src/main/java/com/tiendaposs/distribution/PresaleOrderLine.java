package com.tiendaposs.distribution;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Línea de un pedido de preventa; deliveredQty se llena al entregar. */
@Entity
@Table(name = "presale_order_lines")
public class PresaleOrderLine {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long orderId;
    public Long presentationId;
    public Long productId;
    public String productName;
    public String presentationName;
    public BigDecimal quantity;
    public BigDecimal unitPrice;
    public BigDecimal deliveredQty;
    public BigDecimal lineTotal = BigDecimal.ZERO;
}
