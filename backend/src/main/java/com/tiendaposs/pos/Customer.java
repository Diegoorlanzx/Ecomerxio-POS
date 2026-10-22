package com.tiendaposs.pos;

import jakarta.persistence.*;

import java.math.BigDecimal;

import java.time.Instant;

@Entity
@Table(name = "customers")
public class Customer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public String name;
    public String docType;
    public String docNumber;
    public String phone;
    public String email;
    public String address;
    public String notes;
    public boolean active = true;
    /** Cupo de fiado; nulo = el de la tienda. */
    public BigDecimal creditLimit;
    public Integer creditDays;
    public boolean creditBlocked;
    // Datos para facturarle a su nombre (Fase 13A)
    public String dv;
    public String personType;
    public String invoiceEmail;
    public String fiscalResponsibilities;
    /** Código DANE del municipio. */
    public String municipalityCode;
    /** Responsable de IVA (null = no se sabe; se factura como "no aplica"). */
    public Boolean ivaResponsible;
    /** SIEMPRE, NUNCA o PREGUNTAR: si se le factura a su nombre (Fase 16A). */
    public String invoicePreference = "PREGUNTAR";
    // Distribución (Fase 12)
    public Long priceListId;
    public Long routeId;
    public int routeOrder;
    public Instant createdAt = Instant.now();
}
