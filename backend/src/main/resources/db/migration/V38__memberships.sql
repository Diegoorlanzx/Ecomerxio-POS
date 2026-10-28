-- Tipos de negocio, fase 6. Gimnasio (módulo MEMBRESIAS): productos que dan días de membresía al venderse a un cliente,
-- membresía vigente por cliente y registro de entradas.
ALTER TABLE products ADD COLUMN membership_days INT;

CREATE TABLE memberships (
    tenant_id   BIGINT       NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    customer_id BIGINT       NOT NULL REFERENCES customers (id) ON DELETE CASCADE,
    start_date  DATE         NOT NULL,
    end_date    DATE         NOT NULL,
    plan_name   VARCHAR(160),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, customer_id)
);
CREATE INDEX idx_memberships_end ON memberships (tenant_id, end_date);

CREATE TABLE gym_checkins (
    id          BIGSERIAL    PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    customer_id BIGINT       NOT NULL REFERENCES customers (id) ON DELETE CASCADE,
    allowed     BOOLEAN      NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_gym_checkins_tenant ON gym_checkins (tenant_id, created_at DESC);
