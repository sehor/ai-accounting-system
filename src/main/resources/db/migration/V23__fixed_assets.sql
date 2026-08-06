create table fixed_asset_category (
    id uuid primary key,
    ledger_id uuid not null references ledger (id),
    code varchar(64) not null,
    name varchar(200) not null,
    useful_life_months integer not null,
    residual_rate numeric(7, 4) not null default 0,
    asset_account_id uuid not null,
    accumulated_depreciation_account_id uuid not null,
    depreciation_expense_account_id uuid not null,
    impairment_account_id uuid,
    clearing_account_id uuid not null,
    disposal_gain_account_id uuid not null,
    disposal_loss_account_id uuid not null,
    status varchar(32) not null default 'ACTIVE',
    version bigint not null default 0,
    created_by uuid not null references app_user (id),
    updated_by uuid not null references app_user (id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    constraint uk_fixed_asset_category_ledger_id unique (ledger_id, id),
    constraint uk_fixed_asset_category_ledger_code unique (ledger_id, code),
    constraint fk_fixed_asset_category_asset_account foreign key (ledger_id, asset_account_id)
        references ledger_account (ledger_id, id),
    constraint fk_fixed_asset_category_accumulated_account foreign key (ledger_id, accumulated_depreciation_account_id)
        references ledger_account (ledger_id, id),
    constraint fk_fixed_asset_category_expense_account foreign key (ledger_id, depreciation_expense_account_id)
        references ledger_account (ledger_id, id),
    constraint fk_fixed_asset_category_impairment_account foreign key (ledger_id, impairment_account_id)
        references ledger_account (ledger_id, id),
    constraint fk_fixed_asset_category_clearing_account foreign key (ledger_id, clearing_account_id)
        references ledger_account (ledger_id, id),
    constraint fk_fixed_asset_category_gain_account foreign key (ledger_id, disposal_gain_account_id)
        references ledger_account (ledger_id, id),
    constraint fk_fixed_asset_category_loss_account foreign key (ledger_id, disposal_loss_account_id)
        references ledger_account (ledger_id, id),
    constraint ck_fixed_asset_category_life check (useful_life_months between 1 and 1200),
    constraint ck_fixed_asset_category_residual check (residual_rate between 0 and 100),
    constraint ck_fixed_asset_category_status check (status in ('ACTIVE', 'INACTIVE'))
);

create table fixed_asset (
    id uuid primary key,
    ledger_id uuid not null references ledger (id),
    category_id uuid not null,
    code varchar(64) not null,
    name varchar(200) not null,
    quantity numeric(19, 6) not null default 1,
    service_date date not null,
    original_cost numeric(19, 2) not null,
    input_tax numeric(19, 2) not null default 0,
    useful_life_months integer not null,
    residual_rate numeric(7, 4) not null,
    opening_accumulated_depreciation numeric(19, 2) not null default 0,
    opening_depreciated_months integer not null default 0,
    impairment_amount numeric(19, 2) not null default 0,
    department_value_id uuid,
    acquisition_voucher_id uuid,
    asset_account_id uuid not null,
    accumulated_depreciation_account_id uuid not null,
    depreciation_expense_account_id uuid not null,
    impairment_account_id uuid,
    clearing_account_id uuid not null,
    disposal_gain_account_id uuid not null,
    disposal_loss_account_id uuid not null,
    status varchar(32) not null default 'ACTIVE',
    disposal_date date,
    note varchar(2000),
    version bigint not null default 0,
    created_by uuid not null references app_user (id),
    updated_by uuid not null references app_user (id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    constraint uk_fixed_asset_ledger_id unique (ledger_id, id),
    constraint uk_fixed_asset_ledger_code unique (ledger_id, code),
    constraint fk_fixed_asset_category foreign key (ledger_id, category_id)
        references fixed_asset_category (ledger_id, id),
    constraint fk_fixed_asset_department foreign key (ledger_id, department_value_id)
        references dimension_value (ledger_id, id),
    constraint fk_fixed_asset_acquisition_voucher foreign key (ledger_id, acquisition_voucher_id)
        references voucher (ledger_id, id),
    constraint fk_fixed_asset_asset_account foreign key (ledger_id, asset_account_id)
        references ledger_account (ledger_id, id),
    constraint fk_fixed_asset_accumulated_account foreign key (ledger_id, accumulated_depreciation_account_id)
        references ledger_account (ledger_id, id),
    constraint fk_fixed_asset_expense_account foreign key (ledger_id, depreciation_expense_account_id)
        references ledger_account (ledger_id, id),
    constraint fk_fixed_asset_impairment_account foreign key (ledger_id, impairment_account_id)
        references ledger_account (ledger_id, id),
    constraint fk_fixed_asset_clearing_account foreign key (ledger_id, clearing_account_id)
        references ledger_account (ledger_id, id),
    constraint fk_fixed_asset_gain_account foreign key (ledger_id, disposal_gain_account_id)
        references ledger_account (ledger_id, id),
    constraint fk_fixed_asset_loss_account foreign key (ledger_id, disposal_loss_account_id)
        references ledger_account (ledger_id, id),
    constraint ck_fixed_asset_quantity check (quantity > 0),
    constraint ck_fixed_asset_cost check (original_cost > 0 and input_tax >= 0),
    constraint ck_fixed_asset_life check (useful_life_months between 1 and 1200),
    constraint ck_fixed_asset_residual check (residual_rate between 0 and 100),
    constraint ck_fixed_asset_opening check (opening_accumulated_depreciation >= 0
        and opening_depreciated_months between 0 and useful_life_months),
    constraint ck_fixed_asset_impairment check (impairment_amount >= 0),
    constraint ck_fixed_asset_status check (status in ('ACTIVE', 'DISPOSED'))
);

create index ix_fixed_asset_ledger_status on fixed_asset (ledger_id, status, category_id);

create table fixed_asset_change (
    id uuid primary key,
    ledger_id uuid not null references ledger (id),
    asset_id uuid not null,
    effective_period_id uuid not null,
    reason varchar(1000) not null,
    before_data jsonb,
    after_data jsonb not null,
    actor_id uuid not null references app_user (id),
    created_at timestamptz not null default now(),
    constraint fk_fixed_asset_change_asset foreign key (ledger_id, asset_id)
        references fixed_asset (ledger_id, id),
    constraint fk_fixed_asset_change_period foreign key (ledger_id, effective_period_id)
        references accounting_period (ledger_id, id)
);

create table fixed_asset_depreciation_run (
    id uuid primary key,
    ledger_id uuid not null references ledger (id),
    period_id uuid not null,
    run_type varchar(32) not null,
    status varchar(32) not null default 'POSTED',
    voucher_id uuid not null,
    input_fingerprint varchar(128) not null,
    total_amount numeric(19, 2) not null,
    reason varchar(1000),
    superseded_by uuid,
    created_by uuid not null references app_user (id),
    created_at timestamptz not null default now(),
    constraint fk_fixed_asset_run_period foreign key (ledger_id, period_id)
        references accounting_period (ledger_id, id),
    constraint fk_fixed_asset_run_voucher foreign key (ledger_id, voucher_id)
        references voucher (ledger_id, id),
    constraint fk_fixed_asset_run_superseded foreign key (superseded_by)
        references fixed_asset_depreciation_run (id),
    constraint ck_fixed_asset_run_type check (run_type in ('MONTH_END', 'DISPOSAL')),
    constraint ck_fixed_asset_run_status check (status in ('POSTED', 'SUPERSEDED', 'STALE'))
);

create index ix_fixed_asset_run_period on fixed_asset_depreciation_run (ledger_id, period_id, status);

create table fixed_asset_depreciation_line (
    id uuid primary key,
    ledger_id uuid not null references ledger (id),
    run_id uuid not null references fixed_asset_depreciation_run (id),
    asset_id uuid not null,
    period_id uuid not null,
    amount numeric(19, 2) not null,
    expense_account_id uuid not null,
    accumulated_account_id uuid not null,
    department_value_id uuid,
    voucher_line_id uuid,
    status varchar(32) not null default 'ACTIVE',
    constraint fk_fixed_asset_line_asset foreign key (ledger_id, asset_id)
        references fixed_asset (ledger_id, id),
    constraint fk_fixed_asset_line_period foreign key (ledger_id, period_id)
        references accounting_period (ledger_id, id),
    constraint fk_fixed_asset_line_department foreign key (ledger_id, department_value_id)
        references dimension_value (ledger_id, id),
    constraint ck_fixed_asset_line_amount check (amount > 0),
    constraint ck_fixed_asset_line_status check (status in ('ACTIVE', 'SUPERSEDED'))
);

create unique index uk_fixed_asset_active_line
    on fixed_asset_depreciation_line (ledger_id, asset_id, period_id)
    where status = 'ACTIVE';

create table fixed_asset_disposal (
    id uuid primary key,
    ledger_id uuid not null references ledger (id),
    asset_id uuid not null,
    period_id uuid not null,
    disposal_date date not null,
    reason varchar(1000) not null,
    proceeds numeric(19, 2) not null default 0,
    output_tax numeric(19, 2) not null default 0,
    clearing_cost numeric(19, 2) not null default 0,
    clearing_input_tax numeric(19, 2) not null default 0,
    receipt_account_id uuid,
    payment_account_id uuid,
    output_tax_account_id uuid,
    input_tax_account_id uuid,
    depreciation_voucher_id uuid,
    transfer_voucher_id uuid not null,
    settlement_voucher_id uuid not null,
    created_by uuid not null references app_user (id),
    created_at timestamptz not null default now(),
    constraint uk_fixed_asset_disposal_asset unique (ledger_id, asset_id),
    constraint fk_fixed_asset_disposal_asset foreign key (ledger_id, asset_id)
        references fixed_asset (ledger_id, id),
    constraint fk_fixed_asset_disposal_period foreign key (ledger_id, period_id)
        references accounting_period (ledger_id, id),
    constraint fk_fixed_asset_disposal_receipt foreign key (ledger_id, receipt_account_id)
        references ledger_account (ledger_id, id),
    constraint fk_fixed_asset_disposal_payment foreign key (ledger_id, payment_account_id)
        references ledger_account (ledger_id, id),
    constraint fk_fixed_asset_disposal_output_tax foreign key (ledger_id, output_tax_account_id)
        references ledger_account (ledger_id, id),
    constraint fk_fixed_asset_disposal_input_tax foreign key (ledger_id, input_tax_account_id)
        references ledger_account (ledger_id, id),
    constraint ck_fixed_asset_disposal_amounts check (
        proceeds >= 0 and output_tax >= 0 and clearing_cost >= 0 and clearing_input_tax >= 0)
);

create table fixed_asset_import_batch (
    id uuid primary key,
    ledger_id uuid not null references ledger (id),
    filename varchar(255) not null,
    status varchar(32) not null default 'PREVIEW',
    row_count integer not null,
    error_count integer not null,
    created_by uuid not null references app_user (id),
    created_at timestamptz not null default now(),
    constraint ck_fixed_asset_import_status check (status in ('PREVIEW', 'COMMITTED'))
);

create table fixed_asset_import_row (
    id uuid primary key,
    ledger_id uuid not null references ledger (id),
    batch_id uuid not null references fixed_asset_import_batch (id) on delete cascade,
    row_no integer not null,
    raw_data jsonb not null,
    errors jsonb not null default '[]'::jsonb,
    parsed_data jsonb,
    constraint uk_fixed_asset_import_row unique (batch_id, row_no)
);

create index ix_fixed_asset_import_ledger on fixed_asset_import_batch (ledger_id, created_at desc);
