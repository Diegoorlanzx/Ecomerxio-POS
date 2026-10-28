package com.tiendaposs.purchasing;

import com.tiendaposs.catalog.CatalogRepos.CategoryRepository;
import com.tiendaposs.catalog.CatalogRepos.ProductRepository;
import com.tiendaposs.catalog.Category;
import com.tiendaposs.catalog.Product;
import com.tiendaposs.inventory.InventoryService;
import com.tiendaposs.inventory.ProductLot;
import com.tiendaposs.purchasing.PurchasingRepos.*;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.settings.SettingsService;
import com.tiendaposs.settings.StoreSettings;
import com.tiendaposs.tenant.Tenant;
import com.tiendaposs.tenant.TenantContext;
import com.tiendaposs.tenant.TenantRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Inventario inteligente y reposición. A partir de las ventas reales calcula, por producto:
 * velocidad de venta, tendencia, días de cobertura, rotación, exceso, clase ABC, valor y capital
 * inmovilizado; y sugiere qué comprar, cuánto y a quién, explicando el porqué.
 *
 * <p>Reposición (por producto): demanda diaria d = 60% del promedio de los últimos 7 días + 40%
 * del promedio de la ventana de análisis (pesa más lo reciente). Punto de pedido = d × entrega +
 * stock de seguridad (o el mínimo, si es mayor). Si lo que hay más lo que viene en camino está en
 * el punto de pedido o por debajo, se pide hasta cubrir d × (entrega + días entre pedidos) +
 * seguridad, sin pasar del máximo; y se ajusta al mínimo y al múltiplo de empaque del proveedor.</p>
 */
