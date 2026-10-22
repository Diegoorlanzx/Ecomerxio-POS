package com.tiendaposs.accounting;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/** Reglas de retención de la tienda. Los valores por defecto son una propuesta que confirma el contador. */
@Entity
@Table(name = "accounting_tax_settings")
public class AccountingTaxSettings {
    @Id
    public Long tenantId;
    public BigDecimal uvt = new BigDecimal("52374");
    public boolean retentionAgent;
    public BigDecimal purchasesBaseUvt = new BigDecimal("27");
    public BigDecimal purchasesRateDeclarant = new BigDecimal("2.5");
    public BigDecimal purchasesRateOther = new BigDecimal("3.5");
    public boolean reteivaAgent;
    public BigDecimal reteivaRate = new BigDecimal("15");
    /** Por mil; null = no practica ReteICA. */
    public BigDecimal reteicaPerMil;
    public boolean configured;
    public Instant updatedAt = Instant.now();
}
