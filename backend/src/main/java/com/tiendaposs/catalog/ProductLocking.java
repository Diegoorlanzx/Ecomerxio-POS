package com.tiendaposs.catalog;

import java.util.Optional;

/** Bloqueo de un producto para mover su existencia (ver {@link ProductLockingImpl}). */
public interface ProductLocking {

    Optional<Product> lockById(Long id, Long tenantId);
}