@Service
public class InsightsService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final EntityManager em;
    private final ProductRepository products;
    private final CategoryRepository categories;
    private final SupplierRepository suppliers;
    private final SupplierProductRepository supplierProducts;
    private final PurchaseOrderRepository orders;
    private final PurchaseService purchases;
    private final SupplierService supplierService;
    private final InventoryService inventory;
    private final SettingsService settings;
    private final TenantRepository tenants;

    public InsightsService(EntityManager em, ProductRepository products, CategoryRepository categories,
                           SupplierRepository suppliers, SupplierProductRepository supplierProducts,
                           PurchaseOrderRepository orders, PurchaseService purchases, SupplierService supplierService,
                           InventoryService inventory, SettingsService settings, TenantRepository tenants) {
        this.em = em;
        this.products = products;
        this.categories = categories;
        this.suppliers = suppliers;
        this.supplierProducts = supplierProducts;
        this.orders = orders;
        this.purchases = purchases;
        this.supplierService = supplierService;
        this.inventory = inventory;
        this.settings = settings;
        this.tenants = tenants;
    }

    // ---------------- DTOs ----------------

    public record ProductInsight(Long productId, String name, String sku, String categoryName, String unit, BigDecimal stock,
                                 BigDecimal stockMin, BigDecimal stockMax, BigDecimal safetyStock, BigDecimal soldWindow,
                                 BigDecimal soldLast7, BigDecimal dailyVelocity, BigDecimal trendPct, BigDecimal coverageDays,
                                 BigDecimal revenueWindow, BigDecimal grossProfitWindow, String abc, Instant lastSaleAt,
                                 BigDecimal stockValue, BigDecimal immobilizedValue, BigDecimal onOrder, List<String> flags) {
    }

    public record AbcSummary(int a, int b, int c, BigDecimal aRevenuePct) {
    }

    public record Summary(int activeProducts, BigDecimal totalValue, BigDecimal immobilizedValue, int outOfStock, int belowMin,
                          int stockoutSoon, int noRotation, int excess, int expiringLots, int expiredLots, AbcSummary abc,
                          int analysisDays) {
    }

    public record Insights(Summary summary, List<ProductInsight> products) {
    }

    public record SuggestionLine(Long productId, String productName, String sku, String priority, BigDecimal stock,
                                 BigDecimal onOrder, BigDecimal dailyDemand, BigDecimal coverageDays, BigDecimal reorderPoint,
                                 BigDecimal targetStock, BigDecimal neededUnits, String packName, BigDecimal packFactor,
                                 BigDecimal packs, BigDecimal costPerPack, BigDecimal estimatedCost, String reason,
                                 List<Alternative> alternatives) {
    }

    public record Alternative(Long supplierId, String supplierName, BigDecimal unitCost, String packName, BigDecimal packFactor) {
    }

    public record SupplierSuggestion(Long supplierId, String supplierName, String whatsapp, BigDecimal leadDays,
                                     String orderDays, LocalDate nextOrderDate, BigDecimal estimatedTotal, int critical,
                                     List<SuggestionLine> lines) {
    }

    public record Suggestions(List<SupplierSuggestion> suppliers, List<SuggestionLine> withoutSupplier, BigDecimal monthlyBudget,
                              BigDecimal spentThisMonth, BigDecimal budgetLeft, BigDecimal suggestedTotal, BigDecimal criticalTotal) {
    }

    public record LotDto(Long id, Long productId, String productName, String lotCode, LocalDate expiryDate, BigDecimal remaining,
                         long daysLeft, BigDecimal value) {
    }

    public record AdjustmentByUser(String userName, int adjustments, BigDecimal unitsIn, BigDecimal unitsOut, BigDecimal valueOut) {
    }

    public record Alert(String type, String severity, String title, String detail, Long productId, Long referenceId) {
    }

    // ---------------- Ventas por producto ----------------

    private record SalesAgg(BigDecimal units, BigDecimal units7, BigDecimal units7prev, BigDecimal revenue, BigDecimal cost, Instant lastSale) {
    }

    @SuppressWarnings("unchecked")
    private Map<Long, SalesAgg> salesAgg(Long tenantId, int windowDays) {
        Instant now = Instant.now();
        Instant from = now.minus(windowDays, ChronoUnit.DAYS);
        Instant d7 = now.minus(7, ChronoUnit.DAYS);
        Instant d14 = now.minus(14, ChronoUnit.DAYS);
        List<Object[]> rows = em.createNativeQuery("""
                        select l.product_id,
                               sum((l.quantity - l.returned_qty) * l.factor),
                               sum(case when s.completed_at >= :d7 then (l.quantity - l.returned_qty) * l.factor else 0 end),
                               sum(case when s.completed_at >= :d14 and s.completed_at < :d7 then (l.quantity - l.returned_qty) * l.factor else 0 end),
                               sum(case when l.quantity > 0 then (l.line_total - l.tax) * (l.quantity - l.returned_qty) / l.quantity else 0 end),
                               sum(l.unit_cost * (l.quantity - l.returned_qty)),
                               max(s.completed_at)
                        from sale_lines l join sales s on s.id = l.sale_id
                        where s.tenant_id = :t and s.status = 'PAGADA' and s.completed_at >= :from
                        group by l.product_id""")
                .setParameter("t", tenantId).setParameter("from", Timestamp.from(from))
                .setParameter("d7", Timestamp.from(d7)).setParameter("d14", Timestamp.from(d14))
                .getResultList();
        Map<Long, SalesAgg> out = new HashMap<>();
        for (Object[] r : rows) {
            out.put(((Number) r[0]).longValue(), new SalesAgg(dec(r[1]), dec(r[2]), dec(r[3]), dec(r[4]), dec(r[5]), instant(r[6])));
        }
        // Lo que salió dentro de combos también es demanda del componente (para comprar y calcular cobertura).
        List<Object[]> comp = em.createNativeQuery("""
                        select c.product_id,
                               sum((l.quantity - l.returned_qty) * c.quantity),
                               sum(case when s.completed_at >= :d7 then (l.quantity - l.returned_qty) * c.quantity else 0 end),
                               sum(case when s.completed_at >= :d14 and s.completed_at < :d7 then (l.quantity - l.returned_qty) * c.quantity else 0 end),
                               max(s.completed_at)
                        from sale_line_components c join sale_lines l on l.id = c.sale_line_id join sales s on s.id = l.sale_id
                        where s.tenant_id = :t and s.status = 'PAGADA' and s.completed_at >= :from
                        group by c.product_id""")
                .setParameter("t", tenantId).setParameter("from", Timestamp.from(from))
                .setParameter("d7", Timestamp.from(d7)).setParameter("d14", Timestamp.from(d14))
                .getResultList();
        for (Object[] r : comp) {
            Long id = ((Number) r[0]).longValue();
            SalesAgg cur = out.get(id);
            Instant last = instant(r[4]);
            out.put(id, cur == null ? new SalesAgg(dec(r[1]), dec(r[2]), dec(r[3]), BigDecimal.ZERO, BigDecimal.ZERO, last)
                    : new SalesAgg(cur.units().add(dec(r[1])), cur.units7().add(dec(r[2])), cur.units7prev().add(dec(r[3])),
                    cur.revenue(), cur.cost(), cur.lastSale() == null || (last != null && last.isAfter(cur.lastSale())) ? last : cur.lastSale()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<Long, Instant> lastSaleEver(Long tenantId) {
        List<Object[]> rows = em.createNativeQuery("""
                        select x.product_id, max(x.at) from (
                          select l.product_id, s.completed_at at from sale_lines l join sales s on s.id = l.sale_id
                          where s.tenant_id = :t and s.status = 'PAGADA'
                          union all
                          select c.product_id, s.completed_at from sale_line_components c join sale_lines l on l.id = c.sale_line_id
                          join sales s on s.id = l.sale_id where s.tenant_id = :t and s.status = 'PAGADA') x group by x.product_id""")
                .setParameter("t", tenantId).getResultList();
        Map<Long, Instant> out = new HashMap<>();
        for (Object[] r : rows) out.put(((Number) r[0]).longValue(), instant(r[1]));
        return out;
    }

    // ---------------- Inventario inteligente ----------------

    @Transactional(readOnly = true)
    public Insights insights() {
        Long t = TenantContext.require();
        StoreSettings st = settings.forTenant(t);
        int window = st.analysisDays;
        Map<Long, SalesAgg> sales = salesAgg(t, Math.max(window, 14));
        Map<Long, SalesAgg> salesWindow = window >= 14 ? salesAgg(t, window) : sales;
        Map<Long, Instant> lastEver = lastSaleEver(t);
        Map<Long, BigDecimal> onOrder = purchases.onOrder(t);
        Map<Long, String> catNames = categories.findByTenantIdOrderBySortOrderAscNameAsc(t).stream().collect(Collectors.toMap(c -> c.id, c -> c.name));
        List<Product> ps = products.findByTenantIdAndStatusOrderByNameAsc(t, "ACTIVE").stream().filter(p -> p.trackStock).toList();
        Instant now = Instant.now();

        // ABC por ingresos de la ventana.
        Map<Long, String> abc = new HashMap<>();
        List<Product> byRevenue = ps.stream().sorted(Comparator.comparing((Product p) -> revenue(salesWindow, p.id)).reversed()).toList();
        BigDecimal totalRevenue = byRevenue.stream().map(p -> revenue(salesWindow, p.id)).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cum = BigDecimal.ZERO, aRevenue = BigDecimal.ZERO;
        for (Product p : byRevenue) {
            BigDecimal rv = revenue(salesWindow, p.id);
            if (rv.signum() <= 0 || totalRevenue.signum() == 0) {
                abc.put(p.id, "C");
                continue;
            }
            BigDecimal before = cum.multiply(HUNDRED).divide(totalRevenue, 2, RoundingMode.HALF_UP);
            cum = cum.add(rv);
            String cls = before.compareTo(st.abcAPct) < 0 ? "A" : before.compareTo(st.abcBPct) < 0 ? "B" : "C";
            abc.put(p.id, cls);
            if (cls.equals("A")) aRevenue = aRevenue.add(rv);
        }

        List<ProductInsight> out = new ArrayList<>();
        int outOfStock = 0, belowMin = 0, soon = 0, noRot = 0, excess = 0;
        BigDecimal totalValue = BigDecimal.ZERO, immobilized = BigDecimal.ZERO;
        for (Product p : ps) {
            SalesAgg w = salesWindow.get(p.id);
            SalesAgg s14 = sales.get(p.id);
            BigDecimal soldW = w == null ? BigDecimal.ZERO : w.units();
            BigDecimal sold7 = s14 == null ? BigDecimal.ZERO : s14.units7();
            BigDecimal prev7 = s14 == null ? BigDecimal.ZERO : s14.units7prev();
            BigDecimal d = demand(soldW, sold7, window);
            BigDecimal trend = prev7.signum() == 0 ? (sold7.signum() > 0 ? null : BigDecimal.ZERO)
                    : sold7.subtract(prev7).multiply(HUNDRED).divide(prev7, 1, RoundingMode.HALF_UP);
            BigDecimal coverage = d.signum() == 0 ? null : p.stock.max(BigDecimal.ZERO).divide(d, 1, RoundingMode.HALF_UP);
            BigDecimal value = p.stock.max(BigDecimal.ZERO).multiply(p.cost).setScale(2, RoundingMode.HALF_UP);
            totalValue = totalValue.add(value);
            Instant last = lastEver.get(p.id);
            List<String> flags = new ArrayList<>();
            BigDecimal lead = BigDecimal.valueOf(st.defaultLeadDays);
            if (p.stock.signum() <= 0) {
                flags.add("AGOTADO");
                outOfStock++;
            } else if (p.stockMin.signum() > 0 && p.stock.compareTo(p.stockMin) <= 0) {
                flags.add("BAJO_MINIMO");
                belowMin++;
            }
            if (p.stock.signum() > 0 && coverage != null && coverage.compareTo(lead.max(BigDecimal.valueOf(3))) <= 0) {
                flags.add("AGOTAMIENTO_INMINENTE");
                soon++;
            }
            boolean noRotation = p.stock.signum() > 0 && (last == null ? p.createdAt.isBefore(now.minus(st.noRotationDays, ChronoUnit.DAYS))
                    : last.isBefore(now.minus(st.noRotationDays, ChronoUnit.DAYS)));
            BigDecimal immob = BigDecimal.ZERO;
            if (noRotation) {
                flags.add("SIN_ROTACION");
                noRot++;
                immob = value;
            } else {
                BigDecimal excessUnits = BigDecimal.ZERO;
                if (coverage != null && coverage.compareTo(BigDecimal.valueOf(st.excessDays)) > 0) {
                    excessUnits = p.stock.subtract(d.multiply(BigDecimal.valueOf(st.excessDays)));
                }
                if (p.stockMax.signum() > 0 && p.stock.compareTo(p.stockMax) > 0) excessUnits = excessUnits.max(p.stock.subtract(p.stockMax));
                if (excessUnits.signum() > 0) {
                    flags.add("EXCESO");
                    excess++;
                    immob = excessUnits.multiply(p.cost).setScale(2, RoundingMode.HALF_UP);
                }
            }
            immobilized = immobilized.add(immob);
            boolean costs = CurrentUser.get().can(Permission.COSTOS_VER);
            out.add(new ProductInsight(p.id, p.name, p.sku, p.categoryId == null ? null : catNames.get(p.categoryId), p.unit, p.stock,
                    p.stockMin, p.stockMax, p.safetyStock, soldW, sold7, d.setScale(2, RoundingMode.HALF_UP), trend, coverage,
                    w == null ? BigDecimal.ZERO : w.revenue().setScale(2, RoundingMode.HALF_UP),
                    costs && w != null ? w.revenue().subtract(w.cost()).setScale(2, RoundingMode.HALF_UP) : null,
                    abc.getOrDefault(p.id, "C"), last, costs ? value : null, costs ? immob : null, onOrder.getOrDefault(p.id, BigDecimal.ZERO), flags));
        }
        LocalDate today = LocalDate.now(zone(t));
        List<ProductLot> soonLots = inventory.expiringBefore(t, today.plusDays(st.expiryAlertDays));
        int expired = (int) soonLots.stream().filter(l -> !l.expiryDate.isAfter(today)).count();
        int a = (int) abc.values().stream().filter("A"::equals).count();
        int b = (int) abc.values().stream().filter("B"::equals).count();
        boolean costs = CurrentUser.get().can(Permission.COSTOS_VER);
        Summary sum = new Summary(ps.size(), costs ? totalValue : null, costs ? immobilized : null, outOfStock, belowMin, soon, noRot, excess,
                soonLots.size() - expired, expired, new AbcSummary(a, b, ps.size() - a - b,
                totalRevenue.signum() == 0 ? BigDecimal.ZERO : aRevenue.multiply(HUNDRED).divide(totalRevenue, 1, RoundingMode.HALF_UP)), window);
        return new Insights(sum, out);
    }

    // ---------------- Sugerencias de compra ----------------

    @Transactional(readOnly = true)
    public Suggestions suggestions() {
        Long t = TenantContext.require();
        StoreSettings st = settings.forTenant(t);
        Insights ins = insights();
        Map<Long, Product> ps = products.findByTenantIdAndStatusOrderByNameAsc(t, "ACTIVE").stream().collect(Collectors.toMap(p -> p.id, Function.identity()));
        Map<Long, Supplier> sup = suppliers.findByTenantIdOrderByNameAsc(t).stream().filter(s -> s.active).collect(Collectors.toMap(s -> s.id, Function.identity()));
        Map<Long, List<SupplierProduct>> byProduct = supplierProducts.findByTenantId(t).stream().filter(sp -> sup.containsKey(sp.supplierId))
                .collect(Collectors.groupingBy(sp -> sp.productId));
        Map<Long, BigDecimal> leadBySupplier = new HashMap<>();
        for (Supplier s : sup.values()) leadBySupplier.put(s.id, supplierService.effectiveLead(s, supplierService.metrics(s).avgLeadDays()));

        Map<Long, List<SuggestionLine>> grouped = new LinkedHashMap<>();
        List<SuggestionLine> noSupplier = new ArrayList<>();
        Map<Long, LocalDate> nextDate = new HashMap<>();
        LocalDate today = LocalDate.now(zone(t));
        for (ProductInsight in : ins.products()) {
            Product p = ps.get(in.productId());
            List<SupplierProduct> options = byProduct.getOrDefault(p.id, List.of());
            SupplierProduct chosen = options.stream().filter(x -> x.isPrimary).findFirst()
                    .orElse(options.stream().min(Comparator.comparing(SupplierProduct::unitCost)).orElse(null));
            BigDecimal lead = chosen == null ? BigDecimal.valueOf(st.defaultLeadDays) : leadBySupplier.get(chosen.supplierId);
            BigDecimal d = in.dailyVelocity();
            BigDecimal safety = p.safetyStock.signum() > 0 ? p.safetyStock : d.multiply(lead).multiply(BigDecimal.valueOf(0.5)).setScale(0, RoundingMode.UP);
            BigDecimal reorderPoint = d.multiply(lead).add(safety).max(p.stockMin).setScale(1, RoundingMode.HALF_UP);
            BigDecimal target = d.multiply(lead.add(BigDecimal.valueOf(st.reviewDays))).add(safety).max(p.stockMin);
            if (p.stockMax.signum() > 0) target = target.min(p.stockMax).max(p.stockMin);
            BigDecimal position = p.stock.add(in.onOrder());
            if (chosen != null && d.signum() > 0 && position.compareTo(reorderPoint) > 0) {
                // Fecha estimada en que tocará pedir (para "próxima fecha de pedido").
                long days = position.subtract(reorderPoint).divide(d, 0, RoundingMode.DOWN).longValue();
                LocalDate when = today.plusDays(days);
                nextDate.merge(chosen.supplierId, when, (x, y) -> x.isBefore(y) ? x : y);
            }
            boolean needs = position.compareTo(reorderPoint) <= 0 && (d.signum() > 0 || p.stock.compareTo(p.stockMin) < 0 || p.stock.signum() <= 0);
            if (!needs) continue;
            BigDecimal need = target.subtract(position).max(BigDecimal.ZERO);
            if (need.signum() == 0 && p.stock.signum() <= 0) need = p.stockMin.max(BigDecimal.ONE);
            if (need.signum() == 0) continue;
            String priority = p.stock.signum() <= 0 || (in.coverageDays() != null && in.coverageDays().compareTo(lead) < 0) ? "CRITICO"
                    : p.stock.compareTo(reorderPoint) <= 0 ? "ALTO" : "NORMAL";
            String packName = chosen == null ? "Unidad" : chosen.packName;
            BigDecimal factor = chosen == null ? BigDecimal.ONE : chosen.packFactor;
            BigDecimal packs = need.divide(factor, 0, RoundingMode.UP);
            if (chosen != null) {
                packs = packs.max(chosen.minPacks.setScale(0, RoundingMode.UP));
                BigDecimal mult = chosen.multiplePacks.signum() <= 0 ? BigDecimal.ONE : chosen.multiplePacks;
                packs = packs.divide(mult, 0, RoundingMode.UP).multiply(mult);
            }
            BigDecimal costPerPack = chosen == null ? p.lastCost.multiply(factor) : chosen.costPerPack;
            BigDecimal est = costPerPack.multiply(packs).setScale(2, RoundingMode.HALF_UP);
            String reason = explain(p, in, d, lead, safety, reorderPoint, target, need, packs, packName, factor, chosen, st);
            List<Alternative> alts = options.stream().filter(x -> chosen == null || !x.id.equals(chosen.id))
                    .map(x -> new Alternative(x.supplierId, sup.get(x.supplierId).name, x.unitCost(), x.packName, x.packFactor))
                    .sorted(Comparator.comparing(Alternative::unitCost)).toList();
            SuggestionLine line = new SuggestionLine(p.id, p.name, p.sku, priority, p.stock, in.onOrder(), d, in.coverageDays(),
                    reorderPoint, target.setScale(1, RoundingMode.HALF_UP), need.setScale(1, RoundingMode.HALF_UP), packName, factor, packs,
                    costPerPack, est, reason, alts);
            if (chosen == null) noSupplier.add(line);
            else grouped.computeIfAbsent(chosen.supplierId, k -> new ArrayList<>()).add(line);
        }
        Comparator<SuggestionLine> byPriority = Comparator.comparing((SuggestionLine l) -> switch (l.priority()) {
            case "CRITICO" -> 0;
            case "ALTO" -> 1;
            default -> 2;
        }).thenComparing(SuggestionLine::productName);
        List<SupplierSuggestion> groups = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO, critical = BigDecimal.ZERO;
        for (var e : grouped.entrySet()) {
            Supplier s = sup.get(e.getKey());
            List<SuggestionLine> ls = e.getValue().stream().sorted(byPriority).toList();
            BigDecimal gt = ls.stream().map(SuggestionLine::estimatedCost).reduce(BigDecimal.ZERO, BigDecimal::add);
            int crit = (int) ls.stream().filter(l -> l.priority().equals("CRITICO")).count();
            critical = critical.add(ls.stream().filter(l -> l.priority().equals("CRITICO")).map(SuggestionLine::estimatedCost).reduce(BigDecimal.ZERO, BigDecimal::add));
            total = total.add(gt);
            groups.add(new SupplierSuggestion(s.id, s.name, s.whatsapp, leadBySupplier.get(s.id), s.orderDays,
                    alignToOrderDay(today, s.orderDays), gt, crit, ls));
        }
        // Proveedores sin nada que pedir hoy: se informa cuándo tocaría.
        for (var e : nextDate.entrySet()) {
            if (grouped.containsKey(e.getKey())) continue;
            Supplier s = sup.get(e.getKey());
            groups.add(new SupplierSuggestion(s.id, s.name, s.whatsapp, leadBySupplier.get(s.id), s.orderDays,
                    alignToOrderDay(e.getValue(), s.orderDays), BigDecimal.ZERO, 0, List.of()));
        }
        groups.sort(Comparator.comparing((SupplierSuggestion g) -> -g.critical()).thenComparing(g -> g.estimatedTotal().negate()));
        BigDecimal spent = orders.approvedSince(t, LocalDate.now(zone(t)).withDayOfMonth(1).atStartOfDay(zone(t)).toInstant());
        BigDecimal left = st.purchaseBudget.signum() == 0 ? null : st.purchaseBudget.subtract(spent);
        boolean costs = CurrentUser.get().can(Permission.COSTOS_VER) || CurrentUser.get().can(Permission.COMPRAS_VER);
        return new Suggestions(groups, noSupplier.stream().sorted(byPriority).toList(), costs ? st.purchaseBudget : null,
                costs ? spent : null, costs ? left : null, costs ? total : null, costs ? critical : null);
    }

    /** Texto con el porqué de la sugerencia (también lo usará el asistente de IA). */
    private static String explain(Product p, ProductInsight in, BigDecimal d, BigDecimal lead, BigDecimal safety, BigDecimal rp,
                                  BigDecimal target, BigDecimal need, BigDecimal packs, String packName, BigDecimal factor,
                                  SupplierProduct chosen, StoreSettings st) {
        StringBuilder sb = new StringBuilder();
        if (d.signum() > 0) {
            sb.append("Vende ").append(fmt(d)).append(" ").append(unitWord(p)).append(" al día");
            if (in.trendPct() != null && in.trendPct().abs().compareTo(BigDecimal.TEN) >= 0) {
                sb.append(in.trendPct().signum() > 0 ? " (subiendo " : " (bajando ").append(fmt(in.trendPct().abs())).append("%)");
            }
            sb.append(". ");
        } else {
            sb.append("Sin ventas recientes, pero está por debajo del mínimo. ");
        }
        sb.append("Hay ").append(fmt(p.stock));
        if (in.onOrder().signum() > 0) sb.append(" y vienen ").append(fmt(in.onOrder())).append(" en camino");
        sb.append("; el proveedor tarda ").append(fmt(lead)).append(" días. ");
        sb.append("Punto de pedido ").append(fmt(rp)).append(" (incluye ").append(fmt(safety)).append(" de seguridad). ");
        sb.append("Para ").append(fmt(lead.add(BigDecimal.valueOf(st.reviewDays)))).append(" días hacen falta ").append(fmt(target))
                .append(": faltan ").append(fmt(need)).append(". ");
        if (factor.compareTo(BigDecimal.ONE) != 0 || chosen != null) {
            sb.append("Se piden ").append(fmt(packs)).append(" ").append(packName).append(" (").append(fmt(packs.multiply(factor))).append(" ").append(unitWord(p)).append(")");
            if (chosen != null && (chosen.minPacks.compareTo(BigDecimal.ONE) > 0 || chosen.multiplePacks.compareTo(BigDecimal.ONE) > 0)) {
                sb.append(" respetando el mínimo de ").append(fmt(chosen.minPacks)).append(" y múltiplos de ").append(fmt(chosen.multiplePacks));
            }
            sb.append(".");
        }
        return sb.toString().trim();
    }

    /** La próxima fecha (desde {@code from}) que coincide con los días de pedido del proveedor. */
    static LocalDate alignToOrderDay(LocalDate from, String orderDays) {
        if (orderDays == null || orderDays.isBlank()) return from;
        Set<DayOfWeek> days = new HashSet<>();
        for (String d : orderDays.split(",")) {
            switch (d.trim()) {
                case "LUN" -> days.add(DayOfWeek.MONDAY);
                case "MAR" -> days.add(DayOfWeek.TUESDAY);
                case "MIE" -> days.add(DayOfWeek.WEDNESDAY);
                case "JUE" -> days.add(DayOfWeek.THURSDAY);
                case "VIE" -> days.add(DayOfWeek.FRIDAY);
                case "SAB" -> days.add(DayOfWeek.SATURDAY);
                case "DOM" -> days.add(DayOfWeek.SUNDAY);
                default -> {
                }
            }
        }
        if (days.isEmpty()) return from;
        LocalDate x = from;
        for (int i = 0; i < 7 && !days.contains(x.getDayOfWeek()); i++) x = x.plusDays(1);
        return x;
    }

    // ---------------- Lotes, ajustes y alertas ----------------

    @Transactional(readOnly = true)
    public List<LotDto> expiring(Integer days) {
        Long t = TenantContext.require();
        StoreSettings st = settings.forTenant(t);
        LocalDate today = LocalDate.now(zone(t));
        List<ProductLot> lots = inventory.expiringBefore(t, today.plusDays(days == null ? st.expiryAlertDays : Math.max(0, Math.min(days, 3660))));
        Map<Long, Product> ps = products.findAllById(lots.stream().map(l -> l.productId).distinct().toList()).stream()
                .collect(Collectors.toMap(p -> p.id, Function.identity()));
        boolean costs = CurrentUser.get().can(Permission.COSTOS_VER);
        return lots.stream().map(l -> {
            Product p = ps.get(l.productId);
            return new LotDto(l.id, l.productId, p == null ? "?" : p.name, l.lotCode, l.expiryDate, l.remaining,
                    ChronoUnit.DAYS.between(today, l.expiryDate), costs && p != null ? l.remaining.multiply(p.cost).setScale(2, RoundingMode.HALF_UP) : null);
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<LotDto> lotsOf(Long productId) {
        Long t = TenantContext.require();
        Product p = products.findByIdAndTenantId(productId, t).orElseThrow();
        LocalDate today = LocalDate.now(zone(t));
        return inventory.lotsOf(p.id).stream().map(l -> new LotDto(l.id, p.id, p.name, l.lotCode, l.expiryDate, l.remaining,
                l.expiryDate == null ? 9999 : ChronoUnit.DAYS.between(today, l.expiryDate), null)).toList();
    }

    /** Ajustes manuales por empleado en la ventana de análisis (para detectar patrones). */
    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public List<AdjustmentByUser> adjustmentsByUser(Integer days) {
        Long t = TenantContext.require();
        int d = days == null ? settings.forTenant(t).analysisDays : Math.max(1, Math.min(days, 3660));
        List<Object[]> rows = em.createNativeQuery("""
                        select coalesce(m.user_name, 'Sistema'), count(*),
                               sum(case when m.quantity > 0 then m.quantity else 0 end),
                               sum(case when m.quantity < 0 then -m.quantity else 0 end),
                               sum(case when m.quantity < 0 then -m.quantity * coalesce(m.unit_cost, 0) else 0 end)
                        from stock_movements m
                        where m.tenant_id = :t and m.type in ('AJUSTE_ENTRADA','AJUSTE_SALIDA','DANADO','CONTEO') and m.created_at >= :from
                        group by coalesce(m.user_name, 'Sistema') order by 5 desc""")
                .setParameter("t", t).setParameter("from", Timestamp.from(Instant.now().minus(d, ChronoUnit.DAYS))).getResultList();
        boolean costs = CurrentUser.get().can(Permission.COSTOS_VER);
        return rows.stream().map(r -> new AdjustmentByUser((String) r[0], ((Number) r[1]).intValue(), dec(r[2]), dec(r[3]),
                costs ? dec(r[4]).setScale(2, RoundingMode.HALF_UP) : null)).toList();
    }

    /** Alertas de inventario y compras para el panel y la campana. */
    @Transactional(readOnly = true)
    public List<Alert> alerts() {
        Long t = TenantContext.require();
        StoreSettings st = settings.forTenant(t);
        List<Alert> out = new ArrayList<>();
        Insights ins = insights();
        for (ProductInsight p : ins.products()) {
            if (p.flags().contains("AGOTADO")) {
                out.add(new Alert("AGOTADO", "CRITICA", p.name() + " está agotado",
                        p.dailyVelocity().signum() > 0 ? "Se venden " + fmt(p.dailyVelocity()) + " al día" : "Sin existencias", p.productId(), null));
            } else if (p.flags().contains("AGOTAMIENTO_INMINENTE")) {
                out.add(new Alert("AGOTAMIENTO_INMINENTE", "ALTA", p.name() + " se acaba en " + fmt(p.coverageDays()) + " días",
                        "Quedan " + fmt(p.stock()) + " y se venden " + fmt(p.dailyVelocity()) + " al día", p.productId(), null));
            } else if (p.flags().contains("BAJO_MINIMO")) {
                out.add(new Alert("STOCK_MINIMO", "MEDIA", p.name() + " llegó al mínimo", "Quedan " + fmt(p.stock()) + " (mínimo " + fmt(p.stockMin()) + ")",
                        p.productId(), null));
            }
        }
        for (LotDto l : expiring(st.expiryAlertDays)) {
            out.add(new Alert(l.daysLeft() < 0 ? "VENCIDO" : "POR_VENCER", l.daysLeft() < 0 ? "CRITICA" : "MEDIA",
                    l.productName() + (l.daysLeft() < 0 ? " vencido" : " vence en " + l.daysLeft() + " días"),
                    "Lote " + (l.lotCode() == null ? "sin código" : l.lotCode()) + " · " + fmt(l.remaining()) + " unidades", l.productId(), l.id()));
        }
        for (PurchaseService.OrderDto o : purchases.late()) {
            out.add(new Alert("PEDIDO_RETRASADO", "ALTA", "Pedido #" + o.number() + " de " + o.supplierName() + " retrasado",
                    "Se esperaba el " + o.expectedDate(), null, o.id()));
        }
        for (PurchaseService.OrderDto o : purchases.list("POR_APROBAR", null)) {
            out.add(new Alert("PEDIDO_POR_APROBAR", "MEDIA", "Pedido #" + o.number() + " de " + o.supplierName() + " espera aprobación",
                    o.estimatedTotal() == null ? "" : "Total estimado " + com.tiendaposs.common.Money.fmt(o.estimatedTotal()), null, o.id()));
        }
        unusualMovements(t, st).forEach(out::add);
        Map<String, Integer> rank = Map.of("CRITICA", 0, "ALTA", 1, "MEDIA", 2);
        out.sort(Comparator.comparing((Alert a) -> rank.getOrDefault(a.severity(), 3)));
        return out;
    }

    /** Ajustes y conteos de los últimos 7 días con diferencias grandes (posibles pérdidas o errores). */
    @SuppressWarnings("unchecked")
    private List<Alert> unusualMovements(Long t, StoreSettings st) {
        List<Object[]> rows = em.createNativeQuery("""
                        select m.id, m.product_id, p.name, m.quantity, coalesce(m.unit_cost, p.cost), m.stock_after, m.user_name, m.type
                        from stock_movements m join products p on p.id = m.product_id
                        where m.tenant_id = :t and m.type in ('AJUSTE_SALIDA','DANADO','CONTEO') and m.quantity < 0 and m.created_at >= :from""")
                .setParameter("t", t).setParameter("from", Timestamp.from(Instant.now().minus(7, ChronoUnit.DAYS))).getResultList();
        List<Alert> out = new ArrayList<>();
        for (Object[] r : rows) {
            BigDecimal qty = dec(r[3]);
            BigDecimal before = dec(r[5]).subtract(qty);
            if (CountService.isUnusual(qty, before, dec(r[4]), st)) {
                out.add(new Alert("DIFERENCIA_INUSUAL", "ALTA", "Diferencia inusual en " + r[2],
                        fmt(qty.negate()) + " unidades menos (" + ("CONTEO".equals(r[7]) ? "conteo" : "ajuste") + " de " + (r[6] == null ? "sistema" : r[6]) + ")",
                        ((Number) r[1]).longValue(), ((Number) r[0]).longValue()));
            }
        }
        return out;
    }

    // ---------------- utilidades ----------------

    static BigDecimal demand(BigDecimal soldWindow, BigDecimal sold7, int window) {
        BigDecimal avgWindow = soldWindow.divide(BigDecimal.valueOf(Math.max(window, 1)), 4, RoundingMode.HALF_UP);
        BigDecimal avg7 = sold7.divide(BigDecimal.valueOf(7), 4, RoundingMode.HALF_UP);
        return avg7.multiply(BigDecimal.valueOf(0.6)).add(avgWindow.multiply(BigDecimal.valueOf(0.4))).setScale(3, RoundingMode.HALF_UP);
    }

    private static BigDecimal revenue(Map<Long, SalesAgg> m, Long id) {
        SalesAgg a = m.get(id);
        return a == null ? BigDecimal.ZERO : a.revenue();
    }

    private ZoneId zone(Long tenantId) {
        return ZoneId.of(tenants.findById(tenantId).map(Tenant::getTimezone).orElse("America/Bogota"));
    }

    private static String unitWord(Product p) {
        return switch (p.unit) {
            case "KILOGRAMO" -> "kg";
            case "GRAMO" -> "g";
            case "LITRO" -> "L";
            case "MILILITRO" -> "ml";
            default -> "unidades";
        };
    }

    private static String fmt(BigDecimal v) {
        if (v == null) return "—";
        BigDecimal x = v.abs().compareTo(BigDecimal.TEN) >= 0 ? v.setScale(0, RoundingMode.HALF_UP) : v.setScale(1, RoundingMode.HALF_UP);
        return x.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private static BigDecimal dec(Object o) {
        return o == null ? BigDecimal.ZERO : o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
    }

    private static Instant instant(Object o) {
        if (o == null) return null;
        if (o instanceof Timestamp ts) return ts.toInstant();
        if (o instanceof Instant i) return i;
        if (o instanceof OffsetDateTime odt) return odt.toInstant();
        return Instant.parse(o.toString());
    }
}
