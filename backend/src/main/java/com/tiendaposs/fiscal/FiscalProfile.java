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
    /** Persona natural: nombres y apellidos por separado (el proveedor los pide así). */
    public String firstNames;
    public String lastNames;
    public String tradeName;
    /** Matrícula mercantil (Cámara de Comercio), opcional. */
    public String registrationCode;
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
    /** OBLIGADO: toda venta lleva documento. A_PEDIDO: solo cuando el cliente lo pide (Fase 16A). */
    public String invoicingMode = "OBLIGADO";
    /** Fase 16B: fecha de corte (primera activación; se vuelve a fijar al pasar a producción). */
    public java.time.Instant fiscalStartAt;
    public String fiscalStartEnvironment;
    /** Ocultar de Ventas, Analítica y Dashboard las ventas anteriores al corte (no se borran). */
    public boolean hidePreCutoff = true;
    public Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
