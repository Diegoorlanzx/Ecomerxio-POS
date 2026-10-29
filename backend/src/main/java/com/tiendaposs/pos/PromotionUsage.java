package com.tiendaposs.pos;

import com.tiendaposs.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * Lleva la cuenta del cupo de las promociones. El cupo se gasta al cobrar la venta y vuelve si se anula.
 * Con internet nunca se pasa: dos cajas cobrando a la vez se turnan en la base y la segunda recibe "se agotó".
 * Sin internet la venta ya se cobró: se respeta aunque el cupo se haya pasado y queda marcada para revisar.
 */
@Component
public class PromotionUsage {

    private final JdbcTemplate jdbc;

    public PromotionUsage(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void record(Sale s, Map<Long, BigDecimal> uses, Map<Long, String> names, boolean offline, Set<String> flags) {
        for (var e : uses.entrySet()) {
            if (e.getValue().signum() <= 0) continue;
            int ok = jdbc.update("UPDATE promotions SET used_count = used_count + ? WHERE id = ? AND tenant_id = ?"
                    + " AND (usage_limit IS NULL OR used_count + ? <= usage_limit)", e.getValue(), e.getKey(), s.tenantId, e.getValue());
            if (ok == 0) {
                if (!offline) {
                    throw ApiException.conflict("La promoción \"" + names.get(e.getKey()) + "\" se acabó mientras cobrabas. "
                            + "Revisa el total y vuelve a cobrar.");
                }
                jdbc.update("UPDATE promotions SET used_count = used_count + ? WHERE id = ? AND tenant_id = ?", e.getValue(), e.getKey(), s.tenantId);
                flags.add("PROMO_SIN_CUPO");
            }
            jdbc.update("INSERT INTO promotion_uses (tenant_id, promotion_id, sale_id, uses) VALUES (?, ?, ?, ?)",
                    s.tenantId, e.getKey(), s.id, e.getValue());
        }
    }

    /** Anulación: el cupo que gastó la venta vuelve a quedar libre. */
    void revert(Sale s) {
        jdbc.update("UPDATE promotions p SET used_count = GREATEST(p.used_count - u.uses, 0) FROM promotion_uses u"
                + " WHERE u.sale_id = ? AND u.tenant_id = ? AND p.id = u.promotion_id", s.id, s.tenantId);
        jdbc.update("DELETE FROM promotion_uses WHERE sale_id = ? AND tenant_id = ?", s.id, s.tenantId);
    }
}
