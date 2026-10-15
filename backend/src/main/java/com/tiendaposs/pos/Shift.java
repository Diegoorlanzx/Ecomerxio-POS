package com.tiendaposs.pos;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "shifts")
public class Shift {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long registerId;
    public long number;
    public Long userId;
    public String userName;
    /** OPEN o CLOSED. */
    public String status = "OPEN";
    public BigDecimal openingCash;
    public Instant openedAt = Instant.now();
    public Instant closedAt;
    public Long closedById;
    public String closedByName;
    public BigDecimal expectedCash;
    public BigDecimal countedCash;
    public BigDecimal difference;
    /** Conteo por denominación, "50000x3,20000x5,...". */
    public String countDetail;
    public String closeNotes;
    public String differenceAuthorizedBy;
}
