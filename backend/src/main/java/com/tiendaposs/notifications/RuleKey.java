package com.tiendaposs.notifications;

import jakarta.persistence.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public class RuleKey implements Serializable {
    public Long tenantId;
    public String type;

    public RuleKey() {
    }

    public RuleKey(Long tenantId, String type) {
        this.tenantId = tenantId;
        this.type = type;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RuleKey k && Objects.equals(k.tenantId, tenantId) && Objects.equals(k.type, type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, type);
    }
}
