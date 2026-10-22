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
    /** Fase 16C: qué muestra el recibo por tipo de comprobante (JSON, ver ReceiptConfig). */
    @Column(columnDefinition = "text")
    public String receiptConfig;
    public String defaultTaxName = "IVA";
    public BigDecimal defaultTaxRate = BigDecimal.ZERO;
    public String cashDenominations = "100000,50000,20000,10000,5000,2000,1000,500,200,100,50";
    // Parámetros de inventario y compras (Fase 2)
    public int analysisDays = 30;
    public int noRotationDays = 30;
    public int excessDays = 90;
    public int expiryAlertDays = 15;
    public int defaultLeadDays = 3;
    public int reviewDays = 7;
    public BigDecimal unusualDiffPct = BigDecimal.TEN;
    public BigDecimal unusualDiffValue = BigDecimal.valueOf(50000);
    @Column(name = "abc_a_pct")
    public BigDecimal abcAPct = BigDecimal.valueOf(80);
    @Column(name = "abc_b_pct")
    public BigDecimal abcBPct = BigDecimal.valueOf(95);
    public BigDecimal purchaseBudget = BigDecimal.ZERO;
    // Pagos y fiado (Fase 3)
    public boolean verifyDigitalPayments = true;
    public boolean creditEnabled = true;
    /** Propina voluntaria sugerida en el cobro (restaurante). */
    public boolean tipsEnabled = true;
    public BigDecimal tipSuggestedPct = BigDecimal.TEN;
    /** Los clientes pueden pedir cita desde la tienda en línea (Fase 11). */
    public boolean bookingEnabled;
    public int bookingSlotMinutes = 30;
    public BigDecimal defaultCreditLimit = BigDecimal.valueOf(200000);
    public int defaultCreditDays = 15;
    // Analítica y rentabilidad
    public BigDecimal decliningPct = BigDecimal.valueOf(20);
    public BigDecimal lowMarginPct = BigDecimal.valueOf(15);
    public BigDecimal highMarginPct = BigDecimal.valueOf(30);
    public int rankingSize = 10;
    public boolean allocateExpenses = true;
    public boolean autoBackup = true;
    public boolean aiEnabled = true;
    public int aiDailyLimit = 60;
    // Perfil del negocio y módulos (Fase 8)
    public String businessType = "TIENDA";
    public String modules = "LOTES,CUENTAS_ABIERTAS,COMPRAS";
    public String scalePrefixes = "20,21,22,23,24,25,26,27,28,29";
    public String scaleMode = "PESO";
    public int scalePluDigits = 5;
    public Instant updatedAt = Instant.now();

    /** Módulos activos; FIADO sale de creditEnabled. */
    public List<String> moduleList() {
        List<String> out = new java.util.ArrayList<>(Arrays.stream(modules.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList());
        if (creditEnabled) out.add("FIADO");
        return out;
    }

    public boolean hasModule(String module) {
        return moduleList().contains(module);
    }

    public List<String> paymentMethodList() {
        return Arrays.stream(paymentMethods.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
