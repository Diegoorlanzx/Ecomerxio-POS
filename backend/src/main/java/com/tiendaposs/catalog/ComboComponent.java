package com.tiendaposs.catalog;

import jakarta.persistence.*;

import java.math.BigDecimal;

/** Un producto que hace parte de un combo, con la cantidad en su unidad base. */
@Entity
@Table(name = "combo_components")
public class ComboComponent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long comboId;
    public Long componentId;
    public BigDecimal quantity;
    /** Merma en receta: % que se pierde al preparar (se descuenta de más del inventario). */
    public BigDecimal wastePct = BigDecimal.ZERO;
    public int sortOrder;
}
