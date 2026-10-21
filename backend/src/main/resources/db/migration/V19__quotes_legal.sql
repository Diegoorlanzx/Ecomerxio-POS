-- Cotizaciones y prefacturas (documentos comerciales previos a la venta) y aceptación de los documentos legales.

-- Una sola tabla para los dos tipos: comparten líneas, cliente y totales; cada tipo lleva su propio consecutivo
-- (COT-1, PF-1) que nunca consume la numeración autorizada por la DIAN.
CREATE TABLE quotes (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    kind              VARCHAR(12) NOT NULL,   -- COTIZACION | PREFACTURA
    number            BIGINT NOT NULL,
    -- Cotización: BORRADOR, ENVIADA, ACEPTADA, RECHAZADA, CONVERTIDA, COBRADA, ANULADA (VENCIDA se calcula).
    -- Prefactura: BORRADOR, APROBADA, FACTURADA, ANULADA.
    status            VARCHAR(12) NOT NULL,
    customer_id       BIGINT REFERENCES customers(id) ON DELETE SET NULL,
    -- Copia de los datos del cliente tal como se cotizaron / se van a facturar.
    customer_name     VARCHAR(160),
    customer_doc_type VARCHAR(10),
    customer_doc      VARCHAR(30),
    customer_dv       VARCHAR(1),
    customer_person_type VARCHAR(10),
    customer_email    VARCHAR(160),
    customer_phone    VARCHAR(30),
    customer_address  VARCHAR(200),
    customer_municipality VARCHAR(5),
    issue_date        DATE NOT NULL,
    valid_until       DATE,
    notes             VARCHAR(1000),
    terms             VARCHAR(2000),
    sale_discount_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
    subtotal          NUMERIC(14,2) NOT NULL DEFAULT 0,
    discount_total    NUMERIC(14,2) NOT NULL DEFAULT 0,
    tax_total         NUMERIC(14,2) NOT NULL DEFAULT 0,
    total             NUMERIC(14,2) NOT NULL DEFAULT 0,
    prices_include_tax BOOLEAN NOT NULL DEFAULT TRUE,
    source_id         BIGINT REFERENCES quotes(id) ON DELETE SET NULL,   -- la cotización de la que sale la prefactura
    sale_id           BIGINT REFERENCES sales(id) ON DELETE SET NULL,
    public_token      VARCHAR(40) NOT NULL UNIQUE,
    created_by        VARCHAR(120),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at           TIMESTAMPTZ,
    -- Respuesta del cliente (enlace público) o registrada por la tienda.
    decided_at        TIMESTAMPTZ,
    decided_by        VARCHAR(160),
    decision_note     VARCHAR(500),
    decision_ip       VARCHAR(64),
    -- Prefactura: el cliente confirma (o reporta un error en) sus datos antes de facturar.
    client_check      VARCHAR(12),            -- CONFIRMADO | CON_ERROR
    client_check_note VARCHAR(500),
    client_check_at   TIMESTAMPTZ,
    approved_by       VARCHAR(120),
    approved_at       TIMESTAMPTZ,
    approved_warnings VARCHAR(2000),          -- advertencias que se aceptaron al aprobar
    cancelled_at      TIMESTAMPTZ,
    cancel_reason     VARCHAR(300),
    version           INT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, kind, number)
);
CREATE INDEX ix_quotes_tenant ON quotes (tenant_id, kind, created_at DESC);

CREATE TABLE quote_lines (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    quote_id        BIGINT NOT NULL REFERENCES quotes(id) ON DELETE CASCADE,
    product_id      BIGINT REFERENCES products(id) ON DELETE SET NULL,   -- null = línea libre (servicio, trabajo a medida)
    presentation_id BIGINT REFERENCES product_presentations(id) ON DELETE SET NULL,
    description     VARCHAR(200) NOT NULL,
    presentation_name VARCHAR(60),
    factor          NUMERIC(14,3) NOT NULL DEFAULT 1,
    quantity        NUMERIC(14,3) NOT NULL,
    unit_price      NUMERIC(14,2) NOT NULL,
    discount_pct    NUMERIC(5,2) NOT NULL DEFAULT 0,
    tax_name        VARCHAR(20),
    tax_rate        NUMERIC(5,2) NOT NULL DEFAULT 0,
    fixed_tax       NUMERIC(14,2) NOT NULL DEFAULT 0,
    gross           NUMERIC(14,2) NOT NULL DEFAULT 0,
    discount        NUMERIC(14,2) NOT NULL DEFAULT 0,
    tax             NUMERIC(14,2) NOT NULL DEFAULT 0,
    line_total      NUMERIC(14,2) NOT NULL DEFAULT 0,
    sort_order      INT NOT NULL DEFAULT 0
);
CREATE INDEX ix_quote_lines_quote ON quote_lines (quote_id, sort_order);

-- Prueba de aceptación (Ley 527 de 1999: el clic es una manifestación de voluntad por mensaje de datos).
-- Solo se agrega: nunca se edita ni se borra mientras exista la tienda.
CREATE TABLE legal_acceptances (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id     BIGINT,
    user_name   VARCHAR(160),
    email       VARCHAR(160),
    document    VARCHAR(20) NOT NULL,   -- TERMINOS | LICENCIA | SERVICIO | ENCARGO | PRIVACIDAD
    version     VARCHAR(20) NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ip          VARCHAR(64),
    user_agent  VARCHAR(300),
    context     VARCHAR(20) NOT NULL    -- REGISTRO | ACTUALIZACION | PRIMER_INGRESO
);
CREATE INDEX ix_legal_user ON legal_acceptances (user_id, document, version);
CREATE INDEX ix_legal_tenant ON legal_acceptances (tenant_id, accepted_at DESC);

-- Permisos nuevos para los roles predefinidos que ya existen (las tiendas nuevas los reciben al crearse).
INSERT INTO role_permissions (role_id, permission)
SELECT id, p FROM roles, (VALUES ('COTIZACIONES_VER'), ('COTIZACIONES_CREAR'), ('PREFACTURAS_APROBAR')) AS x(p)
WHERE system_code IN ('DUENO', 'ADMINISTRADOR')
ON CONFLICT DO NOTHING;
INSERT INTO role_permissions (role_id, permission)
SELECT id, p FROM roles, (VALUES ('COTIZACIONES_VER'), ('COTIZACIONES_CREAR')) AS x(p)
WHERE system_code IN ('SUPERVISOR', 'CAJERO')
ON CONFLICT DO NOTHING;
INSERT INTO role_permissions (role_id, permission)
SELECT id, 'COTIZACIONES_VER' FROM roles WHERE system_code IN ('CONTADOR', 'AUDITOR')
ON CONFLICT DO NOTHING;
