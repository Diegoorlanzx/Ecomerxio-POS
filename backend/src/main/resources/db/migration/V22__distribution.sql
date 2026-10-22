-- Fase 12 — Distribución: listas de precios, rutas con día de visita, preventa (también sin internet), alistamiento,
-- despacho con remisión, entrega total o parcial, cobro en ruta y cierre de ruta con cuadre del dinero.

create table price_lists (
    id bigserial primary key,
    tenant_id bigint not null references tenants(id) on delete cascade,
    name varchar(80) not null,
    -- Descuento general sobre el precio de venta para lo que no tenga precio propio en la lista.
    discount_pct numeric(5,2) not null default 0,
    active boolean not null default true,
    created_at timestamptz not null default now()
);
create index ix_price_lists on price_lists(tenant_id);

-- Precio por presentación; varias filas con distinta cantidad mínima = descuento por volumen.
create table price_list_items (
    id bigserial primary key,
    tenant_id bigint not null references tenants(id) on delete cascade,
    list_id bigint not null references price_lists(id) on delete cascade,
    presentation_id bigint not null,
    min_qty numeric(14,3) not null default 1,
    price numeric(14,2) not null,
    unique (list_id, presentation_id, min_qty)
);
create index ix_price_list_items on price_list_items(list_id);

create table routes (
    id bigserial primary key,
    tenant_id bigint not null references tenants(id) on delete cascade,
    name varchar(80) not null,
    seller_user_id bigint,
    seller_name varchar(120),
    -- Días de visita: 1 = lunes … 7 = domingo, separados por coma.
    visit_days varchar(20) not null default '',
    commission_pct numeric(5,2) not null default 0,
    active boolean not null default true,
    created_at timestamptz not null default now()
);
create index ix_routes on routes(tenant_id);

alter table customers add column price_list_id bigint;
alter table customers add column route_id bigint;
alter table customers add column route_order int not null default 0;

-- Pedido de preventa: PENDIENTE → ALISTADO → DESPACHADO → ENTREGADO / PARCIAL / DEVUELTO (o ANULADO).
create table presale_orders (
    id bigserial primary key,
    tenant_id bigint not null references tenants(id) on delete cascade,
    number bigint not null,
    client_ref varchar(80),
    customer_id bigint not null,
    customer_name varchar(120) not null,
    route_id bigint,
    seller_user_id bigint,
    seller_name varchar(120),
    status varchar(12) not null default 'PENDIENTE',
    notes varchar(500),
    total numeric(14,2) not null default 0,
    -- Cuándo lo tomó el vendedor (puede ser sin internet) y cuándo llegó al servidor.
    taken_at timestamptz not null default now(),
    synced_at timestamptz not null default now(),
    offline boolean not null default false,
    picked_at timestamptz,
    dispatch_id bigint,
    delivered_at timestamptz,
    sale_id bigint,
    delivered_total numeric(14,2) not null default 0,
    collected_total numeric(14,2) not null default 0,
    credit_total numeric(14,2) not null default 0,
    return_reason varchar(300),
    unique (tenant_id, number),
    unique (tenant_id, client_ref)
);
create index ix_presale_orders on presale_orders(tenant_id, status);

create table presale_order_lines (
    id bigserial primary key,
    tenant_id bigint not null references tenants(id) on delete cascade,
    order_id bigint not null references presale_orders(id) on delete cascade,
    presentation_id bigint not null,
    product_id bigint not null,
    product_name varchar(200) not null,
    presentation_name varchar(60),
    quantity numeric(14,3) not null,
    unit_price numeric(14,2) not null,
    delivered_qty numeric(14,3),
    line_total numeric(14,2) not null default 0
);
create index ix_presale_order_lines on presale_order_lines(order_id);

-- Visitas del vendedor (con o sin pedido): para la efectividad de la ruta.
create table route_visits (
    id bigserial primary key,
    tenant_id bigint not null references tenants(id) on delete cascade,
    route_id bigint,
    customer_id bigint not null,
    seller_user_id bigint,
    seller_name varchar(120),
    visit_date date not null,
    -- PEDIDO, NO_PIDIO, CERRADO (el negocio estaba cerrado)
    result varchar(10) not null,
    reason varchar(300),
    order_id bigint,
    client_ref varchar(80),
    created_at timestamptz not null default now(),
    unique (tenant_id, client_ref)
);
create index ix_route_visits on route_visits(tenant_id, visit_date);

-- Despacho (salida de un vehículo con pedidos) y su cierre con el cuadre del dinero recogido.
create table dispatches (
    id bigserial primary key,
    tenant_id bigint not null references tenants(id) on delete cascade,
    number bigint not null,
    route_id bigint,
    driver_name varchar(120) not null,
    vehicle varchar(60),
    status varchar(10) not null default 'EN_RUTA',
    created_by varchar(120),
    created_at timestamptz not null default now(),
    closed_at timestamptz,
    closed_by varchar(120),
    cash_expected numeric(14,2),
    cash_counted numeric(14,2),
    difference numeric(14,2),
    notes varchar(500),
    unique (tenant_id, number)
);
create index ix_dispatches on dispatches(tenant_id, status);
