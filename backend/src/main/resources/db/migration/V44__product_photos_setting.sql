-- Mostrar u ocultar las fotos de producto en el POS (Más → Tipo de negocio y módulos). Por defecto, con fotos.
ALTER TABLE store_settings ADD COLUMN product_photos BOOLEAN NOT NULL DEFAULT TRUE;
