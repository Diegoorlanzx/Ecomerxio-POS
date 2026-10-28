-- Tipos de negocio, fase 4. Cafetería, heladería o comidas rápidas (módulo MOSTRADOR): cada venta recibe un número de
-- turno del día y pasa por PREPARANDO → LISTO → ENTREGADO en la pantalla de pedidos.
CREATE TABLE counter_orders (
    sale_id       BIGINT       PRIMARY KEY REFERENCES sales (id) ON DELETE CASCADE,
    tenant_id     BIGINT       NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    day           DATE         NOT NULL,
    number        INT          NOT NULL,
    status        VARCHAR(12)  NOT NULL DEFAULT 'PREPARANDO',
    customer_name VARCHAR(120),
    items         VARCHAR(600),
    ready_at      TIMESTAMPTZ,
    delivered_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_counter_orders_day_number ON counter_orders (tenant_id, day, number);
CREATE INDEX idx_counter_orders_status ON counter_orders (tenant_id, day, status);

-- Taller: historial por placa (o serial) del vehículo o equipo.
CREATE INDEX idx_service_orders_serial ON service_orders (tenant_id, lower(serial));
