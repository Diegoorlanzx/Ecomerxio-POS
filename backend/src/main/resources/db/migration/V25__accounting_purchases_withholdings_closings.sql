-- Fase 14 (resto): asientos de compras, pagos, gastos, abonos, inventario y caja; terceros y retenciones; notas
-- manuales; cierres de mes y de año. Todo es una PROPUESTA que revisa un contador antes de venderse.

-- Datos tributarios del proveedor para proponer retenciones.
ALTER TABLE suppliers ADD COLUMN iva_responsible BOOLEAN;                       -- null = no se sabe
ALTER TABLE suppliers ADD COLUMN income_tax_declarant BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE suppliers ADD COLUMN self_withholder BOOLEAN NOT NULL DEFAULT FALSE; -- autorretenedor
ALTER TABLE suppliers ADD COLUMN simple_regime BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE suppliers ADD COLUMN great_contributor BOOLEAN NOT NULL DEFAULT FALSE;

-- Lo retenido al proveedor se descuenta de lo que se le debe: saldo = total - retenido - pagado.
ALTER TABLE goods_receipts ADD COLUMN withheld NUMERIC(14,2) NOT NULL DEFAULT 0;

CREATE TABLE receipt_withholdings (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    receipt_id  BIGINT NOT NULL REFERENCES goods_receipts(id) ON DELETE CASCADE,
    kind        VARCHAR(12) NOT NULL,     -- RETEFUENTE | RETEIVA | RETEICA
    base        NUMERIC(14,2) NOT NULL,
    rate        NUMERIC(8,4) NOT NULL,    -- porcentaje (2.5 = 2,5 %)
    amount      NUMERIC(14,2) NOT NULL,
    confirmed   BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_receipt_withholdings ON receipt_withholdings (receipt_id, kind);

-- Reglas de retención de la tienda. Los valores por defecto son una propuesta: el contador los confirma.
CREATE TABLE accounting_tax_settings (
    tenant_id                 BIGINT PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    uvt                       NUMERIC(10,2) NOT NULL DEFAULT 52374,
    retention_agent           BOOLEAN NOT NULL DEFAULT FALSE,
    purchases_base_uvt        NUMERIC(8,2) NOT NULL DEFAULT 27,
    purchases_rate_declarant  NUMERIC(6,3) NOT NULL DEFAULT 2.5,
    purchases_rate_other      NUMERIC(6,3) NOT NULL DEFAULT 3.5,
    reteiva_agent             BOOLEAN NOT NULL DEFAULT FALSE,
    reteiva_rate              NUMERIC(6,3) NOT NULL DEFAULT 15,
    reteica_per_mil           NUMERIC(6,3),                       -- null = no practica ReteICA
    configured                BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Meses cerrados: lo que llegue con fecha de un mes cerrado se registra el primer día del siguiente mes abierto.
CREATE TABLE accounting_periods (
    tenant_id      BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    period         VARCHAR(7) NOT NULL,     -- 2026-10
    closed         BOOLEAN NOT NULL DEFAULT TRUE,
    closed_at      TIMESTAMPTZ,
    closed_by      VARCHAR(120),
    reopened_at    TIMESTAMPTZ,
    reopened_by    VARCHAR(120),
    reopen_reason  VARCHAR(300),
    PRIMARY KEY (tenant_id, period)
);

-- Fecha original cuando el comprobante se movió por caer en un mes cerrado.
ALTER TABLE journal_entries ADD COLUMN original_date DATE;
ALTER TABLE journal_entries ADD COLUMN created_by VARCHAR(120);

-- Cuentas nuevas de la propuesta para las tiendas que ya tienen plan de cuentas.
INSERT INTO accounting_accounts (tenant_id, code, name, nature)
SELECT DISTINCT a.tenant_id, x.code, x.name, x.nature
FROM accounting_accounts a CROSS JOIN (VALUES
    ('3610', 'Pérdida del ejercicio', 'DEBITO'),
    ('4295', 'Ingresos diversos', 'CREDITO'),
    ('5310', 'Pérdida en venta y retiro de bienes', 'DEBITO')
) AS x(code, name, nature)
WHERE NOT EXISTS (SELECT 1 FROM accounting_accounts b WHERE b.tenant_id = a.tenant_id AND b.code = x.code);
