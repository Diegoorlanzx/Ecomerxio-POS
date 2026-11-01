-- Planes editables desde el Super Admin de Ecomerxio (antes fijos en Plans.java). Los códigos no cambian: el registro
-- (/registro?plan=), los pagos y la landing los usan. NULL en un tope = sin límite.
CREATE TABLE plans (
    code            VARCHAR(10) PRIMARY KEY,
    name            VARCHAR(40) NOT NULL,
    price_monthly   BIGINT NOT NULL CHECK (price_monthly >= 0),
    price_annual    BIGINT NOT NULL CHECK (price_annual >= 0),
    max_registers   INT CHECK (max_registers IS NULL OR max_registers > 0),
    max_users       INT CHECK (max_users IS NULL OR max_users > 0),
    sort_order      INT NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO plans (code, name, price_monthly, price_annual, max_registers, max_users, sort_order) VALUES
    ('TIENDA', 'Tienda', 39900, 399000, 1, 3, 1),
    ('NEGOCIO', 'Negocio', 69900, 699000, 3, 10, 2),
    ('PRO', 'Pro', 119900, 1199000, NULL, NULL, 3);

-- Precio con que entró cada tienda a su plan: si después se cambia el precio del catálogo, la tienda conserva el suyo.
-- Se vuelve a fijar (con el precio del momento) solo cuando la tienda cambia de plan. NULL = usa el del catálogo.
ALTER TABLE tenants ADD COLUMN plan_price_monthly BIGINT;
ALTER TABLE tenants ADD COLUMN plan_price_annual BIGINT;
UPDATE tenants t SET plan_price_monthly = p.price_monthly, plan_price_annual = p.price_annual
FROM plans p WHERE p.code = t.plan;
