-- Fase 16A: modo fiscal de la tienda, cliente con o sin factura, refacturar y fiado como crédito en la factura.

-- OBLIGADO: toda venta lleva documento (a nombre del cliente o a consumidor final).
-- A_PEDIDO: solo se factura cuando el cliente lo pide; el resto sale con comprobante interno (no es factura).
ALTER TABLE tenant_fiscal_profiles ADD COLUMN invoicing_mode VARCHAR(12) NOT NULL DEFAULT 'OBLIGADO';

-- SIEMPRE: la caja marca la factura sola y cobrarle sin ella pide PIN. NUNCA: no se le factura a su nombre.
ALTER TABLE customers ADD COLUMN invoice_preference VARCHAR(10) NOT NULL DEFAULT 'PREGUNTAR';

-- Quién autorizó cobrar sin factura a un cliente que siempre la pide.
ALTER TABLE sales ADD COLUMN invoice_skipped_by VARCHAR(120);

-- El documento guarda su propio comprador: al refacturar, la venta cambia de cliente pero la nota crédito del
-- documento anterior debe salir a nombre de quien lo recibió.
ALTER TABLE electronic_documents ADD COLUMN customer_id BIGINT REFERENCES customers(id) ON DELETE SET NULL;
ALTER TABLE electronic_documents ADD COLUMN payment_form VARCHAR(10) NOT NULL DEFAULT 'CONTADO';  -- CONTADO | CREDITO
ALTER TABLE electronic_documents ADD COLUMN due_date DATE;
-- Documento reemplazado al refacturar (lo anula su nota crédito y lo reemplaza replaced_by_id).
ALTER TABLE electronic_documents ADD COLUMN superseded BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE electronic_documents ADD COLUMN replaced_by_id BIGINT REFERENCES electronic_documents(id);

UPDATE electronic_documents d SET customer_id = s.customer_id
FROM sales s
WHERE d.sale_id = s.id AND d.document_type <> 'DOCUMENTO_SOPORTE' AND d.customer_id_number <> '222222222222';

DROP INDEX ux_edoc_sale;
CREATE UNIQUE INDEX ux_edoc_sale ON electronic_documents (sale_id)
    WHERE sale_id IS NOT NULL AND document_type <> 'NOTA_CREDITO' AND NOT superseded;

-- Permiso nuevo para los roles predefinidos que ya existen.
INSERT INTO role_permissions (role_id, permission)
SELECT id, 'FACTURA_OMITIR' FROM roles WHERE system_code IN ('DUENO', 'ADMINISTRADOR', 'SUPERVISOR')
ON CONFLICT DO NOTHING;
