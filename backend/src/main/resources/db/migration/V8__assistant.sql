-- Fase 6: asistente de inteligencia artificial.

CREATE TABLE assistant_conversations (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title       VARCHAR(120) NOT NULL,
    -- Historial exacto que se envía al modelo (solo se agrega al final; nunca se edita).
    transcript  TEXT NOT NULL DEFAULT '[]',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_assistant_conv_user ON assistant_conversations (tenant_id, user_id, updated_at DESC);

-- Lo que ve la persona: su pregunta y la respuesta, con las consultas que usó el asistente.
CREATE TABLE assistant_messages (
    id               BIGSERIAL PRIMARY KEY,
    conversation_id  BIGINT NOT NULL REFERENCES assistant_conversations(id) ON DELETE CASCADE,
    role             VARCHAR(10) NOT NULL,   -- USER, ASSISTANT
    text             TEXT NOT NULL,
    tools_used       VARCHAR(500),
    input_tokens     INT,
    output_tokens    INT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_assistant_msg_conv ON assistant_messages (conversation_id, id);

CREATE TABLE assistant_usage (
    tenant_id      BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    day            DATE NOT NULL,
    questions      INT NOT NULL DEFAULT 0,
    input_tokens   BIGINT NOT NULL DEFAULT 0,
    output_tokens  BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, day)
);

-- Recomendaciones de precios y promociones: nada se aplica sin que una persona lo apruebe.
CREATE TABLE ai_recommendations (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    type             VARCHAR(12) NOT NULL,   -- PRECIO, PROMOCION
    product_id       BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    product_name     VARCHAR(160) NOT NULL,
    current_price    NUMERIC(14,2),
    suggested_price  NUMERIC(14,2),
    discount_pct     NUMERIC(5,2),
    current_margin   NUMERIC(6,1),
    expected_margin  NUMERIC(6,1),
    reason           VARCHAR(600) NOT NULL,
    source           VARCHAR(12) NOT NULL DEFAULT 'REGLAS',  -- REGLAS, ASISTENTE
    status           VARCHAR(12) NOT NULL DEFAULT 'PENDIENTE', -- PENDIENTE, APLICADA, DESCARTADA
    created_by       VARCHAR(120),
    decided_by       VARCHAR(120),
    decided_at       TIMESTAMPTZ,
    decision_note    VARCHAR(300),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_ai_reco_tenant ON ai_recommendations (tenant_id, status, created_at DESC);

ALTER TABLE store_settings
    ADD COLUMN ai_enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN ai_daily_limit  INT NOT NULL DEFAULT 60;
