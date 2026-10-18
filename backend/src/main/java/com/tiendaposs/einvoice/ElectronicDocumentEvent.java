package com.tiendaposs.einvoice;

import jakarta.persistence.*;

import java.time.Instant;

/** Historial inalterable de un documento electrónico: solo se agregan filas. */
@Entity
@Table(name = "electronic_document_events")
public class ElectronicDocumentEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long documentId;
    public String type;
    public String statusAfter;
    public String code;
    public String message;
    public String userName;
    public Instant createdAt = Instant.now();
}
