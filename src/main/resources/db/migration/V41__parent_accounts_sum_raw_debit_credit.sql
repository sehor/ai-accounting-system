-- Parent accounts must only roll up raw debit and credit amounts. A parent's
-- display balance is then derived from its own normal balance, never from the
-- already-formatted balances of its children. Rebuild the sparse snapshots with
-- that rule so initial migration data matches the runtime snapshot rebuilder.

delete from account_period_balance;

with recursive periods as (
    select p.ledger_id, p.id period_id, p.period_code, p.status,
        row_number() over (partition by p.ledger_id order by p.period_code) period_no
    from accounting_period p
), leaf_accounts as (
    select a.ledger_id, a.id account_id
    from ledger_account a
    where not exists (
        select 1 from ledger_account child
        where child.ledger_id = a.ledger_id and child.parent_id = a.id)
), opening_facts as (
    select ob.ledger_id, ob.account_id,
        sum(ob.debit_base) opening_debit,
        sum(ob.credit_base) opening_credit
    from opening_balance ob
    where ob.confirmed
    group by ob.ledger_id, ob.account_id
), movements as (
    select v.ledger_id, v.period_id, vl.account_id,
        sum(case when vl.side = 'DEBIT' then vl.base_amount else 0 end) period_debit,
        sum(case when vl.side = 'CREDIT' then vl.base_amount else 0 end) period_credit
    from voucher v
    join voucher_line vl on vl.ledger_id = v.ledger_id and vl.voucher_id = v.id
    where v.status = 'POSTED' and v.deleted_at is null
    group by v.ledger_id, v.period_id, vl.account_id
), leaf_rollup as (
    select p.ledger_id, p.period_id, p.period_no, p.status, leaf.account_id,
        coalesce(opening.opening_debit, 0::numeric) opening_debit,
        coalesce(opening.opening_credit, 0::numeric) opening_credit,
        coalesce(movement.period_debit, 0::numeric) period_debit,
        coalesce(movement.period_credit, 0::numeric) period_credit,
        coalesce(opening.opening_debit, 0::numeric)
            + coalesce(movement.period_debit, 0::numeric) closing_debit,
        coalesce(opening.opening_credit, 0::numeric)
            + coalesce(movement.period_credit, 0::numeric) closing_credit
    from periods p
    join leaf_accounts leaf on leaf.ledger_id = p.ledger_id
    left join opening_facts opening
      on opening.ledger_id = leaf.ledger_id and opening.account_id = leaf.account_id
    left join movements movement
      on movement.ledger_id = p.ledger_id and movement.period_id = p.period_id
     and movement.account_id = leaf.account_id
    where p.period_no = 1

    union all

    select p.ledger_id, p.period_id, p.period_no, p.status, previous.account_id,
        previous.closing_debit opening_debit,
        previous.closing_credit opening_credit,
        coalesce(movement.period_debit, 0::numeric) period_debit,
        coalesce(movement.period_credit, 0::numeric) period_credit,
        previous.closing_debit + coalesce(movement.period_debit, 0::numeric) closing_debit,
        previous.closing_credit + coalesce(movement.period_credit, 0::numeric) closing_credit
    from leaf_rollup previous
    join periods p
      on p.ledger_id = previous.ledger_id and p.period_no = previous.period_no + 1
    left join movements movement
      on movement.ledger_id = p.ledger_id and movement.period_id = p.period_id
     and movement.account_id = previous.account_id
), account_path as (
    select a.ledger_id, a.id source_id, a.id account_id, a.parent_id
    from ledger_account a

    union all

    select path.ledger_id, path.source_id, parent.id, parent.parent_id
    from account_path path
    join ledger_account parent
      on parent.ledger_id = path.ledger_id and parent.id = path.parent_id
), snapshots as (
    select leaf.ledger_id, leaf.period_id, path.account_id,
        sum(leaf.opening_debit) opening_debit,
        sum(leaf.opening_credit) opening_credit,
        sum(leaf.period_debit) period_debit,
        sum(leaf.period_credit) period_credit,
        sum(leaf.closing_debit) closing_debit,
        sum(leaf.closing_credit) closing_credit,
        max(leaf.status) period_status
    from leaf_rollup leaf
    join account_path path
      on path.ledger_id = leaf.ledger_id and path.source_id = leaf.account_id
    group by leaf.ledger_id, leaf.period_id, path.account_id
)
insert into account_period_balance (
    ledger_id, period_id, account_id,
    opening_debit_base, opening_credit_base,
    period_debit_base, period_credit_base,
    closing_debit_base, closing_credit_base,
    finalized_at, version, updated_at)
select ledger_id, period_id, account_id,
    opening_debit, opening_credit,
    period_debit, period_credit,
    closing_debit, closing_credit,
    case when period_status = 'CLOSED' then now() else null end,
    1, now()
from snapshots
where opening_debit <> 0
   or opening_credit <> 0
   or period_debit <> 0
   or period_credit <> 0
   or closing_debit <> 0
   or closing_credit <> 0;
