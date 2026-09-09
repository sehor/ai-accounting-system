create table voucher_renumber_operation (
    id uuid primary key,
    ledger_id uuid not null references ledger (id),
    period_id uuid not null,
    actor_id uuid not null references app_user (id),
    idempotency_key varchar(128) not null,
    request_hash varchar(64) not null,
    mode varchar(32) not null check (mode in ('NUMBER_ORDER', 'DATE_ORDER')),
    start_number varchar(19) not null,
    result jsonb not null,
    created_at timestamptz not null default now(),
    foreign key (ledger_id, period_id) references accounting_period (ledger_id, id),
    unique (ledger_id, actor_id, idempotency_key)
);
