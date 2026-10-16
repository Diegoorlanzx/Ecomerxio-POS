package com.tiendaposs.pos;

import jakarta.persistence.*;

import java.math.BigDecimal;

/** Lo que salió del inventario por cada unidad de una línea de combo (en unidades base del componente). */
@Entity
@Table(name = "sale_line_components")
public class SaleLineComponent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long saleLineId;
    public Long productId;
    public String productName;
    public BigDecimal quantity;
    public BigDecimal unitCost = BigDecimal.ZERO;
}
