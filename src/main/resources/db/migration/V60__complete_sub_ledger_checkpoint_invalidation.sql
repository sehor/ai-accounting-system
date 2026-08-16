-- Voucher summaries are displayed when a line has no summary, so summary-only
-- edits must invalidate cached sub-ledger rows as well.
create or replace function mark_sub_ledger_checkpoints_for_voucher() returns trigger language plpgsql as $$
declare target_ledger uuid := coalesce(new.ledger_id, old.ledger_id);
begin
    if tg_op = 'UPDATE' and new.status is not distinct from old.status
       and new.period_id is not distinct from old.period_id
       and new.voucher_date is not distinct from old.voucher_date
       and new.voucher_number is not distinct from old.voucher_number
       and new.summary is not distinct from old.summary
       and new.deleted_at is not distinct from old.deleted_at then
        return new;
    end if;
    perform bump_sub_ledger_checkpoint_epoch(target_ledger);
    update sub_ledger_checkpoint_state set dirty = true where ledger_id = target_ledger;
    if tg_op = 'UPDATE' and old.ledger_id is distinct from new.ledger_id then
        perform bump_sub_ledger_checkpoint_epoch(old.ledger_id);
        update sub_ledger_checkpoint_state set dirty = true where ledger_id = old.ledger_id;
    end if;
    return coalesce(new, old);
end $$;

-- Hierarchy changes alter the descendant scope of a parent-account ledger.
-- They are rare, so conservatively invalidate every checkpoint in the ledger.
create or replace function mark_sub_ledger_checkpoints_for_account_hierarchy() returns trigger language plpgsql as $$
begin
    if tg_op = 'UPDATE' and new.ledger_id is not distinct from old.ledger_id
       and new.parent_id is not distinct from old.parent_id then
        return new;
    end if;
    if tg_op <> 'DELETE' then
        perform bump_sub_ledger_checkpoint_epoch(new.ledger_id);
        update sub_ledger_checkpoint_state set dirty = true where ledger_id = new.ledger_id;
    end if;
    if tg_op <> 'INSERT' and (tg_op = 'DELETE' or old.ledger_id is distinct from new.ledger_id) then
        perform bump_sub_ledger_checkpoint_epoch(old.ledger_id);
        update sub_ledger_checkpoint_state set dirty = true where ledger_id = old.ledger_id;
    end if;
    return coalesce(new, old);
end $$;

create trigger tr_sub_ledger_checkpoint_account_hierarchy_dirty
after insert or update or delete on ledger_account
for each row execute function mark_sub_ledger_checkpoints_for_account_hierarchy();
