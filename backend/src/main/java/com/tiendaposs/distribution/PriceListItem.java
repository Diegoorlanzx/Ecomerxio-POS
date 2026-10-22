package com.tiendaposs.distribution;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Precio de una presentación en una lista desde cierta cantidad (descuento por volumen). */
@Entity
@Table(name = "price_list_items")
public class PriceListItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long listId;
    public Long presentationId;
    public BigDecimal minQty = BigDecimal.ONE;
    public BigDecimal price;
}
