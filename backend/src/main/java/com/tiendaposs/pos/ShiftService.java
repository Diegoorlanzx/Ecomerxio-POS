package com.tiendaposs.pos;

import com.tiendaposs.offline.OfflineTime;
import com.tiendaposs.audit.AuditService;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.common.Counters;
import com.tiendaposs.common.Money;
import com.tiendaposs.finance.FinanceService;
import com.tiendaposs.pos.PosRepos.*;
import com.tiendaposs.security.AuthUser;
import com.tiendaposs.security.CurrentUser;
import com.tiendaposs.security.Permission;
import com.tiendaposs.tenant.TenantContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

/** Cajas, turnos, movimientos de caja, arqueo y cierre. */
@Service
public class ShiftService {

    public static final Set<String> MOVEMENT_TYPES = Set.of("INGRESO", "RETIRO", "GASTO", "AJUSTE_ENTRADA", "AJUSTE_SALIDA");

    private final RegisterRepository registers;
    private final ShiftRepository shifts;
    private final CashMovementRepository movements;
    private final SaleRepository sales;
    private final PaymentRepository payments;
    private final SaleReturnRepository returns;
    private final ApprovalService approvals;
    private final Counters counters;
    private final AuditService audit;
    private final FinanceService finance;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager em;

    public ShiftService(RegisterRepository registers, ShiftRepository shifts, CashMovementRepository movements,
                        SaleRepository sales, PaymentRepository payments, SaleReturnRepository returns,
                        ApprovalService approvals, Counters counters, AuditService audit,
                        @Lazy FinanceService finance) {
        this.registers = registers;
        this.shifts = shifts;
        this.movements = movements;
        this.sales = sales;
        this.payments = payments;
        this.returns = returns;
        this.approvals = approvals;
        this.counters = counters;
        this.audit = audit;
        this.finance = finance;
    }

    // ---------------- Cajas ----------------

    public record RegisterDto(Long id, String name, boolean active, Long openShiftId, String openShiftUser) {
    }

    @Transactional(readOnly = true)
    public List<RegisterDto> listRegisters() {
        Long t = TenantContext.require();
        return registers.findByTenantIdOrderByIdAsc(t).stream().map(r -> {
            Shift s = shifts.findFirstByRegisterIdAndStatus(r.id, "OPEN").orElse(null);
            return new RegisterDto(r.id, r.name, r.active, s == null ? null : s.id, s == null ? null : s.userName);
        }).toList();
    }

    @Transactional
    public RegisterDto saveRegister(Long id, String name, Boolean active) {
        Long t = TenantContext.require();
        if (name == null || name.isBlank()) throw ApiException.field("name", "es obligatorio");
        if (registers.nameTaken(t, name.trim(), id)) throw ApiException.field("name", "ya existe una caja con ese nombre");
        Register r = id == null ? new Register() : registers.findByIdAndTenantId(id, t)
                .orElseThrow(() -> ApiException.notFound("Caja no encontrada"));
        r.tenantId = t;
        if (r.branchId == null) r.branchId = registers.mainBranch(t);
        r.name = name.trim();
        if (active != null) {
            if (!active && shifts.findFirstByRegisterIdAndStatus(r.id, "OPEN").isPresent()) {
                throw ApiException.badRequest("Cierra el turno abierto de esta caja antes de desactivarla");
            }
            r.active = active;
        }
        r = registers.save(r);
        audit.log(id == null ? "CAJA_CREADA" : "CAJA_EDITADA", "REGISTER", r.id, (id == null ? "Creó" : "Editó") + " la caja " + r.name);
        return new RegisterDto(r.id, r.name, r.active, null, null);
    }

    // ---------------- Turnos ----------------

    public record MovementDto(Long id, String type, BigDecimal amount, String reason, String category, String userName,
                              String authorizedBy, Instant createdAt) {
    }

    public record MethodTotal(String method, BigDecimal amount, long count) {
    }

