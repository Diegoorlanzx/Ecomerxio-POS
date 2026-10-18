package com.tiendaposs.fiscal;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

/** Datos del comercio como emisor de documentos electrónicos (uno por tienda). */
@Entity
@Table(name = "tenant_fiscal_profiles")
public class FiscalProfile {
    @Id
    public Long tenantId;
    public String personType;
    public String idType = "NIT";
    public String idNumber;
    public String dv;
    public String legalName;
    public String tradeName;
    public Boolean ivaResponsible;
    /** CSV de códigos (FiscalCodes.RESPONSIBILITIES). */
    public String responsibilities;
    public String ciiu;
    public String address;
    public String municipalityCode;
    public String municipalityName;
    public String departmentName;
    public String postalCode;
    public String invoicingEmail;
    public String phone;
    public boolean registeredAsIssuer;
    public LocalDate issuerRegisteredOn;
    public String providerName;
    public boolean providerAuthorized;
    public String environment = "PRUEBAS";
    /** La tienda emite documentos electrónicos en cada venta (Fase 13B). */
    public boolean invoicingEnabled;
    public Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
