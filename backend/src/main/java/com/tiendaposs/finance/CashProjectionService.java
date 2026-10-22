package com.tiendaposs.finance;

import com.tiendaposs.pos.PosRepos.ShiftRepository;
import com.tiendaposs.pos.ShiftService;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.tenant.Tenant;
import com.tiendaposs.tenant.TenantContext;
import com.tiendaposs.tenant.TenantRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Fase 15: flujo de caja proyectado por semanas, con supuestos a la vista para que el dueño entienda cada número.
 * <ul>
 *   <li>Parte de lo que hay hoy: el efectivo de las cajas abiertas y el saldo de las cuentas.</li>
 *   <li>Entra: el promedio diario de ventas cobradas (sin fiado) de los últimos 28 días y el fiado que vence cada semana.
 *       El fiado ya vencido NO se cuenta: es incierto.</li>
 *   <li>Sale: las facturas de proveedores según su vencimiento (lo vencido, en la primera semana), los gastos fijos
 *       (promedio de los últimos 3 meses) y la reposición de mercancía: si lo que vence esa semana es menor que lo que
 *       normalmente se gasta en mercancía (costo de lo vendido), se completa con el promedio.</li>
 * </ul>
 */
@Service
public class CashProjectionService {

    private final MoneyAccountService accounts;
    private final ShiftRepository shifts;
    private final ShiftService shiftService;
    private final TenantRepository tenants;
    private final EntityManager em;

    public CashProjectionService(MoneyAccountService accounts, ShiftRepository shifts, ShiftService shiftService, TenantRepository tenants,
                                 EntityManager em) {
        this.accounts = accounts;
        this.shifts = shifts;
        this.shiftService = shiftService;
        this.tenants = tenants;
        this.em = em;
    }

    public record Week(int index, LocalDate start, LocalDate end, BigDecimal opening, BigDecimal sales, BigDecimal receivables,
                       BigDecimal payables, BigDecimal fixedExpenses, BigDecimal restock, BigDecimal closing) {
    }

    public record Projection(LocalDate today, BigDecimal cash, BigDecimal accounts, BigDecimal start, BigDecimal avgDailySales,
                             BigDecimal avgWeeklyRestock, BigDecimal monthlyFixed, BigDecimal overduePayables,
                             BigDecimal overdueReceivables, List<Week> weeks, BigDecimal lowest, LocalDate lowestWeek,
                             int negativeWeeks, List<String> assumptions) {
    }

    @Transactional(readOnly = true)
    public Projection project(int weeks) {
        CurrentUser.require(Permission.FINANZAS_VER);
        return project(TenantContext.require(), Math.min(Math.max(weeks, 1), 12));
    }

