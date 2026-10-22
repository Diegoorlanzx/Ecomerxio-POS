package com.tiendaposs.accounting;

import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** Fase 14: cierres, notas manuales, terceros y retenciones. */
@RestController
@RequestMapping("/api/accounting")
public class ClosingController {

    private final ClosingService closing;
    private final WithholdingService withholdings;

    public ClosingController(ClosingService closing, WithholdingService withholdings) {
        this.closing = closing;
        this.withholdings = withholdings;
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("America/Bogota"));
    }

    // ---------- Cierres ----------

    @GetMapping("/periods")
    @PreAuthorize("hasAuthority('CONTABILIDAD_VER')")
    public ClosingService.Periods periods() {
        return closing.list();
    }

    @PostMapping("/periods/{period}/close")
    @PreAuthorize("hasAuthority('CONTABILIDAD_EDITAR')")
    public ClosingService.Periods close(@PathVariable String period) {
        return closing.close(period);
    }

    @PostMapping("/periods/{period}/reopen")
    @PreAuthorize("hasAuthority('CONTABILIDAD_EDITAR')")
    public ClosingService.Periods reopen(@PathVariable String period, @Valid @RequestBody ClosingService.ReopenRequest r) {
        return closing.reopen(period, r);
    }

    @PostMapping("/years/{year}/close")
    @PreAuthorize("hasAuthority('CONTABILIDAD_EDITAR')")
    public ClosingService.Periods closeYear(@PathVariable int year) {
        return closing.closeYear(year);
    }

    // ---------- Notas manuales ----------

    @PostMapping("/notes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CONTABILIDAD_EDITAR')")
    public AccountingService.EntryDto note(@Valid @RequestBody ClosingService.NoteRequest r) {
        return closing.note(r);
    }

    @PostMapping("/entries/{id}/reverse")
    @PreAuthorize("hasAuthority('CONTABILIDAD_EDITAR')")
    public AccountingService.EntryDto reverse(@PathVariable Long id, @Valid @RequestBody ClosingService.ReverseRequest r) {
        return closing.reverseNote(id, r);
    }

    // ---------- Terceros ----------

    @GetMapping("/third-parties")
    @PreAuthorize("hasAuthority('CONTABILIDAD_VER')")
    public List<ClosingService.ThirdPartyRow> thirdParties(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return closing.thirdParties(from == null ? today().withDayOfYear(1) : from, to == null ? today() : to);
    }

    @GetMapping("/third-parties/{id}/ledger")
    @PreAuthorize("hasAuthority('CONTABILIDAD_VER')")
    public List<ClosingService.ThirdPartyLine> thirdPartyLedger(@PathVariable String id,
                                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return closing.thirdPartyLedger(id, from == null ? today().withDayOfYear(1) : from, to == null ? today() : to);
    }

    @GetMapping("/export/third-parties")
    @PreAuthorize("hasAuthority('REPORTES_EXPORTAR') and hasAuthority('CONTABILIDAD_VER')")
    public ResponseEntity<byte[]> exportThirdParties(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate f = from == null ? today().withDayOfYear(1) : from, t = to == null ? today() : to;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"terceros_" + f + "_" + t + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(closing.thirdPartiesCsv(f, t).getBytes(StandardCharsets.UTF_8));
    }

    // ---------- Retenciones ----------

    @GetMapping("/withholdings/settings")
    @PreAuthorize("hasAnyAuthority('CONTABILIDAD_VER','FINANZAS_VER')")
    public WithholdingService.SettingsDto withholdingSettings() {
        return withholdings.settings();
    }

    @PutMapping("/withholdings/settings")
    @PreAuthorize("hasAuthority('CONTABILIDAD_EDITAR')")
    public WithholdingService.SettingsDto saveWithholdingSettings(@Valid @RequestBody WithholdingService.SettingsRequest r) {
        return withholdings.saveSettings(r);
    }

    @GetMapping("/withholdings/report")
    @PreAuthorize("hasAnyAuthority('CONTABILIDAD_VER','FINANZAS_VER')")
    public WithholdingService.Report withholdingReport(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return withholdings.report(from == null ? today().withDayOfMonth(1) : from, to == null ? today() : to);
    }

    @GetMapping("/receipts/{id}/withholdings")
    @PreAuthorize("hasAnyAuthority('CONTABILIDAD_VER','FINANZAS_VER','FINANZAS_GASTOS')")
    public WithholdingService.ReceiptView receiptWithholdings(@PathVariable Long id) {
        return withholdings.forReceipt(id);
    }

    @PutMapping("/receipts/{id}/withholdings")
    @PreAuthorize("hasAnyAuthority('CONTABILIDAD_EDITAR','FINANZAS_GASTOS')")
    public WithholdingService.ReceiptView updateReceiptWithholdings(@PathVariable Long id, @Valid @RequestBody WithholdingService.UpdateRequest r) {
        return withholdings.update(id, r);
    }
}
