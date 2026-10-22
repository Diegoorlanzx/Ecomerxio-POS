-- Fase 16E: nota débito, nota crédito manual (rebaja, ajuste de precio, descuentos) y nota de ajuste al documento soporte.
ALTER TABLE electronic_documents ADD COLUMN manual BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE electronic_documents ADD COLUMN note_concept VARCHAR(4);          -- código DIAN del concepto de la nota
ALTER TABLE electronic_documents ADD COLUMN line_description VARCHAR(200);    -- la nota manual lleva una sola línea
ALTER TABLE electronic_documents ADD COLUMN line_tax_rate NUMERIC(5,2);
ALTER TABLE electronic_documents ADD COLUMN settlement VARCHAR(20);           -- FIADO (cuenta del cliente) o el medio de pago
ALTER TABLE electronic_documents ALTER COLUMN document_type TYPE VARCHAR(30);

-- Una venta tiene un solo documento de venta vigente (las notas, de cualquier tipo, no cuentan).
DROP INDEX ux_edoc_sale;
CREATE UNIQUE INDEX ux_edoc_sale ON electronic_documents (sale_id)
    WHERE sale_id IS NOT NULL AND document_type IN ('FACTURA', 'POS_ELECTRONICO') AND NOT superseded;

-- La nota de anulación o refacturación es una sola por documento; las manuales pueden ser varias.
DROP INDEX ux_edoc_void_note;
CREATE UNIQUE INDEX ux_edoc_void_note ON electronic_documents (original_id)
    WHERE document_type = 'NOTA_CREDITO' AND return_id IS NULL AND NOT manual;
