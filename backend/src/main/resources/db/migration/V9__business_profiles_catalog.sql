-- Fase 8: perfiles de negocio, módulos activables y catálogo avanzado (variantes, combos, seriales y básculas).

-- Perfil del negocio y módulos que usa. FIADO sigue en credit_enabled; el resto va en la lista "modules".
ALTER TABLE store_settings ADD COLUMN business_type VARCHAR(30) NOT NULL DEFAULT 'TIENDA';
ALTER TABLE store_settings ADD COLUMN modules VARCHAR(300) NOT NULL DEFAULT 'LOTES,CUENTAS_ABIERTAS,COMPRAS';
-- Etiquetas de balanza (EAN-13 que empieza por 2): prefijos aceptados, si el código trae peso o precio y cuántos
-- dígitos usa el PLU (el valor ocupa el resto: 10 - dígitos del PLU).
ALTER TABLE store_settings ADD COLUMN scale_prefixes VARCHAR(60) NOT NULL DEFAULT '20,21,22,23,24,25,26,27,28,29';
ALTER TABLE store_settings ADD COLUMN scale_mode VARCHAR(10) NOT NULL DEFAULT 'PESO';
ALTER TABLE store_settings ADD COLUMN scale_plu_digits INT NOT NULL DEFAULT 5;

-- Tipo de producto: SIMPLE (como hasta ahora), PADRE (agrupa variantes, no se vende ni tiene stock propio),
-- VARIANTE (hija de un padre: su propio SKU, código, precio, costo y stock) y COMBO (se vende y descuenta sus componentes).
ALTER TABLE products ADD COLUMN product_type VARCHAR(10) NOT NULL DEFAULT 'SIMPLE';
ALTER TABLE products ADD COLUMN parent_id BIGINT REFERENCES products(id) ON DELETE CASCADE;
-- "M / Rojo" y su detalle {"Talla":"M","Color":"Rojo"}.
ALTER TABLE products ADD COLUMN variant_label VARCHAR(120);
ALTER TABLE products ADD COLUMN variant_values TEXT;
-- En el padre: las opciones y sus valores, [{"name":"Talla","values":["S","M","L"]}, ...].
ALTER TABLE products ADD COLUMN variant_options TEXT;
ALTER TABLE products ADD COLUMN track_serials BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE products ADD COLUMN warranty_months INT NOT NULL DEFAULT 0;
-- Código del producto en la balanza (lo que va dentro de la etiqueta).
ALTER TABLE products ADD COLUMN plu VARCHAR(6);
CREATE INDEX ix_products_parent ON products (parent_id);
CREATE UNIQUE INDEX ux_products_plu ON products (tenant_id, plu) WHERE plu IS NOT NULL;

-- Qué lleva un combo (cantidades en unidades base del componente).
CREATE TABLE combo_components (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    combo_id      BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    component_id  BIGINT NOT NULL REFERENCES products(id),
    quantity      NUMERIC(14,3) NOT NULL,
    sort_order    INT NOT NULL DEFAULT 0
);
CREATE INDEX ix_combo_components ON combo_components (combo_id);

-- Lo que realmente salió del inventario al vender un combo (por unidad de la línea), para la demanda y las devoluciones.
CREATE TABLE sale_line_components (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    sale_line_id  BIGINT NOT NULL REFERENCES sale_lines(id) ON DELETE CASCADE,
    product_id    BIGINT NOT NULL REFERENCES products(id),
    product_name  VARCHAR(200) NOT NULL,
    quantity      NUMERIC(14,3) NOT NULL,
    unit_cost     NUMERIC(14,4) NOT NULL DEFAULT 0
);
CREATE INDEX ix_sale_line_components ON sale_line_components (sale_line_id);
CREATE INDEX ix_sale_line_components_product ON sale_line_components (tenant_id, product_id);

-- Seriales elegidos en una línea (cuentas abiertas y ventas en espera los guardan antes de cobrar).
ALTER TABLE sale_lines ADD COLUMN serials TEXT;
ALTER TABLE sale_return_lines ADD COLUMN serials TEXT;
ALTER TABLE goods_receipt_lines ADD COLUMN serials TEXT;

-- Cada unidad con serial / IMEI: de dónde entró, a quién se vendió y hasta cuándo tiene garantía.
CREATE TABLE product_serials (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    product_id      BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    serial          VARCHAR(80) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'EN_STOCK',  -- EN_STOCK, VENDIDO, DEFECTUOSO
    unit_cost       NUMERIC(14,4) NOT NULL DEFAULT 0,
    receipt_id      BIGINT REFERENCES goods_receipts(id) ON DELETE SET NULL,
    sale_id         BIGINT REFERENCES sales(id) ON DELETE SET NULL,
    sale_line_id    BIGINT REFERENCES sale_lines(id) ON DELETE SET NULL,
    customer_id     BIGINT REFERENCES customers(id) ON DELETE SET NULL,
    customer_name   VARCHAR(160),
    sold_at         TIMESTAMPTZ,
    warranty_until  DATE,
    notes           VARCHAR(500),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_product_serials ON product_serials (tenant_id, product_id, lower(serial));
CREATE INDEX ix_product_serials_search ON product_serials (tenant_id, lower(serial));
CREATE INDEX ix_product_serials_sale ON product_serials (sale_id);
