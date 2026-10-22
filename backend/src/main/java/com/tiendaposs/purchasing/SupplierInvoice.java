package com.tiendaposs.purchasing;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Factura electrónica recibida de un proveedor e importada como recepción (Fase 13E). Guarda la copia del XML. */
@Entity
@Table(name = "supplier_invoices")
public class SupplierInvoice {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long supplierId;
    public Long receiptId;
    public Long orderId;
    public String invoiceNumber;
    public String cufe;
    public LocalDate issueDate;
    public LocalDate dueDate;
    public String supplierTaxId;
    public String supplierName;
    public String buyerTaxId;
    public BigDecimal subtotal = BigDecimal.ZERO;
    public BigDecimal taxTotal = BigDecimal.ZERO;
    public BigDecimal total = BigDecimal.ZERO;
    public String fileName;
    public String xml;
    public String importedBy;
    public Instant createdAt = Instant.now();
}
