package com.tiendaposs.accounting;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/** Un mes contable cerrado (o reabierto). */
@Entity
@Table(name = "accounting_periods")
@IdClass(AccountingPeriod.Key.class)
public class AccountingPeriod {
    @Id
    public Long tenantId;
    /** 2026-10 */
    @Id
    public String period;
    public boolean closed = true;
    public Instant closedAt;
    public String closedBy;
    public Instant reopenedAt;
    public String reopenedBy;
    public String reopenReason;

    public static class Key implements Serializable {
        public Long tenantId;
        public String period;

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(tenantId, k.tenantId) && Objects.equals(period, k.period);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tenantId, period);
        }
    }
}
