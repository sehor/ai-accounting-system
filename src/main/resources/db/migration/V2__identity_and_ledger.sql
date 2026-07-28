create table app_user (
    id uuid primary key,
    issuer varchar(512) not null,
    subject varchar(512) not null,
    display_name varchar(200),
    email varchar(320),
    status varchar(32) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    deleted_at timestamptz,
    constraint uk_app_user_issuer_subject unique (issuer, subject),
    constraint ck_app_user_status check (status in ('ACTIVE', 'INACTIVE'))
);

create table ledger (
    id uuid primary key,
    name varchar(200) not null,
    accounting_standard_code varchar(64) not null,
    accounting_standard_version varchar(64) not null,
    base_currency varchar(3) not null,
    start_date date not null,
    approval_enabled boolean not null default false,
    status varchar(32) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    created_by uuid not null references app_user (id),
    updated_at timestamptz not null default now(),
    updated_by uuid not null references app_user (id),
    version bigint not null default 0,
    deleted_at timestamptz,
    constraint ck_ledger_status check (status in ('ACTIVE', 'INACTIVE')),
    constraint ck_ledger_base_currency check (base_currency ~ '^[A-Z]{3}$')
);

create table ledger_membership (
    id uuid primary key,
    ledger_id uuid not null references ledger (id),
    user_id uuid not null references app_user (id),
    role varchar(32) not null,
    status varchar(32) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    created_by uuid not null references app_user (id),
    updated_at timestamptz not null default now(),
    updated_by uuid not null references app_user (id),
    version bigint not null default 0,
    deleted_at timestamptz,
    constraint uk_ledger_membership_ledger_user unique (ledger_id, user_id),
    constraint ck_ledger_membership_role check (role in ('OWNER', 'EDITOR', 'REVIEWER', 'VIEWER', 'AGENT')),
    constraint ck_ledger_membership_status check (status in ('ACTIVE', 'INACTIVE'))
);

create index ix_ledger_membership_user_status
    on ledger_membership (user_id, status);

create index ix_ledger_membership_ledger_status
    on ledger_membership (ledger_id, status);
