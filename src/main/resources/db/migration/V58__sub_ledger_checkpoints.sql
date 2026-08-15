create table sub_ledger_checkpoint_state (
    ledger_id uuid not null references ledger (id) on delete cascade,
    account_id uuid not null,
    period_from varchar(7) not null,
    period_to varchar(7) not null,
    dirty boolean not null default true,
    total_items bigint not null default 0,
    total_debit numeric(19, 2) not null default 0,
    total_credit numeric(19, 2) not null default 0,
    refreshed_at timestamptz,
    primary key (ledger_id, account_id, period_from, period_to),
    constraint fk_sub_ledger_checkpoint_state_account foreign key (ledger_id, account_id)
        references ledger_account (ledger_id, id) on delete cascade,
    constraint ck_sub_ledger_checkpoint_period_range check (period_from <= period_to)
);

create table sub_ledger_checkpoint (
    ledger_id uuid not null,
    account_id uuid not null,
    period_from varchar(7) not null,
    period_to varchar(7) not null,
    row_ordinal bigint not null,
    voucher_id uuid not null,
    voucher_number varchar(32) not null,
    voucher_date date not null,
    posting_account_id uuid not null,
    line_no integer not null,
    line_id uuid not null,
    summary varchar(1000) not null,
    debit numeric(19, 2) not null,
    credit numeric(19, 2) not null,
    cumulative_debit numeric(19, 2) not null,
    cumulative_credit numeric(19, 2) not null,
    primary key (ledger_id, account_id, period_from, period_to, row_ordinal),
    constraint uk_sub_ledger_checkpoint_order unique
        (ledger_id, account_id, period_from, period_to, voucher_date, voucher_number, line_no, line_id),
    constraint fk_sub_ledger_checkpoint_state foreign key (ledger_id, account_id, period_from, period_to)
        references sub_ledger_checkpoint_state (ledger_id, account_id, period_from, period_to) on delete cascade
);

create or replace function mark_sub_ledger_checkpoints_for_line() returns trigger language plpgsql as $$
declare
    changed_ledger uuid := coalesce(new.ledger_id, old.ledger_id);
    changed_account uuid := coalesce(new.account_id, old.account_id);
begin
    with recursive ancestors as (
        select id, parent_id from ledger_account where ledger_id = changed_ledger and id = changed_account
        union all
        select parent.id, parent.parent_id from ledger_account parent
        join ancestors child on child.parent_id = parent.id
        where parent.ledger_id = changed_ledger
    )
    update sub_ledger_checkpoint_state state set dirty = true
    where state.ledger_id = changed_ledger and state.account_id in (select id from ancestors);
    return coalesce(new, old);
end $$;

create trigger tr_sub_ledger_checkpoint_line_dirty
after insert or update or delete on voucher_line
for each row execute function mark_sub_ledger_checkpoints_for_line();

create or replace function mark_sub_ledger_checkpoints_for_voucher() returns trigger language plpgsql as $$
begin
    if tg_op = 'UPDATE' and new.status is not distinct from old.status
       and new.period_id is not distinct from old.period_id
       and new.voucher_date is not distinct from old.voucher_date
       and new.voucher_number is not distinct from old.voucher_number
       and new.deleted_at is not distinct from old.deleted_at then
        return new;
    end if;
    update sub_ledger_checkpoint_state set dirty = true
    where ledger_id = coalesce(new.ledger_id, old.ledger_id);
    return coalesce(new, old);
end $$;

create trigger tr_sub_ledger_checkpoint_voucher_dirty
after insert or update or delete on voucher
for each row execute function mark_sub_ledger_checkpoints_for_voucher();
