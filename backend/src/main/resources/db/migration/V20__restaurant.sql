-- Fase 10 — Restaurante: mesas, meseros, modificadores, comandas a cocina/bar, recetas con merma, dividir cuenta y
-- propina voluntaria. Una mesa ocupada es una cuenta abierta (sales.status = 'ABIERTA') con table_id.

CREATE TABLE dining_areas (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name        VARCHAR(60) NOT NULL,
    sort_order  INT NOT NULL DEFAULT 0,
    active      BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX ix_dining_areas_tenant ON dining_areas (tenant_id);

CREATE TABLE dining_tables (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    area_id     BIGINT REFERENCES dining_areas(id) ON DELETE SET NULL,
    name        VARCHAR(40) NOT NULL,
    seats       INT NOT NULL DEFAULT 4,
    sort_order  INT NOT NULL DEFAULT 0,
    active      BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE UNIQUE INDEX ux_dining_tables_name ON dining_tables (tenant_id, lower(name)) WHERE active;

ALTER TABLE sales ADD COLUMN table_id BIGINT REFERENCES dining_tables(id) ON DELETE SET NULL;
ALTER TABLE sales ADD COLUMN guests INT;
ALTER TABLE sales ADD COLUMN prebill_at TIMESTAMPTZ;
-- Propina voluntaria: aparte del total de la venta (no es ingreso de la tienda: es del personal).
ALTER TABLE sales ADD COLUMN tip_amount NUMERIC(14,2) NOT NULL DEFAULT 0;
ALTER TABLE sales ADD COLUMN tip_method VARCHAR(20);
-- Una mesa puede tener varias cuentas abiertas (cuando se divide la cuenta, cada parte queda en la misma mesa).
CREATE INDEX ix_sales_open_table ON sales (table_id) WHERE status = 'ABIERTA';

-- Modificadores ("sin cebolla", "término medio", "extra queso +$2.000").
CREATE TABLE modifier_groups (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name        VARCHAR(60) NOT NULL,
    min_select  INT NOT NULL DEFAULT 0,
    max_select  INT NOT NULL DEFAULT 1,
    sort_order  INT NOT NULL DEFAULT 0,
    active      BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE modifier_options (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    group_id    BIGINT NOT NULL REFERENCES modifier_groups(id) ON DELETE CASCADE,
    name        VARCHAR(60) NOT NULL,
    price       NUMERIC(14,2) NOT NULL DEFAULT 0,
    sort_order  INT NOT NULL DEFAULT 0,
    active      BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE product_modifier_groups (
    product_id  BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    group_id    BIGINT NOT NULL REFERENCES modifier_groups(id) ON DELETE CASCADE,
    sort_order  INT NOT NULL DEFAULT 0,
    PRIMARY KEY (product_id, group_id)
);

ALTER TABLE sale_lines ADD COLUMN modifiers VARCHAR(500);
ALTER TABLE sale_lines ADD COLUMN kitchen_note VARCHAR(200);
-- Cuánto de la línea ya se mandó a cocina/bar (lo que falte se manda en la siguiente comanda).
ALTER TABLE sale_lines ADD COLUMN sent_qty NUMERIC(14,3) NOT NULL DEFAULT 0;

-- Estación de preparación: por categoría, y el producto puede cambiarla. NULL = no va a cocina.
ALTER TABLE categories ADD COLUMN station VARCHAR(10);
ALTER TABLE products ADD COLUMN station VARCHAR(10);

CREATE TABLE kitchen_tickets (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    sale_id       BIGINT NOT NULL REFERENCES sales(id) ON DELETE CASCADE,
    sale_line_id  BIGINT REFERENCES sale_lines(id) ON DELETE SET NULL,
    comanda       INT NOT NULL,
    station       VARCHAR(10) NOT NULL,
    table_label   VARCHAR(60) NOT NULL,
    waiter_name   VARCHAR(120),
    product_name  VARCHAR(200) NOT NULL,
    quantity      NUMERIC(14,3) NOT NULL,
    modifiers     VARCHAR(500),
    note          VARCHAR(200),
    -- ANULAR: el mesero quitó algo que ya se había pedido (la cocina debe saberlo).
    kind          VARCHAR(10) NOT NULL DEFAULT 'PEDIDO',
    status        VARCHAR(12) NOT NULL DEFAULT 'PENDIENTE',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at    TIMESTAMPTZ,
    ready_at      TIMESTAMPTZ,
    delivered_at  TIMESTAMPTZ
);
CREATE INDEX ix_kitchen_active ON kitchen_tickets (tenant_id, station, status);
CREATE INDEX ix_kitchen_sale ON kitchen_tickets (sale_id);

-- Recetas: merma por ingrediente (lo que se pierde al limpiar o preparar).
ALTER TABLE combo_components ADD COLUMN waste_pct NUMERIC(5,2) NOT NULL DEFAULT 0;

ALTER TABLE store_settings ADD COLUMN tips_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE store_settings ADD COLUMN tip_suggested_pct NUMERIC(5,2) NOT NULL DEFAULT 10;