    public record ShiftSummary(Long id, long number, Long registerId, String registerName, Long userId, String userName,
                               String status, BigDecimal openingCash, Instant openedAt, Instant closedAt, String closedByName,
                               long salesCount, BigDecimal salesTotal, long voidedCount, BigDecimal voidedTotal,
                               List<MethodTotal> byMethod, BigDecimal cashSales, BigDecimal cashIn, BigDecimal cashOut,
                               BigDecimal expenses, BigDecimal adjustments, BigDecimal cashRefunds, BigDecimal refundsTotal,
                               BigDecimal expectedCash, BigDecimal countedCash, BigDecimal difference, String countDetail,
                               String closeNotes, String differenceAuthorizedBy, BigDecimal discountTotal,
                               List<MovementDto> movements, BigDecimal creditSales, BigDecimal creditPayments) {
        // creditSales: vendido a fiado en el turno (no entró dinero). creditPayments: abonos de fiado recibidos en el
        // turno, en cualquier medio (los de efectivo ya están en el esperado como ingreso a la caja).
    }

    @Transactional
    public ShiftSummary open(Long registerId, BigDecimal openingCash, Instant offlineAt) {
        Long t = TenantContext.require();
        AuthUser me = CurrentUser.get();
        CurrentUser.require(Permission.CAJA_OPERAR);
        if (openingCash == null || openingCash.signum() < 0) throw ApiException.field("openingCash", "escribe el efectivo inicial (0 o más)");
        Register r = registers.findByIdAndTenantId(registerId, t).filter(x -> x.active)
                .orElseThrow(() -> ApiException.field("registerId", "la caja no existe o está desactivada"));
        shifts.findFirstByTenantIdAndUserIdAndStatus(t, me.userId(), "OPEN").ifPresent(s -> {
            throw ApiException.conflict("Ya tienes un turno abierto (turno #" + s.number + "). Ciérralo antes de abrir otro.");
        });
        shifts.findFirstByRegisterIdAndStatus(r.id, "OPEN").ifPresent(s -> {
            throw ApiException.conflict(r.name + " ya está abierta por " + s.userName + ".");
        });
        Shift s = new Shift();
        s.tenantId = t;
        s.registerId = r.id;
        s.number = counters.next(t, "TURNO");
        s.userId = me.userId();
        s.userName = me.fullName();
        s.openingCash = openingCash.setScale(2, RoundingMode.HALF_UP);
        s.openedAt = OfflineTime.when(offlineAt);
        try {
            s = shifts.saveAndFlush(s);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("La caja o el usuario ya tienen un turno abierto.");
        }
        audit.log("TURNO_ABIERTO", "SHIFT", s.id, "Abrió el turno #" + s.number + " en " + r.name + " con base " + Money.fmt(s.openingCash));
        return summary(s);
    }

    @Transactional(readOnly = true)
    public Optional<ShiftSummary> current() {
        AuthUser me = CurrentUser.get();
        return shifts.findFirstByTenantIdAndUserIdAndStatus(TenantContext.require(), me.userId(), "OPEN").map(this::summary);
    }

    /** Turno abierto del usuario actual, o error si la tienda exige turno para vender. */
    public Shift requireOpenShift() {
        AuthUser me = CurrentUser.get();
        return shifts.findFirstByTenantIdAndUserIdAndStatus(TenantContext.require(), me.userId(), "OPEN")
                .orElseThrow(() -> ApiException.conflict("Abre tu turno de caja antes de vender."));
    }

    public Optional<Shift> openShiftOfCurrentUser() {
        AuthUser me = CurrentUser.get();
        return shifts.findFirstByTenantIdAndUserIdAndStatus(TenantContext.require(), me.userId(), "OPEN");
    }

    @Transactional(readOnly = true)
    public ShiftSummary get(Long id) {
        Shift s = findVisible(id);
        return summary(s);
    }

