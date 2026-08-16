-- Dimension combinations are immutable accounting facts.  The canonical key is
-- the authoritative uniqueness boundary; dimension_key is only its MD5 fingerprint.
create table dimension_combination (
    id uuid primary key,
    ledger_id uuid not null references ledger (id),
    kind varchar(32) not null,
    canonical_key text not null,
    dimension_key varchar(32) not null,
    created_at timestamptz not null default now(),
    constraint uk_dimension_combination_ledger_id unique (ledger_id, id),
    constraint uk_dimension_combination_canonical_key unique (ledger_id, canonical_key),
    constraint ck_dimension_combination_kind check (kind in ('STRUCTURED', 'LEGACY_UNMAPPED')),
    constraint ck_dimension_combination_fingerprint check (dimension_key = md5(canonical_key))
);

create table dimension_combination_member (
    ledger_id uuid not null,
    combination_id uuid not null,
    dimension_type_id uuid not null,
    dimension_value_id uuid not null,
    dimension_type_code varchar(64) not null,
    dimension_type_name varchar(200) not null,
    dimension_value_code varchar(64) not null,
    dimension_value_name varchar(200) not null,
    primary key (ledger_id, combination_id, dimension_type_id),
    constraint uk_dimension_combination_member_value
        unique (ledger_id, combination_id, dimension_type_id, dimension_value_id),
    constraint fk_dimension_combination_member_combination
        foreign key (ledger_id, combination_id)
        references dimension_combination (ledger_id, id) on delete restrict,
    constraint fk_dimension_combination_member_type
        foreign key (ledger_id, dimension_type_id)
        references dimension_type (ledger_id, id),
    constraint fk_dimension_combination_member_value
        foreign key (ledger_id, dimension_type_id, dimension_value_id)
        references dimension_value (ledger_id, dimension_type_id, id),
    constraint ck_dimension_combination_member_snapshot check (
        btrim(dimension_type_code) <> '' and btrim(dimension_type_name) <> ''
        and btrim(dimension_value_code) <> '' and btrim(dimension_value_name) <> '')
);

create index ix_dimension_combination_member_value
    on dimension_combination_member (ledger_id, dimension_type_id, dimension_value_id, combination_id);

alter table voucher_line
    add constraint uk_voucher_line_ledger_id unique (ledger_id, id),
    add column dimension_combination_id uuid;

alter table opening_balance
    add constraint uk_opening_balance_ledger_id unique (ledger_id, id),
    add column dimension_combination_id uuid;

-- Structured voucher dimensions are converted to a sorted UUID representation.
-- Empty dimension lists deliberately map to the single empty structured combination.
with voucher_keys as (
    select line.id line_id, line.ledger_id,
        'v1;' || coalesce((
            select string_agg(
                member.dimension_type_id::text || '=' || member.dimension_value_id::text,
                ';' order by member.dimension_type_id::text) || ';'
            from voucher_line_dimension member
            where member.ledger_id = line.ledger_id and member.voucher_line_id = line.id
        ), '') canonical_key
    from voucher_line line
), opening_keys as (
    select balance.id opening_balance_id, balance.ledger_id,
        case when balance.dimension_key = '' then 'v1;'
            else 'legacy-v1;' || balance.dimension_key end canonical_key,
        case when balance.dimension_key = '' then 'STRUCTURED' else 'LEGACY_UNMAPPED' end kind
    from opening_balance balance
), all_keys as (
    select ledger_id, canonical_key, 'STRUCTURED'::varchar kind from voucher_keys
    union
    select ledger_id, canonical_key, kind from opening_keys
)
insert into dimension_combination (id, ledger_id, kind, canonical_key, dimension_key)
select (
        substr(md5('dimension-combination:' || ledger_id::text || ':' || canonical_key), 1, 8) || '-' ||
        substr(md5('dimension-combination:' || ledger_id::text || ':' || canonical_key), 9, 4) || '-' ||
        substr(md5('dimension-combination:' || ledger_id::text || ':' || canonical_key), 13, 4) || '-' ||
        substr(md5('dimension-combination:' || ledger_id::text || ':' || canonical_key), 17, 4) || '-' ||
        substr(md5('dimension-combination:' || ledger_id::text || ':' || canonical_key), 21, 12)
    )::uuid,
    ledger_id, kind, canonical_key, md5(canonical_key)
from all_keys
on conflict (ledger_id, canonical_key) do nothing;

with voucher_keys as (
    select line.id line_id, line.ledger_id,
        'v1;' || coalesce((
            select string_agg(
                member.dimension_type_id::text || '=' || member.dimension_value_id::text,
                ';' order by member.dimension_type_id::text) || ';'
            from voucher_line_dimension member
            where member.ledger_id = line.ledger_id and member.voucher_line_id = line.id
        ), '') canonical_key
    from voucher_line line
)
insert into dimension_combination_member (
    ledger_id, combination_id, dimension_type_id, dimension_value_id,
    dimension_type_code, dimension_type_name, dimension_value_code, dimension_value_name)
select member.ledger_id, combination.id, member.dimension_type_id, member.dimension_value_id,
    dimension_type.code, dimension_type.name, dimension_value.code, dimension_value.name
from voucher_line_dimension member
join voucher_keys key on key.ledger_id = member.ledger_id and key.line_id = member.voucher_line_id
join dimension_combination combination
  on combination.ledger_id = key.ledger_id and combination.canonical_key = key.canonical_key
join dimension_type dimension_type
  on dimension_type.ledger_id = member.ledger_id and dimension_type.id = member.dimension_type_id
