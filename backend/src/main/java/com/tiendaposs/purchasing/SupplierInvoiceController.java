package com.tiendaposs.purchasing;

import com.tiendaposs.purchasing.SupplierInvoiceService.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Fase 13E: facturas electrónicas de proveedores (XML/ZIP) → recepción de mercancía. */
@RestController
@RequestMapping("/api/supplier-invoices")
public class SupplierInvoiceController {

    private final SupplierInvoiceService service;

    public SupplierInvoiceController(SupplierInvoiceService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('COMPRAS_VER')")
    public List<InvoiceDto> list() {
        return service.list();
    }

    /** Lee el archivo y propone proveedor y productos. No guarda nada. */
    @PostMapping("/preview")
    @PreAuthorize("hasAuthority('COMPRAS_RECIBIR')")
    public Preview preview(@Valid @RequestBody FileRequest r) {
        return service.preview(r);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('COMPRAS_RECIBIR')")
    public InvoiceDto importInvoice(@Valid @RequestBody ImportRequest r) {
        return service.importInvoice(r);
    }

    /** La copia del XML de la factura. */
    @GetMapping("/{id}/xml")
    @PreAuthorize("hasAuthority('COMPRAS_VER')")
    public ResponseEntity<byte[]> xml(@PathVariable Long id) {
        SupplierInvoice x = service.find(id);
        String name = "factura-" + x.invoiceNumber.replaceAll("[^A-Za-z0-9_-]", "") + ".xml";
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .body(x.xml.getBytes(StandardCharsets.UTF_8));
    }
}
