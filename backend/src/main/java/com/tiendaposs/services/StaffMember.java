package com.tiendaposs.services;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/** Profesional (estilista, barbero, técnico…): su horario, sus comisiones y qué servicios hace. */
@Entity
@Table(name = "staff_members")
public class StaffMember {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long userId;
    public String name;
    public String phone;
    public String color = "#1e66f5";
    public BigDecimal commissionServicePct = BigDecimal.ZERO;
    public BigDecimal commissionProductPct = BigDecimal.ZERO;
    /** Ids de los servicios que hace, separados por coma; vacío = todos. */
    public String serviceIds;
    public boolean onlineBooking = true;
    public int sortOrder;
    public boolean active = true;
    public Instant createdAt = Instant.now();
}
