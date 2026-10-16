package com.tiendaposs.serials;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Una unidad con su serial / IMEI. EN_STOCK, VENDIDO o DEFECTUOSO. */
@Entity
@Table(name = "product_serials")
public class ProductSerial {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long productId;
    public String serial;
    public String status = "EN_STOCK";
    public BigDecimal unitCost = BigDecimal.ZERO;
    public Long receiptId;
    public Long saleId;
    public Long saleLineId;
    public Long customerId;
    public String customerName;
    public Instant soldAt;
    public LocalDate warrantyUntil;
    public String notes;
    public Instant createdAt = Instant.now();
    public Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
