-- Fase 13A: datos fiscales y habilitación para la facturación electrónica (sin emitir todavía).

-- Perfil fiscal del comercio (emisor). Uno por tienda; se precarga con los datos generales de la tienda.
CREATE TABLE tenant_fiscal_profiles (
    tenant_id              BIGINT PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    person_type            VARCHAR(10),            -- NATURAL | JURIDICA
    id_type                VARCHAR(10) NOT NULL DEFAULT 'NIT',
    id_number              VARCHAR(20),
    dv                     VARCHAR(1),
    legal_name             VARCHAR(160),
    trade_name             VARCHAR(160),
    iva_responsible        BOOLEAN,                -- responsable de IVA (null = sin definir)
    responsibilities       VARCHAR(80),            -- CSV de códigos: O-13, O-15, O-23, O-47, R-99-PN
    ciiu                   VARCHAR(10),
    address                VARCHAR(200),
    municipality_code      VARCHAR(5),              -- código DANE del municipio
    municipality_name      VARCHAR(80),
    department_name        VARCHAR(80),
    postal_code            VARCHAR(10),
    invoicing_email        VARCHAR(160),
    phone                  VARCHAR(30),
    -- Habilitación (lo marca el dueño; el sistema no lo puede verificar solo)
    registered_as_issuer   BOOLEAN NOT NULL DEFAULT FALSE,
    issuer_registered_on   DATE,
    provider_name          VARCHAR(80),
    provider_authorized    BOOLEAN NOT NULL DEFAULT FALSE,
    environment            VARCHAR(12) NOT NULL DEFAULT 'PRUEBAS', -- PRUEBAS | PRODUCCION
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Resoluciones de numeración autorizadas (prefijo, rango y vigencia) por tipo de documento.
CREATE TABLE numbering_resolutions (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    document_type     VARCHAR(20) NOT NULL,   -- FACTURA | POS_ELECTRONICO | DOCUMENTO_SOPORTE
    resolution_number VARCHAR(40) NOT NULL,
    resolution_date   DATE,
    prefix            VARCHAR(10),
    range_from        BIGINT NOT NULL,
    range_to          BIGINT NOT NULL,
    next_number       BIGINT NOT NULL,        -- siguiente consecutivo a usar (lo usará la Fase 13B)
    valid_from        DATE NOT NULL,
    valid_to          DATE NOT NULL,
    environment       VARCHAR(12) NOT NULL DEFAULT 'PRUEBAS',
    technical_key     VARCHAR(120),           -- clave técnica de la resolución, si el proveedor la pide
    active            BOOLEAN NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_resolution_range CHECK (range_from > 0 AND range_to >= range_from
        AND next_number BETWEEN range_from AND range_to + 1),
    CONSTRAINT ck_resolution_dates CHECK (valid_to >= valid_from)
);
CREATE INDEX ix_resolutions_tenant ON numbering_resolutions (tenant_id, document_type, active);

-- Productos: clasificación tributaria. Con tarifa > 0 es GRAVADO; con tarifa 0 el dueño elige EXENTO o EXCLUIDO.
ALTER TABLE products ADD COLUMN tax_class VARCHAR(10);

-- Clientes: datos para facturarles a su nombre.
ALTER TABLE customers ADD COLUMN dv VARCHAR(1);
ALTER TABLE customers ADD COLUMN person_type VARCHAR(10);
ALTER TABLE customers ADD COLUMN invoice_email VARCHAR(160);
ALTER TABLE customers ADD COLUMN fiscal_responsibilities VARCHAR(80);

-- Permiso nuevo para los roles predefinidos que ya existen (las tiendas nuevas lo reciben al crearse).
INSERT INTO role_permissions (role_id, permission)
SELECT id, 'FACTURACION_CONFIGURAR' FROM roles WHERE system_code IN ('ADMINISTRADOR', 'CONTADOR')
ON CONFLICT DO NOTHING;
