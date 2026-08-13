alter table ledger_account
    drop constraint if exists ck_ledger_account_category;

alter table ledger_account
    disable trigger tr_ledger_account_hierarchy_and_lock;

update ledger_account
set category = case
    when category = 'ASSET' then
        case when code ~ '^[0-9]{4}' and substring(code from 1 for 4)::int < 1500
            then 'CURRENT_ASSET' else 'NON_CURRENT_ASSET' end
    when category = 'LIABILITY' then
        case when code ~ '^[0-9]{4}' and substring(code from 1 for 4)::int < 2500
            then 'CURRENT_LIABILITY' else 'NON_CURRENT_LIABILITY' end
    when category = 'EQUITY' then 'EQUITY'
    when category = 'COST' then 'COST'
    when category = 'REVENUE' then
        case when code ~ '^[0-9]{4}' and substring(code from 1 for 4) = '5001'
            then 'OPERATING_REVENUE' else 'OTHER_INCOME' end
    when category = 'EXPENSE' then
        case substring(code from 1 for 4)
            when '5401' then 'OPERATING_COST_AND_TAX'
            when '5402' then 'OTHER_EXPENSE'
            when '5403' then 'OPERATING_COST_AND_TAX'
            when '5601' then 'PERIOD_EXPENSE'
            when '5602' then 'PERIOD_EXPENSE'
            when '5603' then 'PERIOD_EXPENSE'
            when '5801' then 'INCOME_TAX'
            when '6000' then 'PRIOR_YEAR_ADJUSTMENT'
            else 'OTHER_EXPENSE'
        end
    else category
end
where category in ('ASSET', 'LIABILITY', 'EQUITY', 'COST', 'REVENUE', 'EXPENSE');

alter table ledger_account
    add constraint ck_ledger_account_category check (
        category in (
            'CURRENT_ASSET', 'NON_CURRENT_ASSET',
            'CURRENT_LIABILITY', 'NON_CURRENT_LIABILITY',
            'EQUITY', 'COST',
            'OPERATING_REVENUE', 'OTHER_INCOME',
            'OPERATING_COST_AND_TAX', 'OTHER_EXPENSE', 'PERIOD_EXPENSE',
            'INCOME_TAX', 'PRIOR_YEAR_ADJUSTMENT'
        )
    );

update report_formula_snapshot
set formula_json = jsonb_set(
        jsonb_set(
            formula_json,
            '{debitCategories}',
            '["CURRENT_ASSET","NON_CURRENT_ASSET"]'::jsonb
        ),
        '{creditCategories}',
        '["CURRENT_LIABILITY","NON_CURRENT_LIABILITY","EQUITY"]'::jsonb
    )
where code = 'BALANCE_SHEET';

update report_formula_snapshot
set formula_json = jsonb_set(
        jsonb_set(
            formula_json,
            '{revenueCategories}',
            '["OPERATING_REVENUE","OTHER_INCOME"]'::jsonb
        ),
        '{expenseCategories}',
        '["COST","OPERATING_COST_AND_TAX","OTHER_EXPENSE","PERIOD_EXPENSE","INCOME_TAX","PRIOR_YEAR_ADJUSTMENT"]'::jsonb
    )
where code = 'INCOME_STATEMENT';

create or replace function enforce_account_hierarchy_and_lock()
returns trigger language plpgsql as $$
declare
    parent ledger_account%rowtype;
begin
    if tg_op = 'UPDATE' and new.normal_balance is distinct from old.normal_balance then
        raise exception 'account normal balance is immutable';
    end if;

    if tg_op = 'UPDATE' then
        if old.is_template and (
            new.code is distinct from old.code
            or new.parent_id is distinct from old.parent_id
            or new.category is distinct from old.category
            or new.normal_balance is distinct from old.normal_balance) then
            raise exception 'template account structure is immutable';
        end if;
        if (
            new.code is distinct from old.code
            or new.parent_id is distinct from old.parent_id
            or new.category is distinct from old.category
            or new.normal_balance is distinct from old.normal_balance
            or new.cash_flow_required is distinct from old.cash_flow_required
            or new.default_cash_flow_item_id is distinct from old.default_cash_flow_item_id
            or new.quantity_enabled is distinct from old.quantity_enabled
            or new.unit_name is distinct from old.unit_name
        ) and (
            exists (
                select 1 from voucher_line line
                join voucher voucher
                  on voucher.ledger_id = line.ledger_id and voucher.id = line.voucher_id
                where line.ledger_id = old.ledger_id and line.account_id = old.id
                  and voucher.status = 'POSTED')
            or exists (
                select 1 from opening_balance balance
                where balance.ledger_id = old.ledger_id and balance.account_id = old.id
                  and balance.confirmed)
        ) then
            raise exception 'account core attributes are locked';
        end if;
    end if;

    if new.parent_id is null then
        if new.level <> 1 then
            raise exception 'root account must be level one';
        end if;
    else
        select * into strict parent from ledger_account
        where ledger_id = new.ledger_id and id = new.parent_id;
        if new.level <> parent.level + 1
            or new.category <> parent.category then
            raise exception 'child account must inherit its parent category';
        end if;
        if (tg_op = 'INSERT' or new.parent_id is distinct from old.parent_id)
            and (
                exists (
                    select 1 from voucher_line
                    where ledger_id = parent.ledger_id and account_id = parent.id)
                or exists (
                    select 1 from opening_balance
                    where ledger_id = parent.ledger_id and account_id = parent.id)
            ) then
            raise exception 'used account cannot become a parent';
        end if;
    end if;
    return new;
end $$;

alter table ledger_account
    enable trigger tr_ledger_account_hierarchy_and_lock;
