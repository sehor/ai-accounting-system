create table period_closing_setting (
    ledger_id uuid primary key references ledger (id),
    profit_account_id uuid,
    retained_earnings_account_id uuid,
    version bigint not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint fk_period_closing_profit_account foreign key (ledger_id, profit_account_id)
        references ledger_account (ledger_id, id),
    constraint fk_period_closing_retained_account foreign key (ledger_id, retained_earnings_account_id)
        references ledger_account (ledger_id, id)
);

create table period_closing_step (
    id uuid primary key,
    ledger_id uuid not null,
    period_id uuid not null,
    step_type varchar(40) not null,
    status varchar(20) not null default 'PENDING',
    amount numeric(19, 2) not null default 0,
    input_fingerprint varchar(128),
    voucher_id uuid,
    blocker_code varchar(80),
    blocker_detail varchar(2000),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uk_period_closing_step unique (ledger_id, period_id, step_type),
    constraint fk_period_closing_step_period foreign key (ledger_id, period_id)
        references accounting_period (ledger_id, id) on delete cascade,
    constraint fk_period_closing_step_voucher foreign key (ledger_id, voucher_id)
        references voucher (ledger_id, id),
    constraint ck_period_closing_step_type check (step_type in (
        'DEPRECIATION', 'EXPENSE_TRANSFER', 'REVENUE_TRANSFER', 'YEAR_END_PROFIT_TRANSFER')),
    constraint ck_period_closing_step_status check (status in (
        'NOT_REQUIRED', 'PENDING', 'GENERATED', 'STALE', 'BLOCKED'))
);

create index ix_period_closing_step_period on period_closing_step (ledger_id, period_id, status);
