package com.tiendaposs.pos;

import com.tiendaposs.audit.AuditService;
import com.tiendaposs.catalog.CatalogRepos.ProductRepository;
import com.tiendaposs.catalog.Product;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.common.Counters;
import com.tiendaposs.common.Money;
import com.tiendaposs.credit.CreditService;
import com.tiendaposs.inventory.InventoryService;
import com.tiendaposs.payments.PaymentService;
import com.tiendaposs.pos.PosRepos.*;
import com.tiendaposs.pos.SaleDtos.*;
import com.tiendaposs.security.AuthUser;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.settings.SettingsService;
import com.tiendaposs.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Devoluciones parciales o totales de una venta pagada. El reembolso se calcula con lo que el
 * cliente realmente pagó por la línea (incluye descuentos e impuestos) y sale del turno de quien
 * devuelve. Lo que está en buen estado vuelve al inventario; lo dañado no.
 */
@Service
public class ReturnService {

    private final SaleRepository sales;
    private final SaleLineRepository lines;
    private final SaleReturnRepository returns;
    private final SaleReturnLineRepository returnLines;
    private final ProductRepository products;
    private final InventoryService inventory;
    private final ShiftService shifts;
    private final ApprovalService approvals;
    private final SettingsService settings;
    private final SaleService saleService;
    private final Counters counters;
    private final AuditService audit;
    private final CreditService credit;
    private final PaymentService paymentService;
    private final com.tiendaposs.serials.SerialService serialService;
    private final SaleLineComponentRepository lineComponents;

    public ReturnService(SaleRepository sales, SaleLineRepository lines, SaleReturnRepository returns,
                         SaleReturnLineRepository returnLines, ProductRepository products, InventoryService inventory,
                         ShiftService shifts, ApprovalService approvals, SettingsService settings, SaleService saleService,
                         Counters counters, AuditService audit, CreditService credit, PaymentService paymentService,
                         com.tiendaposs.serials.SerialService serialService, SaleLineComponentRepository lineComponents) {
        this.serialService = serialService;
        this.lineComponents = lineComponents;
        this.sales = sales;
        this.lines = lines;
        this.returns = returns;
        this.returnLines = returnLines;
        this.products = products;
        this.inventory = inventory;
        this.shifts = shifts;
        this.approvals = approvals;
        this.settings = settings;
        this.saleService = saleService;
        this.counters = counters;
        this.audit = audit;
        this.credit = credit;
        this.paymentService = paymentService;
    }

