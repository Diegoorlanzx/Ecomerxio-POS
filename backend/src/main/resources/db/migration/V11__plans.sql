-- Planes de Ecomerxio POS (Tienda, Negocio, Pro). Las tiendas nuevas eligen uno al registrarse y arrancan
-- 15 días de prueba; las que ya existían (demos) quedan en Pro sin fecha de fin de prueba.
ALTER TABLE tenants ADD COLUMN plan VARCHAR(10) NOT NULL DEFAULT 'PRO';
ALTER TABLE tenants ADD COLUMN trial_ends_at TIMESTAMPTZ;
