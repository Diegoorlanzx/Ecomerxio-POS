package com.tiendaposs.analytics;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.common.ApiException;
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
 * Analítica de ventas y rentabilidad. Todo se calcula sobre ventas PAGADAS, descontando lo devuelto
 * (cada devolución reduce la venta original). "Ingreso" = lo cobrado con impuestos; "ingreso neto" =
 * sin impuestos, que es la base de la utilidad y del margen. El costo es el registrado al vender.
 */
@Service
public class AnalyticsService {

    static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    static final String[] WEEKDAYS = {"Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo"};

    private final EntityManager em;
    private final SettingsService settings;
    private final TenantRepository tenants;
    private final AuditService audit;

    public AnalyticsService(EntityManager em, SettingsService settings, TenantRepository tenants, AuditService audit) {
        this.em = em;
        this.settings = settings;
        this.tenants = tenants;
        this.audit = audit;
    }

    // =====================================================================
    // Hechos de venta (una fila por línea vendida)
    // =====================================================================

    record Fact(long saleId, LocalDateTime at, Long userId, String userName, Long shiftId, Long productId, String productName,
                Long categoryId, String brand, BigDecimal units, BigDecimal revenue, BigDecimal revenueEx, BigDecimal cost,
                BigDecimal discount, BigDecimal tax) {
    }

