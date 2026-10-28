package com.tiendaposs.catalog;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "products")
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long categoryId;
    public String sku;
    public String name;
    public String description;
    public String brand;
    public String photo;
    public String unit = "UNIDAD";
    public boolean trackStock = true;
    /** En unidades base. */
    public BigDecimal stock = BigDecimal.ZERO;
    /** Costo por unidad base (promedio ponderado o último, según la configuración). */
    public BigDecimal cost = BigDecimal.ZERO;
    public BigDecimal lastCost = BigDecimal.ZERO;
    public BigDecimal stockMin = BigDecimal.ZERO;
    public BigDecimal stockMax = BigDecimal.ZERO;
    public BigDecimal safetyStock = BigDecimal.ZERO;
    public String taxName = "IVA";
    public BigDecimal taxRate = BigDecimal.ZERO;
    public BigDecimal fixedTax = BigDecimal.ZERO;
    /** Clasificación tributaria para la factura electrónica: EXENTO o EXCLUIDO cuando la tarifa es 0 (con tarifa > 0 es GRAVADO). */
    public String taxClass;
    /** ACTIVE o DISCONTINUED. "Agotado" se deduce del stock. */
    public String status = "ACTIVE";
    public boolean favorite;
    /** Controla lotes y fechas de vencimiento (se venden primero los que vencen antes). */
    public boolean trackLots;
    /** SIMPLE, PADRE (agrupa variantes: no se vende), VARIANTE (hija de un padre) o COMBO (descuenta sus componentes). */
    public String productType = "SIMPLE";
    /** Estación de preparación propia (si no, la de su categoría). */
    public String station;
    /** Servicio (Fase 11): duración en minutos; null = no es un servicio. */
    public Integer serviceMinutes;
    /** Comisión propia del servicio; null = la del profesional. */
    public BigDecimal commissionPct;
    public Long parentId;
    /** "M / Rojo" en una variante. */
    public String variantLabel;
    /** JSON {"Talla":"M","Color":"Rojo"} en una variante. */
    public String variantValues;
    /** JSON [{"name":"Talla","values":["S","M"]}] en el padre. */
    public String variantOptions;
    /** Cada unidad lleva serial / IMEI (stock = seriales disponibles). */
    public boolean trackSerials;
    public int warrantyMonths;
    /** Código del producto en la báscula. */
    public String plu;
    /** Se muestra en la tienda en línea. */
    public boolean onlineVisible = true;
    /** Droguería (módulo DROGUERIA): registro sanitario, principio activo, si exige fórmula y si es de control especial. */
    public String invimaCode;
    public String activeIngredient;
    public boolean requiresPrescription;
    public boolean controlled;
    /** Agro (módulo AGRO): registro ICA. Licorera/tienda (módulo MAYORES_EDAD): solo se vende a mayores de edad. */
    public String icaCode;
    public boolean adultsOnly;
    /** Gimnasio (módulo MEMBRESIAS): días de membresía que da este producto al venderse a un cliente. */
    public Integer membershipDays;
    /** Unidades base apartadas por pedidos en línea aceptados (no se pueden vender en mostrador). */
    public java.math.BigDecimal reserved = java.math.BigDecimal.ZERO;
    public Instant createdAt = Instant.now();
    public Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public boolean sellable() {
        return !"PADRE".equals(productType);
    }

    /** AGOTADO, BAJO, NORMAL o EXCESO (para chips y alertas). */
    public String stockStatus() {
        if (!trackStock) return "NORMAL";
        if (stock.signum() <= 0) return "AGOTADO";
        if (stockMin.signum() > 0 && stock.compareTo(stockMin) <= 0) return "BAJO";
        if (stockMax.signum() > 0 && stock.compareTo(stockMax) > 0) return "EXCESO";
        return "NORMAL";
    }
}
