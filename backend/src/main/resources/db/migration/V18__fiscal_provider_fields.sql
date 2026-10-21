-- Datos que pide el proveedor de facturación (Factus) y que el perfil fiscal no tenía.

-- Persona natural: el proveedor pide nombres y apellidos por separado (la razón social queda como nombre completo).
ALTER TABLE tenant_fiscal_profiles ADD COLUMN first_names VARCHAR(80);
ALTER TABLE tenant_fiscal_profiles ADD COLUMN last_names VARCHAR(80);
-- Número de matrícula mercantil (Cámara de Comercio), opcional.
ALTER TABLE tenant_fiscal_profiles ADD COLUMN registration_code VARCHAR(20);

-- Clientes: municipio (código DANE) y si es responsable de IVA, para facturarles a su nombre.
ALTER TABLE customers ADD COLUMN municipality_code VARCHAR(5);
ALTER TABLE customers ADD COLUMN iva_responsible BOOLEAN;
