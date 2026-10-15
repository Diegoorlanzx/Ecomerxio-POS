package com.tiendaposs.payments;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "reconciliation_rows")
public class ReconciliationRow {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long reconciliationId;
    public LocalDate rowDate;
    public BigDecimal amount;
    public String reference;
    public String description;
    /** CONCILIADO, PROBABLE, INCONSISTENTE, SIN_REGISTRO, DUPLICADO. */
    public String status;
    public Long paymentId;
    public String note;
}
