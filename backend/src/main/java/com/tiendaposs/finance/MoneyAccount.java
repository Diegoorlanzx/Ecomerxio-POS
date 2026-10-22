package com.tiendaposs.finance;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/** Banco, billetera o datáfono con saldo (Fase 15). Se define por los medios de pago que caen en ella. */
@Entity
@Table(name = "money_accounts")
public class MoneyAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public String name;
    /** BANCO, BILLETERA u OTRA. */
    public String kind;
    /** CSV de medios de pago: TRANSFERENCIA,TARJETA… */
    public String methods;
    public BigDecimal openingBalance = BigDecimal.ZERO;
    public LocalDate openingDate;
    public boolean active = true;
    public Instant createdAt = Instant.now();

    public List<String> methodList() {
        return methods == null || methods.isBlank() ? List.of() : Arrays.stream(methods.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
