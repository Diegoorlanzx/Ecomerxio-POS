package com.tiendaposs.analytics;

import com.tiendaposs.analytics.AnalyticsService.*;
import com.tiendaposs.finance.AlertsService;
import com.tiendaposs.payments.PaymentService;
import com.tiendaposs.pos.ShiftService;
import com.tiendaposs.purchasing.InsightsService;
import com.tiendaposs.purchasing.PurchaseService;
import com.tiendaposs.security.AuthUser;
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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** Tablero principal: cómo va el negocio hoy y qué necesita atención. */
@Service
public class DashboardService {

    private final AnalyticsService analytics;
    private final InsightsService insights;
    private final PurchaseService purchases;
    private final PaymentService payments;
    private final ShiftService shifts;
    private final AlertsService alerts;
    private final SettingsService settings;
    private final EntityManager em;

    public DashboardService(AnalyticsService analytics, InsightsService insights, PurchaseService purchases, PaymentService payments,
                            ShiftService shifts, AlertsService alerts, SettingsService settings, EntityManager em) {
        this.analytics = analytics;
        this.insights = insights;
        this.purchases = purchases;
        this.payments = payments;
        this.shifts = shifts;
        this.alerts = alerts;
        this.settings = settings;
        this.em = em;
    }

    /**
     * sales = lo vendido (incluye fiado: el inventario y la utilidad ya ocurrieron). moneyIn = el dinero que entró en el
     * período: lo cobrado en efectivo y digitales (collected) más los abonos de fiado recibidos ese día (creditPayments).
     * creditSales = lo fiado en el período (no entró dinero: queda por cobrar).
     */
    public record PeriodStat(String key, String label, LocalDate from, LocalDate to, BigDecimal sales, long transactions,
                             BigDecimal avgTicket, BigDecimal grossProfit, LocalDate prevFrom, LocalDate prevTo, BigDecimal prevSales,
                             BigDecimal changePct, BigDecimal moneyIn, BigDecimal collected, BigDecimal creditSales,
                             BigDecimal creditPayments) {
    }

    public record CashStatus(Long shiftId, long number, String registerName, String userName, BigDecimal expectedCash, BigDecimal salesTotal) {
    }

    public record StockItem(Long productId, String name, BigDecimal stock, BigDecimal coverageDays, String flag) {
    }

    public record Finance(BigDecimal grossProfit, BigDecimal marginPct, BigDecimal expenses, BigDecimal netProfit) {
    }

    public record Pending(int toOrderProducts, int toOrderCritical, BigDecimal toOrderTotal, int toReceiveOrders, int lateOrders,
                          BigDecimal toReceiveTotal, int paymentsToVerify, BigDecimal paymentsToVerifyTotal) {
    }

