package com.tiendaposs.pos;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "sale_lines")
public class SaleLine {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long saleId;
    public Long tenantId;
    public Long productId;
    public Long presentationId;
    public String productName;
    public String presentationName;
    public BigDecimal factor;
    public BigDecimal quantity;
    /** Precio de lista de la presentación al momento de agregar. */
    public BigDecimal listPrice;
    /** Precio cobrado por unidad (puede ser un precio especial autorizado). */
    public BigDecimal unitPrice;
    public String priceAuthorizedBy;
    public BigDecimal discountPct = BigDecimal.ZERO;
    public Long promoId;
    public String promoName;
    public BigDecimal gross = BigDecimal.ZERO;
    public BigDecimal discount = BigDecimal.ZERO;
    public String taxName;
    public BigDecimal taxRate = BigDecimal.ZERO;
    public BigDecimal fixedTax = BigDecimal.ZERO;
    public BigDecimal tax = BigDecimal.ZERO;
    public BigDecimal lineTotal = BigDecimal.ZERO;
    /** Costo de la presentación (costo base x factor) al momento de cobrar. */
    public BigDecimal unitCost = BigDecimal.ZERO;
    public BigDecimal returnedQty = BigDecimal.ZERO;
    public String addedBy;
    /** Seriales elegidos (separados por coma) en productos con serial. */
    public String serials;
    /** Modificadores elegidos ("Término medio · Extra queso"); su precio ya va en unitPrice. */
    public String modifiers;
    /** Nota para la cocina ("sin hielo"). */
    public String kitchenNote;
    /** Cuánto de la línea ya se mandó a cocina/bar. */
    public BigDecimal sentQty = BigDecimal.ZERO;
    /** Profesional de esta línea si no es el de la venta. */
    public Long staffId;
    /** Precio ya pactado (pedido en línea): no se le aplican promociones. */
    @Transient
    public boolean noPromo;
    public int sortOrder;
}
