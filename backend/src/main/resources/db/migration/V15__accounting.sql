-- Fase 14 (primer bloque): plan de cuentas, mapa contable, asientos automáticos de ventas, costo, devoluciones y
-- anulaciones, pendientes por contabilizar. Las cuentas son una PROPUESTA: las valida un contador antes de vender.

CREATE TABLE accounting_settings (
    tenant_id   BIGINT PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    enabled     BOOLEAN NOT NULL DEFAULT FALSE,
    start_date  DATE,                        -- no se contabiliza hacia atrás
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Plan de cuentas (PUC) por tienda.
CREATE TABLE accounting_accounts (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    code        VARCHAR(12) NOT NULL,
    name        VARCHAR(120) NOT NULL,
    nature      VARCHAR(7) NOT NULL,         -- DEBITO | CREDITO
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_account_code CHECK (code ~ '^[1-9][0-9]{3,11}$')
);
CREATE UNIQUE INDEX ux_accounts_code ON accounting_accounts (tenant_id, code);

-- Mapa contable: qué cuenta usa cada cosa (medio de pago, ventas, IVA, costo...).
CREATE TABLE accounting_map (
    tenant_id     BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    map_key       VARCHAR(60) NOT NULL,
    account_code  VARCHAR(12),
    PRIMARY KEY (tenant_id, map_key)
);

-- Comprobantes contables y sus líneas (partida doble: suma de débitos = suma de créditos).
CREATE TABLE journal_entries (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    number       BIGINT NOT NULL,
    entry_type   VARCHAR(15) NOT NULL,       -- VENTA | DEVOLUCION | ANULACION
    entry_date   DATE NOT NULL,
    source_type  VARCHAR(15) NOT NULL,       -- SALE | RETURN | VOID
    source_id    BIGINT NOT NULL,
    reversal_of  BIGINT REFERENCES journal_entries(id),
    description  VARCHAR(300) NOT NULL,
    total        NUMERIC(14,2) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Una operación de origen produce un solo comprobante de cada tipo (contabilizar dos veces es imposible).
CREATE UNIQUE INDEX ux_journal_source ON journal_entries (tenant_id, source_type, source_id, entry_type);
CREATE UNIQUE INDEX ux_journal_number ON journal_entries (tenant_id, number);
CREATE INDEX ix_journal_date ON journal_entries (tenant_id, entry_date);

CREATE TABLE journal_lines (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    entry_id         BIGINT NOT NULL REFERENCES journal_entries(id) ON DELETE CASCADE,
    account_code     VARCHAR(12) NOT NULL,
    third_party_id   VARCHAR(30),
    third_party_name VARCHAR(160),
    debit            NUMERIC(14,2) NOT NULL DEFAULT 0,
    credit           NUMERIC(14,2) NOT NULL DEFAULT 0,
    description      VARCHAR(200),
    CONSTRAINT ck_journal_line CHECK (debit >= 0 AND credit >= 0 AND (debit = 0 OR credit = 0))
);
CREATE INDEX ix_journal_lines_entry ON journal_lines (entry_id);
CREATE INDEX ix_journal_lines_account ON journal_lines (tenant_id, account_code);

-- Operaciones que no se pudieron contabilizar (falta una cuenta en el mapa): nunca se inventa una cuenta.
CREATE TABLE accounting_pending (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    source_type  VARCHAR(15) NOT NULL,
    source_id    BIGINT NOT NULL,
    entry_date   DATE NOT NULL,
    description  VARCHAR(300) NOT NULL,
    reason       VARCHAR(500) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at  TIMESTAMPTZ
);
CREATE UNIQUE INDEX ux_accounting_pending ON accounting_pending (tenant_id, source_type, source_id) WHERE resolved_at IS NULL;

-- Permisos nuevos para los roles predefinidos que ya existen.
INSERT INTO role_permissions (role_id, permission)
SELECT id, p FROM roles, (VALUES ('CONTABILIDAD_VER'), ('CONTABILIDAD_EDITAR')) AS x(p)
WHERE system_code IN ('ADMINISTRADOR', 'CONTADOR')
ON CONFLICT DO NOTHING;
INSERT INTO role_permissions (role_id, permission)
SELECT id, 'CONTABILIDAD_VER' FROM roles WHERE system_code = 'AUDITOR'
ON CONFLICT DO NOTHING;
