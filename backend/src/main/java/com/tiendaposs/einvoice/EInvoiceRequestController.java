package com.tiendaposs.einvoice;

import com.tiendaposs.tenant.TenantContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Solicitud de facturación electrónica de la tienda (opcional): documentos y envío a Ecomerxio. */
@RestController
@RequestMapping("/api/einvoice/request")
@PreAuthorize("hasAuthority('FACTURACION_CONFIGURAR')")
public class EInvoiceRequestController {

    private final EInvoiceRequestService requests;

    public EInvoiceRequestController(EInvoiceRequestService requests) {
        this.requests = requests;
    }

    public record SubmitRequest(boolean acceptedTerms) {
    }

    @GetMapping
    public EInvoiceRequestService.RequestView get() {
        return requests.view(TenantContext.require());
    }

    /** El archivo llega como cuerpo binario; el tipo de documento y el nombre, en cabeceras. */
    @PostMapping(value = "/documents", consumes = "application/octet-stream")
    public EInvoiceRequestService.Doc upload(@RequestBody byte[] data, @RequestHeader("X-Doc-Kind") String kind,
                                             @RequestHeader(value = "X-Filename", required = false) String filename) {
        String name = filename == null ? null : URLDecoder.decode(filename, StandardCharsets.UTF_8);
        return requests.upload(TenantContext.require(), kind, name, data);
    }

    @DeleteMapping("/documents/{id}")
    public EInvoiceRequestService.RequestView delete(@PathVariable Long id) {
        Long t = TenantContext.require();
        requests.deleteDocument(t, id);
        return requests.view(t);
    }

    @PostMapping("/submit")
    public EInvoiceRequestService.RequestView submit(@RequestBody SubmitRequest req) {
        return requests.submit(TenantContext.require(), req.acceptedTerms());
    }
}
