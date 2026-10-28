package com.tiendaposs.counter;

import com.tiendaposs.common.ApiException;
import com.tiendaposs.pos.SaleLine;
import com.tiendaposs.settings.ModulesService;
import com.tiendaposs.settings.SettingsService;
import com.tiendaposs.tenant.TenantContext;
import com.tiendaposs.tenant.TenantRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/**
 * Mostrador (cafetería, heladería, comidas rápidas): número de turno del día por venta y pantalla de pedidos
 * PREPARANDO → LISTO → ENTREGADO. Solo para tiendas con el módulo MOSTRADOR.
 */
@Service
public class CounterService {

    public static final String MODULE = "MOSTRADOR";

    public record CounterOrder(Long saleId, int number, String status, String customerName, String items, Instant createdAt,
                               Instant readyAt, Instant deliveredAt) {
    }

    private final JdbcTemplate jdbc;
    private final SettingsService settings;
    private final TenantRepository tenants;

    public CounterService(JdbcTemplate jdbc, SettingsService settings, TenantRepository tenants) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.tenants = tenants;
    }

    private LocalDate today(Long tenantId) {
        return LocalDate.now(ZoneId.of(tenants.findById(tenantId).map(t -> t.getTimezone()).orElse("America/Bogota")));
    }

    /** Al cobrar: siguiente número del día (se bloquea la tienda para que dos cajas no saquen el mismo). */
    @Transactional
    public Integer assign(Long tenantId, Long saleId, String customerName, List<SaleLine> lines) {
        if (!settings.forTenant(tenantId).hasModule(MODULE)) return null;
        Integer existing = number(saleId);
        if (existing != null) return existing;
        jdbc.queryForObject("SELECT id FROM tenants WHERE id = ? FOR UPDATE", Long.class, tenantId);
        LocalDate day = today(tenantId);
        Integer next = jdbc.queryForObject("SELECT coalesce(max(number), 0) + 1 FROM counter_orders WHERE tenant_id = ? AND day = ?", Integer.class, tenantId, day);
        StringBuilder items = new StringBuilder();
        for (SaleLine l : lines) {
            if (!items.isEmpty()) items.append(", ");
            items.append(l.quantity.stripTrailingZeros().toPlainString()).append(" ").append(l.productName);
        }
        String text = items.length() > 600 ? items.substring(0, 597) + "…" : items.toString();
        jdbc.update("INSERT INTO counter_orders (sale_id, tenant_id, day, number, customer_name, items) VALUES (?,?,?,?,?,?)",
                saleId, tenantId, day, next, customerName, text);
        return next;
    }

    public Integer number(Long saleId) {
        if (saleId == null) return null;
        List<Integer> n = jdbc.queryForList("SELECT number FROM counter_orders WHERE sale_id = ?", Integer.class, saleId);
        return n.isEmpty() ? null : n.get(0);
    }

    /** Pedidos de hoy por entregar, y los últimos entregados. */
    public List<CounterOrder> today() {
        Long t = TenantContext.require();
        ModulesService.check(settings.forTenant(t), MODULE);
        return jdbc.query("""
                SELECT sale_id, number, status, customer_name, items, created_at, ready_at, delivered_at FROM counter_orders
                WHERE tenant_id = ? AND day = ? AND (status <> 'ENTREGADO' OR delivered_at > now() - interval '15 minutes')
                ORDER BY number""",
                (rs, i) -> new CounterOrder(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getTimestamp(6).toInstant(), rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant(),
                        rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant()),
                t, today(t));
    }

    @Transactional
    public void setStatus(Long saleId, String status) {
        Long t = TenantContext.require();
        ModulesService.check(settings.forTenant(t), MODULE);
        String st = status == null ? "" : status.toUpperCase(Locale.ROOT);
        String col = switch (st) {
            case "LISTO" -> "ready_at";
            case "ENTREGADO" -> "delivered_at";
            case "PREPARANDO" -> null;
            default -> throw ApiException.field("status", "PREPARANDO, LISTO o ENTREGADO");
        };
        int n = jdbc.update("UPDATE counter_orders SET status = ?" + (col == null ? ", ready_at = NULL" : ", " + col + " = now()")
                + " WHERE tenant_id = ? AND sale_id = ?", st, t, saleId);
        if (n == 0) throw ApiException.notFound("Pedido no encontrado");
    }
}
