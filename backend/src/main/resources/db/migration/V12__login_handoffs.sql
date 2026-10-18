-- Traspaso de sesión desde el login de ecomerxio.com: código de un solo uso que vence en 60 s.
-- Solo se guarda el hash SHA-256 del código.
CREATE TABLE login_handoffs (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash   VARCHAR(64) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_login_handoffs_expires ON login_handoffs (expires_at);
