package com.tiendaposs.online;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/** Unidades base apartadas de un producto por un pedido aceptado. */
@Entity
@Table(name = "online_reservations")
public class OnlineReservation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long orderId;
    public Long productId;
    public BigDecimal quantity;
}
