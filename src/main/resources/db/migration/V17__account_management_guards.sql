alter table dimension_value
    add constraint uk_dimension_value_ledger_type_id
        unique (ledger_id, dimension_type_id, id);

alter table voucher_line_dimension
    drop constraint fk_voucher_line_dimension_value,
    add constraint fk_voucher_line_dimension_value
        foreign key (ledger_id, dimension_type_id, dimension_value_id)
        references dimension_value (ledger_id, dimension_type_id, id);

create or replace function enforce_account_hierarchy_and_lock()
returns trigger language plpgsql as $$
declare
    parent ledger_account%rowtype;
begin
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
            or new.category <> parent.category
            or new.normal_balance <> parent.normal_balance then
            raise exception 'child account must inherit its parent hierarchy';
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

create trigger tr_ledger_account_hierarchy_and_lock
before insert or update of code, parent_id, level, category, normal_balance,
    cash_flow_required, default_cash_flow_item_id, quantity_enabled, unit_name
on ledger_account
for each row execute function enforce_account_hierarchy_and_lock();

create or replace function enforce_leaf_account_reference()
returns trigger language plpgsql as $$
begin
    if exists (
        select 1 from ledger_account child
        where child.ledger_id = new.ledger_id and child.parent_id = new.account_id
    ) then
        raise exception 'only leaf accounts may be referenced';
    end if;
    return new;
end $$;

create trigger tr_voucher_line_leaf_account
before insert or update of account_id on voucher_line
for each row execute function enforce_leaf_account_reference();

create trigger tr_opening_balance_leaf_account
before insert or update of account_id on opening_balance
for each row execute function enforce_leaf_account_reference();

create or replace function enforce_account_dimension_lock()
returns trigger language plpgsql as $$
declare
    target_ledger uuid;
    target_account uuid;
begin
    target_ledger := coalesce(new.ledger_id, old.ledger_id);
    target_account := coalesce(new.account_id, old.account_id);
    if exists (
        select 1 from voucher_line line
        join voucher voucher
          on voucher.ledger_id = line.ledger_id and voucher.id = line.voucher_id
        where line.ledger_id = target_ledger and line.account_id = target_account
          and voucher.status = 'POSTED'
    ) or exists (
        select 1 from opening_balance balance
        where balance.ledger_id = target_ledger and balance.account_id = target_account
          and balance.confirmed
    ) then
        raise exception 'account dimension bindings are locked';
    end if;
    if tg_op = 'DELETE' then
        return old;
    end if;
    return new;
end $$;

create trigger tr_ledger_account_dimension_lock
before insert or update or delete on ledger_account_dimension
for each row execute function enforce_account_dimension_lock();

create or replace function enforce_account_delete()
returns trigger language plpgsql as $$
begin
    if old.is_template
        or exists (
            select 1 from ledger_account child
            where child.ledger_id = old.ledger_id and child.parent_id = old.id)
        or exists (
            select 1 from voucher_line line
            where line.ledger_id = old.ledger_id and line.account_id = old.id)
        or exists (
            select 1 from opening_balance balance
            where balance.ledger_id = old.ledger_id and balance.account_id = old.id) then
        raise exception 'only an unused custom leaf account may be deleted';
    end if;
    return old;
end $$;

create trigger tr_ledger_account_delete
before delete on ledger_account
for each row execute function enforce_account_delete();
