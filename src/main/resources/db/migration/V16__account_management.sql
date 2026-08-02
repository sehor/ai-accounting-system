alter table ledger
    add column account_code_separator varchar(1) not null default '.',
    add column account_level2_width smallint not null default 2,
    add column account_level3_width smallint not null default 2,
    add column account_level4_width smallint not null default 2,
    add constraint ck_ledger_account_code_separator check (account_code_separator in ('.', '-')),
    add constraint ck_ledger_account_code_widths check (
        account_level2_width between 1 and 8
        and account_level3_width between 1 and 8
        and account_level4_width between 1 and 8
        and 7 + account_level2_width + account_level3_width + account_level4_width <= 32);

create table cash_flow_item (
    id uuid primary key,
    ledger_id uuid not null references ledger (id),
    code varchar(64) not null,
    name varchar(200) not null,
    status varchar(32) not null default 'ACTIVE',
    is_template boolean not null default false,
    created_at timestamptz not null default now(),
    constraint uk_cash_flow_item_ledger_id unique (ledger_id, id),
    constraint uk_cash_flow_item_ledger_code unique (ledger_id, code),
    constraint ck_cash_flow_item_status check (status in ('ACTIVE', 'INACTIVE'))
);

alter table ledger_account
    add column parent_id uuid,
    add column level smallint,
    add column is_template boolean not null default false,
    add column legacy_code boolean not null default false,
    add column cash_flow_required boolean not null default false,
    add column default_cash_flow_item_id uuid,
    add column quantity_enabled boolean not null default false,
    add column unit_name varchar(64),
    add column updated_at timestamptz not null default now(),
    add column version bigint not null default 0,
    add constraint fk_ledger_account_parent foreign key (ledger_id, parent_id)
        references ledger_account (ledger_id, id),
    add constraint fk_ledger_account_cash_flow_item foreign key (ledger_id, default_cash_flow_item_id)
        references cash_flow_item (ledger_id, id),
    add constraint ck_ledger_account_not_own_parent check (parent_id is null or parent_id <> id),
    add constraint ck_ledger_account_quantity_unit check (
        (quantity_enabled and unit_name is not null and btrim(unit_name) <> '')
        or (not quantity_enabled and unit_name is null));

update ledger_account child
set parent_id = (
    select parent.id
    from ledger_account parent
    where parent.ledger_id = child.ledger_id
      and length(parent.code) < length(child.code)
      and (child.code like parent.code || '.%' or child.code like parent.code || '-%')
    order by length(parent.code) desc
    limit 1
);

update ledger_account
set level = 1
where parent_id is null;

do $$
begin
    for pass in 1..3 loop
        update ledger_account child
        set level = parent.level + 1
        from ledger_account parent
        where child.parent_id = parent.id
          and child.ledger_id = parent.ledger_id
          and child.level is null
          and parent.level is not null
          and parent.level < 4;
    end loop;
end $$;

update ledger_account
set parent_id = null, level = 1, legacy_code = true
where level is null;

update ledger_account
set legacy_code = true
where code !~ '^[0-9]{4}([.-][0-9]{2}){0,3}$';

alter table ledger_account
    alter column level set not null,
    add constraint ck_ledger_account_level check (level between 1 and 4);

create index ix_ledger_account_parent on ledger_account (ledger_id, parent_id);
create index ix_ledger_account_usage on ledger_account (ledger_id, id, status);

create table ledger_account_dimension (
    account_id uuid not null,
    ledger_id uuid not null,
    dimension_type_id uuid not null,
    required boolean not null default false,
    created_at timestamptz not null default now(),
    primary key (account_id, dimension_type_id),
    constraint fk_ledger_account_dimension_account foreign key (ledger_id, account_id)
        references ledger_account (ledger_id, id) on delete cascade,
    constraint fk_ledger_account_dimension_type foreign key (ledger_id, dimension_type_id)
        references dimension_type (ledger_id, id)
);

create index ix_ledger_account_dimension_type
    on ledger_account_dimension (ledger_id, dimension_type_id);

alter table voucher_line
    add column cash_flow_item_id uuid,
    add column quantity numeric(19, 6),
    add column unit_price numeric(19, 8),
    add constraint fk_voucher_line_cash_flow_item foreign key (ledger_id, cash_flow_item_id)
        references cash_flow_item (ledger_id, id),
    add constraint ck_voucher_line_quantity_price check (
        (quantity is null and unit_price is null)
        or (quantity > 0 and unit_price > 0));

create table voucher_line_dimension (
    voucher_line_id uuid not null references voucher_line (id) on delete cascade,
    ledger_id uuid not null,
    dimension_type_id uuid not null,
    dimension_value_id uuid not null,
    primary key (voucher_line_id, dimension_type_id),
    constraint fk_voucher_line_dimension_type foreign key (ledger_id, dimension_type_id)
        references dimension_type (ledger_id, id),
    constraint fk_voucher_line_dimension_value foreign key (ledger_id, dimension_value_id)
        references dimension_value (ledger_id, id)
);

create index ix_voucher_line_dimension_value
    on voucher_line_dimension (ledger_id, dimension_value_id);

create table account_import (
    id uuid primary key,
    ledger_id uuid not null references ledger (id),
    format varchar(16) not null,
    status varchar(16) not null default 'PREVIEW',
    ledger_version bigint not null,
    original_filename varchar(255) not null,
    content_sha256 varchar(64) not null,
    row_count integer not null default 0,
    error_count integer not null default 0,
    ai_status varchar(16) not null default 'NOT_CONFIGURED',
    created_at timestamptz not null default now(),
    created_by uuid not null references app_user (id),
    updated_at timestamptz not null default now(),
    constraint uk_account_import_idempotency unique (ledger_id, content_sha256),
    constraint ck_account_import_format check (format in ('STANDARD', 'KINGDEE')),
    constraint ck_account_import_status check (status in ('PREVIEW', 'COMMITTED')),
    constraint ck_account_import_ai_status check (
        ai_status in ('NOT_CONFIGURED', 'PENDING', 'READY', 'FAILED'))
);

create table account_import_row (
    import_id uuid not null references account_import (id) on delete cascade,
    row_no integer not null,
    raw_data jsonb not null,
    cleaned_data jsonb not null,
    account_code varchar(32),
    target_account_id uuid,
    expected_account_version bigint,
    action varchar(16),
    confirmed boolean not null default false,
    confidence numeric(5, 4),
    issues jsonb not null default '[]'::jsonb,
    primary key (import_id, row_no),
    constraint ck_account_import_row_action check (
        action is null or action in ('CREATE', 'UPDATE', 'MAP', 'SKIP')),
    constraint ck_account_import_row_confidence check (
        confidence is null or confidence between 0 and 1)
);

create index ix_account_import_ledger_created
    on account_import (ledger_id, created_at desc);
