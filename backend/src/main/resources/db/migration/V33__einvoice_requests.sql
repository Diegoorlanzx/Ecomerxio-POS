-- Solicitud de facturación electrónica (opcional: solo la hace la tienda que la quiere, desde el módulo de facturación).
-- ENVIADA (en revisión) → CORREGIR (con comentario de Ecomerxio) → APROBADA (falta pago/habilitación) → ACTIVA.
CREATE TABLE einvoice_requests (
    tenant_id       BIGINT PRIMARY KEY REFERENCES tenants (id) ON DELETE CASCADE,
    status          VARCHAR(12)  NOT NULL,
    admin_comment   VARCHAR(1000),
    accepted_terms  BOOLEAN      NOT NULL DEFAULT FALSE,
    submitted_by    VARCHAR(120),
    submitted_at    TIMESTAMPTZ,
    reviewed_at     TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_einvoice_requests_status ON einvoice_requests (status, submitted_at);

-- Documentos de la solicitud (RUT, cédula, cámara de comercio). Privados: solo los lee la tienda (lista) y el
-- Super Admin de Ecomerxio por la API de plataforma; nunca tienen enlace público.
CREATE TABLE einvoice_request_documents (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    kind          VARCHAR(12)  NOT NULL,
    filename      VARCHAR(160) NOT NULL,
    content_type  VARCHAR(40)  NOT NULL,
    size_bytes    INT          NOT NULL,
    sha256        VARCHAR(64)  NOT NULL,
    data          BYTEA        NOT NULL,
    uploaded_by   VARCHAR(120),
    uploaded_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_einvoice_request_documents_kind ON einvoice_request_documents (tenant_id, kind);
