create table report_formula_snapshot (
    id uuid primary key,
    ledger_id uuid not null references ledger (id),
    code varchar(64) not null,
    name varchar(200) not null,
    formula_json jsonb not null,
    created_at timestamptz not null default now(),
    constraint uk_report_formula_snapshot_ledger_code unique (ledger_id, code)
);
