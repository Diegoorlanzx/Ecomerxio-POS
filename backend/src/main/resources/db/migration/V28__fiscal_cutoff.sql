-- Fase 16B: fecha de corte de la facturación electrónica.
-- Se fija al activar la facturación (y se vuelve a fijar al pasar de pruebas a producción). Las ventas anteriores
-- nunca se facturan y, por defecto, se ocultan de Ventas, Analítica y Dashboard (no se borran).
-- Las tiendas que ya tenían la facturación activa no reciben corte aquí (no se les oculta nada de golpe): lo reciben
-- al volver a activarla o al pasar a producción.
ALTER TABLE tenant_fiscal_profiles ADD COLUMN fiscal_start_at TIMESTAMPTZ;
ALTER TABLE tenant_fiscal_profiles ADD COLUMN fiscal_start_environment VARCHAR(12);
ALTER TABLE tenant_fiscal_profiles ADD COLUMN hide_pre_cutoff BOOLEAN NOT NULL DEFAULT TRUE;

INSERT INTO role_permissions (role_id, permission)
SELECT id, 'VENTAS_HISTORIAL_PREVIO' FROM roles WHERE system_code IN ('DUENO', 'ADMINISTRADOR')
ON CONFLICT DO NOTHING;
