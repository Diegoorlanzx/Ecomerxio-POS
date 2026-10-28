package com.tiendaposs.verticals;

import com.tiendaposs.catalog.CatalogRepos.ProductRepository;
import com.tiendaposs.catalog.Product;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.pos.SaleLine;
import com.tiendaposs.settings.ModulesService;
import com.tiendaposs.settings.SettingsService;
import com.tiendaposs.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Gimnasio (módulo MEMBRESIAS): un producto con días de membresía (mensualidad = 30) suma esos días al cliente cuando se le
 * vende; si aún tenía días vigentes se suman al final. La entrada busca al socio y dice si está al día.
 */
@Service
public class MembershipService {

    public static final String MODULE = "MEMBRESIAS";

    public record Member(Long customerId, String name, String docNumber, String phone, LocalDate startDate, LocalDate endDate,
                         String planName, long daysLeft, boolean active, Instant lastCheckin) {
    }

    public record CheckinResult(boolean allowed, String message, Member member) {
    }

    private final JdbcTemplate jdbc;
    private final SettingsService settings;
    private final ProductRepository products;

    public MembershipService(JdbcTemplate jdbc, SettingsService settings, ProductRepository products) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.products = products;
    }

    private Long tenant() {
        Long t = TenantContext.require();
        ModulesService.check(settings.forTenant(t), MODULE);
        return t;
    }

    @Transactional
    public Integer setMembershipDays(Long productId, Integer days) {
        Long t = tenant();
        if (days != null && (days < 1 || days > 730)) throw ApiException.field("membershipDays", "entre 1 y 730 días");
        Product p = products.findByIdAndTenantId(productId, t).orElseThrow(() -> ApiException.notFound("Producto no encontrado"));
        p.membershipDays = days;
        products.save(p);
        return p.membershipDays;
    }

    /** Antes de cobrar: una membresía se vende a un cliente identificado. Sin internet la venta queda para revisión. */
    public void checkSale(Long tenantId, List<SaleLine> lines, Long customerId, boolean offline, Set<String> flags) {
        if (!settings.forTenant(tenantId).hasModule(MODULE) || customerId != null) return;
        for (SaleLine l : lines) {
            Product p = products.findByIdAndTenantId(l.productId, tenantId).orElse(null);
            if (p != null && p.membershipDays != null) {
                if (!offline) throw ApiException.badRequest(p.name + " es una membresía: elige el cliente (socio) antes de cobrar.");
                flags.add("MEMBRESIA_SIN_CLIENTE");
                return;
            }
        }
    }

    /** Después de cobrar: suma los días de las membresías vendidas al cliente. */
    @Transactional
    public void afterSale(Long tenantId, Long customerId, List<SaleLine> lines) {
        if (customerId == null || !settings.forTenant(tenantId).hasModule(MODULE)) return;
        long days = 0;
        String plan = null;
        for (SaleLine l : lines) {
            Product p = products.findByIdAndTenantId(l.productId, tenantId).orElse(null);
            if (p == null || p.membershipDays == null) continue;
            days += Math.round(p.membershipDays * l.quantity.multiply(l.factor).doubleValue());
            plan = p.name;
        }
        if (days <= 0) return;
        LocalDate today = LocalDate.now();
        List<LocalDate> current = jdbc.queryForList("SELECT end_date FROM memberships WHERE tenant_id = ? AND customer_id = ? FOR UPDATE",
                LocalDate.class, tenantId, customerId);
        if (current.isEmpty()) {
            jdbc.update("INSERT INTO memberships (tenant_id, customer_id, start_date, end_date, plan_name) VALUES (?,?,?,?,?)",
                    tenantId, customerId, Date.valueOf(today), Date.valueOf(today.plusDays(days - 1)), plan);
        } else {
            LocalDate end = current.get(0);
            boolean active = !end.isBefore(today);
            LocalDate newEnd = (active ? end : today.minusDays(1)).plusDays(days);
            jdbc.update("UPDATE memberships SET start_date = CASE WHEN ? THEN start_date ELSE ? END, end_date = ?, plan_name = ?, updated_at = now() "
                    + "WHERE tenant_id = ? AND customer_id = ?", active, Date.valueOf(today), Date.valueOf(newEnd), plan, tenantId, customerId);
        }
    }

    public List<Member> search(String q) {
        Long t = tenant();
        String like = q == null || q.isBlank() ? "%" : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        return jdbc.query("""
                SELECT c.id, c.name, c.doc_number, c.phone, m.start_date, m.end_date, m.plan_name,
                       (SELECT max(created_at) FROM gym_checkins g WHERE g.tenant_id = c.tenant_id AND g.customer_id = c.id)
                FROM customers c LEFT JOIN memberships m ON m.tenant_id = c.tenant_id AND m.customer_id = c.id
                WHERE c.tenant_id = ? AND (lower(c.name) LIKE ? OR coalesce(c.doc_number, '') LIKE ? OR coalesce(c.phone, '') LIKE ?)
                ORDER BY m.end_date IS NULL, c.name LIMIT 50""",
                (rs, i) -> member(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getDate(5) == null ? null : rs.getDate(5).toLocalDate(), rs.getDate(6) == null ? null : rs.getDate(6).toLocalDate(),
                        rs.getString(7), rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant()),
                t, like, like, like);
    }

    /** Socios que vencen en los próximos días (para recordarles por WhatsApp). */
    public List<Member> expiring(int days) {
        return search(null).stream().filter(m -> m.endDate() != null && m.daysLeft() >= -7 && m.daysLeft() <= Math.max(0, days)).toList();
    }

    @Transactional
    public CheckinResult checkin(Long customerId) {
        Long t = tenant();
        List<Member> found = jdbc.query("""
                SELECT c.id, c.name, c.doc_number, c.phone, m.start_date, m.end_date, m.plan_name
                FROM customers c LEFT JOIN memberships m ON m.tenant_id = c.tenant_id AND m.customer_id = c.id
                WHERE c.id = ? AND c.tenant_id = ?""",
                (rs, i) -> member(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getDate(5) == null ? null : rs.getDate(5).toLocalDate(), rs.getDate(6) == null ? null : rs.getDate(6).toLocalDate(),
                        rs.getString(7), Instant.now()),
                customerId, t);
        if (found.isEmpty()) throw ApiException.notFound("Cliente no encontrado");
        Member m = found.get(0);
        boolean ok = m.active();
        jdbc.update("INSERT INTO gym_checkins (tenant_id, customer_id, allowed) VALUES (?,?,?)", t, customerId, ok);
        String msg = ok ? "Bienvenido, " + m.name() + ". Membresía al día hasta el " + m.endDate() + "."
                : m.endDate() == null ? m.name() + " no tiene membresía. Véndele un plan en la caja."
                : "La membresía de " + m.name() + " venció el " + m.endDate() + ". Renueva en la caja.";
        return new CheckinResult(ok, msg, m);
    }

    private static Member member(Long id, String name, String doc, String phone, LocalDate start, LocalDate end, String plan, Instant last) {
        LocalDate today = LocalDate.now();
        long left = end == null ? -1 : ChronoUnit.DAYS.between(today, end);
        return new Member(id, name, doc, phone, start, end, plan, left, end != null && !end.isBefore(today), last);
    }
}
