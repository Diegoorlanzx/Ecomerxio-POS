package com.tiendaposs.finance;

import com.tiendaposs.finance.FinanceService.*;
import com.tiendaposs.tenant.Tenant;
import com.tiendaposs.tenant.TenantContext;
import com.tiendaposs.tenant.TenantRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

@RestController
@RequestMapping("/api/finance")
public class FinanceController {

    private final FinanceService finance;
    private final TenantRepository tenants;

    public FinanceController(FinanceService finance, TenantRepository tenants) {
        this.finance = finance;
        this.tenants = tenants;
    }

    @GetMapping("/categories")
    @PreAuthorize("hasAnyAuthority('FINANZAS_VER','FINANZAS_GASTOS','CAJA_MOVIMIENTOS')")
    public List<CategoryDto> categories() {
        return finance.categories();
    }

    @PostMapping("/categories")
    @PreAuthorize("hasAuthority('FINANZAS_GASTOS')")
    public CategoryDto createCategory(@Valid @RequestBody CategoryRequest r) {
        return finance.createCategory(r);
    }

    @GetMapping("/entries")
    @PreAuthorize("hasAnyAuthority('FINANZAS_VER','FINANZAS_GASTOS')")
    public List<EntryDto> entries(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                  @RequestParam(required = false) String kind, @RequestParam(required = false) Long categoryId) {
        LocalDate t = to == null ? today() : to;
        return finance.entries(from == null ? t.withDayOfMonth(1) : from, t, kind, categoryId);
    }

    @PostMapping("/entries")
    @PreAuthorize("hasAuthority('FINANZAS_GASTOS')")
    public EntryDto createEntry(@Valid @RequestBody EntryRequest r) {
        return finance.createEntry(r);
    }

    public record VoidRequest(@NotBlank(message = "explica por qué se anula") @Size(max = 300) String reason) {
    }

    @PostMapping("/entries/{id}/void")
    @PreAuthorize("hasAuthority('FINANZAS_GASTOS')")
    public EntryDto voidEntry(@PathVariable Long id, @Valid @RequestBody VoidRequest r) {
        return finance.voidEntry(id, r.reason());
    }

    @GetMapping("/payables")
    @PreAuthorize("hasAnyAuthority('FINANZAS_VER','FINANZAS_GASTOS','COMPRAS_VER')")
    public Payables payables(@RequestParam(required = false) Long supplierId) {
        return finance.payables(supplierId);
    }

    @PostMapping("/supplier-payments")
    @PreAuthorize("hasAuthority('FINANZAS_GASTOS')")
    public SupplierPaymentDto paySupplier(@Valid @RequestBody SupplierPaymentRequest r) {
        return finance.paySupplier(r);
    }

    @GetMapping("/supplier-payments")
    @PreAuthorize("hasAnyAuthority('FINANZAS_VER','FINANZAS_GASTOS','COMPRAS_VER')")
    public List<SupplierPaymentDto> supplierPayments(@RequestParam(required = false) Long supplierId,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return finance.supplierPayments(supplierId, from, to);
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('FINANZAS_VER')")
    public Summary summary(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate[] r = range(from, to);
        return finance.summary(r[0], r[1]);
    }

    @GetMapping("/monthly")
    @PreAuthorize("hasAuthority('FINANZAS_VER')")
    public MonthlyReport monthly(@RequestParam(required = false) String month) {
        return finance.monthly(month == null || month.isBlank() ? YearMonth.from(today()) : YearMonth.parse(month));
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('REPORTES_EXPORTAR') and hasAuthority('FINANZAS_VER')")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate[] r = range(from, to);
        byte[] body = finance.exportCsv(r[0], r[1]).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"finanzas_" + r[0] + "_" + r[1] + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8)).body(body);
    }

    private LocalDate[] range(LocalDate from, LocalDate to) {
        LocalDate t = to == null ? today() : to;
        LocalDate f = from == null ? t.withDayOfMonth(1) : from;
        if (f.isAfter(t)) throw com.tiendaposs.common.ApiException.field("from", "la fecha inicial es posterior a la final");
        if (ChronoUnit.DAYS.between(f, t) > 400) throw com.tiendaposs.common.ApiException.field("from", "el rango máximo es de un año");
        return new LocalDate[]{f, t};
    }

    private LocalDate today() {
        return LocalDate.now(ZoneId.of(tenants.findById(TenantContext.require()).map(Tenant::getTimezone).orElse("America/Bogota")));
    }
}