    @SuppressWarnings("unchecked")
    List<Fact> facts(Long t, ZoneId z, LocalDate from, LocalDate to, Long productId) {
        List<Object[]> rows = em.createNativeQuery("""
                        select s.id, s.completed_at, s.user_id, s.user_name, s.shift_id, l.product_id, l.product_name, p.category_id, p.brand,
                               (l.quantity - l.returned_qty) * l.factor,
                               case when l.quantity > 0 then l.line_total * (l.quantity - l.returned_qty) / l.quantity else 0 end,
                               case when l.quantity > 0 then (l.line_total - l.tax) * (l.quantity - l.returned_qty) / l.quantity else 0 end,
                               l.unit_cost * (l.quantity - l.returned_qty),
                               case when l.quantity > 0 then l.discount * (l.quantity - l.returned_qty) / l.quantity else 0 end,
                               case when l.quantity > 0 then l.tax * (l.quantity - l.returned_qty) / l.quantity else 0 end
                        from sale_lines l join sales s on s.id = l.sale_id left join products p on p.id = l.product_id
                        where s.tenant_id = :t and s.status = 'PAGADA' and s.completed_at >= :f and s.completed_at < :tt
                          and (cast(:pid as bigint) is null or l.product_id = :pid)""")
                .setParameter("t", t).setParameter("f", ts(from, z)).setParameter("tt", ts(to.plusDays(1), z))
                .setParameter("pid", productId).getResultList();
        List<Fact> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(new Fact(((Number) r[0]).longValue(), LocalDateTime.ofInstant(instant(r[1]), z), lng(r[2]), (String) r[3], lng(r[4]),
                    lng(r[5]), (String) r[6], lng(r[7]), (String) r[8], dec(r[9]), dec(r[10]), dec(r[11]), dec(r[12]), dec(r[13]), dec(r[14])));
        }
        return out;
    }

    // =====================================================================
    // Indicadores
    // =====================================================================

    public record Kpis(BigDecimal sales, BigDecimal revenueEx, BigDecimal cost, BigDecimal grossProfit, BigDecimal marginPct,
                       long transactions, BigDecimal units, BigDecimal avgTicket, BigDecimal discounts, BigDecimal taxes,
                       BigDecimal refunds, long voidedCount, BigDecimal voidedTotal) {
    }

    public Kpis kpis(Long t, ZoneId z, LocalDate from, LocalDate to, boolean costs) {
        Object[] a = (Object[]) em.createNativeQuery("""
                        select count(distinct s.id),
                               coalesce(sum(case when l.quantity > 0 then l.line_total * (l.quantity - l.returned_qty) / l.quantity else 0 end),0),
                               coalesce(sum(case when l.quantity > 0 then (l.line_total - l.tax) * (l.quantity - l.returned_qty) / l.quantity else 0 end),0),
                               coalesce(sum(l.unit_cost * (l.quantity - l.returned_qty)),0),
                               coalesce(sum((l.quantity - l.returned_qty) * l.factor),0),
                               coalesce(sum(case when l.quantity > 0 then l.discount * (l.quantity - l.returned_qty) / l.quantity else 0 end),0),
                               coalesce(sum(case when l.quantity > 0 then l.tax * (l.quantity - l.returned_qty) / l.quantity else 0 end),0)
                        from sales s join sale_lines l on l.sale_id = s.id
                        where s.tenant_id = :t and s.status = 'PAGADA' and s.completed_at >= :f and s.completed_at < :tt""")
                .setParameter("t", t).setParameter("f", ts(from, z)).setParameter("tt", ts(to.plusDays(1), z)).getSingleResult();
        Object[] v = (Object[]) em.createNativeQuery("""
                        select count(*), coalesce(sum(total),0) from sales
                        where tenant_id = :t and status = 'ANULADA' and completed_at >= :f and completed_at < :tt""")
                .setParameter("t", t).setParameter("f", ts(from, z)).setParameter("tt", ts(to.plusDays(1), z)).getSingleResult();
        BigDecimal refunds = dec(em.createNativeQuery("""
                        select coalesce(sum(r.refund_amount),0) from sale_returns r join sales s on s.id = r.sale_id
                        where s.tenant_id = :t and s.completed_at >= :f and s.completed_at < :tt""")
                .setParameter("t", t).setParameter("f", ts(from, z)).setParameter("tt", ts(to.plusDays(1), z)).getSingleResult());
        long tx = ((Number) a[0]).longValue();
        BigDecimal sales = m(dec(a[1])), revEx = m(dec(a[2])), cost = m(dec(a[3]));
        BigDecimal profit = revEx.subtract(cost);
        return new Kpis(sales, revEx, costs ? cost : null, costs ? profit : null, costs ? pct(profit, revEx) : null, tx,
                dec(a[4]).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros(), tx == 0 ? BigDecimal.ZERO : sales.divide(BigDecimal.valueOf(tx), 0, RoundingMode.HALF_UP),
                m(dec(a[5])), m(dec(a[6])), m(refunds), ((Number) v[0]).longValue(), m(dec(v[1])));
    }

    // =====================================================================
    // Informe de ventas
    // =====================================================================

    public record Point(String key, String label, BigDecimal sales, long transactions, BigDecimal profit) {
    }

    public record Group(String key, String label, BigDecimal units, BigDecimal sales, BigDecimal revenueEx, BigDecimal profit,
                        BigDecimal marginPct, long transactions, BigDecimal sharePct) {
    }

    public record ProductRow(Long productId, String name, String categoryName, String brand, BigDecimal units, BigDecimal sales,
                             BigDecimal revenueEx, BigDecimal cost, BigDecimal profit, BigDecimal marginPct, BigDecimal profitPerUnit,
                             BigDecimal discount, long transactions, BigDecimal prevUnits, BigDecimal prevSales, BigDecimal changePct) {
    }

    public record Report(LocalDate from, LocalDate to, LocalDate prevFrom, LocalDate prevTo, Kpis current, Kpis previous,
                         Map<String, BigDecimal> changes, List<Point> days, List<Point> hours, List<Point> weekdays, List<Point> months,
                         List<ProductRow> products, List<Group> categories, List<Group> brands, List<Group> employees, List<Group> shifts,
                         List<Group> methods, List<ProductRow> declining, String bestWeekday, Integer bestHour, boolean costs) {
    }

    @Transactional(readOnly = true)
    public Report report(LocalDate from, LocalDate to) {
        CurrentUser.require(Permission.REPORTES_VER);
        Long t = TenantContext.require();
        ZoneId z = zone(t);
        StoreSettings st = settings.forTenant(t);
        boolean costs = CurrentUser.get().can(Permission.COSTOS_VER);
        long len = ChronoUnit.DAYS.between(from, to) + 1;
        LocalDate pTo = from.minusDays(1), pFrom = pTo.minusDays(len - 1);
        List<Fact> cur = facts(t, z, from, to, null);
        List<Fact> prev = facts(t, z, pFrom, pTo, null);
        Kpis k = kpis(t, z, from, to, costs), pk = kpis(t, z, pFrom, pTo, costs);
        Map<String, BigDecimal> changes = new LinkedHashMap<>();
        changes.put("sales", change(k.sales(), pk.sales()));
        changes.put("transactions", change(BigDecimal.valueOf(k.transactions()), BigDecimal.valueOf(pk.transactions())));
        changes.put("avgTicket", change(k.avgTicket(), pk.avgTicket()));
        changes.put("units", change(k.units(), pk.units()));
        if (costs) changes.put("grossProfit", change(k.grossProfit(), pk.grossProfit()));

        // Series por día, hora y día de la semana.
        Map<LocalDate, Agg> byDay = new TreeMap<>();
        for (LocalDate d = from; !d.isAfter(to) && byDay.size() < 400; d = d.plusDays(1)) byDay.put(d, new Agg());
        Agg[] hours = new Agg[24], wd = new Agg[7];
        for (int i = 0; i < 24; i++) hours[i] = new Agg();
        for (int i = 0; i < 7; i++) wd[i] = new Agg();
        for (Fact f : cur) {
            Agg d = byDay.get(f.at().toLocalDate());
            if (d != null) d.add(f);
            hours[f.at().getHour()].add(f);
            wd[f.at().getDayOfWeek().getValue() - 1].add(f);
        }
        List<Point> days = byDay.entrySet().stream().map(e -> e.getValue().point(e.getKey().toString(), e.getKey().toString(), costs)).toList();
        List<Point> hourPts = new ArrayList<>();
        for (int h = 0; h < 24; h++) hourPts.add(hours[h].point(String.valueOf(h), String.format("%02d:00", h), costs));
        int[] wdCount = new int[7];
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) wdCount[d.getDayOfWeek().getValue() - 1]++;
        List<Point> wdPts = new ArrayList<>();
        for (int i = 0; i < 7; i++) wdPts.add(wd[i].point(String.valueOf(i + 1), WEEKDAYS[i], costs));
        // Mejor día = mayor promedio por día de ese tipo dentro del período.
        String bestWd = null;
        BigDecimal bestAvg = BigDecimal.ZERO;
        for (int i = 0; i < 7; i++) {
            if (wdCount[i] == 0) continue;
            BigDecimal avg = wd[i].sales.divide(BigDecimal.valueOf(wdCount[i]), 2, RoundingMode.HALF_UP);
            if (avg.compareTo(bestAvg) > 0) {
                bestAvg = avg;
                bestWd = WEEKDAYS[i];
            }
        }
        Integer bestHour = null;
        for (int h = 0; h < 24; h++) if (hours[h].sales.signum() > 0 && (bestHour == null || hours[h].sales.compareTo(hours[bestHour].sales) > 0)) bestHour = h;

        Map<Long, String> catNames = categoryNames(t);
        List<ProductRow> products = productRows(cur, prev, catNames, costs);
        BigDecimal total = k.sales();
        List<Group> categories = group(cur, f -> f.categoryId() == null ? "0" : f.categoryId().toString(),
                f -> f.categoryId() == null ? "Sin categoría" : catNames.getOrDefault(f.categoryId(), "Sin categoría"), total, costs);
        List<Group> brands = group(cur, f -> f.brand() == null || f.brand().isBlank() ? "-" : f.brand().trim().toLowerCase(),
                f -> f.brand() == null || f.brand().isBlank() ? "Sin marca" : f.brand().trim(), total, costs);
        List<Group> employees = group(cur, f -> String.valueOf(f.userId()), Fact::userName, total, costs);
        Map<Long, String> shiftNames = shiftNames(t, cur.stream().map(Fact::shiftId).filter(Objects::nonNull).collect(Collectors.toSet()), z);
        List<Group> shifts = group(cur, f -> f.shiftId() == null ? "0" : f.shiftId().toString(),
                f -> f.shiftId() == null ? "Sin turno" : shiftNames.getOrDefault(f.shiftId(), "Turno"), total, costs);
        List<Group> methods = methods(t, z, from, to);
        BigDecimal threshold = st.decliningPct.negate();
        List<ProductRow> declining = products.stream()
                .filter(p -> p.prevSales().signum() > 0 && p.changePct() != null && p.changePct().compareTo(threshold) <= 0)
                .sorted(Comparator.comparing(ProductRow::changePct)).toList();
        return new Report(from, to, pFrom, pTo, k, pk, changes, days, hourPts, wdPts, months(t, z, to, costs),
                products.stream().filter(p -> p.units().signum() != 0 || p.sales().signum() != 0).toList(),
                categories, brands, employees, shifts, methods, declining, bestWd, bestHour, costs);
    }

    /** Últimos 12 meses (hasta el mes de `to`): para ver la temporada. */
    @SuppressWarnings("unchecked")
    List<Point> months(Long t, ZoneId z, LocalDate to, boolean costs) {
        YearMonth last = YearMonth.from(to);
        YearMonth first = last.minusMonths(11);
        Map<YearMonth, BigDecimal[]> map = new TreeMap<>();
        for (YearMonth ym = first; !ym.isAfter(last); ym = ym.plusMonths(1)) map.put(ym, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
        List<Object[]> rows = em.createNativeQuery("""
                        select to_char(s.completed_at at time zone :tz, 'YYYY-MM'), count(distinct s.id),
                               coalesce(sum(case when l.quantity > 0 then l.line_total * (l.quantity - l.returned_qty) / l.quantity else 0 end),0),
                               coalesce(sum(case when l.quantity > 0 then (l.line_total - l.tax) * (l.quantity - l.returned_qty) / l.quantity else 0 end
                                            - l.unit_cost * (l.quantity - l.returned_qty)),0)
                        from sales s join sale_lines l on l.sale_id = s.id
                        where s.tenant_id = :t and s.status = 'PAGADA' and s.completed_at >= :f and s.completed_at < :tt group by 1""")
                .setParameter("tz", z.getId()).setParameter("t", t).setParameter("f", ts(first.atDay(1), z))
                .setParameter("tt", ts(last.plusMonths(1).atDay(1), z)).getResultList();
        for (Object[] r : rows) {
            BigDecimal[] v = map.get(YearMonth.parse((String) r[0]));
            if (v != null) {
                v[0] = dec(r[1]);
                v[1] = dec(r[2]);
                v[2] = dec(r[3]);
            }
        }
        String[] names = {"Ene", "Feb", "Mar", "Abr", "May", "Jun", "Jul", "Ago", "Sep", "Oct", "Nov", "Dic"};
        return map.entrySet().stream().map(e -> new Point(e.getKey().toString(), names[e.getKey().getMonthValue() - 1] + " " + (e.getKey().getYear() % 100),
                m(e.getValue()[1]), e.getValue()[0].longValue(), costs ? m(e.getValue()[2]) : null)).toList();
    }

    @SuppressWarnings("unchecked")
    public List<Group> methods(Long t, ZoneId z, LocalDate from, LocalDate to) {
        List<Object[]> rows = em.createNativeQuery("""
                        select p.method, count(distinct p.sale_id), coalesce(sum(p.amount),0) from payments p join sales s on s.id = p.sale_id
                        where s.tenant_id = :t and s.status = 'PAGADA' and p.status not in ('RECHAZADO','ANULADO')
                          and s.completed_at >= :f and s.completed_at < :tt group by p.method order by 3 desc""")
                .setParameter("t", t).setParameter("f", ts(from, z)).setParameter("tt", ts(to.plusDays(1), z)).getResultList();
        BigDecimal total = rows.stream().map(r -> dec(r[2])).reduce(BigDecimal.ZERO, BigDecimal::add);
        return rows.stream().map(r -> new Group((String) r[0], (String) r[0], null, m(dec(r[2])), null, null, null,
                ((Number) r[1]).longValue(), pct(dec(r[2]), total))).toList();
    }

    List<ProductRow> productRows(List<Fact> cur, List<Fact> prev, Map<Long, String> catNames, boolean costs) {
        Map<Long, Agg> c = new LinkedHashMap<>(), p = new HashMap<>();
        Map<Long, Fact> sample = new HashMap<>();
        for (Fact f : cur) {
            c.computeIfAbsent(f.productId(), x -> new Agg()).add(f);
            sample.putIfAbsent(f.productId(), f);
        }
        for (Fact f : prev) {
            p.computeIfAbsent(f.productId(), x -> new Agg()).add(f);
            sample.putIfAbsent(f.productId(), f);
        }
        Set<Long> ids = new LinkedHashSet<>(c.keySet());
        ids.addAll(p.keySet());
        List<ProductRow> out = new ArrayList<>();
        for (Long id : ids) {
            Agg a = c.getOrDefault(id, new Agg()), b = p.getOrDefault(id, new Agg());
            Fact s = sample.get(id);
            BigDecimal profit = a.revEx.subtract(a.cost);
            out.add(new ProductRow(id, s.productName(), s.categoryId() == null ? null : catNames.get(s.categoryId()), s.brand(), q(a.units), m(a.sales),
                    m(a.revEx), costs ? m(a.cost) : null, costs ? m(profit) : null, costs ? pct(profit, a.revEx) : null,
                    costs && a.units.signum() > 0 ? profit.divide(a.units, 0, RoundingMode.HALF_UP) : null, m(a.discount), a.sales().size(),
                    q(b.units), m(b.sales), change(a.sales, b.sales)));
        }
        out.sort(Comparator.comparing(ProductRow::sales).reversed());
        return out;
    }

    List<Group> group(List<Fact> facts, Function<Fact, String> key, Function<Fact, String> label, BigDecimal total, boolean costs) {
        Map<String, Agg> map = new LinkedHashMap<>();
        Map<String, String> labels = new HashMap<>();
        for (Fact f : facts) {
            String k = key.apply(f);
            map.computeIfAbsent(k, x -> new Agg()).add(f);
            labels.putIfAbsent(k, label.apply(f));
        }
        return map.entrySet().stream().map(e -> {
            Agg a = e.getValue();
            BigDecimal profit = a.revEx.subtract(a.cost);
            return new Group(e.getKey(), labels.get(e.getKey()), q(a.units), m(a.sales), m(a.revEx), costs ? m(profit) : null,
                    costs ? pct(profit, a.revEx) : null, a.sales().size(), pct(a.sales, total));
        }).sorted(Comparator.comparing(Group::sales).reversed()).toList();
    }

    /** Acumulador. `sales()` devuelve las ventas distintas para contar transacciones. */
    static final class Agg {
        BigDecimal units = BigDecimal.ZERO, sales = BigDecimal.ZERO, revEx = BigDecimal.ZERO, cost = BigDecimal.ZERO, discount = BigDecimal.ZERO;
        final Set<Long> ids = new HashSet<>();

        void add(Fact f) {
            units = units.add(f.units());
            sales = sales.add(f.revenue());
            revEx = revEx.add(f.revenueEx());
            cost = cost.add(f.cost());
            discount = discount.add(f.discount());
            ids.add(f.saleId());
        }

        Set<Long> sales() {
            return ids;
        }

        Point point(String key, String label, boolean costs) {
            return new Point(key, label, m(sales), ids.size(), costs ? m(revEx.subtract(cost)) : null);
        }
    }

    // =====================================================================
    // Rentabilidad por producto
    // =====================================================================

    public record ProfitRow(Long productId, String name, String categoryName, String brand, BigDecimal units, BigDecimal revenueEx,
                            BigDecimal cost, BigDecimal grossProfit, BigDecimal marginPct, BigDecimal avgPrice, BigDecimal avgCost,
                            BigDecimal profitPerUnit, BigDecimal discount, BigDecimal marginWithoutDiscountPct, BigDecimal allocatedExpenses,
                            BigDecimal profitAfterCosts, BigDecimal currentPrice, BigDecimal currentCost, BigDecimal currentMarginPct,
                            BigDecimal profitSharePct, String quadrant) {
    }

    public record Profitability(LocalDate from, LocalDate to, BigDecimal revenueEx, BigDecimal cost, BigDecimal grossProfit,
                                BigDecimal marginPct, BigDecimal discounts, BigDecimal marginWithoutDiscountsPct, BigDecimal expenses,
                                BigDecimal netProfit, BigDecimal netMarginPct, boolean allocateExpenses, BigDecimal lowMarginPct,
                                BigDecimal highMarginPct, List<ProfitRow> products, List<Group> categories,
                                Map<String, Integer> quadrantCounts) {
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public Profitability profitability(LocalDate from, LocalDate to) {
        CurrentUser.require(Permission.COSTOS_VER);
        Long t = TenantContext.require();
        ZoneId z = zone(t);
        StoreSettings st = settings.forTenant(t);
        List<Fact> facts = facts(t, z, from, to, null);
        Map<Long, String> catNames = categoryNames(t);
        BigDecimal expenses = dec(em.createNativeQuery("""
                        select coalesce(sum(amount),0) from finance_entries
                        where tenant_id = :t and kind = 'GASTO' and voided_at is null and entry_date >= :f and entry_date <= :tt""")
                .setParameter("t", t).setParameter("f", from).setParameter("tt", to).getSingleResult());
        Map<Long, Object[]> current = new HashMap<>();
        for (Object[] r : (List<Object[]>) em.createNativeQuery("""
                        select p.id, p.cost, pr.price from products p
                        left join product_presentations pr on pr.product_id = p.id and pr.is_base
                        where p.tenant_id = :t""").setParameter("t", t).getResultList()) {
            current.put(((Number) r[0]).longValue(), r);
        }
        Map<Long, Agg> by = new LinkedHashMap<>();
        Map<Long, Fact> sample = new HashMap<>();
        for (Fact f : facts) {
            by.computeIfAbsent(f.productId(), x -> new Agg()).add(f);
            sample.putIfAbsent(f.productId(), f);
        }
        BigDecimal revEx = BigDecimal.ZERO, cost = BigDecimal.ZERO, disc = BigDecimal.ZERO;
        for (Agg a : by.values()) {
            revEx = revEx.add(a.revEx);
            cost = cost.add(a.cost);
            disc = disc.add(a.discount);
        }
        BigDecimal gross = revEx.subtract(cost);
        // Clasificación: "vende mucho" = unidades por encima de la mediana de los productos vendidos.
        List<BigDecimal> unitsSorted = by.values().stream().map(a -> a.units).sorted().toList();
        BigDecimal median = unitsSorted.isEmpty() ? BigDecimal.ZERO : unitsSorted.get(unitsSorted.size() / 2);
        List<ProfitRow> rows = new ArrayList<>();
        Map<String, Integer> quadrants = new LinkedHashMap<>();
        for (String qn : List.of("ESTRELLA", "VOLUMEN_BAJO_MARGEN", "NICHO_RENTABLE", "REVISAR", "NORMAL")) quadrants.put(qn, 0);
        for (Map.Entry<Long, Agg> e : by.entrySet()) {
            Agg a = e.getValue();
            Fact s = sample.get(e.getKey());
            BigDecimal profit = a.revEx.subtract(a.cost);
            BigDecimal margin = pct(profit, a.revEx);
            // Sin descuentos: el ingreso habría sido mayor en lo descontado (sin impuesto, aproximado con la proporción del ingreso).
            BigDecimal discEx = a.sales.signum() == 0 ? BigDecimal.ZERO : a.discount.multiply(a.revEx).divide(a.sales, 2, RoundingMode.HALF_UP);
            BigDecimal marginNoDisc = pct(profit.add(discEx), a.revEx.add(discEx));
            BigDecimal alloc = st.allocateExpenses && revEx.signum() > 0 ? expenses.multiply(a.revEx).divide(revEx, 2, RoundingMode.HALF_UP) : null;
            Object[] cur = current.get(e.getKey());
            BigDecimal cCost = cur == null ? null : dec(cur[1]).setScale(2, RoundingMode.HALF_UP);
            BigDecimal cPrice = cur == null || cur[2] == null ? null : dec(cur[2]);
            boolean high = a.units.compareTo(median) >= 0 && a.units.signum() > 0;
            String quadrant = margin.compareTo(st.highMarginPct) >= 0 ? (high ? "ESTRELLA" : "NICHO_RENTABLE")
                    : margin.compareTo(st.lowMarginPct) < 0 ? (high ? "VOLUMEN_BAJO_MARGEN" : "REVISAR") : "NORMAL";
            quadrants.merge(quadrant, 1, Integer::sum);
            rows.add(new ProfitRow(e.getKey(), s.productName(), s.categoryId() == null ? null : catNames.get(s.categoryId()), s.brand(), q(a.units),
                    m(a.revEx), m(a.cost), m(profit), margin,
                    a.units.signum() > 0 ? a.revEx.divide(a.units, 0, RoundingMode.HALF_UP) : null,
                    a.units.signum() > 0 ? a.cost.divide(a.units, 0, RoundingMode.HALF_UP) : null,
                    a.units.signum() > 0 ? profit.divide(a.units, 0, RoundingMode.HALF_UP) : null,
                    m(a.discount), marginNoDisc, alloc, alloc == null ? null : m(profit.subtract(alloc)), cPrice, cCost,
                    cPrice == null || cCost == null || cPrice.signum() == 0 ? null : pct(cPrice.subtract(cCost), cPrice),
                    pct(profit, gross), quadrant));
        }
        rows.sort(Comparator.comparing(ProfitRow::grossProfit).reversed());
        BigDecimal discEx = revEx.add(disc).signum() == 0 ? BigDecimal.ZERO : disc;
        List<Group> categories = group(facts, f -> f.categoryId() == null ? "0" : f.categoryId().toString(),
                f -> f.categoryId() == null ? "Sin categoría" : catNames.getOrDefault(f.categoryId(), "Sin categoría"),
                facts.stream().map(Fact::revenue).reduce(BigDecimal.ZERO, BigDecimal::add), true);
        return new Profitability(from, to, m(revEx), m(cost), m(gross), pct(gross, revEx), m(disc), pct(gross.add(discEx), revEx.add(discEx)),
                m(expenses), m(gross.subtract(expenses)), pct(gross.subtract(expenses), revEx), st.allocateExpenses, st.lowMarginPct,
                st.highMarginPct, rows, categories, quadrants);
    }

    public record PricePoint(Instant at, String field, String presentation, BigDecimal oldValue, BigDecimal newValue, String reason, String userName) {
    }

    public record CostPoint(Instant at, String type, BigDecimal unitCost, BigDecimal quantity, String reason) {
    }

    public record MonthPoint(String month, BigDecimal units, BigDecimal avgPrice, BigDecimal avgCost, BigDecimal marginPct) {
    }

    public record Evolution(Long productId, String name, BigDecimal currentPrice, BigDecimal currentCost, List<PricePoint> priceChanges,
                            List<CostPoint> purchaseCosts, List<MonthPoint> months) {
    }

    /** Evolución del costo de compra y del precio de venta de un producto. */
    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public Evolution evolution(Long productId) {
        CurrentUser.require(Permission.COSTOS_VER);
        Long t = TenantContext.require();
        ZoneId z = zone(t);
        List<Object[]> p = em.createNativeQuery("""
                        select p.name, p.cost, pr.price from products p left join product_presentations pr on pr.product_id = p.id and pr.is_base
                        where p.id = :id and p.tenant_id = :t""").setParameter("id", productId).setParameter("t", t).getResultList();
        if (p.isEmpty()) throw ApiException.notFound("Producto no encontrado");
        List<PricePoint> prices = ((List<Object[]>) em.createNativeQuery("""
                        select h.created_at, h.field, pr.name, h.old_value, h.new_value, h.reason, h.user_name from price_history h
                        left join product_presentations pr on pr.id = h.presentation_id
                        where h.product_id = :id and h.tenant_id = :t order by h.created_at""")
                .setParameter("id", productId).setParameter("t", t).getResultList()).stream()
                .map(r -> new PricePoint(instant(r[0]), (String) r[1], (String) r[2], r[3] == null ? null : dec(r[3]), r[4] == null ? null : dec(r[4]),
                        (String) r[5], (String) r[6])).toList();
        List<CostPoint> costs = ((List<Object[]>) em.createNativeQuery("""
                        select m.created_at, m.type, m.unit_cost, m.quantity, m.reason from stock_movements m
                        where m.product_id = :id and m.tenant_id = :t and m.unit_cost is not null and m.quantity > 0
                          and m.type in ('COMPRA','INICIAL','AJUSTE_ENTRADA','IMPORTACION') order by m.created_at""")
                .setParameter("id", productId).setParameter("t", t).getResultList()).stream()
                .map(r -> new CostPoint(instant(r[0]), (String) r[1], dec(r[2]).setScale(2, RoundingMode.HALF_UP), dec(r[3]), (String) r[4])).toList();
        LocalDate today = LocalDate.now(z);
        Map<YearMonth, Agg> byMonth = new TreeMap<>();
        for (YearMonth ym = YearMonth.from(today).minusMonths(11); !ym.isAfter(YearMonth.from(today)); ym = ym.plusMonths(1)) byMonth.put(ym, new Agg());
        for (Fact f : facts(t, z, YearMonth.from(today).minusMonths(11).atDay(1), today, productId)) {
            Agg a = byMonth.get(YearMonth.from(f.at()));
            if (a != null) a.add(f);
        }
        List<MonthPoint> months = byMonth.entrySet().stream().map(e -> {
            Agg a = e.getValue();
            boolean any = a.units.signum() > 0;
            return new MonthPoint(e.getKey().toString(), q(a.units), any ? a.revEx.divide(a.units, 0, RoundingMode.HALF_UP) : null,
                    any ? a.cost.divide(a.units, 0, RoundingMode.HALF_UP) : null, any ? pct(a.revEx.subtract(a.cost), a.revEx) : null);
        }).toList();
        Object[] r = p.get(0);
        return new Evolution(productId, (String) r[0], r[2] == null ? null : dec(r[2]), dec(r[1]).setScale(2, RoundingMode.HALF_UP), prices, costs, months);
    }

    // =====================================================================
    // Exportación
    // =====================================================================

    @Transactional
    public String exportCsv(String type, LocalDate from, LocalDate to) {
        CurrentUser.require(Permission.REPORTES_EXPORTAR);
        StringBuilder sb = new StringBuilder("﻿");
        if ("rentabilidad".equals(type)) {
            Profitability p = profitability(from, to);
            sb.append("Rentabilidad por producto;").append(from).append(" a ").append(to).append("\r\n");
            sb.append("Producto;Categoría;Unidades;Ingreso neto;Costo;Utilidad bruta;Margen %;Utilidad por unidad;Descuentos;Margen sin descuentos %;Gastos asignados;Utilidad después de gastos;Clasificación\r\n");
            for (ProfitRow r : p.products()) {
                sb.append(csv(r.name())).append(';').append(csv(r.categoryName())).append(';').append(n(r.units())).append(';').append(n(r.revenueEx()))
                        .append(';').append(n(r.cost())).append(';').append(n(r.grossProfit())).append(';').append(n(r.marginPct())).append(';')
                        .append(n(r.profitPerUnit())).append(';').append(n(r.discount())).append(';').append(n(r.marginWithoutDiscountPct())).append(';')
                        .append(n(r.allocatedExpenses())).append(';').append(n(r.profitAfterCosts())).append(';').append(r.quadrant()).append("\r\n");
            }
            sb.append("\r\nTotal ingreso neto;").append(n(p.revenueEx())).append("\r\nUtilidad bruta;").append(n(p.grossProfit()))
                    .append("\r\nGastos operativos;").append(n(p.expenses())).append("\r\nUtilidad neta estimada;").append(n(p.netProfit())).append("\r\n");
        } else {
            Report r = report(from, to);
            Kpis k = r.current();
            sb.append("Informe de ventas;").append(from).append(" a ").append(to).append("\r\n");
            sb.append("Ventas;").append(n(k.sales())).append("\r\nVentas sin impuestos;").append(n(k.revenueEx())).append("\r\nTransacciones;")
                    .append(k.transactions()).append("\r\nTicket promedio;").append(n(k.avgTicket())).append("\r\nUnidades;").append(n(k.units()))
                    .append("\r\nDescuentos;").append(n(k.discounts())).append("\r\nDevoluciones;").append(n(k.refunds()));
            if (r.costs()) sb.append("\r\nUtilidad bruta;").append(n(k.grossProfit())).append("\r\nMargen %;").append(n(k.marginPct()));
            sb.append("\r\nPeríodo anterior (").append(r.prevFrom()).append(" a ").append(r.prevTo()).append(");").append(n(r.previous().sales())).append("\r\n");
            sb.append("\r\nProducto;Categoría;Marca;Unidades;Ventas;Ventas sin impuestos;").append(r.costs() ? "Utilidad;Margen %;" : "")
                    .append("Período anterior;Cambio %\r\n");
            for (ProductRow p : r.products()) {
                sb.append(csv(p.name())).append(';').append(csv(p.categoryName())).append(';').append(csv(p.brand())).append(';').append(n(p.units()))
                        .append(';').append(n(p.sales())).append(';').append(n(p.revenueEx())).append(';');
                if (r.costs()) sb.append(n(p.profit())).append(';').append(n(p.marginPct())).append(';');
                sb.append(n(p.prevSales())).append(';').append(n(p.changePct())).append("\r\n");
            }
            appendGroups(sb, "Categoría", r.categories());
            appendGroups(sb, "Marca", r.brands());
            appendGroups(sb, "Empleado", r.employees());
            appendGroups(sb, "Turno", r.shifts());
            appendGroups(sb, "Medio de pago", r.methods());
            sb.append("\r\nDía;Ventas;Transacciones\r\n");
            for (Point d : r.days()) sb.append(d.key()).append(';').append(n(d.sales())).append(';').append(d.transactions()).append("\r\n");
            sb.append("\r\nHora;Ventas;Transacciones\r\n");
            for (Point d : r.hours()) if (d.transactions() > 0) sb.append(d.label()).append(';').append(n(d.sales())).append(';').append(d.transactions()).append("\r\n");
        }
        audit.log("REPORTE_EXPORTADO", "ANALYTICS", null, "Exportó el informe de " + ("rentabilidad".equals(type) ? "rentabilidad" : "ventas") + " del " + from + " al " + to);
        return sb.toString();
    }

    private static void appendGroups(StringBuilder sb, String title, List<Group> gs) {
        sb.append("\r\n").append(title).append(";Ventas;Transacciones;Participación %\r\n");
        for (Group g : gs) sb.append(csv(g.label())).append(';').append(n(g.sales())).append(';').append(g.transactions()).append(';').append(n(g.sharePct())).append("\r\n");
    }

    // =====================================================================
    // Utilidades
    // =====================================================================

    Map<Long, String> categoryNames(Long t) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("select id, name from categories where tenant_id = :t").setParameter("t", t).getResultList();
        Map<Long, String> out = new HashMap<>();
        for (Object[] r : rows) out.put(((Number) r[0]).longValue(), (String) r[1]);
        return out;
    }

    @SuppressWarnings("unchecked")
    Map<Long, String> shiftNames(Long t, Set<Long> ids, ZoneId z) {
        if (ids.isEmpty()) return Map.of();
        List<Object[]> rows = em.createNativeQuery("""
                        select s.id, s.number, r.name, s.user_name, s.opened_at from shifts s join registers r on r.id = s.register_id
                        where s.tenant_id = :t and s.id in (:ids)""").setParameter("t", t).setParameter("ids", ids).getResultList();
        Map<Long, String> out = new HashMap<>();
        for (Object[] r : rows) {
            LocalDate d = LocalDate.ofInstant(instant(r[4]), z);
            out.put(((Number) r[0]).longValue(), "Turno #" + r[1] + " · " + r[2] + " · " + r[3] + " · " + d);
        }
        return out;
    }

    public ZoneId zone(Long t) {
        return ZoneId.of(tenants.findById(t).map(Tenant::getTimezone).orElse("America/Bogota"));
    }

    LocalDate today() {
        return LocalDate.now(zone(TenantContext.require()));
    }

    static Timestamp ts(LocalDate d, ZoneId z) {
        return Timestamp.from(d.atStartOfDay(z).toInstant());
    }

    static Instant instant(Object o) {
        if (o instanceof Instant i) return i;
        if (o instanceof Timestamp ts) return ts.toInstant();
        if (o instanceof OffsetDateTime odt) return odt.toInstant();
        if (o instanceof ZonedDateTime zdt) return zdt.toInstant();
        if (o instanceof LocalDateTime ldt) return ldt.toInstant(ZoneOffset.UTC);
        return Instant.parse(o.toString());
    }

    static BigDecimal dec(Object o) {
        return o == null ? BigDecimal.ZERO : o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
    }

    static Long lng(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }

    static BigDecimal m(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal q(BigDecimal v) {
        return v.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        if (part == null || whole == null || whole.signum() == 0) return BigDecimal.ZERO;
        return part.multiply(HUNDRED).divide(whole, 1, RoundingMode.HALF_UP);
    }

    static BigDecimal change(BigDecimal cur, BigDecimal prev) {
        if (cur == null || prev == null || prev.signum() == 0) return null;
        return cur.subtract(prev).multiply(HUNDRED).divide(prev.abs(), 1, RoundingMode.HALF_UP);
    }

    private static String n(BigDecimal v) {
        return v == null ? "" : v.setScale(v.scale() > 2 ? 2 : Math.max(v.scale(), 0), RoundingMode.HALF_UP).toPlainString();
    }

    private static String csv(String s) {
        return s == null ? "" : s.replace(";", ",").replace("\n", " ");
    }
}
