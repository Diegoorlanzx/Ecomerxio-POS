package com.tiendaposs.tenant;

import com.tiendaposs.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Hace cumplir los topes del plan ({@link Plans}): cajas activas y usuarios activos. Solo frena lo NUEVO (crear o reactivar):
 * si soporte baja de plan a una tienda que ya tiene más, no se desactiva nada; simplemente no puede sumar hasta quedar dentro.
 */
@Component
public class PlanLimits {

    private final JdbcTemplate jdbc;
    private final TenantRepository tenants;

    public PlanLimits(JdbcTemplate jdbc, TenantRepository tenants) {
        this.jdbc = jdbc;
        this.tenants = tenants;
    }

    /** Antes de crear o reactivar un usuario. */
    public void checkUsers(Long tenantId) {
        Plans.Plan p = plan(tenantId);
        long active = count("SELECT count(*) FROM users WHERE tenant_id = ? AND active", tenantId);
        if (active >= p.maxUsers()) {
            throw limit("USERS", p, "Tu plan " + p.name() + " permite hasta " + p.maxUsers() + " usuarios activos. "
                    + "Desactiva uno que ya no use la app o cambia de plan en Más → Suscripción.");
        }
    }

    /** Antes de crear o reactivar una caja. */
    public void checkRegisters(Long tenantId) {
        Plans.Plan p = plan(tenantId);
        long active = count("SELECT count(*) FROM registers WHERE tenant_id = ? AND active", tenantId);
        if (active >= p.maxRegisters()) {
            throw limit("REGISTERS", p, "Tu plan " + p.name() + " permite " + (p.maxRegisters() == 1 ? "1 caja" : "hasta " + p.maxRegisters() + " cajas")
                    + ". Desactiva una o cambia de plan en Más → Suscripción.");
        }
    }

    private Plans.Plan plan(Long tenantId) {
        // Bloquea la fila de la tienda hasta el final de la transacción: dos altas al tiempo no pueden pasarse del tope.
        jdbc.queryForList("SELECT id FROM tenants WHERE id = ? FOR UPDATE", tenantId);
        return Plans.of(tenants.findById(tenantId).map(Tenant::getPlan).orElse(null));
    }

    private long count(String sql, Long tenantId) {
        Long n = jdbc.queryForObject(sql, Long.class, tenantId);
        return n == null ? 0 : n;
    }

    private static ApiException limit(String what, Plans.Plan p, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message, Map.of("planLimit", what, "plan", p.code()));
    }
}
