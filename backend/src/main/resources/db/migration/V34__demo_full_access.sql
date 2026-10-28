-- Tienda demo de ventas (dueno@demo.tiendaposs.test, Cigarrería El Paisa): acceso completo para mostrarla, pero con
-- los módulos de SU tipo de negocio (tienda de barrio): cada negocio es distinto, una cigarrería no tiene mesas ni
-- cocina. Lotes, fiado, cuentas abiertas, compras, combos, báscula y tienda en línea; plan Pro sin vencimiento,
-- facturación electrónica habilitada y el dueño activo y desbloqueado. Si la cuenta no existe, no cambia nada.
UPDATE store_settings
SET business_type  = 'TIENDA',
    modules        = 'LOTES,CUENTAS_ABIERTAS,COMPRAS,COMBOS,BASCULA,TIENDA_ONLINE',
    credit_enabled = TRUE
WHERE tenant_id IN (SELECT tenant_id FROM users WHERE lower(email) = 'dueno@demo.tiendaposs.test');

UPDATE tenants
SET plan           = 'PRO',
    status         = 'ACTIVE',
    einvoice_addon = TRUE,
    trial_ends_at  = NULL
WHERE id IN (SELECT tenant_id FROM users WHERE lower(email) = 'dueno@demo.tiendaposs.test');

UPDATE users
SET active = TRUE, failed_attempts = 0, locked_until = NULL
WHERE lower(email) = 'dueno@demo.tiendaposs.test';