    public record Dashboard(LocalDate today, List<PeriodStat> periods, Finance monthFinance, List<CashStatus> cash, BigDecimal cashTotal,
                            List<Group> methodsToday, List<Point> todayByHour, List<ProductRow> topProducts, List<StockItem> outOfStock,
                            List<StockItem> runningOut, List<StockItem> lowRotation, int lowRotationCount, Pending pending,
                            List<AlertsService.Alert> alerts, int alertsTotal, boolean costs) {
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard() {
        CurrentUser.require(Permission.DASHBOARD_VER);
        AuthUser me = CurrentUser.get();
        Long t = TenantContext.require();
        ZoneId z = analytics.zone(t);
        StoreSettings st = settings.forTenant(t);
        boolean costs = me.can(Permission.COSTOS_VER);
        LocalDate today = LocalDate.now(z);

        List<PeriodStat> periods = new ArrayList<>();
        periods.add(period(t, z, "dia", "Hoy", today, today, today.minusDays(1), today.minusDays(1), costs));
        LocalDate monday = today.with(DayOfWeek.MONDAY);
        periods.add(period(t, z, "semana", "Esta semana", monday, today, monday.minusWeeks(1), today.minusWeeks(1), costs));
        // Quincena colombiana: del 1 al 15 y del 16 a fin de mes; se compara con los mismos días de la anterior.
        LocalDate qStart = today.getDayOfMonth() <= 15 ? today.withDayOfMonth(1) : today.withDayOfMonth(16);
        LocalDate pqStart = qStart.getDayOfMonth() == 1 ? qStart.minusMonths(1).withDayOfMonth(16) : qStart.withDayOfMonth(1);
        LocalDate pqEnd = min(pqStart.plusDays(today.getDayOfMonth() - qStart.getDayOfMonth()),
                pqStart.getDayOfMonth() == 1 ? pqStart.withDayOfMonth(15) : pqStart.withDayOfMonth(pqStart.lengthOfMonth()));
        periods.add(period(t, z, "quincena", "Esta quincena", qStart, today, pqStart, pqEnd, costs));
        LocalDate mStart = today.withDayOfMonth(1);
        LocalDate pm = mStart.minusMonths(1);
        periods.add(period(t, z, "mes", "Este mes", mStart, today, pm, min(pm.withDayOfMonth(Math.min(today.getDayOfMonth(), pm.lengthOfMonth())), pm.withDayOfMonth(pm.lengthOfMonth())), costs));

        Finance fin = null;
        if (costs) {
            Kpis k = analytics.kpis(t, z, mStart, today, true);
            BigDecimal exp = AnalyticsService.dec(em.createNativeQuery("""
                            select coalesce(sum(amount),0) from finance_entries
                            where tenant_id = :t and kind = 'GASTO' and voided_at is null and entry_date >= :f and entry_date <= :tt""")
                    .setParameter("t", t).setParameter("f", mStart).setParameter("tt", today).getSingleResult());
            fin = new Finance(k.grossProfit(), k.marginPct(), AnalyticsService.m(exp), AnalyticsService.m(k.grossProfit().subtract(exp)));
        }

        List<CashStatus> cash = new ArrayList<>();
        BigDecimal cashTotal = BigDecimal.ZERO;
        if (me.can(Permission.CAJA_SUPERVISAR) || me.can(Permission.REPORTES_VER)) {
            for (ShiftService.ShiftSummary s : shifts.openShifts()) {
                cash.add(new CashStatus(s.id(), s.number(), s.registerName(), s.userName(), s.expectedCash(), s.salesTotal()));
                cashTotal = cashTotal.add(s.expectedCash());
            }
        }

        List<Fact> todayFacts = analytics.facts(t, z, today, today, null);
        AnalyticsService.Agg[] hours = new AnalyticsService.Agg[24];
        for (int i = 0; i < 24; i++) hours[i] = new AnalyticsService.Agg();
        for (Fact f : todayFacts) hours[f.at().getHour()].add(f);
        int firstHour = 23, lastHour = 0;
        for (int h = 0; h < 24; h++) {
            if (!hours[h].sales().isEmpty()) {
                firstHour = Math.min(firstHour, h);
                lastHour = Math.max(lastHour, h);
            }
        }
        List<Point> byHour = new ArrayList<>();
        if (firstHour <= lastHour) {
            for (int h = Math.min(firstHour, 7); h <= Math.max(lastHour, 20); h++) byHour.add(hours[h].point(String.valueOf(h), String.format("%02d", h), costs));
        }

        List<Fact> week = analytics.facts(t, z, today.minusDays(6), today, null);
        List<ProductRow> top = analytics.productRows(week, List.of(), analytics.categoryNames(t), costs).stream()
                .sorted(Comparator.comparing(ProductRow::units).reversed()).limit(st.rankingSize).toList();

        List<StockItem> out = new ArrayList<>(), soon = new ArrayList<>(), noRot = new ArrayList<>();
        Pending pending;
        if (me.can(Permission.INVENTARIO_VER) || me.can(Permission.COMPRAS_VER)) {
            for (InsightsService.ProductInsight p : insights.insights().products()) {
                if (p.flags().contains("AGOTADO")) out.add(new StockItem(p.productId(), p.name(), p.stock(), p.coverageDays(), "AGOTADO"));
                else if (p.flags().contains("AGOTAMIENTO_INMINENTE")) soon.add(new StockItem(p.productId(), p.name(), p.stock(), p.coverageDays(), "AGOTAMIENTO_INMINENTE"));
                else if (p.flags().contains("BAJO_MINIMO")) soon.add(new StockItem(p.productId(), p.name(), p.stock(), p.coverageDays(), "BAJO_MINIMO"));
                if (p.flags().contains("SIN_ROTACION")) noRot.add(new StockItem(p.productId(), p.name(), p.stock(), p.coverageDays(), "SIN_ROTACION"));
            }
            InsightsService.Suggestions sug = insights.suggestions();
            int lines = sug.withoutSupplier().size(), critical = 0;
            for (InsightsService.SupplierSuggestion s : sug.suppliers()) {
                lines += s.lines().size();
                critical += s.critical();
            }
            List<PurchaseService.OrderDto> open = new ArrayList<>(purchases.list("APROBADA", null));
            open.addAll(purchases.list("PARCIAL", null));
            BigDecimal toReceive = open.stream().map(o -> nz(o.estimatedTotal()).subtract(nz(o.receivedTotal())).max(BigDecimal.ZERO))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            pending = new Pending(lines, critical, costs ? sug.suggestedTotal() : null, open.size(), (int) open.stream().filter(PurchaseService.OrderDto::late).count(),
                    costs ? toReceive : null, 0, BigDecimal.ZERO);
        } else {
            pending = new Pending(0, 0, null, 0, 0, null, 0, BigDecimal.ZERO);
        }
        if (me.can(Permission.PAGOS_VERIFICAR) || me.can(Permission.REPORTES_VER)) {
            List<PaymentService.PaymentRow> pend = payments.list(PaymentService.PENDIENTE, null, null, null);
            pending = new Pending(pending.toOrderProducts(), pending.toOrderCritical(), pending.toOrderTotal(), pending.toReceiveOrders(),
                    pending.lateOrders(), pending.toReceiveTotal(), pend.size(),
                    pend.stream().map(PaymentService.PaymentRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
        }
        List<AlertsService.Alert> all = alerts.all();
        return new Dashboard(today, periods, fin, cash, cashTotal, analytics.methods(t, z, today, today), byHour, top,
                out.stream().limit(8).toList(), soon.stream().sorted(Comparator.comparing(s -> s.coverageDays() == null ? BigDecimal.valueOf(9999) : s.coverageDays())).limit(8).toList(),
                noRot.stream().limit(8).toList(), noRot.size(), pending, all.stream().limit(6).toList(), all.size(), costs);
    }

    private PeriodStat period(Long t, ZoneId z, String key, String label, LocalDate from, LocalDate to, LocalDate pFrom, LocalDate pTo, boolean costs) {
        Kpis k = analytics.kpis(t, z, from, to, costs);
        Kpis p = analytics.kpis(t, z, pFrom, pTo, false);
        var f = from.atStartOfDay(z).toInstant();
        var tt = to.plusDays(1).atStartOfDay(z).toInstant();
        // Por fecha del pago, no de la venta: un abono cuenta el día en que se recibe.
        BigDecimal collected = sum("""
                select coalesce(sum(p.amount),0) from payments p join sales s on s.id = p.sale_id
                where p.tenant_id = :t and s.status = 'PAGADA' and p.method <> 'FIADO' and p.status not in ('RECHAZADO','ANULADO')
                  and p.created_at >= :f and p.created_at < :tt""", t, f, tt);
        BigDecimal credit = sum("""
                select coalesce(sum(p.amount),0) from payments p join sales s on s.id = p.sale_id
                where p.tenant_id = :t and s.status = 'PAGADA' and p.method = 'FIADO' and p.created_at >= :f and p.created_at < :tt""", t, f, tt);
        BigDecimal abonos = sum("""
                select coalesce(sum(amount),0) from credit_entries
                where tenant_id = :t and type = 'ABONO' and created_at >= :f and created_at < :tt""", t, f, tt);
        return new PeriodStat(key, label, from, to, k.sales(), k.transactions(), k.avgTicket(), k.grossProfit(), pFrom, pTo, p.sales(),
                AnalyticsService.change(k.sales(), p.sales()), collected.add(abonos), collected, credit, abonos);
    }

    private BigDecimal sum(String sql, Long t, java.time.Instant f, java.time.Instant tt) {
        return nz(AnalyticsService.dec(em.createNativeQuery(sql).setParameter("t", t).setParameter("f", f).setParameter("tt", tt).getSingleResult()));
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }
}
