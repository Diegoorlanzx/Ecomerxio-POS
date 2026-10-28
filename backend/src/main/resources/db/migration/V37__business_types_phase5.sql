-- Tipos de negocio, fase 5.
-- Agro (módulo AGRO): registro ICA por producto. Licorera/tienda (módulo MAYORES_EDAD): productos solo para mayores de edad.
ALTER TABLE products ADD COLUMN ica_code VARCHAR(40);
ALTER TABLE products ADD COLUMN adults_only BOOLEAN NOT NULL DEFAULT FALSE;

-- Veterinaria (módulo MASCOTAS): ficha de cada mascota del cliente y sus vacunas con la próxima dosis.
CREATE TABLE pets (
    id          BIGSERIAL    PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    customer_id BIGINT       REFERENCES customers (id) ON DELETE SET NULL,
    owner_name  VARCHAR(120),
    owner_phone VARCHAR(30),
    name        VARCHAR(60)  NOT NULL,
    species     VARCHAR(30)  NOT NULL,
    breed       VARCHAR(60),
    sex         VARCHAR(10),
    birth_date  DATE,
    notes       VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_pets_tenant ON pets (tenant_id, name);

CREATE TABLE pet_vaccines (
    id         BIGSERIAL   PRIMARY KEY,
    tenant_id  BIGINT      NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    pet_id     BIGINT      NOT NULL REFERENCES pets (id) ON DELETE CASCADE,
    vaccine    VARCHAR(80) NOT NULL,
    applied_on DATE        NOT NULL,
    next_due   DATE,
    notes      VARCHAR(300),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_pet_vaccines_due ON pet_vaccines (tenant_id, next_due);

-- Óptica (módulo OPTICA): fórmula óptica del paciente (ojo derecho e izquierdo).
CREATE TABLE optical_prescriptions (
    id           BIGSERIAL    PRIMARY KEY,
    tenant_id    BIGINT       NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    customer_id  BIGINT       REFERENCES customers (id) ON DELETE SET NULL,
    patient_name VARCHAR(120) NOT NULL,
    patient_doc  VARCHAR(30),
    patient_phone VARCHAR(30),
    exam_date    DATE         NOT NULL,
    optometrist  VARCHAR(120),
    od_sphere    NUMERIC(5,2),
    od_cylinder  NUMERIC(5,2),
    od_axis      INT,
    oi_sphere    NUMERIC(5,2),
    oi_cylinder  NUMERIC(5,2),
    oi_axis      INT,
    addition     NUMERIC(4,2),
    pupillary_distance NUMERIC(4,1),
    lens_type    VARCHAR(60),
    notes        VARCHAR(500),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_optical_prescriptions_tenant ON optical_prescriptions (tenant_id, patient_name);

-- Papelería (módulo LISTAS_UTILES): listas de útiles por colegio y grado que se cargan de una vez a la caja.
CREATE TABLE school_lists (
    id         BIGSERIAL    PRIMARY KEY,
    tenant_id  BIGINT       NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    school     VARCHAR(120) NOT NULL,
    grade      VARCHAR(40)  NOT NULL,
    year       INT          NOT NULL,
    items      TEXT         NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_school_lists_tenant ON school_lists (tenant_id, school, grade);
