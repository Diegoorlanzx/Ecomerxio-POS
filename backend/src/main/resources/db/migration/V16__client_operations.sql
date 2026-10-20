-- Sin internet (S2): cada operación que la app manda con "Idempotency-Key" queda registrada con su respuesta, en la
-- MISMA transacción que la operación. Si la app la reenvía (apagón, red cortada, reintento), se devuelve la respuesta
-- guardada en vez de hacerla dos veces.
CREATE TABLE client_operations (
    tenant_id     BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    client_ref    VARCHAR(80) NOT NULL,
    method        VARCHAR(10) NOT NULL,
    path          VARCHAR(300) NOT NULL,
    status        INTEGER,
    content_type  VARCHAR(120),
    response      TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, client_ref)
);
CREATE INDEX ix_client_operations_created ON client_operations (created_at);
