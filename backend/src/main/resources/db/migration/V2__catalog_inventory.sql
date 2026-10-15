-- Fase 1: catálogo (categorías, productos, presentaciones), inventario por movimientos y ajustes de la tienda.

-- Consecutivos por tienda (ventas, turnos, devoluciones...). Se toman con SELECT ... FOR UPDATE.
CREATE TABLE tenant_counters (
    tenant_id  BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name       VARCHAR(30) NOT NULL,
    value      BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, name)
);

-- Ajustes del punto de venta (uno por tienda).
CREATE TABLE store_settings (
    tenant_id               BIGINT PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    prices_include_tax      BOOLEAN       NOT NULL DEFAULT TRUE,
    max_discount_pct        NUMERIC(5,2)  NOT NULL DEFAULT 10,
    allow_negative_stock    BOOLEAN       NOT NULL DEFAULT FALSE,
    require_open_shift      BOOLEAN       NOT NULL DEFAULT TRUE,
    open_accounts_limit     INT           NOT NULL DEFAULT 20,
    cost_method             VARCHAR(20)   NOT NULL DEFAULT 'PROMEDIO',
    payment_methods         VARCHAR(200)  NOT NULL DEFAULT 'EFECTIVO,TRANSFERENCIA,NEQUI,DAVIPLATA,BREB,TARJETA',
    receipt_header          VARCHAR(500),
    receipt_footer          VARCHAR(500)  DEFAULT '¡Gracias por su compra!',
    receipt_width_mm        INT           NOT NULL DEFAULT 80,
    receipt_show_tax        BOOLEAN       NOT NULL DEFAULT TRUE,
    default_tax_name        VARCHAR(30)   NOT NULL DEFAULT 'IVA',
    default_tax_rate        NUMERIC(5,2)  NOT NULL DEFAULT 0,
    cash_denominations      VARCHAR(200)  NOT NULL DEFAULT '100000,50000,20000,10000,5000,2000,1000,500,200,100,50',
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now()
);
INSERT INTO store_settings (tenant_id) SELECT id FROM tenants;

CREATE TABLE categories (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    parent_id   BIGINT REFERENCES categories(id) ON DELETE SET NULL,
    name        VARCHAR(80) NOT NULL,
    icon        VARCHAR(40),
    sort_order  INT NOT NULL DEFAULT 0,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_categories_name ON categories (tenant_id, coalesce(parent_id, 0), lower(name));

CREATE TABLE products (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    category_id     BIGINT REFERENCES categories(id) ON DELETE SET NULL,
    sku             VARCHAR(40) NOT NULL,
    name            VARCHAR(160) NOT NULL,
    description     VARCHAR(1000),
    brand           VARCHAR(80),
    photo           TEXT,
    unit            VARCHAR(20) NOT NULL DEFAULT 'UNIDAD',
    track_stock     BOOLEAN NOT NULL DEFAULT TRUE,
    -- Existencias y costo SIEMPRE en la unidad base (presentación con factor 1).
    stock           NUMERIC(14,3) NOT NULL DEFAULT 0,
    cost            NUMERIC(14,4) NOT NULL DEFAULT 0,
    last_cost       NUMERIC(14,4) NOT NULL DEFAULT 0,
    stock_min       NUMERIC(14,3) NOT NULL DEFAULT 0,
    stock_max       NUMERIC(14,3) NOT NULL DEFAULT 0,
    safety_stock    NUMERIC(14,3) NOT NULL DEFAULT 0,
    tax_name        VARCHAR(30) NOT NULL DEFAULT 'IVA',
    tax_rate        NUMERIC(5,2) NOT NULL DEFAULT 0,
    -- Impuesto fijo por unidad base (p. ej. impuesto al consumo de bolsas).
    fixed_tax       NUMERIC(12,2) NOT NULL DEFAULT 0,
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    favorite        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_products_sku ON products (tenant_id, lower(sku));
CREATE INDEX ix_products_tenant_name ON products (tenant_id, lower(name));
CREATE INDEX ix_products_category ON products (tenant_id, category_id);

-- Formas de vender un producto: unidad, six pack, caja x24... factor = unidades base que contiene.
CREATE TABLE product_presentations (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    product_id  BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    name        VARCHAR(60) NOT NULL,
    factor      NUMERIC(14,3) NOT NULL DEFAULT 1,
    barcode     VARCHAR(60),
    price       NUMERIC(14,2) NOT NULL,
    min_price   NUMERIC(14,2) NOT NULL DEFAULT 0,
    is_base     BOOLEAN NOT NULL DEFAULT FALSE,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order  INT NOT NULL DEFAULT 0
);
CREATE INDEX ix_presentations_product ON product_presentations (product_id);
CREATE UNIQUE INDEX ux_presentations_barcode ON product_presentations (tenant_id, barcode) WHERE barcode IS NOT NULL;

CREATE TABLE price_history (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    product_id       BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    presentation_id  BIGINT REFERENCES product_presentations(id) ON DELETE SET NULL,
    field            VARCHAR(20) NOT NULL,   -- PRECIO, PRECIO_MINIMO, COSTO
    old_value        NUMERIC(14,4),
    new_value        NUMERIC(14,4),
    reason           VARCHAR(200),
    user_id          BIGINT,
    user_name        VARCHAR(120),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_price_history_product ON price_history (product_id, created_at DESC);

-- Todo cambio de existencias es un movimiento: el stock del producto es su suma.
CREATE TABLE stock_movements (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    product_id      BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    type            VARCHAR(30) NOT NULL,
    quantity        NUMERIC(14,3) NOT NULL,
    stock_after     NUMERIC(14,3) NOT NULL,
    unit_cost       NUMERIC(14,4),
    reference_type  VARCHAR(30),
    reference_id    BIGINT,
    reason          VARCHAR(300),
    user_id         BIGINT,
    user_name       VARCHAR(120),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_stock_mov_product ON stock_movements (product_id, created_at DESC);
CREATE INDEX ix_stock_mov_tenant ON stock_movements (tenant_id, created_at DESC);

CREATE TABLE customers (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name        VARCHAR(120) NOT NULL,
    doc_type    VARCHAR(10),
    doc_number  VARCHAR(30),
    phone       VARCHAR(30),
    email       VARCHAR(160),
    address     VARCHAR(200),
    notes       VARCHAR(500),
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_customers_tenant ON customers (tenant_id, lower(name));
CREATE UNIQUE INDEX ux_customers_doc ON customers (tenant_id, doc_number) WHERE doc_number IS NOT NULL;

CREATE TABLE promotions (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name          VARCHAR(100) NOT NULL,
    -- PORCENTAJE: % de descuento; PRECIO_POR_CANTIDAD: N unidades por $X; LLEVA_PAGA: lleva N, paga M.
    type          VARCHAR(30) NOT NULL,
    product_id    BIGINT REFERENCES products(id) ON DELETE CASCADE,
    category_id   BIGINT REFERENCES categories(id) ON DELETE CASCADE,
    percent       NUMERIC(5,2),
    min_qty       NUMERIC(14,3),
    bundle_qty    NUMERIC(14,3),
    bundle_price  NUMERIC(14,2),
    pay_qty       NUMERIC(14,3),
    starts_on     DATE,
    ends_on       DATE,
    active        BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_promotions_tenant ON promotions (tenant_id, active);
