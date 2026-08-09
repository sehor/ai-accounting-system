create table accounting_experience (
    id uuid primary key,
    scope varchar(16) not null,
    ledger_id uuid references ledger (id),
    title varchar(200) not null,
    content text not null,
    tags text[] not null default '{}',
    status varchar(16) not null default 'ACTIVE',
    version bigint not null default 0,
    created_by uuid not null references app_user (id),
    updated_by uuid not null references app_user (id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_accounting_experience_scope check (scope in ('GENERAL', 'LEDGER')),
    constraint ck_accounting_experience_scope_ledger check (
        (scope = 'GENERAL' and ledger_id is null) or (scope = 'LEDGER' and ledger_id is not null)
    ),
    constraint ck_accounting_experience_status check (status in ('ACTIVE', 'ARCHIVED')),
    constraint ck_accounting_experience_tags check (cardinality(tags) <= 20)
);

create index ix_accounting_experience_scope_status_updated
    on accounting_experience (scope, status, updated_at desc, id);

create index ix_accounting_experience_ledger_status_updated
    on accounting_experience (ledger_id, status, updated_at desc, id);

create index ix_accounting_experience_tags on accounting_experience using gin (tags);
