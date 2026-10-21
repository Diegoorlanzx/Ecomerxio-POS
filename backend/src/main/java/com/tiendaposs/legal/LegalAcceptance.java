package com.tiendaposs.legal;

import jakarta.persistence.*;

import java.time.Instant;

/** Prueba de que un usuario aceptó una versión de un documento legal (solo se agrega, nunca se edita). */
@Entity
@Table(name = "legal_acceptances")
public class LegalAcceptance {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long tenantId;
    public Long userId;
    public String userName;
    public String email;
    public String document;
    public String version;
    public Instant acceptedAt = Instant.now();
    public String ip;
    public String userAgent;
    /** REGISTRO, ACTUALIZACION o PRIMER_INGRESO. */
    public String context;
}
