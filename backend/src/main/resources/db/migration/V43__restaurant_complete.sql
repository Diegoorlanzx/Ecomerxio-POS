-- Restaurante completo: roles de mesero y cocina, pedidos en línea y por QR que llegan a la cocina, reservas de mesa
-- y "gaseosa a elección" (opciones de modificador que descuentan un producto del inventario).

-- ---------------------------------------------------------------------------------------------------------------
-- Permisos nuevos para los roles predefinidos que ya existen (el dueño los tiene todos sin guardarlos).
INSERT INTO role_permissions (role_id, permission)
SELECT id, p FROM roles, (VALUES ('MESAS_ATENDER'), ('MESAS_TODAS'), ('COCINA_VER')) AS x(p)
WHERE system_code IN ('ADMINISTRADOR', 'SUPERVISOR')
ON CONFLICT DO NOTHING;

-- Roles "Mesero" y "Cocina o bar" en las tiendas que ya tienen el restaurante encendido (las demás los reciben al
-- encenderlo). Si el dueño ya creó un rol con ese nombre, se respeta el suyo.
INSERT INTO roles (tenant_id, system_code, name, description)
SELECT ss.tenant_id, 'MESERO', 'Mesero',
       'Atiende sus mesas desde el celular: pide platos, envía a cocina y saca la precuenta. No cobra ni abre caja.'
FROM store_settings ss
WHERE (',' || ss.modules || ',') LIKE '%,RESTAURANTE,%'
  AND NOT EXISTS (SELECT 1 FROM roles r WHERE r.tenant_id = ss.tenant_id AND (r.system_code = 'MESERO' OR lower(r.name) = 'mesero'));
INSERT INTO roles (tenant_id, system_code, name, description)
SELECT ss.tenant_id, 'COCINA', 'Cocina o bar',
       'Solo ve la pantalla de comandas de su estación y marca los platos en preparación y listos.'
FROM store_settings ss
WHERE (',' || ss.modules || ',') LIKE '%,RESTAURANTE,%'
  AND NOT EXISTS (SELECT 1 FROM roles r WHERE r.tenant_id = ss.tenant_id AND (r.system_code = 'COCINA' OR lower(r.name) = 'cocina o bar'));
INSERT INTO role_permissions (role_id, permission)
SELECT id, p FROM roles, (VALUES ('MESAS_ATENDER'), ('PRODUCTOS_VER')) AS x(p) WHERE system_code = 'MESERO'
ON CONFLICT DO NOTHING;
INSERT INTO role_permissions (role_id, permission)
SELECT id, 'COCINA_VER' FROM roles WHERE system_code = 'COCINA'
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------------------------------------------
-- Comandas de pedidos en línea (domicilio, para llevar, pedido desde la mesa por QR): no tienen cuenta abierta.
ALTER TABLE kitchen_tickets ALTER COLUMN sale_id DROP NOT NULL;
ALTER TABLE kitchen_tickets ADD COLUMN online_order_id BIGINT REFERENCES online_orders(id) ON DELETE CASCADE;
CREATE INDEX ix_kitchen_online ON kitchen_tickets (online_order_id) WHERE online_order_id IS NOT NULL;

-- Código QR de cada mesa (el enlace lleva este código, no el id: no se puede adivinar la mesa de otro).
ALTER TABLE dining_tables ADD COLUMN qr_token VARCHAR(40);
UPDATE dining_tables SET qr_token = substr(md5(random()::text || id::text || clock_timestamp()::text), 1, 16) WHERE qr_token IS NULL;
ALTER TABLE dining_tables ALTER COLUMN qr_token SET NOT NULL;
CREATE UNIQUE INDEX ux_dining_tables_qr ON dining_tables (qr_token);

-- Pedido hecho desde la mesa (fulfillment = MESA): el mesero lo revisa y lo pasa a la cuenta de la mesa.
ALTER TABLE online_orders ADD COLUMN table_id BIGINT REFERENCES dining_tables(id) ON DELETE SET NULL;
ALTER TABLE online_orders ADD COLUMN table_label VARCHAR(60);
-- Desde la mesa no se pide celular.
ALTER TABLE online_orders ALTER COLUMN phone DROP NOT NULL;

-- Opciones de los platos (modificadores) en el pedido en línea: "sin cebolla", "Coca-Cola" (texto y sus ids).
ALTER TABLE online_order_lines ADD COLUMN modifiers VARCHAR(500);
ALTER TABLE online_order_lines ADD COLUMN modifier_ids VARCHAR(200);
ALTER TABLE online_order_lines ADD COLUMN note VARCHAR(200);

-- "Gaseosa a elección": una opción puede descontar un producto del inventario (la gaseosa que se eligió, el queso de
-- la adición). Cantidad por cada plato.
ALTER TABLE modifier_options ADD COLUMN product_id BIGINT REFERENCES products(id) ON DELETE SET NULL;
ALTER TABLE modifier_options ADD COLUMN product_qty NUMERIC(14,3) NOT NULL DEFAULT 1;
-- Lo que descuenta cada plato por sus opciones: "productId:cantidad,productId:cantidad" (por unidad del plato).
ALTER TABLE sale_lines ADD COLUMN modifier_stock VARCHAR(300);

-- La tienda en línea puede recibir pedidos desde la mesa aunque no haga domicilios.
ALTER TABLE online_stores ADD COLUMN table_orders BOOLEAN NOT NULL DEFAULT FALSE;

-- ---------------------------------------------------------------------------------------------------------------
-- Reservas de mesa.
CREATE TABLE table_reservations (
    id             BIGSERIAL PRIMARY KEY,
    tenant_id      BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    table_id       BIGINT REFERENCES dining_tables(id) ON DELETE SET NULL,
    customer_id    BIGINT REFERENCES customers(id) ON DELETE SET NULL,
    name           VARCHAR(120) NOT NULL,
    phone          VARCHAR(30),
    people         INT NOT NULL DEFAULT 2,
    reserved_at    TIMESTAMPTZ NOT NULL,
    duration_min   INT NOT NULL DEFAULT 90,
    notes          VARCHAR(300),
    -- PENDIENTE, CONFIRMADA, LLEGO, NO_LLEGO, CANCELADA
    status         VARCHAR(12) NOT NULL DEFAULT 'PENDIENTE',
    sale_id        BIGINT REFERENCES sales(id) ON DELETE SET NULL,
    created_by     VARCHAR(120),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_reservations_day ON table_reservations (tenant_id, reserved_at);
