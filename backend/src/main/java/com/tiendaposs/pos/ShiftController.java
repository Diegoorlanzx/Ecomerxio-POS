package com.tiendaposs.pos;

import com.tiendaposs.pos.ShiftService.*;
import com.tiendaposs.tenant.Tenant;
import com.tiendaposs.tenant.TenantContext;
import com.tiendaposs.tenant.TenantRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ShiftController {

    private final ShiftService shifts;
    private final TenantRepository tenants;

    public ShiftController(ShiftService shifts, TenantRepository tenants) {
        this.shifts = shifts;
        this.tenants = tenants;
    }

    public record RegisterRequest(@NotBlank(message = "es obligatorio") String name, Boolean active) {
    }

    @GetMapping("/registers")
    public List<RegisterDto> registers() {
        return shifts.listRegisters();
    }

    @PostMapping("/registers")
    @PreAuthorize("hasAuthority('CONFIG_EDITAR')")
    public RegisterDto createRegister(@Valid @RequestBody RegisterRequest r) {
        return shifts.saveRegister(null, r.name(), r.active());
    }

    @PutMapping("/registers/{id}")
    @PreAuthorize("hasAuthority('CONFIG_EDITAR')")
    public RegisterDto updateRegister(@PathVariable Long id, @Valid @RequestBody RegisterRequest r) {
        return shifts.saveRegister(id, r.name(), r.active());
    }

    public record OpenRequest(@NotNull Long registerId, @NotNull BigDecimal openingCash) {
    }

    @PostMapping("/shifts/open")
    @PreAuthorize("hasAuthority('CAJA_OPERAR')")
    public ShiftSummary open(@Valid @RequestBody OpenRequest r) {
        return shifts.open(r.registerId(), r.openingCash());
    }

    /** Turno abierto del usuario (204 si no tiene). */
    @GetMapping("/shifts/current")
    public ResponseEntity<ShiftSummary> current() {
        return shifts.current().map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @GetMapping("/shifts/open")
    @PreAuthorize("hasAnyAuthority('CAJA_SUPERVISAR','REPORTES_VER')")
    public List<ShiftSummary> openShifts() {
        return shifts.openShifts();
    }

    @GetMapping("/shifts/{id}")
    public ShiftSummary get(@PathVariable Long id) {
        return shifts.get(id);
    }

    public record MovementRequest(@NotBlank String type, @NotNull BigDecimal amount, String reason, String category,
                                  String approvalToken) {
    }

    @PostMapping("/shifts/{id}/movements")
    public MovementDto movement(@PathVariable Long id, @Valid @RequestBody MovementRequest r) {
        return shifts.addMovement(id, r.type(), r.amount(), r.reason(), r.category(), r.approvalToken());
    }

    public record CloseRequest(BigDecimal countedCash, String countDetail, String closeNotes, String approvalToken) {
    }

    @PostMapping("/shifts/{id}/close")
    public ShiftSummary close(@PathVariable Long id, @RequestBody CloseRequest r) {
        return shifts.close(id, r.countedCash(), r.countDetail(), r.closeNotes(), r.approvalToken());
    }

    @GetMapping("/shifts")
    public List<ShiftSummary> history(@RequestParam(required = false) Long userId,
                                      @RequestParam(required = false) Long registerId,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) LocalDate from,
                                      @RequestParam(required = false) LocalDate to,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "30") int size) {
        ZoneId z = zone();
        Instant f = from == null ? Instant.EPOCH : from.atStartOfDay(z).toInstant();
        Instant t = to == null ? Instant.now().plusSeconds(3600) : to.plusDays(1).atStartOfDay(z).toInstant();
        return shifts.history(userId, registerId, status == null || status.isBlank() ? null : status, f, t,
                Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }

    public record CashierReport(Long userId, String userName, int shifts, long salesCount, BigDecimal salesTotal,
                                BigDecimal cashSales, BigDecimal digitalSales, long voidedCount, BigDecimal refunds,
                                BigDecimal overages, BigDecimal shortages) {
    }

    /** Reporte por cajero en un rango de fechas (turnos cerrados y abiertos). */
    @GetMapping("/shifts/report")
    @PreAuthorize("hasAnyAuthority('CAJA_SUPERVISAR','REPORTES_VER')")
    public List<CashierReport> report(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        ZoneId z = zone();
        Instant f = from == null ? LocalDate.now(z).withDayOfMonth(1).atStartOfDay(z).toInstant() : from.atStartOfDay(z).toInstant();
        Instant t = to == null ? Instant.now().plusSeconds(3600) : to.plusDays(1).atStartOfDay(z).toInstant();
        Map<Long, List<ShiftSummary>> byUser = new LinkedHashMap<>();
        for (ShiftSummary s : shifts.history(null, null, null, f, t, 0, 10_000)) {
            byUser.computeIfAbsent(s.userId(), k -> new ArrayList<>()).add(s);
        }
        List<CashierReport> out = new ArrayList<>();
        byUser.forEach((uid, list) -> {
            BigDecimal total = BigDecimal.ZERO, cash = BigDecimal.ZERO, refunds = BigDecimal.ZERO, over = BigDecimal.ZERO, short_ = BigDecimal.ZERO;
            long count = 0, voided = 0;
            for (ShiftSummary s : list) {
                total = total.add(s.salesTotal());
                cash = cash.add(s.cashSales());
                refunds = refunds.add(s.refundsTotal());
                count += s.salesCount();
                voided += s.voidedCount();
                if (s.difference() != null && s.difference().signum() > 0) over = over.add(s.difference());
                if (s.difference() != null && s.difference().signum() < 0) short_ = short_.add(s.difference().negate());
            }
            out.add(new CashierReport(uid, list.get(0).userName(), list.size(), count, total, cash, total.subtract(cash), voided,
                    refunds, over, short_));
        });
        out.sort(Comparator.comparing(CashierReport::salesTotal).reversed());
        return out;
    }

    private ZoneId zone() {
        return ZoneId.of(tenants.findById(TenantContext.require()).map(Tenant::getTimezone).orElse("America/Bogota"));
    }
}
