alter table ledger_account add constraint uk_ledger_account_ledger_id unique (ledger_id, id);
alter table accounting_period add constraint uk_accounting_period_ledger_id unique (ledger_id, id);

create table opening_balance (
    id uuid primary key,
    ledger_id uuid not null references ledger (id),
    period_id uuid not null,
    account_id uuid not null,
    currency varchar(3) not null,
    dimension_key varchar(128) not null default '',
    debit_original numeric(19, 4) not null default 0,
    credit_original numeric(19, 4) not null default 0,
    exchange_rate numeric(19, 8) not null default 1,
    debit_base numeric(19, 2) not null default 0,
    credit_base numeric(19, 2) not null default 0,
    confirmed boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint fk_opening_balance_period foreign key (ledger_id, period_id)
        references accounting_period (ledger_id, id),
    constraint fk_opening_balance_account foreign key (ledger_id, account_id)
        references ledger_account (ledger_id, id),
    constraint uk_opening_balance_key unique (ledger_id, period_id, account_id, currency, dimension_key),
    constraint ck_opening_balance_currency check (currency ~ '^[A-Z]{3}$'),
    constraint ck_opening_balance_amounts check (
        debit_original >= 0 and credit_original >= 0 and
        debit_base >= 0 and credit_base >= 0 and
        (debit_original = 0 or credit_original = 0)),
    constraint ck_opening_balance_rate check (exchange_rate > 0)
);

create index ix_opening_balance_ledger_confirmed on opening_balance (ledger_id, confirmed);
