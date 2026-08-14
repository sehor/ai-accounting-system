alter table voucher
    add column accounting_role varchar(32) not null default 'OPERATING',
    add constraint ck_voucher_accounting_role
        check (accounting_role in ('OPERATING', 'PROFIT_LOSS_TRANSFER'));

create index idx_voucher_ledger_period_role_posted
    on voucher (ledger_id, period_id, accounting_role)
    where status = 'POSTED' and deleted_at is null;

alter table account_period_balance
    add column operating_debit_base numeric(19, 2) not null default 0,
    add column operating_credit_base numeric(19, 2) not null default 0;

-- Backfill only vouchers that strictly clear the same-period net occurrence of every
-- participating profit-and-loss account.  No source, number, or summary is consulted.
with profit_accounts as (
    select ledger.id ledger_id, coalesce(
        (select setting.profit_account_id
         from period_closing_setting setting
         join ledger_account account
           on account.ledger_id = setting.ledger_id and account.id = setting.profit_account_id
         where setting.ledger_id = ledger.id and account.status = 'ACTIVE' and account.category = 'EQUITY'
           and not exists (select 1 from ledger_account child
                           where child.ledger_id = account.ledger_id and child.parent_id = account.id)),
        (select account.id from ledger_account account
         where account.ledger_id = ledger.id and account.code = '3103'
           and account.status = 'ACTIVE' and account.category = 'EQUITY'
           and not exists (select 1 from ledger_account child
                           where child.ledger_id = account.ledger_id and child.parent_id = account.id) limit 1),
        (select account.id from ledger_account account
         where account.ledger_id = ledger.id and account.code = '4103'
           and account.status = 'ACTIVE' and account.category = 'EQUITY'
           and not exists (select 1 from ledger_account child
                           where child.ledger_id = account.ledger_id and child.parent_id = account.id) limit 1)
    ) account_id
    from ledger
    where deleted_at is null
), candidate_lines as (
    select voucher.id voucher_id, voucher.ledger_id, voucher.period_id, line.account_id, account.category,
        sum(case when line.side = 'DEBIT' then line.base_amount else -line.base_amount end) net,
        sum(case when line.side = 'DEBIT' then line.base_amount else 0 end) debit,
        sum(case when line.side = 'CREDIT' then line.base_amount else 0 end) credit
    from voucher
    join voucher_line line on line.ledger_id = voucher.ledger_id and line.voucher_id = voucher.id
    join ledger_account account on account.ledger_id = line.ledger_id and account.id = line.account_id
    where voucher.status = 'POSTED' and voucher.deleted_at is null
    group by voucher.id, voucher.ledger_id, voucher.period_id, line.account_id, account.category
), transfers as (
    select candidate.voucher_id
    from candidate_lines candidate
    join profit_accounts profit on profit.ledger_id = candidate.ledger_id and profit.account_id is not null
    group by candidate.voucher_id, candidate.ledger_id, candidate.period_id, profit.account_id
    having bool_or(candidate.account_id = profit.account_id)
       and bool_or(candidate.category in ('OPERATING_REVENUE', 'OTHER_INCOME',
           'OPERATING_COST_AND_TAX', 'OTHER_EXPENSE', 'PERIOD_EXPENSE', 'INCOME_TAX', 'PRIOR_YEAR_ADJUSTMENT'))
       and bool_and(candidate.account_id = profit.account_id
           or candidate.category in ('OPERATING_REVENUE', 'OTHER_INCOME',
               'OPERATING_COST_AND_TAX', 'OTHER_EXPENSE', 'PERIOD_EXPENSE', 'INCOME_TAX', 'PRIOR_YEAR_ADJUSTMENT'))
       and sum(candidate.debit) = sum(candidate.credit)
       and not exists (
           select 1 from candidate_lines line
           where line.voucher_id = candidate.voucher_id
             and line.category in ('OPERATING_REVENUE', 'OTHER_INCOME',
                 'OPERATING_COST_AND_TAX', 'OTHER_EXPENSE', 'PERIOD_EXPENSE',
                 'INCOME_TAX', 'PRIOR_YEAR_ADJUSTMENT')
             and line.net + coalesce((
                 select sum(case when posted_line.side = 'DEBIT'
                                     then posted_line.base_amount else -posted_line.base_amount end)
                 from voucher posted
                 join voucher_line posted_line
                   on posted_line.ledger_id = posted.ledger_id and posted_line.voucher_id = posted.id
                 where posted.ledger_id = candidate.ledger_id and posted.period_id = candidate.period_id
                   and posted.id <> candidate.voucher_id and posted.status = 'POSTED'
                   and posted.deleted_at is null and posted_line.account_id = line.account_id
             ), 0) <> 0
       )
)
update voucher set accounting_role = 'PROFIT_LOSS_TRANSFER'
where id in (select voucher_id from transfers);

-- Rebuild rolling balances and independent operating movements from the classified facts.
delete from account_period_balance;

