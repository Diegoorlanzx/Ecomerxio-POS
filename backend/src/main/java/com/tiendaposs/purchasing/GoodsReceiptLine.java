package com.tiendaposs.purchasing;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "goods_receipt_lines")
public class GoodsReceiptLine {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long receiptId;
    public Long orderLineId;
    public Long productId;
    public BigDecimal unitsReceived;
    public BigDecimal unitsRejected = BigDecimal.ZERO;
    /** FALTANTE, INCORRECTO o DANADO. */
    public String rejectReason;
    public BigDecimal unitCost;
    public String lotCode;
    public LocalDate expiryDate;
    public String serials;
}
