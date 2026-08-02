create table document_idempotency (
    ledger_id uuid not null,
    actor_id uuid not null,
    idempotency_key varchar(128) not null,
    request_hash varchar(64) not null,
    document_id uuid not null,
    created_at timestamptz not null default now(),
    primary key (ledger_id, actor_id, idempotency_key)
);
