package com.tiendaposs.pos;

import com.tiendaposs.common.ApiException;
import com.tiendaposs.security.AuthUser;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;

import java.util.Objects;

/**
 * Quién puede tocar una cuenta abierta. Quien vende en la caja (POS_VENDER) maneja todas. El mesero (MESAS_ATENDER)
 * solo maneja cuentas de mesa, y solo las suyas salvo que tenga MESAS_TODAS. El mesero nunca cobra: cobrar sigue
 * pidiendo POS_VENDER.
 */
public final class AccountAccess {

    private AccountAccess() {
    }

    /** Vende en la caja o atiende mesas. */
    public static AuthUser requireServe() {
        AuthUser u = CurrentUser.get();
        if (!u.can(Permission.POS_VENDER) && !u.can(Permission.MESAS_ATENDER)) {
            throw ApiException.forbidden("No tienes permiso para esta acción");
        }
        return u;
    }

    /** Lanza 403 si el usuario no puede tocar esta cuenta. */
    public static void check(Sale s) {
        AuthUser u = requireServe();
        if (u.can(Permission.POS_VENDER)) return;
        if (s.tableId == null) throw ApiException.forbidden("Como mesero solo manejas cuentas de mesa");
        if (u.can(Permission.MESAS_TODAS)) return;
        if (!Objects.equals(s.userId, u.userId())) {
            throw ApiException.forbidden(s.accountLabel + " la atiende " + (s.userName == null ? "otro mesero" : s.userName)
                    + ". Pídele a un supervisor que te la pase.");
        }
    }

    /** true si el usuario ve el detalle de la cuenta (para el plano: las mesas ajenas se ven, pero sin abrirlas). */
    public static boolean canSee(AuthUser u, Sale s) {
        if (u.can(Permission.POS_VENDER) || u.can(Permission.MESAS_TODAS)) return true;
        return u.can(Permission.MESAS_ATENDER) && s.tableId != null && Objects.equals(s.userId, u.userId());
    }
}
