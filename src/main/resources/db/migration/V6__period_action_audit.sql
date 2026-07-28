create table period_action_audit (
    id uuid primary key,
    ledger_id uuid not null references ledger (id),
    period_id uuid not null,
    action varchar(32) not null,
    reason varchar(1000) not null,
    actor_id uuid not null references app_user (id),
    created_at timestamptz not null default now(),
    constraint fk_period_action_period foreign key (ledger_id, period_id)
        references accounting_period (ledger_id, id),
    constraint ck_period_action_action check (action in ('CLOSE', 'REOPEN'))
);

create index ix_period_action_audit_period on period_action_audit (ledger_id, period_id, created_at);
