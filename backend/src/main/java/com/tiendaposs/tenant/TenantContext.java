package com.tiendaposs.tenant;

import com.tiendaposs.common.ApiException;

/**
 * Tienda del usuario autenticado en la petición actual. La resuelve el filtro JWT a partir del
 * usuario cargado desde la base de datos, nunca de un dato enviado por el cliente.
 */
public final class TenantContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(Long tenantId) {
        CURRENT.set(tenantId);
    }

    public static Long get() {
        return CURRENT.get();
    }

    public static Long require() {
        Long id = CURRENT.get();
        if (id == null) {
            throw ApiException.forbidden("Tu cuenta no está asociada a una tienda");
        }
        return id;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
