package com.tiendaposs.finance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/** Fase 15: cuentas de banco y billeteras, flujo proyectado, calendario tributario y tablero financiero. */
@RestController
@RequestMapping("/api/finance")
public class MoneyController {

    private final MoneyAccountService accounts;
    private final CashProjectionService projection;
    private final TaxCalendarService calendar;
    private final FinanceDashboardService dashboard;

    public MoneyController(MoneyAccountService accounts, CashProjectionService projection, TaxCalendarService calendar,
                           FinanceDashboardService dashboard) {
        this.accounts = accounts;
        this.projection = projection;
        this.calendar = calendar;
        this.dashboard = dashboard;
    }

    @GetMapping("/dashboard")
    @PreAuthorize("hasAuthority('FINANZAS_VER')")
    public FinanceDashboardService.Dashboard dashboard() {
        return dashboard.dashboard();
    }

    // ---------- Cuentas ----------

    @GetMapping("/accounts")
    @PreAuthorize("hasAuthority('FINANZAS_VER')")
    public MoneyAccountService.Overview accounts() {
        return accounts.overview();
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('FINANZAS_GASTOS')")
    public MoneyAccountService.AccountDto createAccount(@Valid @RequestBody MoneyAccountService.AccountRequest r) {
        return accounts.save(null, r);
    }

    @PutMapping("/accounts/{id}")
    @PreAuthorize("hasAuthority('FINANZAS_GASTOS')")
    public MoneyAccountService.AccountDto updateAccount(@PathVariable Long id, @Valid @RequestBody MoneyAccountService.AccountRequest r) {
        return accounts.save(id, r);
    }

    @GetMapping("/accounts/{id}/ledger")
    @PreAuthorize("hasAuthority('FINANZAS_VER')")
    public MoneyAccountService.Ledger ledger(@PathVariable Long id, @RequestParam(required = false) LocalDate from,
                                            @RequestParam(required = false) LocalDate to) {
        LocalDate t = to == null ? LocalDate.now() : to;
        return accounts.ledger(id, from == null ? t.withDayOfMonth(1) : from, t);
    }

    @PostMapping("/account-moves")
    @PreAuthorize("hasAuthority('FINANZAS_GASTOS')")
    public MoneyAccountService.Ledger addMove(@Valid @RequestBody MoneyAccountService.MoveRequest r) {
        return accounts.addMove(r);
    }

    public record VoidRequest(@Size(max = 300) String reason) {
    }

    @PostMapping("/account-moves/{id}/void")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('FINANZAS_GASTOS')")
    public void voidMove(@PathVariable Long id, @Valid @RequestBody(required = false) VoidRequest r) {
        accounts.voidMove(id, r == null ? null : r.reason());
    }

    // ---------- Flujo proyectado ----------

    @GetMapping("/projection")
    @PreAuthorize("hasAuthority('FINANZAS_VER')")
    public CashProjectionService.Projection projection(@RequestParam(defaultValue = "8") int weeks) {
        return projection.project(weeks);
    }

    // ---------- Calendario tributario ----------

    @GetMapping("/tax-calendar")
    @PreAuthorize("hasAuthority('FINANZAS_VER')")
    public TaxCalendarService.Calendar taxCalendar() {
        return calendar.calendar();
    }

    @PutMapping("/tax-calendar/settings")
    @PreAuthorize("hasAuthority('FINANZAS_GASTOS')")
    public TaxCalendarService.Calendar saveTaxSettings(@Valid @RequestBody TaxCalendarService.SettingsRequest r) {
        return calendar.saveSettings(r);
    }

    @PutMapping("/tax-calendar/items/{id}")
    @PreAuthorize("hasAuthority('FINANZAS_GASTOS')")
    public TaxCalendarService.ItemDto updateTaxItem(@PathVariable Long id, @Valid @RequestBody TaxCalendarService.ItemUpdate r) {
        return calendar.update(id, r);
    }

    @PostMapping("/tax-calendar/items")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('FINANZAS_GASTOS')")
    public TaxCalendarService.ItemDto addTaxItem(@Valid @RequestBody TaxCalendarService.CustomRequest r) {
        return calendar.addCustom(r);
    }

    @DeleteMapping("/tax-calendar/items/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('FINANZAS_GASTOS')")
    public void deleteTaxItem(@PathVariable Long id) {
        calendar.deleteCustom(id);
    }
}
