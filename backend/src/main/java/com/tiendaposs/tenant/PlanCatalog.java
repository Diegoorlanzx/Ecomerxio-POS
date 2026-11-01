package com.tiendaposs.tenant;

import com.tiendaposs.common.ApiException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/** Lee la tabla `plans` hacia {@link Plans} y aplica los cambios que manda el Super Admin de Ecomerxio. */
@Service
public class PlanCatalog {

    private static final Logger log = LoggerFactory.getLogger(PlanCatalog.class);
    static final long MAX_PRICE = 100_000_000L;
    static final int MAX_LIMIT = 10_000;

    private final JdbcTemplate jdbc;

    public PlanCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void reload() {
        try {
            Plans.replace(jdbc.query("SELECT code, name, price_monthly, price_annual, max_registers, max_users FROM plans ORDER BY sort_order, code",
                    (rs, i) -> new Plans.Plan(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                            limit((Integer) rs.getObject(5)), limit((Integer) rs.getObject(6)))));
        } catch (Exception e) {
            log.error("No se pudieron leer los planes; se usan los de fábrica hasta el próximo intento: {}", e.getMessage());
        }
    }

    /** Cambia precios y topes de un plan. Las tiendas que ya están en él conservan su precio; los topes sí les aplican. */
    @Transactional
    public Plans.Plan update(String code, Long priceMonthly, Long priceAnnual, Integer maxRegisters, Integer maxUsers) {
        Plans.Plan p = Plans.find(code).orElseThrow(() -> ApiException.notFound("Plan no encontrado"));
        if (priceMonthly == null || priceMonthly < 0 || priceMonthly > MAX_PRICE) throw ApiException.field("priceMonthly", "precio mensual inválido");
        if (priceAnnual == null || priceAnnual < 0 || priceAnnual > MAX_PRICE) throw ApiException.field("priceAnnual", "precio anual inválido");
        if (maxRegisters != null && (maxRegisters < 1 || maxRegisters > MAX_LIMIT)) throw ApiException.field("maxRegisters", "entre 1 y " + MAX_LIMIT + ", o sin límite");
        if (maxUsers != null && (maxUsers < 1 || maxUsers > MAX_LIMIT)) throw ApiException.field("maxUsers", "entre 1 y " + MAX_LIMIT + ", o sin límite");
        jdbc.update("UPDATE plans SET price_monthly = ?, price_annual = ?, max_registers = ?, max_users = ?, updated_at = now() WHERE code = ?",
                priceMonthly, priceAnnual, maxRegisters, maxUsers, p.code());
        // Se recarga al confirmar: si la transacción falla, el catálogo en memoria no cambia.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                reload();
            }
        });
        return new Plans.Plan(p.code(), p.name(), priceMonthly, priceAnnual, limit(maxRegisters), limit(maxUsers));
    }

    public List<Plans.Plan> all() {
        return Plans.all();
    }

    private static int limit(Integer v) {
        return v == null ? Plans.UNLIMITED : v;
    }
}
