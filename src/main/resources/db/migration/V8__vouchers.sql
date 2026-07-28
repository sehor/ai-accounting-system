create table voucher (
    id uuid primary key,
    ledger_id uuid not null references ledger (id),
    period_id uuid not null,
    voucher_date date not null,
    voucher_type varchar(32) not null,
    voucher_number varchar(32) not null,
    summary varchar(1000),
    status varchar(32) not null default 'DRAFT',
    current_revision integer not null default 1,
    source_type varchar(64),
    source_id uuid,
    approval_required boolean not null default false,
    reversal_of_id uuid,
    reversed_by_id uuid,
    posted_at timestamptz,
    posted_by uuid references app_user (id),
    version bigint not null default 0,
    created_at timestamptz not null default now(),
    created_by uuid not null references app_user (id),
    updated_at timestamptz not null default now(),
    updated_by uuid not null references app_user (id),
    deleted_at timestamptz,
    constraint uk_voucher_ledger_id unique (ledger_id, id),
    constraint fk_voucher_period foreign key (ledger_id, period_id)
        references accounting_period (ledger_id, id),
    constraint ck_voucher_status check (status in ('DRAFT', 'VALIDATED', 'SUBMITTED', 'APPROVED', 'POSTED', 'DELETED', 'REVERSED'))
);

create unique index uk_voucher_business_key
    on voucher (ledger_id, period_id, voucher_type, voucher_number)
    where deleted_at is null;

create table voucher_line (
    id uuid primary key,
    ledger_id uuid not null,
    voucher_id uuid not null,
    line_no integer not null,
    account_id uuid not null,
    side varchar(8) not null,
    currency varchar(3) not null,
    original_amount numeric(19, 4) not null,
    exchange_rate numeric(19, 8) not null,
    base_amount numeric(19, 2) not null,
    summary varchar(1000),
    constraint uk_voucher_line_no unique (voucher_id, line_no),
    constraint fk_voucher_line_voucher foreign key (ledger_id, voucher_id)
        references voucher (ledger_id, id),
    constraint fk_voucher_line_account foreign key (ledger_id, account_id)
        references ledger_account (ledger_id, id),
    constraint ck_voucher_line_side check (side in ('DEBIT', 'CREDIT')),
    constraint ck_voucher_line_currency check (currency ~ '^[A-Z]{3}$'),
    constraint ck_voucher_line_amount check (original_amount > 0 and exchange_rate > 0 and base_amount > 0)
);

create index ix_voucher_ledger_status_date on voucher (ledger_id, status, voucher_date);
create index ix_voucher_line_account on voucher_line (ledger_id, account_id);

create table voucher_approval (
    id uuid primary key,
    ledger_id uuid not null references ledger (id),
    voucher_id uuid not null,
    action varchar(32) not null,
    comment varchar(1000),
    actor_id uuid not null references app_user (id),
    created_at timestamptz not null default now(),
    constraint fk_voucher_approval_voucher foreign key (ledger_id, voucher_id)
        references voucher (ledger_id, id),
    constraint ck_voucher_approval_action check (action in ('SUBMIT', 'APPROVE', 'REJECT'))
);
