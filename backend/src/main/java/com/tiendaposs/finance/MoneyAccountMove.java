package com.tiendaposs.finance;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Consignación, retiro, traslado, comisión o ajuste de una cuenta de dinero (Fase 15). */
@Entity
@Table(name = "money_account_moves")
public class MoneyAccountMove {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long accountId;
    public Long toAccountId;
    /** CONSIGNACION, RETIRO, TRASLADO, COMISION o AJUSTE. */
    public String type;
    public BigDecimal amount;
    public LocalDate moveDate;
    public String note;
    public String createdBy;
    public Instant createdAt = Instant.now();
    public Instant voidedAt;
    public String voidedBy;
}
