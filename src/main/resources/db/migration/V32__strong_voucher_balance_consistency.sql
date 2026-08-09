-- Remove legacy soft-deleted vouchers before hard-delete constraints take effect.
delete from balance_projection_event e
using voucher v
where e.aggregate_type = 'VOUCHER'
  and e.ledger_id = v.ledger_id
  and e.aggregate_id = v.id
  and (v.deleted_at is not null or v.status = 'DELETED');

delete from voucher_idempotency i
using voucher v
where i.ledger_id = v.ledger_id
  and i.voucher_id = v.id
  and (v.deleted_at is not null or v.status = 'DELETED');

delete from voucher_approval a
using voucher v
where a.ledger_id = v.ledger_id
  and a.voucher_id = v.id
  and (v.deleted_at is not null or v.status = 'DELETED');

delete from voucher_line l
using voucher v
where l.ledger_id = v.ledger_id
  and l.voucher_id = v.id
  and (v.deleted_at is not null or v.status = 'DELETED');

delete from voucher
where deleted_at is not null or status = 'DELETED';

delete from voucher_idempotency i
where not exists (
    select 1 from voucher v
    where v.ledger_id = i.ledger_id and v.id = i.voucher_id);

alter table voucher_line
    drop constraint fk_voucher_line_voucher,
    add constraint fk_voucher_line_voucher foreign key (ledger_id, voucher_id)
        references voucher (ledger_id, id) on delete cascade;

alter table voucher_approval
    drop constraint fk_voucher_approval_voucher,
    add constraint fk_voucher_approval_voucher foreign key (ledger_id, voucher_id)
        references voucher (ledger_id, id) on delete cascade;

alter table voucher_idempotency
    add constraint fk_voucher_idempotency_voucher foreign key (ledger_id, voucher_id)
        references voucher (ledger_id, id) on delete cascade;

alter table voucher
    drop constraint ck_voucher_status,
    add constraint ck_voucher_status
        check (status in ('DRAFT', 'VALIDATED', 'SUBMITTED', 'APPROVED', 'POSTED', 'REVERSED'));

alter table account_period_balance
    drop constraint fk_account_period_balance_account,
    add constraint fk_account_period_balance_account foreign key (ledger_id, account_id)
        references ledger_account (ledger_id, id) on delete cascade;

alter table balance_projection_event_line
    drop constraint fk_balance_projection_event_line_account,
    add constraint fk_balance_projection_event_line_account foreign key (ledger_id, account_id)
        references ledger_account (ledger_id, id) on delete cascade;

alter table fixed_asset_depreciation_line
    add constraint fk_fixed_asset_depreciation_line_expense_account
        foreign key (ledger_id, expense_account_id) references ledger_account (ledger_id, id),
    add constraint fk_fixed_asset_depreciation_line_accumulated_account
        foreign key (ledger_id, accumulated_account_id) references ledger_account (ledger_id, id);

-- Rebuild projection rows from the current accounting facts.
delete from account_period_balance;

with facts as (
    select v.ledger_id, v.period_id, vl.account_id,
        0::numeric opening_debit_base,
        0::numeric opening_credit_base,
        case when vl.side = 'DEBIT' then vl.base_amount else 0 end period_debit_base,
        case when vl.side = 'CREDIT' then vl.base_amount else 0 end period_credit_base
    from voucher v
    join voucher_line vl on vl.ledger_id = v.ledger_id and vl.voucher_id = v.id
    where v.status = 'POSTED'
    union all
    select ob.ledger_id, ob.period_id, ob.account_id,
        greatest(ob.debit_base, 0) + greatest(-ob.credit_base, 0),
        greatest(ob.credit_base, 0) + greatest(-ob.debit_base, 0),
        0::numeric,
        0::numeric
    from opening_balance ob
    where ob.confirmed
), totals as (
    select ledger_id, period_id, account_id,
        sum(opening_debit_base) opening_debit_base,
        sum(opening_credit_base) opening_credit_base,
        sum(period_debit_base) period_debit_base,
        sum(period_credit_base) period_credit_base
    from facts
    group by ledger_id, period_id, account_id
)
insert into account_period_balance (
    ledger_id, period_id, account_id,
    opening_debit_base, opening_credit_base, period_debit_base, period_credit_base,
    version, updated_at)
select ledger_id, period_id, account_id,
    opening_debit_base, opening_credit_base, period_debit_base, period_credit_base,
    1, now()
from totals
where opening_debit_base <> 0
   or opening_credit_base <> 0
   or period_debit_base <> 0
   or period_credit_base <> 0;

-- Existing events are represented by the rebuilt balances and must not be applied again.
insert into balance_projection_state (
    ledger_id, period_id, last_enqueued_event_id, last_applied_event_id,
    last_enqueued_at, projected_at, status, last_error_code, last_error_message,
    attempts, next_attempt_at, updated_at)
select p.ledger_id, p.id,
    max(e.id), max(e.id), max(e.created_at), now(), 'READY', null, null, 0, now(), now()
from accounting_period p
left join balance_projection_event e
  on e.ledger_id = p.ledger_id and e.period_id = p.id
where exists (
        select 1 from account_period_balance b
        where b.ledger_id = p.ledger_id and b.period_id = p.id)
   or e.id is not null
group by p.ledger_id, p.id
on conflict (ledger_id, period_id) do update set
    last_enqueued_event_id = excluded.last_enqueued_event_id,
    last_applied_event_id = excluded.last_applied_event_id,
    last_enqueued_at = excluded.last_enqueued_at,
    projected_at = excluded.projected_at,
    status = 'READY',
    last_error_code = null,
    last_error_message = null,
    attempts = 0,
    next_attempt_at = now(),
    updated_at = now();

delete from balance_projection_state s
where not exists (
        select 1 from account_period_balance b
        where b.ledger_id = s.ledger_id and b.period_id = s.period_id)
  and not exists (
        select 1 from balance_projection_event e
        where e.ledger_id = s.ledger_id and e.period_id = s.period_id);
