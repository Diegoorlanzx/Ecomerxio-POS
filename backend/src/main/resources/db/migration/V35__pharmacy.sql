-- Tipos de negocio, fase 2: droguería (módulo DROGUERIA). Registro sanitario INVIMA, principio activo, fórmula médica
-- y medicamentos de control especial por producto; los datos de la fórmula quedan con cada venta que la exige.
ALTER TABLE products ADD COLUMN invima_code VARCHAR(40);
ALTER TABLE products ADD COLUMN active_ingredient VARCHAR(160);
ALTER TABLE products ADD COLUMN requires_prescription BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE products ADD COLUMN controlled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE sale_prescriptions (
    sale_id             BIGINT       PRIMARY KEY REFERENCES sales (id) ON DELETE CASCADE,
    tenant_id           BIGINT       NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    doctor_name         VARCHAR(120),
    doctor_license      VARCHAR(40),
    prescription_number VARCHAR(40),
    prescription_date   DATE,
    patient_name        VARCHAR(120),
    patient_doc         VARCHAR(30),
    controlled          BOOLEAN      NOT NULL DEFAULT FALSE,
    products            VARCHAR(1000),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_sale_prescriptions_tenant ON sale_prescriptions (tenant_id, created_at DESC);
