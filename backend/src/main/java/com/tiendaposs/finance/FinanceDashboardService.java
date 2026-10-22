package com.tiendaposs.finance;

import com.tiendaposs.einvoice.EInvoiceService;
import com.tiendaposs.payments.PaymentRepos.ReconciliationRepository;
import com.tiendaposs.payments.PaymentService;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.tenant.Tenant;
import com.tiendaposs.tenant.TenantContext;
import com.tiendaposs.tenant.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Fase 15: tablero financiero único. Junta en una pantalla lo que hoy está repartido: ventas y utilidad del mes, cuentas
 * por cobrar y por pagar, dinero disponible, documentos electrónicos, conciliación, obligaciones próximas, alertas y lo
 * que dice el flujo proyectado. No calcula nada nuevo: reutiliza cada módulo.
 */
@Service
public class FinanceDashboardService {

    private final FinanceService finance;
    private final MoneyAccountService accounts;
    private final CashProjectionService projection;
    private final TaxCalendarService calendar;
    private final AlertsService alerts;
    private final EInvoiceService einvoice;
    private final PaymentService payments;
    private final ReconciliationRepository reconciliations;
    private final TenantRepository tenants;

    public FinanceDashboardService(FinanceService finance, MoneyAccountService accounts, CashProjectionService projection,
                                   TaxCalendarService calendar, AlertsService alerts, EInvoiceService einvoice, PaymentService payments,
                                   ReconciliationRepository reconciliations, TenantRepository tenants) {
        this.finance = finance;
        this.accounts = accounts;
        this.projection = projection;
        this.calendar = calendar;
        this.alerts = alerts;
        this.einvoice = einvoice;
        this.payments = payments;
        this.reconciliations = reconciliations;
        this.tenants = tenants;
    }

    public record Results(LocalDate from, LocalDate to, BigDecimal sales, BigDecimal previousSales, BigDecimal salesChangePct,
                          BigDecimal grossProfit, BigDecimal grossMarginPct, BigDecimal expenses, BigDecimal netProfit,
                          BigDecimal netMarginPct) {
    }

    public record Money(BigDecimal cash, BigDecimal inAccounts, BigDecimal total, List<MoneyAccountService.AccountDto> accounts) {
    }

    public record Debts(BigDecimal receivables, BigDecimal receivablesOverdue, BigDecimal payables, BigDecimal payablesOverdue,
                        BigDecimal payablesThisWeek) {
    }

    public record Documents(boolean enabled, String environment, boolean simulated, Map<String, Long> byStatus, long problems) {
    }

    public record Reconciliation(long pendingCount, BigDecimal pendingAmount, String lastMethod, LocalDate lastDateTo, Integer lastInconsistent,
                                 Integer lastUnmatched, Instant lastAt) {
    }

    public record Outlook(BigDecimal lowest, LocalDate lowestWeek, int negativeWeeks, List<BigDecimal> closings) {
    }

    public record Dashboard(Results results, Money money, Debts debts, Documents documents, Reconciliation reconciliation,
                            List<TaxCalendarService.ItemDto> obligations, List<AlertsService.Alert> alerts, Outlook outlook) {
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard() {
        CurrentUser.require(Permission.FINANZAS_VER);
        Long t = TenantContext.require();
        LocalDate today = LocalDate.now(ZoneId.of(tenants.findById(t).map(Tenant::getTimezone).orElse("America/Bogota")));

        // Mes en curso frente a los mismos días del mes anterior.
        LocalDate from = today.withDayOfMonth(1);
        FinanceService.Summary cur = finance.summary(from, today);
        LocalDate prevFrom = from.minusMonths(1);
        LocalDate prevTo = prevFrom.plusDays(today.getDayOfMonth() - 1L);
        if (prevTo.getMonth() != prevFrom.getMonth()) prevTo = prevFrom.withDayOfMonth(prevFrom.lengthOfMonth());
        FinanceService.Summary prev = finance.summary(prevFrom, prevTo);
        Results results = new Results(from, today, cur.netSales(), prev.netSales(), change(cur.netSales(), prev.netSales()), cur.grossProfit(),
                cur.grossMarginPct(), cur.expenses(), cur.netProfit(), cur.netMarginPct());

        CashProjectionService.Projection p = projection.project(t, 8);
        MoneyAccountService.Overview ov = accounts.overview();
        Money money = new Money(p.cash(), p.accounts(), p.start(), ov.accounts().stream().filter(MoneyAccountService.AccountDto::active).toList());

        FinanceService.Payables pay = finance.payables(null);
        Debts debts = new Debts(cur.receivables(), p.overdueReceivables(), pay.total(), pay.overdue(), pay.dueThisWeek());

        EInvoiceService.Summary es = einvoice.summary(t);
        Documents docs = new Documents(es.enabled(), es.environment(), es.simulated(), es.byStatus(), einvoice.problemCount(t));

        List<PaymentService.PaymentRow> pending = payments.list(PaymentService.PENDIENTE, null, null, null);
        var last = reconciliations.findTop30ByTenantIdOrderByCreatedAtDesc(t).stream().findFirst().orElse(null);
        Reconciliation recon = new Reconciliation(pending.size(),
                pending.stream().map(PaymentService.PaymentRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add),
                last == null ? null : last.method, last == null ? null : last.dateTo, last == null ? null : last.inconsistent,
                last == null ? null : last.unmatched, last == null ? null : last.createdAt);

        List<AlertsService.Alert> top = alerts.all().stream().limit(8).toList();
        Outlook outlook = new Outlook(p.lowest(), p.lowestWeek(), p.negativeWeeks(), p.weeks().stream().map(CashProjectionService.Week::closing).toList());
        return new Dashboard(results, money, debts, docs, recon, calendar.upcoming(t, 5), top, outlook);
    }

    private static BigDecimal change(BigDecimal cur, BigDecimal prev) {
        if (prev == null || prev.signum() == 0) return null;
        return cur.subtract(prev).multiply(BigDecimal.valueOf(100)).divide(prev, 1, RoundingMode.HALF_UP);
    }
}