join dimension_value dimension_value
  on dimension_value.ledger_id = member.ledger_id
 and dimension_value.dimension_type_id = member.dimension_type_id
 and dimension_value.id = member.dimension_value_id
on conflict (ledger_id, combination_id, dimension_type_id) do nothing;

with voucher_keys as (
    select line.id line_id, line.ledger_id,
        'v1;' || coalesce((
            select string_agg(
                member.dimension_type_id::text || '=' || member.dimension_value_id::text,
                ';' order by member.dimension_type_id::text) || ';'
            from voucher_line_dimension member
            where member.ledger_id = line.ledger_id and member.voucher_line_id = line.id
        ), '') canonical_key
    from voucher_line line
)
update voucher_line line
set dimension_combination_id = combination.id
from voucher_keys key
join dimension_combination combination
  on combination.ledger_id = key.ledger_id and combination.canonical_key = key.canonical_key
where line.ledger_id = key.ledger_id and line.id = key.line_id;

with opening_keys as (
    select balance.id opening_balance_id, balance.ledger_id,
        case when balance.dimension_key = '' then 'v1;'
            else 'legacy-v1;' || balance.dimension_key end canonical_key
    from opening_balance balance
)
update opening_balance balance
set dimension_combination_id = combination.id
from opening_keys key
join dimension_combination combination
  on combination.ledger_id = key.ledger_id and combination.canonical_key = key.canonical_key
where balance.ledger_id = key.ledger_id and balance.id = key.opening_balance_id;

alter table voucher_line
    add constraint fk_voucher_line_dimension_combination
        foreign key (ledger_id, dimension_combination_id)
        references dimension_combination (ledger_id, id);

alter table opening_balance
    add constraint fk_opening_balance_dimension_combination
        foreign key (ledger_id, dimension_combination_id)
        references dimension_combination (ledger_id, id),
    drop constraint uk_opening_balance_key,
    add constraint uk_opening_balance_combination_key
        unique (ledger_id, period_id, account_id, currency, dimension_combination_id);

-- The legacy dimension_key remains readable for old opening-balance clients.
-- Pointers remain nullable during the rolling release and archive-restore window;
-- a later migration makes them mandatory after all writers dual-write combinations.
-- Structured opening balance members are introduced for all future writes.
create table opening_balance_dimension (
    ledger_id uuid not null,
    opening_balance_id uuid not null,
    dimension_combination_id uuid not null,
    dimension_type_id uuid not null,
    dimension_value_id uuid not null,
    primary key (ledger_id, opening_balance_id, dimension_type_id),
    constraint fk_opening_balance_dimension_opening_balance
        foreign key (ledger_id, opening_balance_id)
        references opening_balance (ledger_id, id) on delete cascade,
    constraint fk_opening_balance_dimension_combination
        foreign key (ledger_id, dimension_combination_id)
        references dimension_combination (ledger_id, id),
    constraint fk_opening_balance_dimension_member
        foreign key (ledger_id, dimension_combination_id, dimension_type_id, dimension_value_id)
        references dimension_combination_member (
            ledger_id, combination_id, dimension_type_id, dimension_value_id)
);

create index ix_opening_balance_dimension_value
    on opening_balance_dimension (ledger_id, dimension_type_id, dimension_value_id, opening_balance_id);

-- This projection is intentionally independent from account_period_balance.
-- Directional values may be negative; only simultaneous debit/credit values are invalid.
create table dimension_period_balance (
    ledger_id uuid not null,
    period_id uuid not null,
    account_id uuid not null,
    dimension_combination_id uuid not null,
    currency varchar(3) not null,
    opening_debit_original numeric(19, 4) not null default 0,
    opening_credit_original numeric(19, 4) not null default 0,
    period_debit_original numeric(19, 4) not null default 0,
    period_credit_original numeric(19, 4) not null default 0,
    closing_debit_original numeric(19, 4) not null default 0,
    closing_credit_original numeric(19, 4) not null default 0,
    opening_debit_base numeric(19, 2) not null default 0,
    opening_credit_base numeric(19, 2) not null default 0,
    period_debit_base numeric(19, 2) not null default 0,
    period_credit_base numeric(19, 2) not null default 0,
    operating_debit_base numeric(19, 2) not null default 0,
    operating_credit_base numeric(19, 2) not null default 0,
    closing_debit_base numeric(19, 2) not null default 0,
    closing_credit_base numeric(19, 2) not null default 0,
    finalized_at timestamptz,
    version bigint not null default 0,
    updated_at timestamptz not null default now(),
    primary key (ledger_id, period_id, account_id, dimension_combination_id, currency),
    constraint fk_dimension_period_balance_period foreign key (ledger_id, period_id)
        references accounting_period (ledger_id, id),
    constraint fk_dimension_period_balance_account foreign key (ledger_id, account_id)
        references ledger_account (ledger_id, id),
    constraint fk_dimension_period_balance_combination foreign key (ledger_id, dimension_combination_id)
        references dimension_combination (ledger_id, id),
    constraint ck_dimension_period_balance_currency check (currency ~ '^[A-Z]{3}$'),
    constraint ck_dimension_period_balance_original_directions check (
        (opening_debit_original = 0 or opening_credit_original = 0)
        and (closing_debit_original = 0 or closing_credit_original = 0)),
    constraint ck_dimension_period_balance_base_directions check (
        (opening_debit_base = 0 or opening_credit_base = 0)
        and (closing_debit_base = 0 or closing_credit_base = 0))
);

create index ix_dimension_period_balance_period
    on dimension_period_balance (ledger_id, period_id, account_id, dimension_combination_id);
