package com.tiendaposs.assistant;

import com.tiendaposs.analytics.AnalyticsService;
import com.tiendaposs.audit.AuditService;
import com.tiendaposs.catalog.CatalogDtos.BulkRequest;
import com.tiendaposs.catalog.ProductBulkService;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.common.Money;
import com.tiendaposs.pos.PosCrudService;
import com.tiendaposs.purchasing.InsightsService;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.settings.SettingsService;
import com.tiendaposs.settings.StoreSettings;
import com.tiendaposs.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Recomendaciones de precios y promociones para revisión humana. Se calculan con reglas claras
 * (margen, rotación, exceso) a partir de los datos reales; el asistente puede proponerlas y
 * explicarlas, pero nada cambia hasta que alguien con permiso de precios las aprueba.
 */
@Service
public class RecommendationService {

    static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** Subida máxima de precio que se propone de una vez. */
    static final BigDecimal MAX_RAISE = new BigDecimal("1.15");

    private final AnalyticsService analytics;
    private final InsightsService insights;
    private final SettingsService settings;
    private final ProductBulkService bulk;
    private final PosCrudService promos;
    private final AuditService audit;
    private final EntityManager em;

    public RecommendationService(AnalyticsService analytics, InsightsService insights, SettingsService settings, ProductBulkService bulk,
                                 PosCrudService promos, AuditService audit, EntityManager em) {
        this.analytics = analytics;
        this.insights = insights;
        this.settings = settings;
        this.bulk = bulk;
        this.promos = promos;
        this.audit = audit;
        this.em = em;
    }

    public record RecommendationDto(Long id, String type, Long productId, String productName, BigDecimal currentPrice, BigDecimal suggestedPrice,
                                    BigDecimal discountPct, BigDecimal currentMargin, BigDecimal expectedMargin, String reason, String source,
                                    String status, String createdBy, String decidedBy, Instant decidedAt, String decisionNote, Instant createdAt) {
    }

