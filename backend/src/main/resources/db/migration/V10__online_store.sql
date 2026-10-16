-- Fase 9: tienda en línea, catálogo por QR y pedidos que llegan al mismo POS.

-- Vitrina de cada tienda. mode: PEDIDOS (se compra en línea) o CARTA (solo ver productos y precios).
CREATE TABLE online_stores (
    tenant_id        BIGINT PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    enabled          BOOLEAN NOT NULL DEFAULT FALSE,
    slug             VARCHAR(60) NOT NULL,
    mode             VARCHAR(10) NOT NULL DEFAULT 'PEDIDOS',
    welcome          VARCHAR(300),
    whatsapp         VARCHAR(30),
    show_stock       BOOLEAN NOT NULL DEFAULT FALSE,
    delivery_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    pickup_enabled   BOOLEAN NOT NULL DEFAULT TRUE,
    min_order        NUMERIC(14,2) NOT NULL DEFAULT 0,
    -- CONTRA_ENTREGA, TRANSFERENCIA, NEQUI, DAVIPLATA, BREB
    pay_methods      VARCHAR(200) NOT NULL DEFAULT 'CONTRA_ENTREGA,NEQUI,TRANSFERENCIA',
    -- [{"day":1,"open":"07:00","close":"21:00","closed":false}, ...] (1 = lunes). Vacío = siempre abierta.
    schedule         TEXT,
    paused           BOOLEAN NOT NULL DEFAULT FALSE,
    paused_message   VARCHAR(200),
    prep_minutes     INT NOT NULL DEFAULT 30,
    show_badge       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_online_stores_slug ON online_stores (lower(slug));

CREATE TABLE delivery_zones (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name        VARCHAR(80) NOT NULL,
    fee         NUMERIC(14,2) NOT NULL DEFAULT 0,
    min_order   NUMERIC(14,2) NOT NULL DEFAULT 0,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order  INT NOT NULL DEFAULT 0
);
CREATE INDEX ix_delivery_zones ON delivery_zones (tenant_id);

-- Qué se publica y a qué precio (vacío = el mismo de mostrador). "reserved": unidades apartadas por pedidos aceptados.
ALTER TABLE products ADD COLUMN online_visible BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE products ADD COLUMN reserved NUMERIC(14,3) NOT NULL DEFAULT 0;
ALTER TABLE product_presentations ADD COLUMN online_price NUMERIC(14,2);

-- Estados: NUEVO → ACEPTADO → PREPARANDO → LISTO → EN_CAMINO → ENTREGADO; o CANCELADO.
CREATE TABLE online_orders (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    number          BIGINT NOT NULL,
    token           VARCHAR(40) NOT NULL,
    status          VARCHAR(12) NOT NULL DEFAULT 'NUEVO',
    customer_id     BIGINT REFERENCES customers(id) ON DELETE SET NULL,
    customer_name   VARCHAR(120) NOT NULL,
    phone           VARCHAR(30) NOT NULL,
    email           VARCHAR(160),
    fulfillment     VARCHAR(10) NOT NULL,      -- DOMICILIO o RECOGER
    zone_id         BIGINT REFERENCES delivery_zones(id) ON DELETE SET NULL,
    zone_name       VARCHAR(80),
    address         VARCHAR(300),
    notes           VARCHAR(500),
    pay_method      VARCHAR(20) NOT NULL,
    subtotal        NUMERIC(14,2) NOT NULL,
    delivery_fee    NUMERIC(14,2) NOT NULL DEFAULT 0,
    total           NUMERIC(14,2) NOT NULL,
    sale_id         BIGINT REFERENCES sales(id) ON DELETE SET NULL,
    cancel_reason   VARCHAR(300),
    handled_by      VARCHAR(120),
    accepted_at     TIMESTAMPTZ,
    ready_at        TIMESTAMPTZ,
    dispatched_at   TIMESTAMPTZ,
    delivered_at    TIMESTAMPTZ,
    cancelled_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_online_orders_token ON online_orders (token);
CREATE UNIQUE INDEX ux_online_orders_number ON online_orders (tenant_id, number);
CREATE INDEX ix_online_orders_status ON online_orders (tenant_id, status, created_at);

CREATE TABLE online_order_lines (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    order_id          BIGINT NOT NULL REFERENCES online_orders(id) ON DELETE CASCADE,
    product_id        BIGINT NOT NULL REFERENCES products(id),
    presentation_id   BIGINT REFERENCES product_presentations(id) ON DELETE SET NULL,
    product_name      VARCHAR(200) NOT NULL,
    presentation_name VARCHAR(60) NOT NULL,
    factor            NUMERIC(14,3) NOT NULL,
    quantity          NUMERIC(14,3) NOT NULL,
    unit_price        NUMERIC(14,2) NOT NULL,
    line_total        NUMERIC(14,2) NOT NULL,
    sort_order        INT NOT NULL DEFAULT 0
);
CREATE INDEX ix_online_order_lines ON online_order_lines (order_id);

-- Lo apartado por cada pedido aceptado (por producto que lleva stock; un combo aparta sus componentes).
CREATE TABLE online_reservations (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    order_id    BIGINT NOT NULL REFERENCES online_orders(id) ON DELETE CASCADE,
    product_id  BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    quantity    NUMERIC(14,3) NOT NULL
);
CREATE INDEX ix_online_reservations ON online_reservations (order_id);
