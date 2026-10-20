-- Abonos de fiado por medios digitales: quedan como pagos "por verificar" igual que las ventas digitales (se confirman
-- en la app del banco o con el extracto). Si se rechazan, el abono se reversa y la deuda del cliente vuelve a subir.
ALTER TABLE payments ALTER COLUMN sale_id DROP NOT NULL;
ALTER TABLE payments ADD COLUMN credit_entry_id BIGINT REFERENCES credit_entries(id) ON DELETE CASCADE;
ALTER TABLE payments ADD CONSTRAINT ck_payment_origin CHECK (sale_id IS NOT NULL OR credit_entry_id IS NOT NULL);
CREATE INDEX ix_payments_credit_entry ON payments (credit_entry_id) WHERE credit_entry_id IS NOT NULL;
