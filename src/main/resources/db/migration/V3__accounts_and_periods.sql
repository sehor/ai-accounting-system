create table ledger_account (
    id uuid primary key,
    ledger_id uuid not null references ledger (id),
    code varchar(32) not null,
    name varchar(200) not null,
    category varchar(32) not null,
    normal_balance varchar(8) not null,
    status varchar(32) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    constraint uk_ledger_account_ledger_code unique (ledger_id, code),
    constraint ck_ledger_account_category check (category in ('ASSET', 'LIABILITY', 'EQUITY', 'COST', 'REVENUE', 'EXPENSE')),
    constraint ck_ledger_account_normal_balance check (normal_balance in ('DEBIT', 'CREDIT')),
    constraint ck_ledger_account_status check (status in ('ACTIVE', 'INACTIVE'))
);

create index ix_ledger_account_ledger_status on ledger_account (ledger_id, status);

create table accounting_period (
    id uuid primary key,
    ledger_id uuid not null references ledger (id),
    period_code varchar(7) not null,
    start_date date not null,
    end_date date not null,
    status varchar(32) not null default 'OPEN',
    created_at timestamptz not null default now(),
    constraint uk_accounting_period_ledger_code unique (ledger_id, period_code),
    constraint ck_accounting_period_code check (period_code ~ '^[0-9]{4}-[0-9]{2}$'),
    constraint ck_accounting_period_dates check (end_date >= start_date),
    constraint ck_accounting_period_status check (status in ('OPEN', 'CLOSED'))
);

create index ix_accounting_period_ledger_status on accounting_period (ledger_id, status);
