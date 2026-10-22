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
    private final NoteService notes;

    public EInvoiceController(EInvoiceService einvoice, NoteService notes) {
        this.einvoice = einvoice;
        this.notes = notes;
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

    /** Desactivar pide la contraseña del dueño (Fase 16B). */
    public record EnableRequest(boolean enabled, String password) {
    }

    @PostMapping("/enable")
    public EInvoiceService.Summary enable(@RequestBody EnableRequest r) {
        Long t = TenantContext.require();
        einvoice.setEnabled(t, r.enabled(), r.password());
        return einvoice.summary(t);
    }

    /** Fase 16B: qué pasa al activar (documentos al mes, ventas antes del corte). */
    @GetMapping("/activation-preview")
    public EInvoiceService.ActivationPreview activationPreview() {
        return einvoice.activationPreview(TenantContext.require());
    }

    public record CutoffViewRequest(boolean hidePreCutoff) {
    }

    @PostMapping("/cutoff-view")
    public EInvoiceService.Summary cutoffView(@RequestBody CutoffViewRequest r) {
        Long t = TenantContext.require();
        einvoice.setHidePreCutoff(t, r.hidePreCutoff());
        return einvoice.summary(t);
    }

    public record ModeRequest(String mode) {
    }

    /** Fase 16A: toda venta lleva documento (OBLIGADO) o solo cuando el cliente lo pide (A_PEDIDO). */
    @PostMapping("/mode")
    public EInvoiceService.Summary mode(@RequestBody ModeRequest r) {
        Long t = TenantContext.require();
        einvoice.setMode(t, r.mode());
        return einvoice.summary(t);
    }

    public record ReinvoiceRequest(@jakarta.validation.constraints.NotNull(message = "elige el cliente") Long customerId) {
    }

    /** Fase 16A: factura a nombre de un cliente una venta ya cobrada (nota crédito al documento anterior + factura nueva). */
    @PostMapping("/sales/{saleId}/reinvoice")
    public EInvoiceService.DocumentDto reinvoice(@PathVariable Long saleId, @jakarta.validation.Valid @RequestBody ReinvoiceRequest r) {
        return einvoice.dto(einvoice.reinvoice(TenantContext.require(), saleId, r.customerId()));
    }

    /** Fase 16E: nota crédito o débito manual, o nota de ajuste al documento soporte. */
    @PostMapping("/documents/{id}/notes")
    public EInvoiceService.DocumentDto note(@PathVariable Long id, @jakarta.validation.Valid @RequestBody NoteService.NoteRequest r) {
        return einvoice.dto(notes.create(id, r));
    }

    @GetMapping("/note-concepts")
    public Map<String, List<Map<String, String>>> noteConcepts() {
        return notes.concepts();
    }

    /** Fase 13E: documento soporte de una recepción hecha antes de activar la facturación o de marcar al proveedor. */
    @PostMapping("/receipts/{receiptId}/support-document")
    public EInvoiceService.DocumentDto supportDocument(@PathVariable Long receiptId) {
        return einvoice.dto(einvoice.createSupportDocument(TenantContext.require(), receiptId));
    }

    /** Procesa ya lo que está en cola (normalmente lo hace solo cada 15 s). */
    @PostMapping("/process")
    public Map<String, Integer> process() {
        return Map.of("processed", einvoice.processDue());
    }
}
