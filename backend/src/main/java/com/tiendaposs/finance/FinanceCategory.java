package com.tiendaposs.finance;

import jakarta.persistence.*;


@Entity
@Table(name = "finance_categories")
public class FinanceCategory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    /** GASTO o INGRESO. */
    public String kind;
    public String name;
    public boolean fixed;
    public boolean active = true;
}
