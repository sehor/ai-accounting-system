-- The existing trigger was declared with UPDATE OF before standard_account_key
-- existed. Recreate it so a direct key-only update reaches the immutable guard
-- in enforce_account_hierarchy_and_lock().
drop trigger if exists tr_ledger_account_hierarchy_and_lock on ledger_account;

create trigger tr_ledger_account_hierarchy_and_lock
before insert or update of code, parent_id, level, category, normal_balance,
    cash_flow_required, default_cash_flow_item_id, quantity_enabled, unit_name,
    standard_account_key
on ledger_account
for each row execute function enforce_account_hierarchy_and_lock();
