-- Fase 11 — Servicios: catálogo de servicios, profesionales con horario, agenda de citas (también desde la tienda en
-- línea), comisiones liquidadas como nómina y órdenes de servicio técnico con repuestos y anticipo.

-- Un servicio es un producto sin inventario con duración. Su comisión propia (si la tiene) manda sobre la del profesional.
alter table products add column service_minutes int;
alter table products add column commission_pct numeric(5,2);

create table staff_members (
    id bigserial primary key,
    tenant_id bigint not null references tenants(id) on delete cascade,
    user_id bigint,
    name varchar(80) not null,
    phone varchar(30),
    color varchar(9) not null default '#1e66f5',
    commission_service_pct numeric(5,2) not null default 0,
    commission_product_pct numeric(5,2) not null default 0,
    -- Qué servicios hace (ids de productos separados por coma; vacío = todos).
    service_ids varchar(1000),
    online_booking boolean not null default true,
    sort_order int not null default 0,
    active boolean not null default true,
    created_at timestamptz not null default now()
);
create index ix_staff_tenant on staff_members(tenant_id);

-- Horario semanal (1 = lunes … 7 = domingo); varias franjas por día (mañana y tarde).
create table staff_hours (
    id bigserial primary key,
    tenant_id bigint not null references tenants(id) on delete cascade,
    staff_id bigint not null references staff_members(id) on delete cascade,
    weekday int not null check (weekday between 1 and 7),
    start_time time not null,
    end_time time not null
);
create index ix_staff_hours on staff_hours(staff_id);

create table appointments (
    id bigserial primary key,
    tenant_id bigint not null references tenants(id) on delete cascade,
    staff_id bigint not null references staff_members(id),
    customer_id bigint,
    customer_name varchar(120) not null,
    customer_phone varchar(30),
    service_id bigint not null,
    service_name varchar(160) not null,
    price numeric(14,2) not null default 0,
    start_at timestamptz not null,
    end_at timestamptz not null,
    -- AGENDADA, CONFIRMADA, ATENDIDA, NO_ASISTIO, CANCELADA
    status varchar(12) not null default 'AGENDADA',
    -- CAJA (la agendó la tienda) u ONLINE (el cliente desde la tienda en línea)
    source varchar(10) not null default 'CAJA',
    notes varchar(500),
    sale_id bigint,
    reminder_sent_at timestamptz,
    created_by varchar(120),
    created_at timestamptz not null default now()
);
create index ix_appointments_day on appointments(tenant_id, start_at);
create index ix_appointments_staff on appointments(staff_id, start_at);

-- Quién atendió (comisiones): la venta completa o una línea puntual.
alter table sales add column staff_id bigint;
alter table sale_lines add column staff_id bigint;

-- Pagos de comisiones: cada liquidación queda como gasto de nómina en finanzas.
create table staff_payouts (
    id bigserial primary key,
    tenant_id bigint not null references tenants(id) on delete cascade,
    staff_id bigint not null references staff_members(id),
    period_from date not null,
    period_to date not null,
    base_amount numeric(14,2) not null,
    amount numeric(14,2) not null,
    finance_entry_id bigint,
    paid_by varchar(120),
    created_at timestamptz not null default now()
);
create index ix_staff_payouts on staff_payouts(tenant_id, staff_id);

-- Órdenes de servicio técnico (celulares, electrodomésticos, motos…).
create table service_orders (
    id bigserial primary key,
    tenant_id bigint not null references tenants(id) on delete cascade,
    number bigint not null,
    customer_id bigint,
    customer_name varchar(120) not null,
    customer_phone varchar(30),
    device varchar(120) not null,
    brand varchar(60),
    model varchar(80),
    serial varchar(80),
    problem varchar(1000) not null,
    accessories varchar(300),
    condition_notes varchar(500),
    diagnosis varchar(1000),
    labor_price numeric(14,2) not null default 0,
    -- SIN_COTIZAR, PENDIENTE, APROBADA, RECHAZADA
    quote_status varchar(12) not null default 'SIN_COTIZAR',
    -- RECIBIDO, DIAGNOSTICO, ESPERANDO_APROBACION, EN_REPARACION, LISTO, ENTREGADO, CANCELADO
    status varchar(22) not null default 'RECIBIDO',
    technician_id bigint,
    warranty_days int not null default 30,
    promised_at date,
    sale_id bigint,
    delivered_at timestamptz,
    created_by varchar(120),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (tenant_id, number)
);
create index ix_service_orders on service_orders(tenant_id, status);

create table service_order_parts (
    id bigserial primary key,
    tenant_id bigint not null references tenants(id) on delete cascade,
    order_id bigint not null references service_orders(id) on delete cascade,
    presentation_id bigint not null,
    name varchar(200) not null,
    quantity numeric(14,3) not null,
    unit_price numeric(14,2) not null
);
create index ix_service_order_parts on service_order_parts(order_id);

-- Anticipos: entran a la caja (o como pago digital) el día en que se reciben; al entregar se descuentan del cobro.
create table service_order_deposits (
    id bigserial primary key,
    tenant_id bigint not null references tenants(id) on delete cascade,
    order_id bigint not null references service_orders(id) on delete cascade,
    amount numeric(14,2) not null,
    method varchar(20) not null,
    reference varchar(80),
    shift_id bigint,
    user_name varchar(120),
    created_at timestamptz not null default now()
);
create index ix_service_order_deposits on service_order_deposits(order_id);

create table service_order_events (
    id bigserial primary key,
    tenant_id bigint not null references tenants(id) on delete cascade,
    order_id bigint not null references service_orders(id) on delete cascade,
    status varchar(22) not null,
    note varchar(500),
    user_name varchar(120),
    created_at timestamptz not null default now()
);
create index ix_service_order_events on service_order_events(order_id);

alter table store_settings add column booking_enabled boolean not null default false;
alter table store_settings add column booking_slot_minutes int not null default 30;