    @Transactional
    public MovementDto addMovement(Long shiftId, String type, BigDecimal amount, String reason, String category, String approvalToken,
                                   boolean offline, Instant offlineAt) {
        Long t = TenantContext.require();
        AuthUser me = CurrentUser.get();
        Shift s = shifts.lockById(shiftId, t).orElseThrow(() -> ApiException.notFound("Turno no encontrado"));
        if (!"OPEN".equals(s.status)) throw ApiException.badRequest("El turno ya está cerrado");
        if (!s.userId.equals(me.userId())) CurrentUser.require(Permission.CAJA_SUPERVISAR);
        if (!MOVEMENT_TYPES.contains(type)) throw ApiException.field("type", "tipo no válido");
        if (amount == null || amount.signum() <= 0) throw ApiException.field("amount", "debe ser mayor que cero");
        if (reason == null || reason.isBlank()) throw ApiException.field("reason", "explica el motivo");
        String authorizedBy = null;
        if (type.startsWith("AJUSTE")) {
            authorizedBy = approvals.require(Permission.CAJA_AJUSTAR, approvalToken == null ? null : List.of(approvalToken), "Un ajuste de caja");
        } else {
            CurrentUser.require(Permission.CAJA_MOVIMIENTOS);
        }
        // Sin internet el movimiento ya pasó en la caja: se registra aunque el saldo esperado no alcance (queda en la actividad).
        if (!offline && !type.equals("INGRESO") && !type.equals("AJUSTE_ENTRADA")) {
            BigDecimal available = summary(s).expectedCash();
            if (amount.compareTo(available) > 0) {
                throw ApiException.field("amount", "en caja solo debería haber " + Money.fmt(available));
            }
        }
        CashMovement m = new CashMovement();
        m.tenantId = t;
        m.shiftId = s.id;
        m.type = type;
        m.amount = amount.setScale(2, RoundingMode.HALF_UP);
        m.reason = reason.trim();
        m.category = category == null || category.isBlank() ? null : category.trim();
        m.userId = me.userId();
        m.userName = me.fullName();
        m.authorizedBy = authorizedBy;
        m.createdAt = OfflineTime.when(offlineAt);
        m = movements.save(m);
        // Los gastos e ingresos de la caja también quedan en finanzas.
        finance.fromCashMovement(m);
        String label = Map.of("INGRESO", "Ingreso", "RETIRO", "Retiro", "GASTO", "Gasto", "AJUSTE_ENTRADA", "Ajuste (+)",
                "AJUSTE_SALIDA", "Ajuste (-)").get(type);
        String msg = label + " de " + Money.fmt(m.amount) + " en el turno #" + s.number + ": " + m.reason
                + (authorizedBy == null ? "" : " (autorizó " + authorizedBy + ")") + (offline ? " (registrado sin internet)" : "");
        if (type.startsWith("AJUSTE") || type.equals("RETIRO")) audit.sensitive("MOVIMIENTO_CAJA", "SHIFT", s.id, msg);
        else audit.log("MOVIMIENTO_CAJA", "SHIFT", s.id, msg);
        return toDto(m);
    }

