package com.tiendaposs.services;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/** Franja de trabajo de un profesional (1 = lunes … 7 = domingo). */
@Entity
@Table(name = "staff_hours")
public class StaffHour {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long staffId;
    public int weekday;
    public LocalTime startTime;
    public LocalTime endTime;
}