    /** Calcula y guarda recomendaciones nuevas (no repite las que siguen pendientes). */
    @Transactional
    public List<RecommendationDto> generate(String source) {
        CurrentUser.require(Permission.COSTOS_VER);
        Long t = TenantContext.require();
        StoreSettings st = settings.forTenant(t);
        LocalDate today = LocalDate.now(analytics.zone(t));
        AnalyticsService.Profitability p = analytics.profitability(today.minusDays(29), today);
        Map<Long, Object[]> info = productInfo(t);
        Set<String> pending = pendingKeys(t);
        List<Long> created = new ArrayList<>();
        BigDecimal target = st.lowMarginPct.add(st.highMarginPct).divide(BigDecimal.valueOf(2), 1, RoundingMode.HALF_UP);

        // 1) Precio: vende y deja poco margen -> subir hacia el margen objetivo (máximo 15% de una vez).
        for (AnalyticsService.ProfitRow r : p.products()) {
            if (r.marginPct().compareTo(st.lowMarginPct) >= 0 || r.units().signum() <= 0) continue;
            Object[] pi = info.get(r.productId());
            if (pi == null || pi[1] == null || pending.contains("PRECIO:" + r.productId())) continue;
            BigDecimal price = dec(pi[1]), cost = dec(pi[2]), tax = dec(pi[3]);
            if (price.signum() <= 0 || cost.signum() <= 0) continue;
            BigDecimal factor = st.pricesIncludeTax ? BigDecimal.ONE.add(tax.divide(HUNDRED, 6, RoundingMode.HALF_UP)) : BigDecimal.ONE;
            BigDecimal ideal = cost.multiply(factor).divide(BigDecimal.ONE.subtract(target.divide(HUNDRED, 6, RoundingMode.HALF_UP)), 2, RoundingMode.HALF_UP);
            BigDecimal suggested = roundUp(ideal.min(price.multiply(MAX_RAISE)), 50);
            if (suggested.compareTo(price) <= 0) continue;
            BigDecimal curMargin = margin(price, cost, factor), newMargin = margin(suggested, cost, factor);
            String reason = "En los últimos 30 días vendió " + plain(r.units()) + " unidades con margen de " + plain(r.marginPct()) + "% (bajo: menos de "
                    + plain(st.lowMarginPct) + "%). Con el costo actual de " + Money.fmt(cost) + ", subir de " + Money.fmt(price) + " a " + Money.fmt(suggested)
                    + " deja " + plain(newMargin) + "% de margen" + (suggested.compareTo(roundUp(ideal, 50)) < 0 ? " (se limita a +15% por ahora)" : "")
                    + ". Revisa el precio de la competencia antes de aprobar.";
            created.add(insert(t, "PRECIO", r.productId(), r.name(), price, suggested, null, curMargin, newMargin, reason, source));
        }

        // 2) Promoción: quieto o con exceso, y con margen suficiente para un descuento sin perder plata.
        for (InsightsService.ProductInsight pi0 : insights.insights().products()) {
            boolean still = pi0.flags().contains("SIN_ROTACION"), excess = pi0.flags().contains("EXCESO");
            if (!still && !excess) continue;
            Object[] pi = info.get(pi0.productId());
            if (pi == null || pi[1] == null || pending.contains("PROMOCION:" + pi0.productId())) continue;
            BigDecimal price = dec(pi[1]), cost = dec(pi[2]), tax = dec(pi[3]);
            if (price.signum() <= 0) continue;
            BigDecimal factor = st.pricesIncludeTax ? BigDecimal.ONE.add(tax.divide(HUNDRED, 6, RoundingMode.HALF_UP)) : BigDecimal.ONE;
            BigDecimal curMargin = margin(price, cost, factor);
            BigDecimal pct = still ? BigDecimal.valueOf(15) : BigDecimal.TEN;
            // Que el margen con descuento no baje del margen bajo de la tienda.
            while (pct.compareTo(BigDecimal.valueOf(5)) >= 0
                    && margin(price.multiply(HUNDRED.subtract(pct)).divide(HUNDRED, 2, RoundingMode.HALF_UP), cost, factor).compareTo(st.lowMarginPct) < 0) {
                pct = pct.subtract(BigDecimal.valueOf(5));
            }
            if (pct.compareTo(BigDecimal.valueOf(5)) < 0) continue;
            BigDecimal promoPrice = price.multiply(HUNDRED.subtract(pct)).divide(HUNDRED, 0, RoundingMode.HALF_UP);
            BigDecimal newMargin = margin(promoPrice, cost, factor);
            String why = still ? "no se vende hace más de " + st.noRotationDays + " días" : "tiene existencias para más de " + st.excessDays + " días";
            String reason = "Quedan " + plain(pi0.stock()) + " unidades y " + why + (pi0.immobilizedValue() != null && pi0.immobilizedValue().signum() > 0
                    ? " (" + Money.fmt(pi0.immobilizedValue()) + " quietos)" : "") + ". Un " + plain(pct) + "% de descuento por 2 semanas (" + Money.fmt(promoPrice)
                    + ") mantiene " + plain(newMargin) + "% de margen y libera capital.";
            created.add(insert(t, "PROMOCION", pi0.productId(), pi0.name(), price, promoPrice, pct, curMargin, newMargin, reason, source));
        }
        if (!created.isEmpty()) audit.log("RECOMENDACIONES", "TENANT", t, "Se generaron " + created.size() + " recomendaciones de precios y promociones para revisar");
        return list("PENDIENTE").stream().filter(r -> created.contains(r.id())).toList();
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public List<RecommendationDto> list(String status) {
        Long t = TenantContext.require();
        List<Object[]> rows = em.createNativeQuery("""
                        select id, type, product_id, product_name, current_price, suggested_price, discount_pct, current_margin, expected_margin,
                               reason, source, status, created_by, decided_by, decided_at, decision_note, created_at
                        from ai_recommendations where tenant_id = :t and (cast(:s as varchar) is null or status = :s)
                        order by case status when 'PENDIENTE' then 0 else 1 end, created_at desc limit 200""")
                .setParameter("t", t).setParameter("s", status == null || status.isBlank() ? null : status).getResultList();
        return rows.stream().map(r -> new RecommendationDto(((Number) r[0]).longValue(), (String) r[1], ((Number) r[2]).longValue(), (String) r[3],
                nd(r[4]), nd(r[5]), nd(r[6]), nd(r[7]), nd(r[8]), (String) r[9], (String) r[10], (String) r[11], (String) r[12], (String) r[13],
                inst(r[14]), (String) r[15], inst(r[16]))).toList();
    }

    /** Aprueba y aplica: el precio cambia (queda en el historial) o se crea la promoción por 14 días. */
    @Transactional
    public RecommendationDto approve(Long id, String note) {
        CurrentUser.require(Permission.PRODUCTOS_PRECIOS);
        RecommendationDto r = find(id);
        if (!"PENDIENTE".equals(r.status())) throw ApiException.badRequest("Esta recomendación ya fue " + r.status().toLowerCase());
        if ("PRECIO".equals(r.type())) {
            bulk.apply(new BulkRequest(List.of(r.productId()), "PRECIO_FIJO", r.suggestedPrice(), null, null, null,
                    "Recomendación #" + r.id() + " aprobada" + (note == null || note.isBlank() ? "" : ": " + note.trim())));
        } else {
            LocalDate today = LocalDate.now(analytics.zone(TenantContext.require()));
            promos.savePromotion(null, new PosCrudService.PromotionRequest("Promo " + truncate(r.productName(), 80) + " -" + plain(r.discountPct()) + "%",
                    "PORCENTAJE", r.productId(), null, r.discountPct(), null, null, null, null, today, today.plusDays(13), true));
        }
        decide(r.id(), "APLICADA", note);
        audit.sensitive("RECOMENDACION_APLICADA", "PRODUCT", r.productId(), "Aprobó la recomendación #" + r.id() + " (" + r.type().toLowerCase() + ") de "
                + r.productName() + ("PRECIO".equals(r.type()) ? ": " + Money.fmt(r.currentPrice()) + " → " + Money.fmt(r.suggestedPrice()) : ": " + plain(r.discountPct()) + "% por 14 días"));
        return find(id);
    }

    @Transactional
    public RecommendationDto dismiss(Long id, String note) {
        CurrentUser.require(Permission.PRODUCTOS_PRECIOS);
        RecommendationDto r = find(id);
        if (!"PENDIENTE".equals(r.status())) throw ApiException.badRequest("Esta recomendación ya fue " + r.status().toLowerCase());
        decide(r.id(), "DESCARTADA", note);
        audit.log("RECOMENDACION_DESCARTADA", "PRODUCT", r.productId(), "Descartó la recomendación #" + r.id() + " de " + r.productName());
        return find(id);
    }

    private void decide(Long id, String status, String note) {
        em.createNativeQuery("update ai_recommendations set status = :s, decided_by = :b, decided_at = now(), decision_note = :n where id = :id")
                .setParameter("s", status).setParameter("b", CurrentUser.get().fullName())
                .setParameter("n", note == null || note.isBlank() ? null : truncate(note.trim(), 300)).setParameter("id", id).executeUpdate();
    }

    private RecommendationDto find(Long id) {
        return list(null).stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow(() -> ApiException.notFound("Recomendación no encontrada"));
    }

    private Long insert(Long t, String type, Long productId, String name, BigDecimal price, BigDecimal suggested, BigDecimal pct,
                        BigDecimal curMargin, BigDecimal newMargin, String reason, String source) {
        Number id = (Number) em.createNativeQuery("""
                        insert into ai_recommendations (tenant_id, type, product_id, product_name, current_price, suggested_price, discount_pct,
                          current_margin, expected_margin, reason, source, created_by)
                        values (:t, :ty, :p, :n, :cp, :sp, :d, :cm, :em, :r, :s, :by) returning id""")
                .setParameter("t", t).setParameter("ty", type).setParameter("p", productId).setParameter("n", truncate(name, 160))
                .setParameter("cp", price).setParameter("sp", suggested).setParameter("d", pct).setParameter("cm", curMargin)
                .setParameter("em", newMargin).setParameter("r", truncate(reason, 600)).setParameter("s", source)
                .setParameter("by", CurrentUser.get().fullName()).getSingleResult();
        return id.longValue();
    }

    @SuppressWarnings("unchecked")
    private Map<Long, Object[]> productInfo(Long t) {
        Map<Long, Object[]> out = new HashMap<>();
        for (Object[] r : (List<Object[]>) em.createNativeQuery("""
                        select p.id, pr.price, p.cost, p.tax_rate from products p
                        left join product_presentations pr on pr.product_id = p.id and pr.is_base
                        where p.tenant_id = :t and p.status = 'ACTIVE'""").setParameter("t", t).getResultList()) {
            out.put(((Number) r[0]).longValue(), r);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Set<String> pendingKeys(Long t) {
        Set<String> out = new HashSet<>();
        for (Object[] r : (List<Object[]>) em.createNativeQuery("select type, product_id from ai_recommendations where tenant_id = :t and status = 'PENDIENTE'")
                .setParameter("t", t).getResultList()) {
            out.add(r[0] + ":" + r[1]);
        }
        return out;
    }

    /** Margen sobre el precio sin impuestos. */
    static BigDecimal margin(BigDecimal price, BigDecimal cost, BigDecimal taxFactor) {
        BigDecimal ex = price.divide(taxFactor, 4, RoundingMode.HALF_UP);
        if (ex.signum() == 0) return BigDecimal.ZERO;
        return ex.subtract(cost).multiply(HUNDRED).divide(ex, 1, RoundingMode.HALF_UP);
    }

    static BigDecimal roundUp(BigDecimal v, int to) {
        BigDecimal step = BigDecimal.valueOf(to);
        return v.divide(step, 0, RoundingMode.CEILING).multiply(step);
    }

    private static String plain(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private static String truncate(String s, int n) {
        return s == null ? null : s.length() > n ? s.substring(0, n) : s;
    }

    private static BigDecimal dec(Object o) {
        return o == null ? BigDecimal.ZERO : o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
    }

    private static BigDecimal nd(Object o) {
        return o == null ? null : dec(o);
    }

    private static Instant inst(Object o) {
        if (o == null) return null;
        if (o instanceof Instant i) return i;
        if (o instanceof Timestamp ts) return ts.toInstant();
        if (o instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        return Instant.parse(o.toString());
    }
}