    @Transactional(readOnly = true)
    public Projection project(Long t, int weeks) {
        ZoneId z = ZoneId.of(tenants.findById(t).map(Tenant::getTimezone).orElse("America/Bogota"));
        LocalDate today = LocalDate.now(z);

        BigDecimal cash = shifts.findByTenantIdAndStatusOrderByOpenedAtAsc(t, "OPEN").stream()
                .map(s -> shiftService.summary(s).expectedCash()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal inAccounts = accounts.totalBalance(t);
        BigDecimal start = cash.add(inAccounts);

        Timestamp d28 = ts(today.minusDays(28), z), now = ts(today.plusDays(1), z);
        BigDecimal sales28 = dec(em.createNativeQuery("""
                        select coalesce(sum(p.amount), 0) from payments p join sales s on s.id = p.sale_id
                        where p.tenant_id = :t and s.status = 'PAGADA' and p.method not in ('FIADO', 'ANTICIPO')
                          and p.status not in ('RECHAZADO', 'ANULADO') and p.created_at >= :f and p.created_at < :to""")
                .setParameter("t", t).setParameter("f", d28).setParameter("to", now).getSingleResult());
        BigDecimal avgDaily = sales28.divide(BigDecimal.valueOf(28), 2, RoundingMode.HALF_UP);
        BigDecimal cogs28 = dec(em.createNativeQuery("""
                        select coalesce(sum(l.unit_cost * (l.quantity - l.returned_qty)), 0) from sale_lines l join sales s on s.id = l.sale_id
                        where s.tenant_id = :t and s.status = 'PAGADA' and s.completed_at >= :f and s.completed_at < :to""")
                .setParameter("t", t).setParameter("f", d28).setParameter("to", now).getSingleResult());
        BigDecimal avgWeeklyRestock = cogs28.divide(BigDecimal.valueOf(4), 2, RoundingMode.HALF_UP);
        LocalDate m3 = today.withDayOfMonth(1).minusMonths(3);
        BigDecimal fixed3 = dec(em.createNativeQuery("""
                        select coalesce(sum(e.amount), 0) from finance_entries e join finance_categories c on c.id = e.category_id
                        where e.tenant_id = :t and e.kind = 'GASTO' and e.voided_at is null and c.fixed
                          and e.entry_date >= :f and e.entry_date < :to""")
                .setParameter("t", t).setParameter("f", m3).setParameter("to", today.withDayOfMonth(1)).getSingleResult());
        BigDecimal monthlyFixed = fixed3.divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP);
        BigDecimal weeklyFixed = monthlyFixed.multiply(BigDecimal.valueOf(7)).divide(BigDecimal.valueOf(30), 2, RoundingMode.HALF_UP);

        // Lo que se debe a proveedores y lo que deben los clientes, con su vencimiento.
        List<Object[]> payables = rows("""
                select r.due_date, r.total - r.withheld - r.paid_amount from goods_receipts r
                where r.tenant_id = :t and r.total > r.withheld + r.paid_amount""", t);
        List<Object[]> receivables = rows("""
                select c.due_date, c.amount - c.paid_amount from credit_entries c
                where c.tenant_id = :t and c.type = 'CARGO' and c.amount > c.paid_amount""", t);
        BigDecimal overduePay = BigDecimal.ZERO, overdueRec = BigDecimal.ZERO;
        for (Object[] r : payables) if (r[0] != null && date(r[0]).isBefore(today)) overduePay = overduePay.add(dec(r[1]));
        for (Object[] r : receivables) if (r[0] != null && date(r[0]).isBefore(today)) overdueRec = overdueRec.add(dec(r[1]));

        List<Week> out = new ArrayList<>();
        BigDecimal balance = start, lowest = start;
        LocalDate lowestWeek = today;
        int negative = 0;
        for (int w = 0; w < weeks; w++) {
            LocalDate ws = today.plusDays(7L * w), we = ws.plusDays(6);
            BigDecimal pay = BigDecimal.ZERO, rec = BigDecimal.ZERO;
            for (Object[] r : payables) {
                LocalDate due = r[0] == null ? today : date(r[0]);
                // Lo vencido (o sin fecha) se paga en la primera semana.
                if ((w == 0 && due.isBefore(ws)) || (!due.isBefore(ws) && !due.isAfter(we))) pay = pay.add(dec(r[1]));
            }
            for (Object[] r : receivables) {
                if (r[0] == null) continue;
                LocalDate due = date(r[0]);
                if (!due.isBefore(ws) && !due.isAfter(we)) rec = rec.add(dec(r[1]));
            }
            BigDecimal sales = avgDaily.multiply(BigDecimal.valueOf(7));
            BigDecimal restock = avgWeeklyRestock.subtract(pay).max(BigDecimal.ZERO);
            BigDecimal opening = balance;
            balance = balance.add(sales).add(rec).subtract(pay).subtract(weeklyFixed).subtract(restock);
            if (balance.signum() < 0) negative++;
            if (balance.compareTo(lowest) < 0) {
                lowest = balance;
                lowestWeek = ws;
            }
            out.add(new Week(w + 1, ws, we, opening, sales, rec, pay, weeklyFixed, restock, balance));
        }

        List<String> assumptions = new ArrayList<>();
        assumptions.add("Parte del efectivo de las cajas abiertas y del saldo de tus cuentas de banco y billeteras.");
        assumptions.add("Ventas: el promedio de lo cobrado en los últimos 28 días (" + money(avgDaily) + " al día), sin fiado.");
        assumptions.add("Fiado: solo lo que vence cada semana. Lo ya vencido (" + money(overdueRec) + ") no se cuenta porque es incierto.");
        assumptions.add("Proveedores: cada factura en la semana en que vence; lo vencido (" + money(overduePay) + ") en la primera semana.");
        assumptions.add("Gastos fijos: el promedio de los últimos 3 meses de las categorías marcadas como fijas (" + money(monthlyFixed) + " al mes).");
        assumptions.add("Reposición: si lo que vence una semana es menor que lo que gastas en mercancía (" + money(avgWeeklyRestock)
                + " a la semana), se completa con el promedio.");
        assumptions.add("No incluye impuestos por pagar: míralos en el calendario tributario.");
        return new Projection(today, cash, inAccounts, start, avgDaily, avgWeeklyRestock, monthlyFixed, overduePay, overdueRec, out, lowest,
                lowestWeek, negative, assumptions);
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> rows(String sql, Long t) {
        return em.createNativeQuery(sql).setParameter("t", t).getResultList();
    }

    private static String money(BigDecimal v) {
        return com.tiendaposs.common.Money.fmt(v);
    }

    private static LocalDate date(Object o) {
        return o instanceof java.sql.Date d ? d.toLocalDate() : o instanceof LocalDate l ? l : LocalDate.parse(o.toString().substring(0, 10));
    }

    private static Timestamp ts(LocalDate d, ZoneId z) {
        return Timestamp.from(d.atStartOfDay(z).toInstant());
    }

    private static BigDecimal dec(Object o) {
        return o == null ? BigDecimal.ZERO : o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
    }
}