    /**
     * Cierra el turno con el arqueo. countDetail = "50000x3,20000x5,..." (opcional; si viene, el
     * contado se calcula de ahí). Una diferencia exige una nota y queda como sobrante o faltante.
     */
    @Transactional
    public ShiftSummary close(Long shiftId, BigDecimal counted, String countDetail, String notes, String approvalToken, Instant offlineAt) {
        Long t = TenantContext.require();
        AuthUser me = CurrentUser.get();
        Shift s = shifts.lockById(shiftId, t).orElseThrow(() -> ApiException.notFound("Turno no encontrado"));
        if (!"OPEN".equals(s.status)) throw ApiException.badRequest("El turno ya está cerrado");
        if (!s.userId.equals(me.userId())) CurrentUser.require(Permission.CAJA_SUPERVISAR);
        else CurrentUser.require(Permission.CAJA_OPERAR);
        BigDecimal fromDetail = parseDetail(countDetail);
        if (fromDetail != null) counted = fromDetail;
        if (counted == null || counted.signum() < 0) throw ApiException.field("countedCash", "escribe el efectivo contado");
        ShiftSummary sum = summary(s);
        BigDecimal diff = counted.subtract(sum.expectedCash()).setScale(2, RoundingMode.HALF_UP);
        if (diff.signum() != 0 && (notes == null || notes.isBlank())) {
            throw ApiException.field("closeNotes", "hay una diferencia de " + Money.fmt(diff) + ": explica qué pasó");
        }
        String authorizedBy = null;
        if (diff.signum() != 0 && approvalToken != null && !approvalToken.isBlank()) {
            authorizedBy = approvals.require(Permission.CAJA_AJUSTAR, List.of(approvalToken), "La diferencia de caja");
        }
        s.status = "CLOSED";
        s.closedAt = OfflineTime.when(offlineAt);
        s.closedById = me.userId();
        s.closedByName = me.fullName();
        s.expectedCash = sum.expectedCash();
        s.countedCash = counted.setScale(2, RoundingMode.HALF_UP);
        s.difference = diff;
        s.countDetail = countDetail == null || countDetail.isBlank() ? null : countDetail.trim();
        s.closeNotes = notes == null || notes.isBlank() ? null : notes.trim();
        s.differenceAuthorizedBy = authorizedBy;
        String what = diff.signum() == 0 ? "cuadre exacto" : (diff.signum() > 0 ? "sobrante de " : "faltante de ") + Money.fmt(diff.abs());
        String msg = "Cerró el turno #" + s.number + " de " + s.userName + ": esperado " + Money.fmt(s.expectedCash)
                + ", contado " + Money.fmt(s.countedCash) + " (" + what + ")";
        if (diff.signum() != 0) audit.sensitive("TURNO_CERRADO", "SHIFT", s.id, msg);
        else audit.log("TURNO_CERRADO", "SHIFT", s.id, msg);
        return summary(s);
    }

    @Transactional(readOnly = true)
    public List<ShiftSummary> history(Long userId, Long registerId, String status, Instant from, Instant to, int page, int size) {
        Long t = TenantContext.require();
        AuthUser me = CurrentUser.get();
        Long u = me.can(Permission.CAJA_SUPERVISAR) || me.can(Permission.REPORTES_VER) ? userId : me.userId();
        return shifts.search(t, u, registerId, status, from, to, PageRequest.of(page, size)).getContent().stream()
                .map(this::summary).toList();
    }

    @Transactional(readOnly = true)
    public List<ShiftSummary> openShifts() {
        return shifts.findByTenantIdAndStatusOrderByOpenedAtAsc(TenantContext.require(), "OPEN").stream().map(this::summary).toList();
    }

    private Shift findVisible(Long id) {
        Shift s = shifts.findByIdAndTenantId(id, TenantContext.require()).orElseThrow(() -> ApiException.notFound("Turno no encontrado"));
        AuthUser me = CurrentUser.get();
        if (!s.userId.equals(me.userId()) && !me.can(Permission.CAJA_SUPERVISAR) && !me.can(Permission.REPORTES_VER)) {
            throw ApiException.forbidden("Solo puedes ver tus propios turnos");
        }
        return s;
    }

