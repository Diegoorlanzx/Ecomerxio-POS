-- Remisiones: documento que acompaña la mercancía que se le entrega a un cliente antes de facturarla (obras,
-- negocios que facturan a fin de mes, consignación). No es factura ni consume la numeración de la DIAN.
-- BORRADOR → ENTREGADA (sale del inventario) → FACTURADA (una o varias en una sola venta) | ANULADA.

CREATE TABLE remissions (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    number            BIGINT NOT NULL,
    status            VARCHAR(12) NOT NULL,
    customer_id       BIGINT REFERENCES customers(id) ON DELETE SET NULL,
    -- Copia de los datos del cliente tal como salieron en la remisión.
    customer_name     VARCHAR(160) NOT NULL,
    customer_doc_type VARCHAR(10),
    customer_doc      VARCHAR(30),
    customer_phone    VARCHAR(30),
    delivery_address  VARCHAR(200),
    issue_date        DATE NOT NULL,
    notes             VARCHAR(1000),
    -- Si el documento impreso muestra precios (muchas remisiones viajan solo con cantidades).
    show_prices       BOOLEAN NOT NULL DEFAULT TRUE,
    subtotal          NUMERIC(14,2) NOT NULL DEFAULT 0,
    discount_total    NUMERIC(14,2) NOT NULL DEFAULT 0,
    tax_total         NUMERIC(14,2) NOT NULL DEFAULT 0,
    total             NUMERIC(14,2) NOT NULL DEFAULT 0,
    prices_include_tax BOOLEAN NOT NULL DEFAULT TRUE,
    quote_id          BIGINT REFERENCES quotes(id) ON DELETE SET NULL,   -- la cotización de la que salió
    sale_id           BIGINT REFERENCES sales(id) ON DELETE SET NULL,    -- la venta que la facturó
    created_by        VARCHAR(120),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at      TIMESTAMPTZ,
    delivered_by      VARCHAR(120),
    -- Quién recibió la mercancía donde el cliente (lo que se firma en el papel).
    received_by       VARCHAR(160),
    received_doc      VARCHAR(30),
    invoiced_at       TIMESTAMPTZ,
    cancelled_at      TIMESTAMPTZ,
    cancel_reason     VARCHAR(300),
    version           INT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, number)
);
CREATE INDEX ix_remissions_tenant ON remissions (tenant_id, created_at DESC);
CREATE INDEX ix_remissions_customer ON remissions (tenant_id, customer_id, status);
CREATE INDEX ix_remissions_sale ON remissions (sale_id);

CREATE TABLE remission_lines (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    remission_id    BIGINT NOT NULL REFERENCES remissions(id) ON DELETE CASCADE,
    product_id      BIGINT REFERENCES products(id) ON DELETE SET NULL,   -- null = línea libre (servicio, flete)
    presentation_id BIGINT REFERENCES product_presentations(id) ON DELETE SET NULL,
    description     VARCHAR(200) NOT NULL,
    presentation_name VARCHAR(60),
    factor          NUMERIC(14,3) NOT NULL DEFAULT 1,
    quantity        NUMERIC(14,3) NOT NULL,
    -- Lo que el cliente devolvió después de entregada (vuelve al inventario y no se factura).
    returned_qty    NUMERIC(14,3) NOT NULL DEFAULT 0,
    unit_price      NUMERIC(14,2) NOT NULL,
    discount_pct    NUMERIC(5,2) NOT NULL DEFAULT 0,
    tax_name        VARCHAR(20),
    tax_rate        NUMERIC(5,2) NOT NULL DEFAULT 0,
    fixed_tax       NUMERIC(14,2) NOT NULL DEFAULT 0,
    gross           NUMERIC(14,2) NOT NULL DEFAULT 0,
    discount        NUMERIC(14,2) NOT NULL DEFAULT 0,
    tax             NUMERIC(14,2) NOT NULL DEFAULT 0,
    line_total      NUMERIC(14,2) NOT NULL DEFAULT 0,
    sort_order      INT NOT NULL DEFAULT 0
);
CREATE INDEX ix_remission_lines_remission ON remission_lines (remission_id, sort_order);

-- La venta que factura remisiones no descuenta inventario (ya salió al entregar) ni lo devuelve si se anula.
ALTER TABLE sales ADD COLUMN remission_billed BOOLEAN NOT NULL DEFAULT FALSE;

-- Permisos nuevos para los roles predefinidos que ya existen (las tiendas nuevas los reciben al crearse).
INSERT INTO role_permissions (role_id, permission)
SELECT id, p FROM roles, (VALUES ('REMISIONES_VER'), ('REMISIONES_CREAR')) AS x(p)
WHERE system_code IN ('DUENO', 'ADMINISTRADOR', 'SUPERVISOR', 'CAJERO')
ON CONFLICT DO NOTHING;
INSERT INTO role_permissions (role_id, permission)
SELECT id, 'REMISIONES_VER' FROM roles WHERE system_code IN ('CONTADOR', 'AUDITOR')
ON CONFLICT DO NOTHING;
