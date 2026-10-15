package com.tiendaposs.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiendaposs.common.ApiException;
import com.tiendaposs.payments.PaymentService.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class PaymentController {

    private final PaymentService payments;
    private final ObjectMapper json;

    public PaymentController(PaymentService payments, ObjectMapper json) {
        this.payments = payments;
        this.json = json;
    }

    @GetMapping("/payments")
    @PreAuthorize("hasAnyAuthority('PAGOS_VERIFICAR','PAGOS_CONCILIAR','REPORTES_VER')")
    public List<PaymentRow> list(@RequestParam(required = false) String status, @RequestParam(required = false) String method,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return payments.list(status, method, from, to);
    }

    public record NoteRequest(@Size(max = 300) String note) {
    }

    public record ReasonRequest(@NotBlank(message = "explica el motivo") @Size(max = 300) String reason) {
    }

    @PostMapping("/payments/{id}/confirm")
    @PreAuthorize("hasAuthority('PAGOS_VERIFICAR')")
    public PaymentRow confirm(@PathVariable Long id, @Valid @RequestBody(required = false) NoteRequest r) {
        return payments.confirm(id, r == null ? null : r.note());
    }

    @PostMapping("/payments/{id}/reject")
    @PreAuthorize("hasAuthority('PAGOS_VERIFICAR')")
    public PaymentRow reject(@PathVariable Long id, @Valid @RequestBody ReasonRequest r) {
        return payments.reject(id, r.reason());
    }

    public record ReplaceRequest(@NotBlank String method, BigDecimal amount, @Size(max = 80) String reference) {
    }

    @PostMapping("/payments/{id}/replace")
    @PreAuthorize("hasAnyAuthority('PAGOS_VERIFICAR','POS_VENDER')")
    public PaymentRow replace(@PathVariable Long id, @Valid @RequestBody ReplaceRequest r) {
        return payments.replace(id, r.method(), r.amount(), r.reference());
    }

    @GetMapping("/payments/events")
    @PreAuthorize("hasAnyAuthority('PAGOS_VERIFICAR','PAGOS_CONCILIAR','AUDITORIA_VER')")
    public List<PaymentEventDto> events(@RequestParam(required = false) Long paymentId) {
        return payments.events(paymentId);
    }

    // ---- Cuentas de cobro con QR (el POS las muestra al cobrar) ----

    @GetMapping("/payment-accounts")
    public List<AccountDto> accounts() {
        return payments.accounts();
    }

    @PostMapping("/payment-accounts")
    @PreAuthorize("hasAuthority('CONFIG_EDITAR')")
    public AccountDto createAccount(@Valid @RequestBody AccountRequest r) {
        return payments.saveAccount(null, r);
    }

    @PutMapping("/payment-accounts/{id}")
    @PreAuthorize("hasAuthority('CONFIG_EDITAR')")
    public AccountDto updateAccount(@PathVariable Long id, @Valid @RequestBody AccountRequest r) {
        return payments.saveAccount(id, r);
    }

    @DeleteMapping("/payment-accounts/{id}")
    @PreAuthorize("hasAuthority('CONFIG_EDITAR')")
    public ResponseEntity<Void> deleteAccount(@PathVariable Long id) {
        payments.deleteAccount(id);
        return ResponseEntity.noContent().build();
    }

    // ---- Integraciones (confirmación automática) ----

    @GetMapping("/payment-integrations")
    @PreAuthorize("hasAuthority('CONFIG_EDITAR')")
    public List<IntegrationDto> integrations() {
        return payments.integrations();
    }

    public record IntegrationRequest(@Size(max = 40) String provider) {
    }

    @PostMapping("/payment-integrations")
    @PreAuthorize("hasAuthority('CONFIG_EDITAR')")
    public IntegrationDto createIntegration(@Valid @RequestBody(required = false) IntegrationRequest r) {
        return payments.createIntegration(r == null ? null : r.provider());
    }

    /** Webhook público firmado (HMAC-SHA256 del cuerpo exacto con el secreto de la integración). */
    @PostMapping(value = "/webhooks/payments/{publicKey}", consumes = "application/json")
    public Map<String, Object> webhook(@PathVariable String publicKey, @RequestBody String raw,
                                       @RequestHeader(value = "X-Signature", required = false) String signature) {
        WebhookPayload body;
        try {
            body = json.readValue(raw, WebhookPayload.class);
        } catch (Exception e) {
            throw ApiException.badRequest("Cuerpo no válido");
        }
        return payments.webhook(publicKey, raw, signature, body);
    }

    // ---- Conciliación ----

    public record ReconcileRequest(@NotBlank(message = "elige el medio") String method, @Size(max = 200) String fileName,
                                   @NotBlank(message = "sube el extracto") @Size(max = 3_000_000, message = "el archivo es muy grande") String csv) {
    }

    @PostMapping("/payments/reconcile")
    @PreAuthorize("hasAuthority('PAGOS_CONCILIAR')")
    public ReconDto reconcile(@Valid @RequestBody ReconcileRequest r) {
        return payments.reconcile(r.method(), r.fileName(), r.csv());
    }

    @GetMapping("/payments/reconciliations")
    @PreAuthorize("hasAuthority('PAGOS_CONCILIAR')")
    public List<ReconDto> reconciliations() {
        return payments.reconciliations();
    }

    @GetMapping("/payments/reconciliations/{id}")
    @PreAuthorize("hasAuthority('PAGOS_CONCILIAR')")
    public ReconDto reconciliation(@PathVariable Long id) {
        return payments.reconciliation(id);
    }
}
