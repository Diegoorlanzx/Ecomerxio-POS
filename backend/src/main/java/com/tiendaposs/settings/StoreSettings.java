package com.tiendaposs.settings;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** Ajustes del punto de venta de una tienda. Campos públicos: la entidad no tiene relaciones ni proxies. */
@Entity
@Table(name = "store_settings")
public class StoreSettings {

    @Id
    public Long tenantId;
    public boolean pricesIncludeTax = true;
    public BigDecimal maxDiscountPct = BigDecimal.TEN;
    public boolean allowNegativeStock;
    public boolean requireOpenShift = true;
    public int openAccountsLimit = 20;
    public String costMethod = "PROMEDIO";
    public String paymentMethods = "EFECTIVO,TRANSFERENCIA,NEQUI,DAVIPLATA,BREB,TARJETA";
    public String receiptHeader;
    public String receiptFooter = "¡Gracias por su compra!";
    public int receiptWidthMm = 80;
    public boolean receiptShowTax = true;
    public String defaultTaxName = "IVA";
    public BigDecimal defaultTaxRate = BigDecimal.ZERO;
    public String cashDenominations = "100000,50000,20000,10000,5000,2000,1000,500,200,100,50";
    public Instant updatedAt = Instant.now();

    public List<String> paymentMethodList() {
        return Arrays.stream(paymentMethods.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
