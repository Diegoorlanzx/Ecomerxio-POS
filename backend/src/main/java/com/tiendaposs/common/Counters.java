package com.tiendaposs.common;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

/** Consecutivos por tienda (número de venta, de turno, de devolución...). Atómico y sin huecos por concurrencia. */
@Component
public class Counters {

    private final EntityManager em;

    public Counters(EntityManager em) {
        this.em = em;
    }

    public long next(Long tenantId, String name) {
        Object v = em.createNativeQuery("""
                        INSERT INTO tenant_counters (tenant_id, name, value) VALUES (?1, ?2, 1)
                        ON CONFLICT (tenant_id, name) DO UPDATE SET value = tenant_counters.value + 1
                        RETURNING value""")
                .setParameter(1, tenantId).setParameter(2, name).getSingleResult();
        return ((Number) v).longValue();
    }
}
