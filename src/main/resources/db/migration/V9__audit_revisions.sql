create table audit_revision (
    id uuid primary key,
    ledger_id uuid not null references ledger (id),
    aggregate_type varchar(64) not null,
    aggregate_id uuid not null,
    revision integer not null,
    action varchar(64) not null,
    actor_type varchar(32) not null default 'USER',
    actor_id uuid not null references app_user (id),
    reason varchar(1000),
    before_data jsonb,
    after_data jsonb,
    trace_id varchar(128),
    created_at timestamptz not null default now(),
    constraint uk_audit_revision_aggregate unique (ledger_id, aggregate_type, aggregate_id, revision)
);

create index ix_audit_revision_aggregate on audit_revision (ledger_id, aggregate_type, aggregate_id, created_at);
