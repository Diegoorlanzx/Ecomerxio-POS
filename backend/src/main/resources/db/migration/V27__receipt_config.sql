-- Fase 16C: qué muestra el recibo por tipo de comprobante (JSON; null = valores por defecto de la app).
ALTER TABLE store_settings ADD COLUMN receipt_config TEXT;
