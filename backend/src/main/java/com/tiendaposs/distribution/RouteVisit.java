package com.tiendaposs.distribution;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Visita del vendedor a un cliente: PEDIDO, NO_PIDIO o CERRADO. */
@Entity
@Table(name = "route_visits")
public class RouteVisit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long routeId;
    public Long customerId;
    public Long sellerUserId;
    public String sellerName;
    public LocalDate visitDate;
    public String result;
    public String reason;
    public Long orderId;
    public String clientRef;
    public Instant createdAt = Instant.now();
}
