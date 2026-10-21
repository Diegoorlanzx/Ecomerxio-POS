package com.tiendaposs.quotes;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Cotización (oferta comercial) o prefactura (revisión de datos antes de facturar). Ninguna de las dos es factura. */
@Entity
@Table(name = "quotes")
public class Quote {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    /** COTIZACION o PREFACTURA. */
    public String kind;
    public long number;
    public String status;
    public Long customerId;
    public String customerName;
    public String customerDocType;
    public String customerDoc;
    public String customerDv;
    public String customerPersonType;
    public String customerEmail;
    public String customerPhone;
    public String customerAddress;
    public String customerMunicipality;
    public LocalDate issueDate;
    public LocalDate validUntil;
    public String notes;
    public String terms;
    public BigDecimal saleDiscountAmount = BigDecimal.ZERO;
    public BigDecimal subtotal = BigDecimal.ZERO;
    public BigDecimal discountTotal = BigDecimal.ZERO;
    public BigDecimal taxTotal = BigDecimal.ZERO;
    public BigDecimal total = BigDecimal.ZERO;
    public boolean pricesIncludeTax = true;
    public Long sourceId;
    public Long saleId;
    public String publicToken;
    public String createdBy;
    public Instant createdAt = Instant.now();
    public Instant updatedAt = Instant.now();
    public Instant sentAt;
    public Instant decidedAt;
    public String decidedBy;
    public String decisionNote;
    public String decisionIp;
    public String clientCheck;
    public String clientCheckNote;
    public Instant clientCheckAt;
    public String approvedBy;
    public Instant approvedAt;
    public String approvedWarnings;
    public Instant cancelledAt;
    public String cancelReason;
    @Version
    public int version;

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public boolean isQuote() {
        return QuoteService.COTIZACION.equals(kind);
    }

    /** COT-0007 / PF-0003. */
    public String code() {
        return (isQuote() ? "COT-" : "PF-") + String.format("%04d", number);
    }
}
