-- Fase 4: parámetros de analítica y rentabilidad.
ALTER TABLE store_settings
    -- Un producto "va en caída" si sus ventas bajan más de este % frente al período anterior equivalente.
    ADD COLUMN declining_pct      NUMERIC(5,2) NOT NULL DEFAULT 20,
    -- Margen bajo / alto para clasificar productos (estrella, volumen con poco margen, nicho rentable).
    ADD COLUMN low_margin_pct     NUMERIC(5,2) NOT NULL DEFAULT 15,
    ADD COLUMN high_margin_pct    NUMERIC(5,2) NOT NULL DEFAULT 30,
    -- Cuántos productos mostrar en los rankings.
    ADD COLUMN ranking_size       INT NOT NULL DEFAULT 10,
    -- Repartir los gastos operativos entre productos (según sus ventas) para la rentabilidad después de costos.
    ADD COLUMN allocate_expenses  BOOLEAN NOT NULL DEFAULT TRUE;

CREATE INDEX IF NOT EXISTS ix_sales_tenant_completed ON sales (tenant_id, completed_at) WHERE status = 'PAGADA';