with recursive periods as (
    select p.ledger_id, p.id period_id, p.period_code, p.status,
        row_number() over (partition by p.ledger_id order by p.period_code) period_no
    from accounting_period p
), leaf_accounts as (
    select a.ledger_id, a.id account_id
    from ledger_account a
    where not exists (select 1 from ledger_account child
                      where child.ledger_id = a.ledger_id and child.parent_id = a.id)
), opening_facts as (
    select ob.ledger_id, ob.account_id, sum(ob.debit_base - ob.credit_base) opening_signed
    from opening_balance ob where ob.confirmed group by ob.ledger_id, ob.account_id
), movements as (
    select v.ledger_id, v.period_id, vl.account_id,
        sum(case when vl.side = 'DEBIT' then vl.base_amount else 0 end) period_debit,
        sum(case when vl.side = 'CREDIT' then vl.base_amount else 0 end) period_credit,
        sum(case when v.accounting_role = 'OPERATING' and vl.side = 'DEBIT'
                 then vl.base_amount else 0 end) operating_debit,
        sum(case when v.accounting_role = 'OPERATING' and vl.side = 'CREDIT'
                 then vl.base_amount else 0 end) operating_credit
    from voucher v
    join voucher_line vl on vl.ledger_id = v.ledger_id and vl.voucher_id = v.id
    where v.status = 'POSTED' and v.deleted_at is null
    group by v.ledger_id, v.period_id, vl.account_id
), leaf_rollup as (
    select p.ledger_id, p.period_id, p.period_no, p.status, leaf.account_id,
        coalesce(opening.opening_signed, 0::numeric) opening_signed,
        coalesce(movement.period_debit, 0::numeric) period_debit,
        coalesce(movement.period_credit, 0::numeric) period_credit,
        coalesce(movement.operating_debit, 0::numeric) operating_debit,
        coalesce(movement.operating_credit, 0::numeric) operating_credit,
        coalesce(opening.opening_signed, 0::numeric) + coalesce(movement.period_debit, 0::numeric)
            - coalesce(movement.period_credit, 0::numeric) closing_signed
    from periods p join leaf_accounts leaf on leaf.ledger_id = p.ledger_id
    left join opening_facts opening on opening.ledger_id = leaf.ledger_id and opening.account_id = leaf.account_id
    left join movements movement on movement.ledger_id = p.ledger_id and movement.period_id = p.period_id
        and movement.account_id = leaf.account_id
    where p.period_no = 1
    union all
    select p.ledger_id, p.period_id, p.period_no, p.status, previous.account_id,
        previous.closing_signed, coalesce(movement.period_debit, 0::numeric),
        coalesce(movement.period_credit, 0::numeric), coalesce(movement.operating_debit, 0::numeric),
        coalesce(movement.operating_credit, 0::numeric), previous.closing_signed
            + coalesce(movement.period_debit, 0::numeric) - coalesce(movement.period_credit, 0::numeric)
    from leaf_rollup previous join periods p on p.ledger_id = previous.ledger_id
        and p.period_no = previous.period_no + 1
    left join movements movement on movement.ledger_id = p.ledger_id and movement.period_id = p.period_id
        and movement.account_id = previous.account_id
), account_path as (
    select a.ledger_id, a.id source_id, a.id account_id, a.parent_id from ledger_account a
    union all
    select path.ledger_id, path.source_id, parent.id, parent.parent_id
    from account_path path join ledger_account parent
      on parent.ledger_id = path.ledger_id and parent.id = path.parent_id
), snapshots as (
    select leaf.ledger_id, leaf.period_id, path.account_id, max(leaf.status) period_status,
        sum(leaf.opening_signed) opening_signed, sum(leaf.period_debit) period_debit,
        sum(leaf.period_credit) period_credit, sum(leaf.operating_debit) operating_debit,
        sum(leaf.operating_credit) operating_credit, sum(leaf.closing_signed) closing_signed
    from leaf_rollup leaf join account_path path
      on path.ledger_id = leaf.ledger_id and path.source_id = leaf.account_id
    group by leaf.ledger_id, leaf.period_id, path.account_id
)
insert into account_period_balance (
    ledger_id, period_id, account_id, opening_debit_base, opening_credit_base,
    period_debit_base, period_credit_base, operating_debit_base, operating_credit_base,
    closing_debit_base, closing_credit_base, finalized_at, version, updated_at)
select ledger_id, period_id, account_id, greatest(opening_signed, 0), greatest(-opening_signed, 0),
    period_debit, period_credit, operating_debit, operating_credit,
    greatest(closing_signed, 0), greatest(-closing_signed, 0),
    case when period_status = 'CLOSED' then now() else null end, 1, now()
from snapshots
where opening_signed <> 0 or period_debit <> 0 or period_credit <> 0
   or operating_debit <> 0 or operating_credit <> 0 or closing_signed <> 0;
