package com.tiendaposs.einvoice;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/** Un documento electrónico (POS electrónico, factura o nota crédito) y su estado ante el proveedor y la DIAN. */
@Entity
@Table(name = "electronic_documents")
public class ElectronicDocument {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long saleId;
    public Long returnId;
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
