alter table ledger
    add column if not exists enable_cash_flow_check boolean not null default false;
