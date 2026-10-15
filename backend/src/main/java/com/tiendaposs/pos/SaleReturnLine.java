package com.tiendaposs.pos;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "sale_return_lines")
public class SaleReturnLine {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long returnId;
    public Long saleLineId;
    public BigDecimal quantity;
    public BigDecimal amount;
    public boolean restock;
    /** BUENO (vuelve al inventario) o DANADO. */
    public String condition = "BUENO";
}
