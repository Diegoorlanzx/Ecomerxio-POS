package com.tiendaposs.einvoice;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/** Un documento electrónico (POS electrónico, factura, nota crédito o documento soporte) y su estado ante el proveedor y la DIAN. */
@Entity
@Table(name = "electronic_documents")
public class ElectronicDocument {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long saleId;
    public Long returnId;
    /** Documento soporte: la recepción de mercancía de la que sale (Fase 13E). */
    public Long receiptId;
    public Long originalId;
    public String documentType;
    public String environment;
    public String provider;
    public Long resolutionId;
    public String prefix;
    public long number;
    public String fullNumber;
    public String externalRef;
    public String status;
    public Instant issueDate;
    public String customerName;
    public String customerIdType;
    public String customerIdNumber;
    /** Comprador registrado (null = consumidor final o proveedor del documento soporte). */
    public Long customerId;
    /** CONTADO o CREDITO (fiado), con su vencimiento (Fase 16A). */
    public String paymentForm = "CONTADO";
    public java.time.LocalDate dueDate;
    /** Reemplazado al refacturar: lo anula su nota crédito y lo reemplaza replacedById. */
    public boolean superseded;
    public Long replacedById;
    /** Fase 16E: nota hecha a mano (no por devolución ni anulación), con su concepto DIAN y una sola línea. */
    public boolean manual;
    public String noteConcept;
    public String lineDescription;
    public BigDecimal lineTaxRate;
    /** FIADO (cuenta del cliente) o el medio de pago con que se arregló la nota. */
    public String settlement;
    public BigDecimal total = BigDecimal.ZERO;
    public BigDecimal taxTotal = BigDecimal.ZERO;
    public String reason;
    public boolean test;
    public String cufe;
    public String qrData;
    public String trackId;
    public int attempts;
    public Instant nextAttemptAt = Instant.now();
    public String lastErrorCode;
    public String lastErrorMessage;
    public Instant acceptedAt;
    public Instant createdAt = Instant.now();
    public Instant updatedAt = Instant.now();
    @Version
    public int version;

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
