package com.tiendaposs.accounting;

import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/** Fase 14 (primer bloque): activación, plan de cuentas, mapa contable, pendientes, libro diario y balance de prueba. */
@RestController
@RequestMapping("/api/accounting")
@PreAuthorize("hasAuthority('CONTABILIDAD_VER')")
public class AccountingController {

    private final AccountingService accounting;

    public AccountingController(AccountingService accounting) {
        this.accounting = accounting;
    }

    @GetMapping("/status")
    public AccountingService.Status status() {
        return accounting.status();
    }

    public record EnableRequest(boolean enabled, LocalDate startDate) {
    }

    @PostMapping("/enable")
    @PreAuthorize("hasAuthority('CONTABILIDAD_EDITAR')")
    public AccountingService.Status enable(@RequestBody EnableRequest r) {
        return accounting.enable(r.enabled(), r.startDate());
    }

    @GetMapping("/accounts")
    public List<AccountingService.AccountDto> accounts() {
        return accounting.accounts();
    }

    @PostMapping("/accounts")
    @PreAuthorize("hasAuthority('CONTABILIDAD_EDITAR')")
    public AccountingService.AccountDto createAccount(@Valid @RequestBody AccountingService.AccountRequest r) {
        return accounting.saveAccount(null, r);
    }

    @PutMapping("/accounts/{id}")
    @PreAuthorize("hasAuthority('CONTABILIDAD_EDITAR')")
    public AccountingService.AccountDto updateAccount(@PathVariable Long id, @Valid @RequestBody AccountingService.AccountRequest r) {
        return accounting.saveAccount(id, r);
    }

    @GetMapping("/map")
    public List<AccountingService.MapRow> map() {
        return accounting.mapRows();
    }

    @PutMapping("/map")
    @PreAuthorize("hasAuthority('CONTABILIDAD_EDITAR')")
    public List<AccountingService.MapRow> saveMap(@RequestBody Map<String, String> changes) {
        return accounting.saveMap(changes);
    }

    @GetMapping("/pending")
    public List<AccountingService.PendingDto> pending() {
        return accounting.pendingList();
    }

    @PostMapping("/pending/process")
    @PreAuthorize("hasAuthority('CONTABILIDAD_EDITAR')")
    public Map<String, Integer> processPending() {
        return accounting.processPending();
    }

    @GetMapping("/journal")
    public List<AccountingService.EntryDto> journal(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                    @RequestParam(defaultValue = "100") int limit) {
        LocalDate today = LocalDate.now(ZoneId.of("America/Bogota"));
        return accounting.journal(from == null ? today.withDayOfMonth(1) : from, to == null ? today : to, limit);
    }

    @GetMapping("/trial-balance")
    public AccountingService.TrialBalance trialBalance(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate today = LocalDate.now(ZoneId.of("America/Bogota"));
        return accounting.trialBalance(from == null ? today.withDayOfMonth(1) : from, to == null ? today : to);
    }

    /** Estado de resultados y balance general (propuesta, pendiente de revisión del contador). */
    @GetMapping("/statements")
    public AccountingService.Statements statements(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate today = LocalDate.now(ZoneId.of("America/Bogota"));
        return accounting.statements(from == null ? today.withDayOfMonth(1) : from, to == null ? today : to);
    }

    /** Comprobantes del período en CSV para importar en Siigo (propuesta, pendiente de revisión del contador). */
    @GetMapping("/export/siigo")
    @PreAuthorize("hasAuthority('REPORTES_EXPORTAR')")
    public ResponseEntity<byte[]> exportSiigo(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                              @RequestParam(defaultValue = "1") String voucherType) {
        LocalDate today = LocalDate.now(ZoneId.of("America/Bogota"));
        LocalDate f = from == null ? today.withDayOfMonth(1) : from, t = to == null ? today : to;
        byte[] body = accounting.siigoCsv(f, t, voucherType).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"siigo_comprobantes_" + f + "_" + t + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8)).body(body);
    }
}
