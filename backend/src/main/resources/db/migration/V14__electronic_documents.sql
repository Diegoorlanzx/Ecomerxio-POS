-- Fase 13B: emisión de documentos electrónicos (con un proveedor simulado mientras se elige el real).

-- El dueño activa la facturación cuando el asistente está completo y el documento de prueba fue aceptado.
ALTER TABLE tenant_fiscal_profiles ADD COLUMN invoicing_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- La venta recuerda si el cliente pidió factura electrónica a su nombre.
ALTER TABLE sales ADD COLUMN invoice_requested BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE electronic_documents (
    id                 BIGSERIAL PRIMARY KEY,
    tenant_id          BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    sale_id            BIGINT REFERENCES sales(id) ON DELETE RESTRICT,   -- null = documento de prueba
    return_id          BIGINT REFERENCES sale_returns(id) ON DELETE RESTRICT,
    original_id        BIGINT REFERENCES electronic_documents(id),       -- la factura que corrige una nota
    document_type      VARCHAR(20) NOT NULL,   -- POS_ELECTRONICO | FACTURA | NOTA_CREDITO
    environment        VARCHAR(12) NOT NULL,   -- PRUEBAS | PRODUCCION
    provider           VARCHAR(30) NOT NULL,   -- SIMULADO | (proveedor real)
    resolution_id      BIGINT REFERENCES numbering_resolutions(id),
    prefix             VARCHAR(10),
    number             BIGINT NOT NULL,
    full_number        VARCHAR(30) NOT NULL,
    -- Clave única que viaja al proveedor: con ella se consulta antes de reintentar (nunca dos documentos por lo mismo).
    external_ref       VARCHAR(80) NOT NULL UNIQUE,
    status             VARCHAR(15) NOT NULL,   -- PENDIENTE | EN_PROCESO | DESCONOCIDO | ACEPTADO | RECHAZADO | BLOQUEADO
    issue_date         TIMESTAMPTZ NOT NULL,   -- fecha real de la venta (también para ventas sin internet)
    customer_name      VARCHAR(160),
    customer_id_type   VARCHAR(10),
    customer_id_number VARCHAR(30),
    total              NUMERIC(14,2) NOT NULL,
    tax_total          NUMERIC(14,2) NOT NULL DEFAULT 0,
    reason             VARCHAR(300),           -- motivo de la nota
    test               BOOLEAN NOT NULL DEFAULT FALSE,
    cufe               VARCHAR(120),
    qr_data            VARCHAR(500),
    track_id           VARCHAR(80),
    attempts           INT NOT NULL DEFAULT 0,
    next_attempt_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error_code    VARCHAR(40),
    last_error_message VARCHAR(500),
    accepted_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    version            INT NOT NULL DEFAULT 0
);
-- Una venta tiene un solo documento de venta; cada devolución, una sola nota.
CREATE UNIQUE INDEX ux_edoc_sale ON electronic_documents (sale_id) WHERE sale_id IS NOT NULL AND document_type <> 'NOTA_CREDITO';
CREATE UNIQUE INDEX ux_edoc_return ON electronic_documents (return_id) WHERE return_id IS NOT NULL;
CREATE UNIQUE INDEX ux_edoc_void_note ON electronic_documents (original_id) WHERE document_type = 'NOTA_CREDITO' AND return_id IS NULL;
CREATE INDEX ix_edoc_tenant ON electronic_documents (tenant_id, created_at DESC);
CREATE INDEX ix_edoc_queue ON electronic_documents (status, next_attempt_at) WHERE status IN ('PENDIENTE', 'EN_PROCESO', 'DESCONOCIDO');

-- Historial inalterable: cada envío, consulta, respuesta y acción de un usuario.
CREATE TABLE electronic_document_events (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    document_id  BIGINT NOT NULL REFERENCES electronic_documents(id) ON DELETE CASCADE,
    type         VARCHAR(30) NOT NULL,   -- CREADO | ENVIO | CONSULTA | ACEPTADO | RECHAZADO | EN_PROCESO | SIN_RESPUESTA | REINTENTO_MANUAL | BLOQUEADO
    status_after VARCHAR(15),
    code         VARCHAR(40),
    message      VARCHAR(1000),
    user_name    VARCHAR(120),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_edoc_events ON electronic_document_events (document_id, id);
