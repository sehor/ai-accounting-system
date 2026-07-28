create table dimension_type (
    id uuid primary key,
    ledger_id uuid not null references ledger (id),
    code varchar(64) not null,
    name varchar(200) not null,
    required boolean not null default false,
    status varchar(32) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    constraint uk_dimension_type_ledger_id unique (ledger_id, id),
    constraint uk_dimension_type_ledger_code unique (ledger_id, code),
    constraint ck_dimension_type_status check (status in ('ACTIVE', 'INACTIVE'))
);

create table dimension_value (
    id uuid primary key,
    ledger_id uuid not null,
    dimension_type_id uuid not null,
    code varchar(64) not null,
    name varchar(200) not null,
    status varchar(32) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    constraint uk_dimension_value_ledger_id unique (ledger_id, id),
    constraint uk_dimension_value_type_code unique (ledger_id, dimension_type_id, code),
    constraint fk_dimension_value_type foreign key (ledger_id, dimension_type_id)
        references dimension_type (ledger_id, id),
    constraint ck_dimension_value_status check (status in ('ACTIVE', 'INACTIVE'))
);

create index ix_dimension_value_type_status on dimension_value (ledger_id, dimension_type_id, status);
