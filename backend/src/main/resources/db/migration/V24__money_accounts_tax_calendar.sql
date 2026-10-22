-- Fase 15: bancos y billeteras con saldo, flujo de caja proyectado, calendario tributario y tablero financiero.

-- Una cuenta de dinero (banco, billetera o datáfono) se define por los medios de pago que caen en ella: lo cobrado y lo
-- pagado por esos medios (con "de dónde salió" = BANCO) mueve su saldo. Un medio pertenece a una sola cuenta.
CREATE TABLE money_accounts (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name             VARCHAR(80) NOT NULL,
    kind             VARCHAR(12) NOT NULL,          -- BANCO | BILLETERA | OTRA
    methods          VARCHAR(120),                  -- CSV: TRANSFERENCIA,TARJETA…
    opening_balance  NUMERIC(14,2) NOT NULL DEFAULT 0,
    opening_date     DATE NOT NULL,                 -- desde cuándo se cuentan los movimientos
    active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_money_accounts_name ON money_accounts (tenant_id, lower(name));

-- Movimientos que no salen de ventas ni gastos: consignaciones, retiros, traslados, comisiones y ajustes al extracto.
CREATE TABLE money_account_moves (
    id             BIGSERIAL PRIMARY KEY,
    tenant_id      BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    account_id     BIGINT NOT NULL REFERENCES money_accounts(id) ON DELETE CASCADE,
    to_account_id  BIGINT REFERENCES money_accounts(id) ON DELETE CASCADE,  -- solo en traslados
    type           VARCHAR(15) NOT NULL,   -- CONSIGNACION | RETIRO | TRASLADO | COMISION | AJUSTE
    amount         NUMERIC(14,2) NOT NULL, -- positivo; en AJUSTE puede ser negativo
    move_date      DATE NOT NULL,
    note           VARCHAR(300),
    created_by     VARCHAR(120),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    voided_at      TIMESTAMPTZ,
    voided_by      VARCHAR(120)
);
CREATE INDEX ix_money_moves_account ON money_account_moves (account_id, move_date);
CREATE INDEX ix_money_moves_to ON money_account_moves (to_account_id, move_date) WHERE to_account_id IS NOT NULL;

-- Calendario tributario: solo recordatorios (no liquida impuestos). Las fechas son estimadas hasta que el dueño o el
-- contador ponen la exacta del calendario DIAN / municipal.
CREATE TABLE tax_calendar_settings (
    tenant_id         BIGINT PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    iva_period        VARCHAR(15) NOT NULL DEFAULT 'NINGUNO',   -- NINGUNO | BIMESTRAL | CUATRIMESTRAL
    simple_regime     BOOLEAN NOT NULL DEFAULT FALSE,
    retention_agent   BOOLEAN NOT NULL DEFAULT FALSE,
    ica_period        VARCHAR(15) NOT NULL DEFAULT 'NINGUNO',   -- NINGUNO | BIMESTRAL | ANUAL
    income_tax        VARCHAR(15) NOT NULL DEFAULT 'NINGUNO',   -- NINGUNO | NATURAL | JURIDICA
    mercantile_registry BOOLEAN NOT NULL DEFAULT TRUE,
    reminder_days     INT NOT NULL DEFAULT 7,
    configured        BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tax_calendar_items (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    code         VARCHAR(30) NOT NULL,     -- IVA, RETENCION, SIMPLE_ANTICIPO, SIMPLE_ANUAL, ICA, RENTA, MATRICULA, OTRA
    name         VARCHAR(120) NOT NULL,
    period_key   VARCHAR(20) NOT NULL,     -- 2026-B5, 2026-C2, 2026-10, 2026 … (OTRA: único)
    period_label VARCHAR(60) NOT NULL,
    due_date     DATE NOT NULL,
    estimated    BOOLEAN NOT NULL DEFAULT TRUE,
    done_at      TIMESTAMPTZ,
    done_by      VARCHAR(120),
    note         VARCHAR(300),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_tax_items ON tax_calendar_items (tenant_id, code, period_key);
CREATE INDEX ix_tax_items_due ON tax_calendar_items (tenant_id, due_date);
