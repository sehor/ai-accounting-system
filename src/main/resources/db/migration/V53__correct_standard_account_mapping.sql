-- V52 was already released to local and shared databases. Keep it immutable and
-- apply the corrected legacy evidence mapping as a forward-only migration.
alter table ledger_account disable trigger tr_ledger_account_hierarchy_and_lock;

-- The original V52 treated legacy SME code 5601 as selling expense. The installed
-- registry proves that code is ambiguous (selling or administrative expense), so
-- remove only migration-derived mappings. Explicit post-V52 mappings are preserved.
with recursive earliest_create as (
    select distinct on (revision.ledger_id, revision.aggregate_id)
           revision.ledger_id,
           revision.aggregate_id,
           revision.after_data ->> 'code' original_code,
           nullif(revision.after_data ->> 'standardAccountKey', '') original_key
    from audit_revision revision
    where revision.aggregate_type = 'ACCOUNT' and revision.action = 'CREATE'
    order by revision.ledger_id, revision.aggregate_id, revision.revision, revision.created_at
), ambiguous_tree as (
    select account.ledger_id, account.id
    from ledger_account account
    join ledger on ledger.id = account.ledger_id
    join earliest_create created
      on created.ledger_id = account.ledger_id and created.aggregate_id = account.id
    where not account.is_template
      and upper(ledger.accounting_standard_code) = 'SME'
      and created.original_code = '5601'
      and created.original_key is null
      and account.standard_account_key = 'EXPENSE.SELLING'
    union all
    select child.ledger_id, child.id
    from ledger_account child
    join ambiguous_tree parent
      on parent.ledger_id = child.ledger_id and parent.id = child.parent_id
    where not child.is_template
)
update ledger_account account
set standard_account_key = null
from ambiguous_tree
where account.ledger_id = ambiguous_tree.ledger_id
  and account.id = ambiguous_tree.id
  and account.standard_account_key = 'EXPENSE.SELLING'
  and not exists (
      select 1
      from earliest_create created
      where created.ledger_id = account.ledger_id
        and created.aggregate_id = account.id
        and created.original_key is not null
  );

