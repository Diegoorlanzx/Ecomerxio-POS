package com.tiendaposs.remissions;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "remission_lines")
public class RemissionLine {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long remissionId;
    /** Nulo en una línea libre (flete, servicio) que no mueve inventario. */
    public Long productId;
    public Long presentationId;
    public String description;
    public String presentationName;
    public BigDecimal factor = BigDecimal.ONE;
    public BigDecimal quantity;
    /** Lo que el cliente devolvió después de entregada: volvió al inventario y no se factura. */
    public BigDecimal returnedQty = BigDecimal.ZERO;
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

    /** Lo que queda por facturar (entregado menos devuelto). */
    public BigDecimal pending() {
        return quantity.subtract(returnedQty);
    }
}
