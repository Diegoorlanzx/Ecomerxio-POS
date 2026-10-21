package com.tiendaposs.restaurant;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Una línea de comanda en una estación (cocina o bar): qué preparar, para qué mesa y en qué va.
 * PENDIENTE → PREPARANDO → LISTO → ENTREGADO. Un ANULAR avisa que el mesero quitó algo ya pedido.
 */
@Entity
@Table(name = "kitchen_tickets")
public class KitchenTicket {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long saleId;
    public Long saleLineId;
    public int comanda;
    public String station;
    public String tableLabel;
    public String waiterName;
    public String productName;
    public BigDecimal quantity;
    public String modifiers;
    public String note;
    public String kind = "PEDIDO";
    public String status = "PENDIENTE";
    public Instant createdAt = Instant.now();
    public Instant startedAt;
    public Instant readyAt;
    public Instant deliveredAt;
}