with legacy_registry(standard_code, standard_version, legacy_code, standard_key) as (
    values
        ('SME','2011-17','1001','ASSET.CASH'),
        ('SME','2011-17','1002','ASSET.BANK_DEPOSIT'),
        ('SME','2011-17','1012','ASSET.OTHER_MONETARY_FUNDS'),
        ('SME','2011-17','1101','ASSET.SHORT_TERM_INVESTMENT'),
        ('SME','2011-17','1121','ASSET.NOTES_RECEIVABLE'),
        ('SME','2011-17','1122','ASSET.ACCOUNTS_RECEIVABLE'),
        ('SME','2011-17','1123','ASSET.PREPAYMENT'),
        ('SME','2011-17','1131','ASSET.DIVIDEND_RECEIVABLE'),
        ('SME','2011-17','1132','ASSET.INTEREST_RECEIVABLE'),
        ('SME','2011-17','1221','ASSET.OTHER_RECEIVABLE'),
        ('SME','2011-17','1401','ASSET.MATERIAL_PROCUREMENT'),
        ('SME','2011-17','1402','ASSET.MATERIAL_IN_TRANSIT'),
        ('SME','2011-17','1403','ASSET.RAW_MATERIAL'),
        ('SME','2011-17','1404','ASSET.MATERIAL_COST_VARIANCE'),
        ('SME','2011-17','1405','ASSET.INVENTORY_GOODS'),
        ('SME','2011-17','1406','ASSET.GOODS_DISPATCHED'),
        ('SME','2011-17','1407','ASSET.MERCHANDISE_PRICE_VARIANCE'),
        ('SME','2011-17','1408','ASSET.ENTRUSTED_PROCESSING_MATERIAL'),
        ('SME','2011-17','1411','ASSET.TURNOVER_MATERIAL'),
        ('SME','2011-17','1412','ASSET.PACKAGING_MATERIAL'),
        ('SME','2011-17','1413','ASSET.LOW_VALUE_CONSUMABLE'),
        ('SME','2011-17','1441','ASSET.CONSUMABLE_BIOLOGICAL_ASSET'),
        ('SME','2011-17','1471','ASSET.INVENTORY_IMPAIRMENT'),
        ('SME','2011-17','1501','ASSET.LONG_TERM_BOND_INVESTMENT'),
        ('SME','2011-17','1511','ASSET.LONG_TERM_EQUITY_INVESTMENT'),
        ('SME','2011-17','1601','ASSET.FIXED_ASSET'),
        ('SME','2011-17','1602','ASSET.ACCUMULATED_DEPRECIATION'),
        ('SME','2011-17','1603','ASSET.CONSTRUCTION_IN_PROGRESS'),
        ('SME','2011-17','1604','ASSET.CONSTRUCTION_MATERIAL'),
        ('SME','2011-17','1606','ASSET.FIXED_ASSET_CLEARING'),
        ('SME','2011-17','1621','ASSET.PRODUCTIVE_BIOLOGICAL_ASSET'),
        ('SME','2011-17','1701','ASSET.INTANGIBLE_ASSET'),
        ('SME','2011-17','1702','ASSET.ACCUMULATED_AMORTIZATION'),
        ('SME','2011-17','1901','ASSET.LONG_TERM_DEFERRED_EXPENSE'),
        ('SME','2011-17','1902','ASSET.OTHER_NON_CURRENT'),
        ('SME','2011-17','2001','LIABILITY.SHORT_TERM_BORROWING'),
        ('SME','2011-17','2201','LIABILITY.NOTES_PAYABLE'),
        ('SME','2011-17','2202','LIABILITY.ACCOUNTS_PAYABLE'),
        ('SME','2011-17','2203','LIABILITY.ADVANCE_RECEIPT'),
        ('SME','2011-17','2211','LIABILITY.EMPLOYEE_BENEFITS_PAYABLE'),
        ('SME','2011-17','2221','LIABILITY.TAX_PAYABLE'),
        ('SME','2011-17','2231','LIABILITY.INTEREST_PAYABLE'),
        ('SME','2011-17','2232','LIABILITY.PROFIT_PAYABLE'),
        ('SME','2011-17','2241','LIABILITY.OTHER_PAYABLE'),
        ('SME','2011-17','2291','LIABILITY.OTHER_CURRENT'),
        ('SME','2011-17','2401','LIABILITY.DEFERRED_INCOME'),
        ('SME','2011-17','2501','LIABILITY.LONG_TERM_BORROWING'),
        ('SME','2011-17','2701','LIABILITY.LONG_TERM_PAYABLE'),
        ('SME','2011-17','2901','LIABILITY.OTHER_NON_CURRENT'),
        ('SME','2011-17','3001','EQUITY.PAID_IN_CAPITAL'),
        ('SME','2011-17','3002','EQUITY.CAPITAL_RESERVE'),
        ('SME','2011-17','3101','EQUITY.SURPLUS_RESERVE'),
        ('SME','2011-17','3103','EQUITY.CURRENT_YEAR_PROFIT'),
        ('SME','2011-17','3104','EQUITY.RETAINED_EARNINGS'),
        ('SME','2011-17','4001','COST.PRODUCTION'),
        ('SME','2011-17','4403','COST.MANUFACTURING_OVERHEAD'),
        ('SME','2011-17','4301','COST.RESEARCH_AND_DEVELOPMENT'),
        ('SME','2011-17','5001','INCOME.MAIN_BUSINESS_REVENUE'),
        ('SME','2011-17','5051','INCOME.OTHER_BUSINESS_REVENUE'),
        ('SME','2011-17','5101','INCOME.OTHER_BUSINESS_REVENUE'),
        ('SME','2011-17','5111','INCOME.INVESTMENT'),
        ('SME','2011-17','5301','INCOME.NON_OPERATING'),
        ('SME','2011-17','5401','EXPENSE.MAIN_BUSINESS_COST'),
        ('SME','2011-17','5405','EXPENSE.OTHER_BUSINESS_COST'),
        ('SME','2011-17','5403','EXPENSE.TAX_AND_SURCHARGE'),
        ('SME','2011-17','540301','EXPENSE.URBAN_MAINTENANCE_TAX'),
        ('SME','2011-17','540305','EXPENSE.EDUCATION_SURCHARGE'),
        ('SME','2011-17','540306','EXPENSE.RESOURCE_AND_ENVIRONMENT_LEVY'),
        ('SME','2011-17','5601','EXPENSE.SELLING'),
        ('SME','2011-17','560101','EXPENSE.PRODUCT_REPAIR'),
        ('SME','2011-17','560102','EXPENSE.ADVERTISING_AND_PROMOTION'),
        ('SME','2011-17','5602','EXPENSE.ADMINISTRATIVE'),
        ('SME','2011-17','5601','EXPENSE.ADMINISTRATIVE'),
        ('SME','2011-17','5603','EXPENSE.FINANCE'),
        ('SME','2011-17','5711','EXPENSE.NON_OPERATING'),
        ('SME','2011-17','5801','EXPENSE.INCOME_TAX'),
        ('CAS','2006-18','1001','ASSET.CASH'),
        ('CAS','2006-18','1002','ASSET.BANK_DEPOSIT'),
        ('CAS','2006-18','1101','ASSET.TRADING_FINANCIAL_ASSET'),
        ('CAS','2006-18','1122','ASSET.ACCOUNTS_RECEIVABLE'),
        ('CAS','2006-18','1403','ASSET.RAW_MATERIAL'),
        ('CAS','2006-18','1405','ASSET.INVENTORY_GOODS'),
        ('CAS','2006-18','1601','ASSET.FIXED_ASSET'),
        ('CAS','2006-18','1701','ASSET.INTANGIBLE_ASSET'),
        ('CAS','2006-18','2001','LIABILITY.SHORT_TERM_BORROWING'),
        ('CAS','2006-18','2202','LIABILITY.ACCOUNTS_PAYABLE'),
        ('CAS','2006-18','2221','LIABILITY.TAX_PAYABLE'),
        ('CAS','2006-18','2501','LIABILITY.LONG_TERM_BORROWING'),
        ('CAS','2006-18','4001','EQUITY.PAID_IN_CAPITAL'),
        ('CAS','2006-18','4103','EQUITY.CURRENT_YEAR_PROFIT'),
        ('CAS','2006-18','4104','EQUITY.RETAINED_EARNINGS'),
        ('CAS','2006-18','5001','COST.PRODUCTION'),
        ('CAS','2006-18','6001','INCOME.MAIN_BUSINESS_REVENUE'),
        ('CAS','2006-18','6401','EXPENSE.MAIN_BUSINESS_COST'),
        ('CAS','2006-18','6601','EXPENSE.SELLING'),
        ('CAS','2006-18','6602','EXPENSE.ADMINISTRATIVE'),
        ('CAS','2006-18','6603','EXPENSE.FINANCE')
), earliest_create as (
    select distinct on (revision.ledger_id, revision.aggregate_id)
           revision.ledger_id, revision.aggregate_id,
           revision.after_data ->> 'code' original_code
    from audit_revision revision
    where revision.aggregate_type = 'ACCOUNT' and revision.action = 'CREATE'
    order by revision.ledger_id, revision.aggregate_id, revision.revision, revision.created_at
), candidates as (
    select account.id, account.ledger_id, min(legacy_registry.standard_key) standard_key
    from ledger_account account
    join ledger on ledger.id = account.ledger_id
    join earliest_create created
      on created.ledger_id = account.ledger_id and created.aggregate_id = account.id
    join legacy_registry
      on legacy_registry.standard_code = upper(ledger.accounting_standard_code)
     and legacy_registry.standard_version = case
           when upper(ledger.accounting_standard_code) = 'SME' and ledger.accounting_standard_version = 'v1'
           then '2011-17' else ledger.accounting_standard_version end
     and legacy_registry.legacy_code = created.original_code
    where not account.is_template and account.standard_account_key is null
    group by account.id, account.ledger_id
    having count(distinct legacy_registry.standard_key) = 1
)
update ledger_account account
set standard_account_key = candidates.standard_key
from candidates
where account.ledger_id = candidates.ledger_id and account.id = candidates.id;

