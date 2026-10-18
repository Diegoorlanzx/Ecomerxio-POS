package com.tiendaposs.einvoice;

import com.tiendaposs.tenant.TenantContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Fase 13B: documentos electrónicos (estado, historial, reintentos, documento de prueba y activación). */
@RestController
@RequestMapping("/api/einvoice")
@PreAuthorize("hasAuthority('FACTURACION_CONFIGURAR')")
public class EInvoiceController {

    private final EInvoiceService einvoice;

    public EInvoiceController(EInvoiceService einvoice) {
        this.einvoice = einvoice;
    }

    @GetMapping("/summary")
    public EInvoiceService.Summary summary() {
        return einvoice.summary(TenantContext.require());
    }

    @GetMapping("/documents")
    public List<EInvoiceService.DocumentDto> documents(@RequestParam(required = false) String status,
                                                       @RequestParam(defaultValue = "100") int limit) {
        return einvoice.list(TenantContext.require(), status, limit);
    }

    @GetMapping("/documents/{id}")
    public EInvoiceService.DocumentDetail document(@PathVariable Long id) {
        return einvoice.detail(TenantContext.require(), id);
    }

    @PostMapping("/documents/{id}/retry")
    public EInvoiceService.DocumentDto retry(@PathVariable Long id) {
        return einvoice.dto(einvoice.retry(TenantContext.require(), id));
    }

    public record BulkRetryRequest(@jakarta.validation.constraints.NotEmpty(message = "elige al menos un documento") List<Long> ids) {
    }

    /** Reintento en lote desde la bandeja de errores. */
    @PostMapping("/documents/retry")
    public EInvoiceService.BulkResult retryMany(@jakarta.validation.Valid @RequestBody BulkRetryRequest r) {
        return einvoice.retryMany(TenantContext.require(), r.ids());
    }

    @PostMapping("/test")
    public EInvoiceService.DocumentDetail test() {
        Long t = TenantContext.require();
        ElectronicDocument d = einvoice.createTest(t);
        einvoice.processDue();
        return einvoice.detail(t, d.id);
    }

    public record EnableRequest(boolean enabled) {
    }

    @PostMapping("/enable")
    public EInvoiceService.Summary enable(@RequestBody EnableRequest r) {
        Long t = TenantContext.require();
        einvoice.setEnabled(t, r.enabled());
        return einvoice.summary(t);
    }

    /** Procesa ya lo que está en cola (normalmente lo hace solo cada 15 s). */
    @PostMapping("/process")
    public Map<String, Integer> process() {
        return Map.of("processed", einvoice.processDue());
    }
}