    /** Resumen del turno con el efectivo esperado: base + ventas en efectivo + ingresos − retiros − gastos ± ajustes − reembolsos en efectivo. */
    private BigDecimal creditPaymentsOf(Long shiftId) {
        Object v = em.createNativeQuery("select coalesce(sum(amount),0) from credit_entries where shift_id = :s and type = 'ABONO'")
                .setParameter("s", shiftId).getSingleResult();
        return v instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(v));
    }

    public ShiftSummary summary(Shift s) {
        String registerName = registers.findById(s.registerId).map(r -> r.name).orElse("Caja");
        List<Sale> paid = sales.findByShiftIdAndStatus(s.id, "PAGADA");
        List<Sale> voided = sales.findByShiftIdAndStatus(s.id, "ANULADA");
        Set<Long> paidIds = new HashSet<>();
        paid.forEach(x -> paidIds.add(x.id));
        Map<String, BigDecimal> byMethod = new TreeMap<>();
        Map<String, Long> byMethodCount = new TreeMap<>();
        for (Payment p : payments.findByShiftId(s.id)) {
            if (!paidIds.contains(p.saleId) || "RECHAZADO".equals(p.status)) continue;
            byMethod.merge(p.method, p.amount, BigDecimal::add);
            byMethodCount.merge(p.method, 1L, Long::sum);
        }
        BigDecimal cashSales = byMethod.getOrDefault("EFECTIVO", BigDecimal.ZERO);
        BigDecimal in = BigDecimal.ZERO, out = BigDecimal.ZERO, exp = BigDecimal.ZERO, adj = BigDecimal.ZERO;
        List<MovementDto> movs = new ArrayList<>();
        for (CashMovement m : movements.findByShiftIdOrderByCreatedAtAsc(s.id)) {
            movs.add(toDto(m));
            switch (m.type) {
                case "INGRESO", "ABONO_FIADO" -> in = in.add(m.amount);
                case "RETIRO" -> out = out.add(m.amount);
                case "GASTO", "PAGO_PROVEEDOR" -> exp = exp.add(m.amount);
                case "AJUSTE_ENTRADA" -> adj = adj.add(m.amount);
                case "AJUSTE_SALIDA" -> adj = adj.subtract(m.amount);
                default -> {
                }
            }
        }
        BigDecimal cashRefunds = BigDecimal.ZERO, refunds = BigDecimal.ZERO;
        for (SaleReturn r : returns.findByShiftId(s.id)) {
            refunds = refunds.add(r.refundAmount);
            if ("EFECTIVO".equals(r.refundMethod)) cashRefunds = cashRefunds.add(r.refundAmount);
        }
        BigDecimal expected = s.openingCash.add(cashSales).add(in).subtract(out).subtract(exp).add(adj).subtract(cashRefunds);
        BigDecimal salesTotal = paid.stream().map(x -> x.total).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal discounts = paid.stream().map(x -> x.discountTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal voidedTotal = voided.stream().map(x -> x.total).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<MethodTotal> methods = byMethod.entrySet().stream()
                .map(e -> new MethodTotal(e.getKey(), e.getValue(), byMethodCount.get(e.getKey()))).toList();
        return new ShiftSummary(s.id, s.number, s.registerId, registerName, s.userId, s.userName, s.status, s.openingCash,
                s.openedAt, s.closedAt, s.closedByName, paid.size(), salesTotal, voided.size(), voidedTotal, methods, cashSales,
                in, out, exp, adj, cashRefunds, refunds, "CLOSED".equals(s.status) ? s.expectedCash : expected, s.countedCash,
                s.difference, s.countDetail, s.closeNotes, s.differenceAuthorizedBy, discounts, movs,
                byMethod.getOrDefault("FIADO", BigDecimal.ZERO), creditPaymentsOf(s.id));
    }

    static BigDecimal parseDetail(String detail) {
        if (detail == null || detail.isBlank()) return null;
        BigDecimal total = BigDecimal.ZERO;
        for (String part : detail.split(",")) {
            String[] f = part.trim().split("x");
            if (f.length != 2) throw ApiException.field("countDetail", "formato no válido");
            try {
                BigDecimal denom = new BigDecimal(f[0].trim());
                BigDecimal qty = new BigDecimal(f[1].trim());
                if (denom.signum() <= 0 || qty.signum() < 0) throw ApiException.field("countDetail", "valores no válidos");
                total = total.add(denom.multiply(qty));
            } catch (NumberFormatException e) {
                throw ApiException.field("countDetail", "formato no válido");
            }
        }
        return total;
    }

    private static MovementDto toDto(CashMovement m) {
        return new MovementDto(m.id, m.type, m.amount, m.reason, m.category, m.userName, m.authorizedBy, m.createdAt);
    }
}