with recursive inherited as (
    select child.ledger_id, child.id, parent.standard_account_key
    from ledger_account child
    join ledger_account parent
      on parent.ledger_id = child.ledger_id and parent.id = child.parent_id
    where child.standard_account_key is null and parent.standard_account_key is not null
    union all
    select child.ledger_id, child.id, inherited.standard_account_key
    from ledger_account child
    join inherited
      on inherited.ledger_id = child.ledger_id and inherited.id = child.parent_id
    where child.standard_account_key is null
)
update ledger_account account
set standard_account_key = inherited.standard_account_key
from inherited
where account.ledger_id = inherited.ledger_id and account.id = inherited.id;

alter table ledger_account enable trigger tr_ledger_account_hierarchy_and_lock;

create or replace function enforce_account_hierarchy_and_lock()
returns trigger language plpgsql as $$
declare
    parent ledger_account%rowtype;
begin
    if tg_op = 'UPDATE' and new.standard_account_key is distinct from old.standard_account_key then
        raise exception 'account standard account key is immutable';
    end if;
    if tg_op = 'UPDATE' and new.normal_balance is distinct from old.normal_balance then
        raise exception 'account normal balance is immutable';
    end if;
    if tg_op = 'UPDATE' then
        if old.is_template and (new.code is distinct from old.code
            or new.parent_id is distinct from old.parent_id
            or new.category is distinct from old.category
            or new.normal_balance is distinct from old.normal_balance) then
            raise exception 'template account structure is immutable';
        end if;
        if (new.code is distinct from old.code or new.parent_id is distinct from old.parent_id
            or new.category is distinct from old.category
            or new.normal_balance is distinct from old.normal_balance
            or new.cash_flow_required is distinct from old.cash_flow_required
            or new.default_cash_flow_item_id is distinct from old.default_cash_flow_item_id
            or new.quantity_enabled is distinct from old.quantity_enabled
            or new.unit_name is distinct from old.unit_name)
        and (exists (select 1 from voucher_line line join voucher voucher
                     on voucher.ledger_id = line.ledger_id and voucher.id = line.voucher_id
                     where line.ledger_id = old.ledger_id and line.account_id = old.id
                       and voucher.status = 'POSTED')
             or exists (select 1 from opening_balance balance
                        where balance.ledger_id = old.ledger_id and balance.account_id = old.id
                          and balance.confirmed)) then
            raise exception 'account core attributes are locked';
        end if;
    end if;
    if new.parent_id is null then
        if new.level <> 1 then raise exception 'root account must be level one'; end if;
    else
        select * into strict parent from ledger_account
        where ledger_id = new.ledger_id and id = new.parent_id;
        if new.level <> parent.level + 1 or new.category <> parent.category then
            raise exception 'child account must inherit its parent category';
        end if;
        if (tg_op = 'INSERT' or new.parent_id is distinct from old.parent_id
                or new.standard_account_key is distinct from old.standard_account_key)
           and (parent.standard_account_key is null
                or new.standard_account_key is distinct from parent.standard_account_key) then
            raise exception 'child account must inherit its mapped parent standard account key';
        end if;
        if (tg_op = 'INSERT' or new.parent_id is distinct from old.parent_id)
           and (exists (select 1 from voucher_line where ledger_id = parent.ledger_id and account_id = parent.id)
                or exists (select 1 from opening_balance where ledger_id = parent.ledger_id and account_id = parent.id)) then
            raise exception 'used account cannot become a parent';
        end if;
    end if;
    return new;
end $$;
