-- Fase 2: proveedores, compras y reposición, recepción de mercancía, lotes/vencimientos,
-- conteos físicos y parámetros de inventario.

ALTER TABLE store_settings
    ADD COLUMN analysis_days        INT           NOT NULL DEFAULT 30,
    ADD COLUMN no_rotation_days     INT           NOT NULL DEFAULT 30,
    ADD COLUMN excess_days          INT           NOT NULL DEFAULT 90,
    ADD COLUMN expiry_alert_days    INT           NOT NULL DEFAULT 15,
    ADD COLUMN default_lead_days    INT           NOT NULL DEFAULT 3,
    ADD COLUMN review_days          INT           NOT NULL DEFAULT 7,
    ADD COLUMN unusual_diff_pct     NUMERIC(5,2)  NOT NULL DEFAULT 10,
    ADD COLUMN unusual_diff_value   NUMERIC(14,2) NOT NULL DEFAULT 50000,
    ADD COLUMN abc_a_pct            NUMERIC(5,2)  NOT NULL DEFAULT 80,
    ADD COLUMN abc_b_pct            NUMERIC(5,2)  NOT NULL DEFAULT 95,
    ADD COLUMN purchase_budget      NUMERIC(14,2) NOT NULL DEFAULT 0;

ALTER TABLE products ADD COLUMN track_lots BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE suppliers (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name            VARCHAR(120) NOT NULL,
    legal_name      VARCHAR(160),
    tax_id          VARCHAR(30),
    contact_name    VARCHAR(120),
    phone           VARCHAR(30),
    whatsapp        VARCHAR(30),
    email           VARCHAR(160),
    address         VARCHAR(200),
    city            VARCHAR(80),
    payment_terms   VARCHAR(200),
    credit_days     INT NOT NULL DEFAULT 0,
    order_days      VARCHAR(40),          -- p. ej. "LUN,JUE"
    delivery_days   VARCHAR(40),
    lead_time_days  INT,                  -- configurado; si es nulo se usa el medido o el de la tienda
    notes           VARCHAR(500),
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_suppliers_name ON suppliers (tenant_id, lower(name));

-- Lo que vende cada proveedor: presentación de compra, costo, mínimos y múltiplos.
CREATE TABLE supplier_products (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    supplier_id      BIGINT NOT NULL REFERENCES suppliers(id) ON DELETE CASCADE,
    product_id       BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    pack_name        VARCHAR(60) NOT NULL DEFAULT 'Unidad',
    pack_factor      NUMERIC(14,3) NOT NULL DEFAULT 1,
    cost_per_pack    NUMERIC(14,2) NOT NULL DEFAULT 0,
    min_packs        NUMERIC(14,3) NOT NULL DEFAULT 1,
    multiple_packs   NUMERIC(14,3) NOT NULL DEFAULT 1,
    supplier_sku     VARCHAR(60),
    is_primary       BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_supplier_products ON supplier_products (supplier_id, product_id);
CREATE INDEX ix_supplier_products_product ON supplier_products (product_id);
CREATE UNIQUE INDEX ux_supplier_products_primary ON supplier_products (product_id) WHERE is_primary;

CREATE TABLE supplier_price_history (
    id                   BIGSERIAL PRIMARY KEY,
    tenant_id            BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    supplier_id          BIGINT NOT NULL REFERENCES suppliers(id) ON DELETE CASCADE,
    product_id           BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    old_cost_per_unit    NUMERIC(14,4),
    new_cost_per_unit    NUMERIC(14,4) NOT NULL,
    source               VARCHAR(20) NOT NULL,       -- MANUAL, COMPRA
    reference_id         BIGINT,
    user_name            VARCHAR(120),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_supplier_price_hist ON supplier_price_history (supplier_id, product_id, created_at DESC);

CREATE TABLE purchase_orders (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    number           BIGINT NOT NULL,
    supplier_id      BIGINT NOT NULL REFERENCES suppliers(id),
    -- BORRADOR, POR_APROBAR, APROBADA (lista para enviar/enviada), PARCIAL, RECIBIDA, CANCELADA
    status           VARCHAR(15) NOT NULL DEFAULT 'BORRADOR',
    source           VARCHAR(15) NOT NULL DEFAULT 'MANUAL',
    expected_date    DATE,
    notes            VARCHAR(500),
    estimated_total  NUMERIC(14,2) NOT NULL DEFAULT 0,
    received_total   NUMERIC(14,2) NOT NULL DEFAULT 0,
    created_by       VARCHAR(120) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    submitted_at     TIMESTAMPTZ,
    approved_by      VARCHAR(120),
    approved_at      TIMESTAMPTZ,
    sent_at          TIMESTAMPTZ,
    sent_via         VARCHAR(20),
    received_at      TIMESTAMPTZ,
    cancelled_at     TIMESTAMPTZ,
    cancel_reason    VARCHAR(300)
);
CREATE UNIQUE INDEX ux_purchase_orders_number ON purchase_orders (tenant_id, number);
CREATE INDEX ix_purchase_orders_status ON purchase_orders (tenant_id, status);

CREATE TABLE purchase_order_lines (
    id               BIGSERIAL PRIMARY KEY,
    order_id         BIGINT NOT NULL REFERENCES purchase_orders(id) ON DELETE CASCADE,
    product_id       BIGINT NOT NULL REFERENCES products(id),
    pack_name        VARCHAR(60) NOT NULL,
    pack_factor      NUMERIC(14,3) NOT NULL,
    packs_ordered    NUMERIC(14,3) NOT NULL,
    cost_per_pack    NUMERIC(14,2) NOT NULL,
    units_received   NUMERIC(14,3) NOT NULL DEFAULT 0,
    units_rejected   NUMERIC(14,3) NOT NULL DEFAULT 0,
    reason           VARCHAR(300)
);
CREATE INDEX ix_po_lines_order ON purchase_order_lines (order_id);

-- Recepción de mercancía (con o sin orden). Su total es lo que se le debe al proveedor.
CREATE TABLE goods_receipts (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    number          BIGINT NOT NULL,
    supplier_id     BIGINT NOT NULL REFERENCES suppliers(id),
    order_id        BIGINT REFERENCES purchase_orders(id),
    invoice_number  VARCHAR(60),
    total           NUMERIC(14,2) NOT NULL DEFAULT 0,
    paid_amount     NUMERIC(14,2) NOT NULL DEFAULT 0,
    due_date        DATE,
    on_time         BOOLEAN,
    notes           VARCHAR(500),
    received_by     VARCHAR(120) NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_goods_receipts_number ON goods_receipts (tenant_id, number);
CREATE INDEX ix_goods_receipts_supplier ON goods_receipts (supplier_id, received_at DESC);

CREATE TABLE goods_receipt_lines (
    id              BIGSERIAL PRIMARY KEY,
    receipt_id      BIGINT NOT NULL REFERENCES goods_receipts(id) ON DELETE CASCADE,
    order_line_id   BIGINT REFERENCES purchase_order_lines(id),
    product_id      BIGINT NOT NULL REFERENCES products(id),
    units_received  NUMERIC(14,3) NOT NULL,
    units_rejected  NUMERIC(14,3) NOT NULL DEFAULT 0,
    reject_reason   VARCHAR(20),            -- FALTANTE, INCORRECTO, DANADO
    unit_cost       NUMERIC(14,4) NOT NULL,
    lot_code        VARCHAR(60),
    expiry_date     DATE
);
CREATE INDEX ix_receipt_lines_receipt ON goods_receipt_lines (receipt_id);

-- Devoluciones de mercancía al proveedor (salen del inventario).
CREATE TABLE supplier_returns (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    supplier_id  BIGINT NOT NULL REFERENCES suppliers(id),
    product_id   BIGINT NOT NULL REFERENCES products(id),
    quantity     NUMERIC(14,3) NOT NULL,
    unit_cost    NUMERIC(14,4) NOT NULL,
    reason       VARCHAR(300) NOT NULL,
    user_name    VARCHAR(120),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Lotes con fecha de vencimiento (solo productos que los controlan). Se consumen primero los que vencen antes.
CREATE TABLE product_lots (
    id             BIGSERIAL PRIMARY KEY,
    tenant_id      BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    product_id     BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    lot_code       VARCHAR(60),
    expiry_date    DATE,
    quantity       NUMERIC(14,3) NOT NULL,
    remaining      NUMERIC(14,3) NOT NULL,
    receipt_id     BIGINT REFERENCES goods_receipts(id) ON DELETE SET NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_lots_product ON product_lots (product_id, expiry_date);

CREATE TABLE inventory_counts (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    number       BIGINT NOT NULL,
    name         VARCHAR(120) NOT NULL,
    status       VARCHAR(12) NOT NULL DEFAULT 'ABIERTO',   -- ABIERTO, CERRADO, ANULADO
    blind        BOOLEAN NOT NULL DEFAULT TRUE,
    category_id  BIGINT REFERENCES categories(id) ON DELETE SET NULL,
    created_by   VARCHAR(120) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_by    VARCHAR(120),
    closed_at    TIMESTAMPTZ,
    notes        VARCHAR(500)
);
CREATE UNIQUE INDEX ux_counts_number ON inventory_counts (tenant_id, number);

CREATE TABLE inventory_count_lines (
    id            BIGSERIAL PRIMARY KEY,
    count_id      BIGINT NOT NULL REFERENCES inventory_counts(id) ON DELETE CASCADE,
    product_id    BIGINT NOT NULL REFERENCES products(id),
    theoretical   NUMERIC(14,3) NOT NULL,
    counted       NUMERIC(14,3),
    unit_cost     NUMERIC(14,4) NOT NULL DEFAULT 0,
    counted_by    VARCHAR(120),
    counted_at    TIMESTAMPTZ,
    unusual       BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX ux_count_lines ON inventory_count_lines (count_id, product_id);
