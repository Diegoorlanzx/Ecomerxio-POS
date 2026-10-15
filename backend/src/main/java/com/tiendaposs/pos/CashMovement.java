package com.tiendaposs.pos;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "cash_movements")
public class CashMovement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long shiftId;
    /** INGRESO, RETIRO, GASTO, AJUSTE_ENTRADA, AJUSTE_SALIDA. */
    public String type;
    public BigDecimal amount;
    public String reason;
    public String category;
    public Long userId;
    public String userName;
    public String authorizedBy;
    public Instant createdAt = Instant.now();
}
