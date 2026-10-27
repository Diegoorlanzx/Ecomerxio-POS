-- Pago de la suscripción de Ecomerxio POS con el checkout de Wompi (misma cuenta de la plataforma que Ecomerxio).
-- Sin cobro automático: cada pago deja la tienda activa hasta paid_until; al vencer, el dueño paga de nuevo.
ALTER TABLE tenants ADD COLUMN paid_until TIMESTAMPTZ;
ALTER TABLE tenants ADD COLUMN billing_period VARCHAR(10);

-- Historial de la suscripción: checkouts iniciados (la referencia que emitimos), pagos aplicados y rechazos.
CREATE TABLE subscription_events (
    id         BIGSERIAL PRIMARY KEY,
    tenant_id  BIGINT       NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    type       VARCHAR(30)  NOT NULL,
    metadata   VARCHAR(500),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_subscription_events_tenant ON subscription_events (tenant_id, created_at DESC);
CREATE INDEX idx_subscription_events_type ON subscription_events (type, created_at);
