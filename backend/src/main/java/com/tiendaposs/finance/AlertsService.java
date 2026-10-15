package com.tiendaposs.finance;

import com.tiendaposs.common.Money;
import com.tiendaposs.credit.CreditService;
import com.tiendaposs.payments.PaymentService;
import com.tiendaposs.purchasing.InsightsService;
import com.tiendaposs.security.AuthUser;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Alertas del negocio en un solo lugar (la campana): inventario y compras, pagos digitales sin
 * verificar o con referencia repetida, facturas de proveedores por vencer y fiados vencidos.
 * Cada usuario ve solo lo que sus permisos le dejan atender.
 */
@Service
public class AlertsService {

    public record Alert(String type, String severity, String title, String detail, Long productId, Long referenceId, String link) {
    }

    private final InsightsService insights;
    private final PaymentService payments;
    private final FinanceService finance;
    private final CreditService credit;

    public AlertsService(InsightsService insights, PaymentService payments, FinanceService finance, CreditService credit) {
        this.insights = insights;
        this.payments = payments;
        this.finance = finance;
        this.credit = credit;
    }

    @Transactional(readOnly = true)
    public List<Alert> all() {
        AuthUser me = CurrentUser.get();
        List<Alert> out = new ArrayList<>();
        if (me.can(Permission.PAGOS_VERIFICAR)) {
            List<PaymentService.PaymentRow> pending = payments.list(PaymentService.PENDIENTE, null, null, null);
            long old = pending.stream().filter(p -> Duration.between(p.createdAt(), Instant.now()).toMinutes() >= 30).count();
            if (!pending.isEmpty()) {
                var total = pending.stream().map(PaymentService.PaymentRow::amount).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
                out.add(new Alert("PAGOS_POR_VERIFICAR", old > 0 ? "ALTA" : "MEDIA", pending.size() + (pending.size() == 1 ? " pago digital" : " pagos digitales") + " sin verificar",
                        Money.fmt(total) + (old > 0 ? " · " + old + " con más de 30 minutos" : ""), null, null, "/pagos"));
            }
            for (PaymentService.PaymentRow p : pending) {
                if (p.duplicateRef()) {
                    out.add(new Alert("REFERENCIA_DUPLICADA", "CRITICA", "Referencia repetida en la venta #" + p.saleNumber(),
                            p.method() + " · ref. " + p.reference() + " · " + Money.fmt(p.amount()), null, p.id(), "/pagos"));
                }
            }
        }
        if (me.can(Permission.FINANZAS_VER) || me.can(Permission.FINANZAS_GASTOS)) {
            FinanceService.Payables pay = finance.payables(null);
            for (FinanceService.PayableDto d : pay.invoices()) {
                if (d.dueDate() == null || d.daysToDue() > 3) continue;
                boolean late = d.daysToDue() < 0;
                out.add(new Alert(late ? "FACTURA_VENCIDA" : "FACTURA_POR_VENCER", late ? "ALTA" : "MEDIA",
                        (late ? "Factura vencida de " : "Pago a ") + d.supplierName() + (late ? "" : d.daysToDue() == 0 ? " vence hoy" : " vence en " + d.daysToDue() + " días"),
                        Money.fmt(d.balance()) + (d.invoiceNumber() == null ? "" : " · fact. " + d.invoiceNumber()), null, d.receiptId(), "/finanzas/por-pagar"));
            }
        }
        if (me.can(Permission.FIADO_GESTIONAR) || me.can(Permission.FIADO_AUTORIZAR)) {
            for (CreditService.CustomerCredit c : credit.list(true)) {
                if (c.overdue().signum() <= 0) continue;
                out.add(new Alert("FIADO_VENCIDO", c.daysOverdue() > 30 ? "ALTA" : "MEDIA", c.name() + " tiene fiado vencido",
                        Money.fmt(c.overdue()) + " vencido hace " + c.daysOverdue() + " días", null, c.customerId(), "/fiados/" + c.customerId()));
            }
        }
        if (me.can(Permission.INVENTARIO_VER) || me.can(Permission.ALERTAS_VER) || me.can(Permission.COMPRAS_VER)) {
            for (InsightsService.Alert a : insights.alerts()) {
                String link = a.type().startsWith("PEDIDO") && a.referenceId() != null ? "/compras/" + a.referenceId()
                        : a.type().equals("VENCIDO") || a.type().equals("POR_VENCER") ? "/inventario/vencimientos" : "/inventario/inteligente";
                out.add(new Alert(a.type(), a.severity(), a.title(), a.detail(), a.productId(), a.referenceId(), link));
            }
        }
        Map<String, Integer> rank = Map.of("CRITICA", 0, "ALTA", 1, "MEDIA", 2);
        out.sort(Comparator.comparing((Alert a) -> rank.getOrDefault(a.severity(), 3)));
        return out;
    }

    @RestController
    public static class AlertsController {
        private final AlertsService alerts;

        public AlertsController(AlertsService alerts) {
            this.alerts = alerts;
        }

        @GetMapping("/api/alerts")
        public List<Alert> alerts() {
            return alerts.all();
        }
    }
}
