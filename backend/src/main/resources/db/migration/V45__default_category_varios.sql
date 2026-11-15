-- Los productos sin categoría pasan a "Varios" (desde ahora el sistema lo hace solo al guardar).
-- No se tocan los productos internos de la caja (DOMICILIO y VARIOS).
INSERT INTO categories (tenant_id, name, sort_order)
SELECT DISTINCT p.tenant_id, 'Varios', 999
FROM products p
WHERE p.category_id IS NULL
  AND (p.sku IS NULL OR p.sku NOT IN ('DOMICILIO', 'VARIOS'))
  AND NOT EXISTS (SELECT 1 FROM categories c WHERE c.tenant_id = p.tenant_id AND c.parent_id IS NULL AND lower(c.name) = 'varios');

UPDATE products p
SET category_id = (SELECT c.id FROM categories c
                   WHERE c.tenant_id = p.tenant_id AND c.parent_id IS NULL AND lower(c.name) = 'varios'
                   ORDER BY c.id LIMIT 1)
WHERE p.category_id IS NULL
  AND (p.sku IS NULL OR p.sku NOT IN ('DOMICILIO', 'VARIOS'));
