package com.tiendaposs.tenant;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "tenants")
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String legalName;
    private String taxId;
    private String taxRegime;
    private String address;
    private String city;
    private String phone;
    private String whatsapp;
    private String email;
    private String logo;
    private String currency = "COP";
    private String timezone = "America/Bogota";
    private String status = "ACTIVE";
    /** Plan de Ecomerxio POS: TIENDA, NEGOCIO o PRO (ver {@link Plans}). */
    private String plan = Plans.DEFAULT;
    private Instant trialEndsAt;
    /** Hasta cuándo está pagada la suscripción (null si nunca ha pagado). */
    private Instant paidUntil;
    /** MENSUAL o ANUAL: periodicidad del último pago. */
    private String billingPeriod;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getLegalName() { return legalName; }
    public void setLegalName(String legalName) { this.legalName = legalName; }
    public String getTaxId() { return taxId; }
    public void setTaxId(String taxId) { this.taxId = taxId; }
    public String getTaxRegime() { return taxRegime; }
    public void setTaxRegime(String taxRegime) { this.taxRegime = taxRegime; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getWhatsapp() { return whatsapp; }
    public void setWhatsapp(String whatsapp) { this.whatsapp = whatsapp; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getLogo() { return logo; }
    public void setLogo(String logo) { this.logo = logo; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPlan() { return plan; }
    public void setPlan(String plan) { this.plan = plan; }
    public Instant getTrialEndsAt() { return trialEndsAt; }
    public void setTrialEndsAt(Instant trialEndsAt) { this.trialEndsAt = trialEndsAt; }
    public Instant getPaidUntil() { return paidUntil; }
    public void setPaidUntil(Instant paidUntil) { this.paidUntil = paidUntil; }
    public String getBillingPeriod() { return billingPeriod; }
    public void setBillingPeriod(String billingPeriod) { this.billingPeriod = billingPeriod; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
