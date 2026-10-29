-- Promociones con cupo ("solo las primeras 100") y el tipo COMPRA_LLEVA: compra N de un producto (o categoría) y
-- lleva M de otro producto con descuento (percent = 100 es gratis). Para COMPRA_LLEVA, bundle_qty es cuántos compra.
ALTER TABLE promotions ADD COLUMN reward_product_id BIGINT REFERENCES products(id) ON DELETE CASCADE;
ALTER TABLE promotions ADD COLUMN reward_qty NUMERIC(14,3);
-- Cupo: cuántas veces se puede aplicar (unidades con descuento en PORCENTAJE; paquetes o combos en los demás).
-- NULL = sin límite. used_count lo suman las ventas y lo devuelve una anulación.
ALTER TABLE promotions ADD COLUMN usage_limit NUMERIC(14,3);
ALTER TABLE promotions ADD COLUMN used_count NUMERIC(14,3) NOT NULL DEFAULT 0;

-- Cuánto cupo gastó cada venta, para devolverlo si se anula.
CREATE TABLE promotion_uses (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    promotion_id  BIGINT NOT NULL REFERENCES promotions(id) ON DELETE CASCADE,
    sale_id       BIGINT NOT NULL REFERENCES sales(id) ON DELETE CASCADE,
    uses          NUMERIC(14,3) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_promotion_uses_sale ON promotion_uses (sale_id);
