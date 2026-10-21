package com.tiendaposs.quotes;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "quote_lines")
public class QuoteLine {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long quoteId;
    /** Nulo en una línea libre (servicio o trabajo a medida que no está en el catálogo). */
    public Long productId;
    public Long presentationId;
    public String description;
    public String presentationName;
    public BigDecimal factor = BigDecimal.ONE;
    public BigDecimal quantity;
    public BigDecimal unitPrice;
    public BigDecimal discountPct = BigDecimal.ZERO;
    public String taxName;
    public BigDecimal taxRate = BigDecimal.ZERO;
    public BigDecimal fixedTax = BigDecimal.ZERO;
    public BigDecimal gross = BigDecimal.ZERO;
    public BigDecimal discount = BigDecimal.ZERO;
    public BigDecimal tax = BigDecimal.ZERO;
    public BigDecimal lineTotal = BigDecimal.ZERO;
    public int sortOrder;
}
