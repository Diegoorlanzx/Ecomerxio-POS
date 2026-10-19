package com.tiendaposs.accounting;

import jakarta.persistence.*;

import java.time.Instant;

/** Cuenta del plan de cuentas (PUC) de una tienda. */
@Entity
@Table(name = "accounting_accounts")
public class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public String code;
    public String name;
    /** DEBITO o CREDITO: hacia dónde crece su saldo. */
    public String nature;
    public boolean active = true;
    public Instant createdAt = Instant.now();
}
