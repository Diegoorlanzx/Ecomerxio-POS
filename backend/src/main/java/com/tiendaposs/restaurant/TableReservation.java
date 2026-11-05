package com.tiendaposs.restaurant;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Reserva de mesa. PENDIENTE → CONFIRMADA → LLEGO (se abre la mesa) o NO_LLEGO; CANCELADA si el cliente avisa.
 * La mesa es opcional: se puede reservar "para 6 a las 8" y asignar la mesa cuando lleguen.
 */
@Entity
@Table(name = "table_reservations")
public class TableReservation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long tableId;
    public Long customerId;
    public String name;
    public String phone;
    public int people = 2;
    public Instant reservedAt;
    public int durationMin = 90;
    public String notes;
    public String status = "PENDIENTE";
    public Long saleId;
    public String createdBy;
    public Instant createdAt = Instant.now();
    public Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
