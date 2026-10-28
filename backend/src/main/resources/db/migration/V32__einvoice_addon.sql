-- La facturación electrónica no viene en ningún plan: se activa por tienda desde el Super Admin de Ecomerxio.
-- Las tiendas que ya la tenían encendida (demos) la conservan.
ALTER TABLE tenants ADD COLUMN einvoice_addon BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE tenants t SET einvoice_addon = TRUE
WHERE EXISTS (SELECT 1 FROM tenant_fiscal_profiles p WHERE p.tenant_id = t.id AND p.invoicing_enabled);
