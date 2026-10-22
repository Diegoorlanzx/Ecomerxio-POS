-- Fase 16F: nómina (empleados, novedades, liquidación mensual) y nómina electrónica.

-- Valores legales por año. No se inventan: el dueño o el contador los confirma cada año (salario mínimo, auxilio...).
CREATE TABLE payroll_parameters (
    id                   BIGSERIAL PRIMARY KEY,
    tenant_id            BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    year                 INT NOT NULL,
    minimum_wage         NUMERIC(14,2) NOT NULL,
    transport_aid        NUMERIC(14,2) NOT NULL,
    monthly_hours        NUMERIC(6,2) NOT NULL,       -- horas de la jornada mensual (base de la hora ordinaria)
    sunday_surcharge_pct NUMERIC(6,2) NOT NULL,       -- recargo dominical/festivo vigente
    exonerated           BOOLEAN NOT NULL DEFAULT FALSE, -- exonerado de salud empleador, SENA e ICBF (art. 114-1 E.T.)
    confirmed_by         VARCHAR(120),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, year)
);

CREATE TABLE employees (
    id                 BIGSERIAL PRIMARY KEY,
    tenant_id          BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id            BIGINT REFERENCES users(id) ON DELETE SET NULL,  -- opcional: el empleado puede no usar el POS
    doc_type           VARCHAR(10) NOT NULL,
    doc_number         VARCHAR(30) NOT NULL,
    first_name         VARCHAR(60) NOT NULL,
    other_names        VARCHAR(60),
    first_surname      VARCHAR(60) NOT NULL,
    second_surname     VARCHAR(60),
    email              VARCHAR(160),
    phone              VARCHAR(30),
    address            VARCHAR(200),
    municipality_code  VARCHAR(5),
    contract_type      VARCHAR(2) NOT NULL,      -- 1 fijo, 2 indefinido, 3 obra o labor, 4 aprendizaje, 5 práctica
    worker_type        VARCHAR(2) NOT NULL DEFAULT '01',
    worker_subtype     VARCHAR(2) NOT NULL DEFAULT '00',
    integral_salary    BOOLEAN NOT NULL DEFAULT FALSE,
    high_risk          BOOLEAN NOT NULL DEFAULT FALSE,
    salary             NUMERIC(14,2) NOT NULL,
    arl_class          INT NOT NULL DEFAULT 1,
    start_date         DATE NOT NULL,
    end_date           DATE,
    eps                VARCHAR(80),
    pension_fund       VARCHAR(80),
    severance_fund     VARCHAR(80),
    compensation_fund  VARCHAR(80),
    payment_method     VARCHAR(3) NOT NULL DEFAULT '10',  -- código DIAN: 10 efectivo, 47 transferencia, 42 consignación
    bank_name          VARCHAR(80),
    account_type       VARCHAR(1),               -- 1 nómina, 2 ahorros, 3 corriente
    account_number     VARCHAR(40),
    active             BOOLEAN NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, doc_number)
);

CREATE TABLE payroll_periods (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    year         INT NOT NULL,
    month        INT NOT NULL,
    status       VARCHAR(12) NOT NULL DEFAULT 'BORRADOR',  -- BORRADOR | APROBADA
    payment_date DATE,
    approved_by  VARCHAR(120),
    approved_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, year, month)
);

-- Novedades del mes por empleado: horas extra y recargos, incapacidades, vacaciones, licencias, bonificaciones,
-- comisiones, auxilios y deducciones (préstamos, libranzas, retención, otras).
CREATE TABLE payroll_novelties (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    period_id   BIGINT NOT NULL REFERENCES payroll_periods(id) ON DELETE CASCADE,
    employee_id BIGINT NOT NULL REFERENCES employees(id) ON DELETE CASCADE,
    kind        VARCHAR(20) NOT NULL,
    code        VARCHAR(4),                       -- subtipo DIAN (p. ej. 1..7 en horas extra)
    quantity    NUMERIC(10,2),                    -- horas o días
    percentage  NUMERIC(6,2),
    amount      NUMERIC(14,2),                    -- si se deja vacío, se calcula
    description VARCHAR(200),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_payroll_novelties ON payroll_novelties (period_id, employee_id);

-- Liquidación de un empleado en un mes. El detalle (devengados y deducciones con sus códigos DIAN) va en JSON.
CREATE TABLE payslips (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    period_id           BIGINT NOT NULL REFERENCES payroll_periods(id) ON DELETE CASCADE,
    employee_id         BIGINT NOT NULL REFERENCES employees(id),
    days_worked         INT NOT NULL,
    contribution_base   NUMERIC(14,2) NOT NULL,
    total_earned        NUMERIC(14,2) NOT NULL,
    total_deductions    NUMERIC(14,2) NOT NULL,
    net_pay             NUMERIC(14,2) NOT NULL,
    employer_cost       NUMERIC(14,2) NOT NULL,   -- aportes del empleador
    provisions          NUMERIC(14,2) NOT NULL,   -- prestaciones sociales del mes
    detail              TEXT NOT NULL,
    deleted_before_dian BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (period_id, employee_id)
);

ALTER TABLE electronic_documents ADD COLUMN payslip_id BIGINT REFERENCES payslips(id) ON DELETE RESTRICT;
CREATE UNIQUE INDEX ux_edoc_payslip ON electronic_documents (payslip_id) WHERE document_type = 'NOMINA';

INSERT INTO role_permissions (role_id, permission)
SELECT id, p FROM roles, (VALUES ('NOMINA_VER'), ('NOMINA_GESTIONAR')) AS x(p)
WHERE system_code IN ('DUENO', 'ADMINISTRADOR', 'CONTADOR')
ON CONFLICT DO NOTHING;
