-- Fase 1: cajas, turnos, movimientos de caja, ventas (incluye cuentas abiertas y ventas en espera),
-- pagos y devoluciones. PIN de supervisor para autorizar en el mostrador.

ALTER TABLE users ADD COLUMN pin_hash VARCHAR(100);

CREATE TABLE registers (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name        VARCHAR(60) NOT NULL,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_registers_name ON registers (tenant_id, lower(name));
INSERT INTO registers (tenant_id, name) SELECT id, 'Caja 1' FROM tenants;

CREATE TABLE shifts (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    register_id     BIGINT NOT NULL REFERENCES registers(id),
    number          BIGINT NOT NULL,
    user_id         BIGINT NOT NULL REFERENCES users(id),
    user_name       VARCHAR(120) NOT NULL,
    status          VARCHAR(10) NOT NULL DEFAULT 'OPEN',
    opening_cash    NUMERIC(14,2) NOT NULL,
    opened_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at       TIMESTAMPTZ,
    closed_by_id    BIGINT,
    closed_by_name  VARCHAR(120),
    expected_cash   NUMERIC(14,2),
    counted_cash    NUMERIC(14,2),
    difference      NUMERIC(14,2),
    count_detail    VARCHAR(1000),
    close_notes     VARCHAR(500),
    difference_authorized_by VARCHAR(120)
);
CREATE UNIQUE INDEX ux_shifts_number ON shifts (tenant_id, number);
-- Una caja solo puede tener un turno abierto a la vez, y un empleado solo uno.
CREATE UNIQUE INDEX ux_shifts_open_register ON shifts (register_id) WHERE status = 'OPEN';
CREATE UNIQUE INDEX ux_shifts_open_user ON shifts (tenant_id, user_id) WHERE status = 'OPEN';
CREATE INDEX ix_shifts_tenant_time ON shifts (tenant_id, opened_at DESC);

CREATE TABLE cash_movements (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    shift_id        BIGINT NOT NULL REFERENCES shifts(id) ON DELETE CASCADE,
    type            VARCHAR(20) NOT NULL,     -- INGRESO, RETIRO, GASTO, AJUSTE_ENTRADA, AJUSTE_SALIDA
    amount          NUMERIC(14,2) NOT NULL,
    reason          VARCHAR(300) NOT NULL,
    category        VARCHAR(60),
    user_id         BIGINT,
    user_name       VARCHAR(120),
    authorized_by   VARCHAR(120),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_cash_mov_shift ON cash_movements (shift_id);

CREATE TABLE sales (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    number              BIGINT,                         -- consecutivo; se asigna al cobrar
    -- ABIERTA (cuenta abierta), EN_ESPERA (venta guardada), PAGADA, ANULADA
    status              VARCHAR(12) NOT NULL,
    account_label       VARCHAR(60),
    shift_id            BIGINT REFERENCES shifts(id),
    register_id         BIGINT REFERENCES registers(id),
    user_id             BIGINT NOT NULL,
    user_name           VARCHAR(120) NOT NULL,
    customer_id         BIGINT REFERENCES customers(id) ON DELETE SET NULL,
    customer_name       VARCHAR(120),
    customer_doc        VARCHAR(30),
    subtotal            NUMERIC(14,2) NOT NULL DEFAULT 0,
    discount_total      NUMERIC(14,2) NOT NULL DEFAULT 0,
    tax_total           NUMERIC(14,2) NOT NULL DEFAULT 0,
    total               NUMERIC(14,2) NOT NULL DEFAULT 0,
    cost_total          NUMERIC(14,2) NOT NULL DEFAULT 0,
    paid_total          NUMERIC(14,2) NOT NULL DEFAULT 0,
    change_amount       NUMERIC(14,2) NOT NULL DEFAULT 0,
    refunded_total      NUMERIC(14,2) NOT NULL DEFAULT 0,
    sale_discount_pct   NUMERIC(5,2),
    sale_discount_amount NUMERIC(14,2),
    discount_authorized_by VARCHAR(120),
    notes               VARCHAR(500),
    client_ref          VARCHAR(64),
    offline             BOOLEAN NOT NULL DEFAULT FALSE,
    review_flags        VARCHAR(300),
    reviewed_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ,
    voided_at           TIMESTAMPTZ,
    voided_by           VARCHAR(120),
    void_reason         VARCHAR(300),
    void_authorized_by  VARCHAR(120),
    version             INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_sales_number ON sales (tenant_id, number) WHERE number IS NOT NULL;
CREATE UNIQUE INDEX ux_sales_client_ref ON sales (tenant_id, client_ref) WHERE client_ref IS NOT NULL;
CREATE INDEX ix_sales_tenant_status ON sales (tenant_id, status);
CREATE INDEX ix_sales_tenant_completed ON sales (tenant_id, completed_at DESC);
CREATE INDEX ix_sales_shift ON sales (shift_id);

CREATE TABLE sale_lines (
    id                  BIGSERIAL PRIMARY KEY,
    sale_id             BIGINT NOT NULL REFERENCES sales(id) ON DELETE CASCADE,
    tenant_id           BIGINT NOT NULL,
    product_id          BIGINT NOT NULL REFERENCES products(id),
    presentation_id     BIGINT REFERENCES product_presentations(id) ON DELETE SET NULL,
    product_name        VARCHAR(160) NOT NULL,
    presentation_name   VARCHAR(60) NOT NULL,
    factor              NUMERIC(14,3) NOT NULL,
    quantity            NUMERIC(14,3) NOT NULL,
    list_price          NUMERIC(14,2) NOT NULL,
    unit_price          NUMERIC(14,2) NOT NULL,
    price_authorized_by VARCHAR(120),
    discount_pct        NUMERIC(5,2) NOT NULL DEFAULT 0,
    promo_id            BIGINT,
    promo_name          VARCHAR(100),
    gross               NUMERIC(14,2) NOT NULL DEFAULT 0,
    discount            NUMERIC(14,2) NOT NULL DEFAULT 0,
    tax_name            VARCHAR(30),
    tax_rate            NUMERIC(5,2) NOT NULL DEFAULT 0,
    fixed_tax           NUMERIC(12,2) NOT NULL DEFAULT 0,
    tax                 NUMERIC(14,2) NOT NULL DEFAULT 0,
    line_total          NUMERIC(14,2) NOT NULL DEFAULT 0,
    unit_cost           NUMERIC(14,4) NOT NULL DEFAULT 0,
    returned_qty        NUMERIC(14,3) NOT NULL DEFAULT 0,
    added_by            VARCHAR(120),
    sort_order          INT NOT NULL DEFAULT 0
);
CREATE INDEX ix_sale_lines_sale ON sale_lines (sale_id);
CREATE INDEX ix_sale_lines_product ON sale_lines (tenant_id, product_id);

CREATE TABLE payments (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    sale_id      BIGINT NOT NULL REFERENCES sales(id) ON DELETE CASCADE,
    shift_id     BIGINT REFERENCES shifts(id),
    method       VARCHAR(20) NOT NULL,
    amount       NUMERIC(14,2) NOT NULL,      -- lo que se abona a la venta
    received     NUMERIC(14,2),               -- efectivo entregado por el cliente
    change_given NUMERIC(14,2) NOT NULL DEFAULT 0,
    reference    VARCHAR(80),
    status       VARCHAR(20) NOT NULL DEFAULT 'CONFIRMADO',
    user_id      BIGINT,
    user_name    VARCHAR(120),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_payments_sale ON payments (sale_id);
CREATE INDEX ix_payments_shift ON payments (shift_id);
CREATE INDEX ix_payments_tenant_ref ON payments (tenant_id, reference) WHERE reference IS NOT NULL;

CREATE TABLE sale_returns (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    sale_id         BIGINT NOT NULL REFERENCES sales(id) ON DELETE CASCADE,
    number          BIGINT NOT NULL,
    shift_id        BIGINT REFERENCES shifts(id),
    reason          VARCHAR(300) NOT NULL,
    refund_method   VARCHAR(20) NOT NULL,
    refund_amount   NUMERIC(14,2) NOT NULL,
    user_id         BIGINT,
    user_name       VARCHAR(120),
    authorized_by   VARCHAR(120),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_returns_sale ON sale_returns (sale_id);

CREATE TABLE sale_return_lines (
    id            BIGSERIAL PRIMARY KEY,
    return_id     BIGINT NOT NULL REFERENCES sale_returns(id) ON DELETE CASCADE,
    sale_line_id  BIGINT NOT NULL REFERENCES sale_lines(id),
    quantity      NUMERIC(14,3) NOT NULL,
    amount        NUMERIC(14,2) NOT NULL,
    restock       BOOLEAN NOT NULL,
    condition     VARCHAR(20) NOT NULL DEFAULT 'BUENO'
);
