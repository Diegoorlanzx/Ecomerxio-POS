-- Fase 3: verificación y conciliación de pagos digitales, cuentas de cobro con QR, integraciones,
-- fiado (cuentas por cobrar), gastos e ingresos, pagos a proveedores.

ALTER TABLE store_settings
    ADD COLUMN verify_digital_payments BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN credit_enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN default_credit_limit    NUMERIC(14,2) NOT NULL DEFAULT 200000,
    ADD COLUMN default_credit_days     INT NOT NULL DEFAULT 15;

-- Pagos: verificación manual o automática y conciliación con el extracto.
ALTER TABLE payments
    ADD COLUMN verified_by       VARCHAR(120),
    ADD COLUMN verified_at       TIMESTAMPTZ,
    ADD COLUMN verification_note VARCHAR(300),
    ADD COLUMN provider_tx_id    VARCHAR(120),
    ADD COLUMN reconciled_at     TIMESTAMPTZ,
    ADD COLUMN duplicate_ref     BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX ix_payments_status ON payments (tenant_id, status);

CREATE TABLE payment_events (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    payment_id  BIGINT NOT NULL REFERENCES payments(id) ON DELETE CASCADE,
    type        VARCHAR(30) NOT NULL,     -- REGISTRADO, CONFIRMADO, RECHAZADO, REEMBOLSADO, CONCILIADO, WEBHOOK, REFERENCIA_DUPLICADA, IMPORTE_INCONSISTENTE, REEMPLAZADO
    detail      VARCHAR(500),
    user_name   VARCHAR(120),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_payment_events_payment ON payment_events (payment_id, created_at);
CREATE INDEX ix_payment_events_tenant ON payment_events (tenant_id, created_at DESC);

-- Cuentas donde la tienda recibe dinero (Nequi, Daviplata, llave Bre-B, banco) con su QR para mostrar en caja.
CREATE TABLE payment_accounts (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    method       VARCHAR(20) NOT NULL,
    label        VARCHAR(80) NOT NULL,
    holder       VARCHAR(120),
    account_ref  VARCHAR(80),       -- número, celular o llave
    qr_image     TEXT,
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Integración que confirma pagos automáticamente (webhook firmado por el proveedor de pagos).
CREATE TABLE payment_integrations (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    provider     VARCHAR(40) NOT NULL,
    public_key   VARCHAR(40) NOT NULL UNIQUE,
    secret       VARCHAR(200) NOT NULL,
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    last_event_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reconciliations (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    method        VARCHAR(20) NOT NULL,
    file_name     VARCHAR(160),
    date_from     DATE,
    date_to       DATE,
    total_rows    INT NOT NULL DEFAULT 0,
    matched       INT NOT NULL DEFAULT 0,
    inconsistent  INT NOT NULL DEFAULT 0,
    unmatched     INT NOT NULL DEFAULT 0,
    created_by    VARCHAR(120) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reconciliation_rows (
    id                 BIGSERIAL PRIMARY KEY,
    reconciliation_id  BIGINT NOT NULL REFERENCES reconciliations(id) ON DELETE CASCADE,
    row_date           DATE,
    amount             NUMERIC(14,2) NOT NULL,
    reference          VARCHAR(120),
    description        VARCHAR(300),
    status             VARCHAR(20) NOT NULL,   -- CONCILIADO, PROBABLE, INCONSISTENTE, SIN_REGISTRO, DUPLICADO
    payment_id         BIGINT REFERENCES payments(id) ON DELETE SET NULL,
    note               VARCHAR(300)
);
CREATE INDEX ix_recon_rows ON reconciliation_rows (reconciliation_id);

-- Fiado: cupo y plazo por cliente; movimientos de cargo (venta a crédito) y abono.
ALTER TABLE customers
    ADD COLUMN credit_limit    NUMERIC(14,2),
    ADD COLUMN credit_days     INT,
    ADD COLUMN credit_blocked  BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE credit_entries (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    customer_id  BIGINT NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    type         VARCHAR(10) NOT NULL,        -- CARGO, ABONO, AJUSTE
    amount       NUMERIC(14,2) NOT NULL,
    paid_amount  NUMERIC(14,2) NOT NULL DEFAULT 0,   -- solo cargos: cuánto se ha abonado
    sale_id      BIGINT REFERENCES sales(id) ON DELETE SET NULL,
    method       VARCHAR(20),
    reference    VARCHAR(80),
    shift_id     BIGINT REFERENCES shifts(id),
    due_date     DATE,
    note         VARCHAR(300),
    user_name    VARCHAR(120),
    authorized_by VARCHAR(120),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_credit_customer ON credit_entries (customer_id, created_at);
CREATE INDEX ix_credit_tenant ON credit_entries (tenant_id, type);

-- Gastos e ingresos que no son ventas. Los de caja quedan enlazados al movimiento del turno.
CREATE TABLE finance_categories (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    kind        VARCHAR(10) NOT NULL,   -- GASTO, INGRESO
    name        VARCHAR(80) NOT NULL,
    fixed       BOOLEAN NOT NULL DEFAULT FALSE,
    active      BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE UNIQUE INDEX ux_finance_categories ON finance_categories (tenant_id, kind, lower(name));

CREATE TABLE finance_entries (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    kind              VARCHAR(10) NOT NULL,    -- GASTO, INGRESO
    category_id       BIGINT REFERENCES finance_categories(id) ON DELETE SET NULL,
    description       VARCHAR(300) NOT NULL,
    amount            NUMERIC(14,2) NOT NULL,
    entry_date        DATE NOT NULL,
    method            VARCHAR(20) NOT NULL,
    paid_from         VARCHAR(10) NOT NULL,    -- CAJA, BANCO, OTRO
    reference         VARCHAR(80),
    cash_movement_id  BIGINT REFERENCES cash_movements(id) ON DELETE SET NULL,
    created_by        VARCHAR(120),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    voided_at         TIMESTAMPTZ,
    voided_by         VARCHAR(120)
);
CREATE INDEX ix_finance_entries ON finance_entries (tenant_id, kind, entry_date);

-- Pagos a proveedores y a qué recepciones (facturas) se aplicaron.
CREATE TABLE supplier_payments (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    supplier_id  BIGINT NOT NULL REFERENCES suppliers(id),
    amount       NUMERIC(14,2) NOT NULL,
    method       VARCHAR(20) NOT NULL,
    paid_from    VARCHAR(10) NOT NULL,
    reference    VARCHAR(80),
    note         VARCHAR(300),
    paid_on      DATE NOT NULL,
    cash_movement_id BIGINT REFERENCES cash_movements(id) ON DELETE SET NULL,
    user_name    VARCHAR(120),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_supplier_payments ON supplier_payments (supplier_id, paid_on DESC);

CREATE TABLE supplier_payment_allocations (
    id          BIGSERIAL PRIMARY KEY,
    payment_id  BIGINT NOT NULL REFERENCES supplier_payments(id) ON DELETE CASCADE,
    receipt_id  BIGINT NOT NULL REFERENCES goods_receipts(id) ON DELETE CASCADE,
    amount      NUMERIC(14,2) NOT NULL
);
CREATE INDEX ix_sp_alloc_receipt ON supplier_payment_allocations (receipt_id);

-- Categorías iniciales para las tiendas existentes (las nuevas se crean al registrarse).
INSERT INTO finance_categories (tenant_id, kind, name, fixed)
SELECT t.id, c.kind, c.name, c.fixed FROM tenants t CROSS JOIN (VALUES
    ('GASTO', 'Arriendo', TRUE), ('GASTO', 'Servicios públicos', TRUE), ('GASTO', 'Internet y teléfono', TRUE),
    ('GASTO', 'Nómina', TRUE), ('GASTO', 'Transporte', FALSE), ('GASTO', 'Insumos', FALSE),
    ('GASTO', 'Mantenimiento', FALSE), ('GASTO', 'Impuestos', FALSE), ('GASTO', 'Otros gastos', FALSE),
    ('INGRESO', 'Aporte del dueño', FALSE), ('INGRESO', 'Préstamo', FALSE), ('INGRESO', 'Otros ingresos', FALSE)
) AS c(kind, name, fixed);
