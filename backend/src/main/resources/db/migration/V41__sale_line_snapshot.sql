-- Analítica con historia estable: la categoría y la marca quedan congeladas en cada línea de venta, igual que el
-- nombre y el costo. Si mañana el producto cambia de categoría, sus ventas pasadas se quedan donde estaban.
-- Sin llave foránea a propósito: es una foto, no una relación viva.
ALTER TABLE sale_lines ADD COLUMN category_id BIGINT;
ALTER TABLE sale_lines ADD COLUMN brand VARCHAR(80);

-- Las ventas que ya existen se etiquetan con lo que el producto tiene hoy (no hay forma de saber lo de antes).
-- Una variante sin categoría o marca propia toma la de su producto padre.
UPDATE sale_lines l
SET category_id = coalesce(p.category_id, pp.category_id),
    brand       = coalesce(nullif(trim(p.brand), ''), nullif(trim(pp.brand), ''))
FROM products p
LEFT JOIN products pp ON pp.id = p.parent_id
WHERE p.id = l.product_id;

-- Cualquier camino que cree líneas (POS, cotización, remisión, mesa, tienda en línea, restauración de copias
-- antiguas) queda cubierto: si la línea llega sin la foto, se toma del producto en ese momento.
CREATE OR REPLACE FUNCTION sale_lines_snapshot() RETURNS trigger AS $$
BEGIN
    IF NEW.category_id IS NULL OR NEW.brand IS NULL THEN
        SELECT coalesce(NEW.category_id, p.category_id, pp.category_id),
               coalesce(NEW.brand, nullif(trim(p.brand), ''), nullif(trim(pp.brand), ''))
        INTO NEW.category_id, NEW.brand
        FROM products p
        LEFT JOIN products pp ON pp.id = p.parent_id
        WHERE p.id = NEW.product_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_sale_lines_snapshot BEFORE INSERT ON sale_lines
    FOR EACH ROW EXECUTE FUNCTION sale_lines_snapshot();

-- Las devoluciones ahora cuentan el día en que ocurren: los informes las buscan por fecha.
CREATE INDEX ix_returns_tenant_created ON sale_returns (tenant_id, created_at);