    @Transactional
    public SaleDto create(Long saleId, ReturnRequest r) {
        Long t = TenantContext.require();
        AuthUser me = CurrentUser.get();
        Sale s = sales.lockById(saleId, t).orElseThrow(() -> ApiException.notFound("Venta no encontrada"));
        if (!SaleService.PAGADA.equals(s.status)) throw ApiException.badRequest("Solo se pueden devolver productos de ventas pagadas");
        String method = r.refundMethod().trim().toUpperCase();
        if (!settings.forTenant(t).paymentMethodList().contains(method)) throw ApiException.field("refundMethod", "medio de reembolso no habilitado");
        String who = approvals.require(Permission.POS_DEVOLUCION, r.approvals(), "Una devolución");
        Shift shift = shifts.openShiftOfCurrentUser().orElse(null);
        if (shift == null && "EFECTIVO".equals(method)) {
            throw ApiException.conflict("Para devolver dinero en efectivo abre tu turno de caja.");
        }
        Map<Long, SaleLine> byId = new HashMap<>();
        lines.findBySaleIdOrderBySortOrderAscIdAsc(s.id).forEach(l -> byId.put(l.id, l));
        SaleReturn ret = new SaleReturn();
        ret.tenantId = t;
        ret.saleId = s.id;
        ret.number = counters.next(t, "DEVOLUCION");
        ret.shiftId = shift == null ? null : shift.id;
        ret.reason = r.reason().trim();
        ret.refundMethod = method;
        ret.userId = me.userId();
        ret.userName = me.fullName();
        ret.authorizedBy = who;
        ret.refundAmount = BigDecimal.ZERO;
        ret = returns.save(ret);
        BigDecimal total = BigDecimal.ZERO;
        List<String> detail = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (ReturnLineRequest lr : r.lines()) {
            if (!seen.add(lr.saleLineId())) throw ApiException.badRequest("Hay una línea repetida en la devolución");
            SaleLine l = byId.get(lr.saleLineId());
            if (l == null) throw ApiException.badRequest("Una línea no pertenece a esta venta");
            BigDecimal available = l.quantity.subtract(l.returnedQty);
            if (lr.quantity().compareTo(available) > 0) {
                throw ApiException.badRequest("De " + l.productName + " solo quedan " + plain(available) + " por devolver");
            }
            // Lo pagado por unidad; la última unidad pendiente se lleva el residuo para no perder pesos.
            BigDecimal amount = lr.quantity().compareTo(available) == 0
                    ? l.lineTotal.subtract(l.lineTotal.multiply(l.returnedQty).divide(l.quantity, 2, RoundingMode.HALF_UP))
                    : l.lineTotal.multiply(lr.quantity()).divide(l.quantity, 2, RoundingMode.HALF_UP);
            String condition = lr.condition() == null ? "BUENO" : lr.condition();
            boolean restock = lr.restock() && "BUENO".equals(condition);
            SaleReturnLine rl = new SaleReturnLine();
            rl.returnId = ret.id;
            rl.saleLineId = l.id;
            rl.quantity = lr.quantity().setScale(3, RoundingMode.HALF_UP);
            rl.amount = amount;
            rl.restock = restock;
            rl.condition = condition;
            Product p = products.lockById(l.productId, t).orElseThrow();
            if (p.trackSerials) {
                var list = com.tiendaposs.serials.SerialService.normalize(lr.serials());
                serialService.returned(p, l.id, rl.quantity.multiply(l.factor), list, restock);
                rl.serials = com.tiendaposs.serials.SerialService.join(list);
            }
            returnLines.save(rl);
            l.returnedQty = l.returnedQty.add(rl.quantity);
            total = total.add(amount);
            if (restock) {
                // Un combo devuelto en buen estado regresa sus componentes al inventario.
                for (SaleLineComponent slc : lineComponents.findBySaleLineIdOrderByIdAsc(l.id)) {
                    Product cp = products.lockById(slc.productId, t).orElseThrow();
                    if (!cp.trackStock) continue;
                    inventory.applyLocked(cp, new InventoryService.Change(cp.id, slc.quantity.multiply(rl.quantity), InventoryService.DEVOLUCION,
                            "RETURN", ret.id, "Devolución #" + ret.number + " de la venta #" + s.number + " (combo " + l.productName + ")",
                            slc.unitCost, true));
                }
            }
            if (restock && p.trackStock) {
                inventory.applyLocked(p, new InventoryService.Change(p.id, rl.quantity.multiply(l.factor), InventoryService.DEVOLUCION,
                        "RETURN", ret.id, "Devolución #" + ret.number + " de la venta #" + s.number, l.unitCost.divide(l.factor, 4, RoundingMode.HALF_UP), true));
            }
            detail.add(plain(rl.quantity) + " x " + l.productName + (restock ? "" : " (" + ("DANADO".equals(condition) ? "dañado" : "no reingresa") + ")"));
        }
        ret.refundAmount = total;
        s.refundedTotal = s.refundedTotal.add(total);
        // Devolver "a fiado" descuenta la deuda del cliente en lugar de entregar dinero.
        if ("FIADO".equals(method)) credit.creditReturn(s, total, "Devolución #" + ret.number + " de la venta #" + s.number);
        if (s.refundedTotal.compareTo(s.total) >= 0) paymentService.markRefunded(s);
        audit.sensitive("DEVOLUCION", "SALE", s.id, "Devolución #" + ret.number + " de la venta #" + s.number + " por "
                + Money.fmt(total) + " en " + method.toLowerCase() + ": " + String.join(", ", detail) + ". Motivo: " + ret.reason
                + (who == null ? "" : " (autorizó " + who + ")"));
        return saleService.toDto(s);
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
