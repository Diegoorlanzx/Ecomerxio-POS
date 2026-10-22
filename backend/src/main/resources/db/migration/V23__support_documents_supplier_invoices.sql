-- Fase 13E: documento soporte (compras a no obligados a facturar) e importación de facturas electrónicas de proveedores.

-- Datos del proveedor que pide el documento soporte (el proveedor es el "vendedor" del documento).
ALTER TABLE suppliers ADD COLUMN doc_type VARCHAR(10) NOT NULL DEFAULT 'NIT';
ALTER TABLE suppliers ADD COLUMN dv VARCHAR(1);
ALTER TABLE suppliers ADD COLUMN person_type VARCHAR(10);          -- NATURAL | JURIDICA
ALTER TABLE suppliers ADD COLUMN municipality_code VARCHAR(5);     -- código DANE
-- FALSE = no está obligado a facturar (p. ej. un campesino o un independiente): sus compras llevan documento soporte.
ALTER TABLE suppliers ADD COLUMN invoices_electronically BOOLEAN NOT NULL DEFAULT TRUE;

-- El documento soporte sale de una recepción de mercancía; una recepción tiene a lo sumo uno.
ALTER TABLE electronic_documents ADD COLUMN receipt_id BIGINT REFERENCES goods_receipts(id);
CREATE UNIQUE INDEX ux_edoc_receipt ON electronic_documents (receipt_id) WHERE receipt_id IS NOT NULL;

-- Facturas electrónicas recibidas de proveedores (XML del AttachedDocument o de la factura), ya convertidas en recepción.
CREATE TABLE supplier_invoices (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    supplier_id      BIGINT NOT NULL REFERENCES suppliers(id),
    receipt_id       BIGINT REFERENCES goods_receipts(id),
    order_id         BIGINT REFERENCES purchase_orders(id),
    invoice_number   VARCHAR(40) NOT NULL,
    cufe             VARCHAR(120),
    issue_date       DATE,
    due_date         DATE,
    supplier_tax_id  VARCHAR(30),
    supplier_name    VARCHAR(200),
    buyer_tax_id     VARCHAR(30),
    subtotal         NUMERIC(14,2) NOT NULL DEFAULT 0,
    tax_total        NUMERIC(14,2) NOT NULL DEFAULT 0,
    total            NUMERIC(14,2) NOT NULL DEFAULT 0,
    file_name        VARCHAR(200),
    xml              TEXT NOT NULL,   -- copia del XML original
    imported_by      VARCHAR(120),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- La misma factura no se importa dos veces (por CUFE y, si no trae, por proveedor y número).
CREATE UNIQUE INDEX ux_supplier_invoice_cufe ON supplier_invoices (tenant_id, cufe) WHERE cufe IS NOT NULL;
CREATE UNIQUE INDEX ux_supplier_invoice_number ON supplier_invoices (tenant_id, supplier_id, invoice_number);
CREATE INDEX ix_supplier_invoices_tenant ON supplier_invoices (tenant_id, created_at DESC);
