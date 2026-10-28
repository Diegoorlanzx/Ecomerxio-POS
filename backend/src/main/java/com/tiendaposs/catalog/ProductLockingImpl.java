package com.tiendaposs.catalog;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

import java.util.Optional;

/**
 * Bloquea la fila del producto (SELECT … FOR UPDATE) y SIEMPRE relee su estado. Si el producto ya estaba cargado en la misma
 * transacción (p. ej. al validar las líneas de una venta), una consulta con bloqueo devolvía la copia vieja: dos cajas
 * vendiendo a la vez descontaban del mismo número y se perdían unidades (encontrado con 20 ventas simultáneas).
 * Los cambios pendientes se escriben antes de releer, para no perderlos.
 */
public class ProductLockingImpl implements ProductLocking {

    @PersistenceContext
    private EntityManager em;

    @Override
    public Optional<Product> lockById(Long id, Long tenantId) {
        if (id == null || tenantId == null) return Optional.empty();
        Product p = em.find(Product.class, id);
        if (p == null || !tenantId.equals(p.tenantId)) return Optional.empty();
        em.flush();
        em.refresh(p, LockModeType.PESSIMISTIC_WRITE);
        return Optional.of(p);
    }
}
