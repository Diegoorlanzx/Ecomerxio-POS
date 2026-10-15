-- TiendaPOSS - Fase 0: tiendas (multi-tienda), usuarios, roles/permisos y auditoría.

CREATE TABLE tenants (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(120) NOT NULL,
    legal_name      VARCHAR(160),
    tax_id          VARCHAR(30),
    tax_regime      VARCHAR(40),
    address         VARCHAR(200),
    city            VARCHAR(80),
    phone           VARCHAR(30),
    whatsapp        VARCHAR(30),
    email           VARCHAR(160),
    logo            TEXT,
    currency        VARCHAR(3)  NOT NULL DEFAULT 'COP',
    timezone        VARCHAR(40) NOT NULL DEFAULT 'America/Bogota',
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         BIGINT REFERENCES tenants(id) ON DELETE CASCADE,
    email             VARCHAR(160) NOT NULL,
    password_hash     VARCHAR(100) NOT NULL,
    full_name         VARCHAR(120) NOT NULL,
    phone             VARCHAR(30),
    active            BOOLEAN     NOT NULL DEFAULT TRUE,
    failed_attempts   INT         NOT NULL DEFAULT 0,
    locked_until      TIMESTAMPTZ,
    token_version     INT         NOT NULL DEFAULT 0,
    last_login_at     TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_users_email ON users (lower(email));
CREATE INDEX ix_users_tenant ON users (tenant_id);

-- Roles por tienda. system_code != NULL = rol predefinido (no se borra; el de DUENO siempre tiene todo).
CREATE TABLE roles (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    system_code  VARCHAR(30),
    name         VARCHAR(60) NOT NULL,
    description  VARCHAR(255),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_roles_tenant_name ON roles (tenant_id, lower(name));
CREATE UNIQUE INDEX ux_roles_tenant_code ON roles (tenant_id, system_code) WHERE system_code IS NOT NULL;

CREATE TABLE role_permissions (
    role_id     BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission  VARCHAR(40) NOT NULL,
    PRIMARY KEY (role_id, permission)
);

CREATE TABLE user_roles (
    user_id  BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id  BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);
CREATE INDEX ix_user_roles_role ON user_roles (role_id);

CREATE TABLE password_reset_tokens (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Registro de actividad: accesos, operaciones sensibles y cambios de configuración.
CREATE TABLE audit_events (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT REFERENCES tenants(id) ON DELETE CASCADE,
    user_id      BIGINT REFERENCES users(id) ON DELETE SET NULL,
    user_name    VARCHAR(120),
    action       VARCHAR(50)  NOT NULL,
    entity_type  VARCHAR(40),
    entity_id    BIGINT,
    summary      VARCHAR(500) NOT NULL,
    sensitive    BOOLEAN      NOT NULL DEFAULT FALSE,
    ip           VARCHAR(64),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_tenant_time ON audit_events (tenant_id, created_at DESC);
CREATE INDEX ix_audit_tenant_user ON audit_events (tenant_id, user_id, created_at DESC);
