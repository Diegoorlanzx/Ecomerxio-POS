-- Fase 5: notificaciones persistentes con reglas y canales, copias de seguridad y sucursales.

-- Una notificación es para quien tenga el permiso indicado (p. ej. INVENTARIO_VER).
CREATE TABLE notifications (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    type        VARCHAR(40) NOT NULL,
    severity    VARCHAR(10) NOT NULL DEFAULT 'MEDIA',   -- CRITICA, ALTA, MEDIA, INFO
    title       VARCHAR(200) NOT NULL,
    body        TEXT,
    link        VARCHAR(200),
    permission  VARCHAR(40),
    -- Evita repetir el mismo aviso (p. ej. STOCK_CRITICO:12:2026-10-15).
    dedup_key   VARCHAR(160) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_notifications_dedup ON notifications (tenant_id, dedup_key);
CREATE INDEX ix_notifications_tenant_time ON notifications (tenant_id, created_at DESC);

CREATE TABLE notification_reads (
    notification_id BIGINT NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    read_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (notification_id, user_id)
);

-- Envío por canales externos: un registro por intento.
CREATE TABLE notification_deliveries (
    id              BIGSERIAL PRIMARY KEY,
    notification_id BIGINT NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
    tenant_id       BIGINT NOT NULL,
    channel         VARCHAR(20) NOT NULL,   -- EMAIL, WEBHOOK, WHATSAPP
    status          VARCHAR(20) NOT NULL,   -- ENVIADO, FALLIDO, SIN_CONFIGURAR
    detail          VARCHAR(500),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_deliveries_notification ON notification_deliveries (notification_id);

-- Reglas por tipo: activa, umbral y por qué canales sale (dentro del sistema siempre).
CREATE TABLE notification_rules (
    tenant_id   BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    type        VARCHAR(40) NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    threshold   NUMERIC(14,2),
    email       BOOLEAN NOT NULL DEFAULT FALSE,
    webhook     BOOLEAN NOT NULL DEFAULT FALSE,
    whatsapp    BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (tenant_id, type)
);

CREATE TABLE notification_channels (
    tenant_id          BIGINT PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    email_to           VARCHAR(300),
    webhook_url        VARCHAR(300),
    webhook_secret     VARCHAR(80),
    whatsapp_phone_id  VARCHAR(40),
    whatsapp_token     VARCHAR(400),
    whatsapp_to        VARCHAR(40),
    daily_report_hour  INT NOT NULL DEFAULT 21,
    last_scan_at       TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Copias de seguridad lógicas de cada tienda (JSON comprimido).
CREATE TABLE backups (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    kind            VARCHAR(15) NOT NULL,   -- MANUAL, AUTOMATICA, ANTES_RESTAURAR
    schema_version  VARCHAR(20) NOT NULL,
    size_bytes      BIGINT NOT NULL,
    checksum        VARCHAR(64) NOT NULL,
    row_counts      VARCHAR(2000),
    created_by      VARCHAR(120),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    data            BYTEA NOT NULL
);
CREATE INDEX ix_backups_tenant ON backups (tenant_id, created_at DESC);

ALTER TABLE store_settings ADD COLUMN auto_backup BOOLEAN NOT NULL DEFAULT TRUE;

-- Sucursales: hoy cada tienda tiene una ("Principal"); la estructura queda lista para varias.
CREATE TABLE branches (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name        VARCHAR(80) NOT NULL,
    address     VARCHAR(200),
    phone       VARCHAR(30),
    is_main     BOOLEAN NOT NULL DEFAULT FALSE,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_branches_main ON branches (tenant_id) WHERE is_main;
INSERT INTO branches (tenant_id, name, address, phone, is_main)
SELECT id, 'Principal', address, phone, TRUE FROM tenants;

ALTER TABLE registers ADD COLUMN branch_id BIGINT REFERENCES branches(id);
UPDATE registers r SET branch_id = b.id FROM branches b WHERE b.tenant_id = r.tenant_id AND b.is_main;
