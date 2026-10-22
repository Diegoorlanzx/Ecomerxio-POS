package com.tiendaposs.purchasing;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "suppliers")
public class Supplier {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public String name;
    public String legalName;
    public String taxId;
    /** Para el documento soporte (Fase 13E). */
    public String docType = "NIT";
    public String dv;
    public String personType;
    public String municipalityCode;
    /** false = no está obligado a facturar: sus compras llevan documento soporte. */
    public boolean invoicesElectronically = true;
    public String contactName;
    public String phone;
    public String whatsapp;
    public String email;
    public String address;
    public String city;
    public String paymentTerms;
    public int creditDays;
    /** Días habituales de pedido, p. ej. "LUN,JUE". */
    public String orderDays;
    public String deliveryDays;
    /** Días que tarda en entregar (configurado). Nulo = se usa el medido o el de la tienda. */
    public Integer leadTimeDays;
    public String notes;
    public boolean active = true;
    public Instant createdAt = Instant.now();
}
