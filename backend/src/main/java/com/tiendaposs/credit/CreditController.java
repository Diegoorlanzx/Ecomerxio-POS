package com.tiendaposs.credit;

import com.tiendaposs.credit.CreditService.*;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Fiado: cuentas por cobrar a clientes. */
@RestController
@RequestMapping("/api/credit")
public class CreditController {

    private final CreditService credit;

    public CreditController(CreditService credit) {
        this.credit = credit;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('FIADO_GESTIONAR','FIADO_AUTORIZAR','FINANZAS_VER')")
    public List<CustomerCredit> list(@RequestParam(defaultValue = "true") boolean onlyWithBalance) {
        return credit.list(onlyWithBalance);
    }

    /** Cuadre de fiados del período: saldo inicial + fiado − abonos ± ajustes = saldo final. */
    @GetMapping("/ledger")
    @PreAuthorize("hasAnyAuthority('FIADO_GESTIONAR','FIADO_AUTORIZAR','FINANZAS_VER')")
    public Ledger ledger(@RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
                         @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to) {
        return credit.ledger(from, to);
    }

    @GetMapping("/{customerId}")
    @PreAuthorize("hasAnyAuthority('FIADO_GESTIONAR','FIADO_AUTORIZAR','FINANZAS_VER')")
    public CustomerCredit detail(@PathVariable Long customerId) {
        return credit.detail(customerId);
    }

    @PostMapping("/{customerId}/payments")
    @PreAuthorize("hasAuthority('FIADO_GESTIONAR')")
    public CustomerCredit pay(@PathVariable Long customerId, @Valid @RequestBody PaymentRequest r) {
        return credit.pay(customerId, r);
    }

    @PutMapping("/{customerId}/terms")
    @PreAuthorize("hasAuthority('FIADO_AUTORIZAR')")
    public CustomerCredit terms(@PathVariable Long customerId, @Valid @RequestBody TermsRequest r) {
        return credit.setTerms(customerId, r);
    }

    @GetMapping("/{customerId}/reminder")
    @PreAuthorize("hasAnyAuthority('FIADO_GESTIONAR','FIADO_AUTORIZAR')")
    public Map<String, String> reminder(@PathVariable Long customerId) {
        return credit.reminder(customerId);
    }
}
