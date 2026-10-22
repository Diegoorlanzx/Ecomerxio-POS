package com.tiendaposs.distribution;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Despacho: un vehículo sale con pedidos (EN_RUTA) y al volver se cierra con el cuadre del dinero (CERRADO). */
@Entity
@Table(name = "dispatches")
public class Dispatch {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public long number;
    public Long routeId;
    public String driverName;
    public String vehicle;
    public String status = "EN_RUTA";
    public String createdBy;
    public Instant createdAt = Instant.now();
    public Instant closedAt;
    public String closedBy;
    public BigDecimal cashExpected;
    public BigDecimal cashCounted;
    public BigDecimal difference;
    public String notes;
}
