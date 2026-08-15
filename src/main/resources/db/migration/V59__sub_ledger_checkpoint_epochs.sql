create table sub_ledger_checkpoint_epoch (
    ledger_id uuid primary key references ledger (id) on delete cascade,
    epoch bigint not null default 0
);

alter table sub_ledger_checkpoint_state
    add column source_epoch bigint not null default 0;

create or replace function bump_sub_ledger_checkpoint_epoch(target_ledger uuid) returns void language sql as $$
    insert into sub_ledger_checkpoint_epoch (ledger_id, epoch) values (target_ledger, 1)
    on conflict (ledger_id) do update set epoch = sub_ledger_checkpoint_epoch.epoch + 1
$$;

create or replace function mark_sub_ledger_checkpoint_account_dirty(target_ledger uuid, target_account uuid)
returns void language plpgsql as $$
begin
    with recursive ancestors as (
        select id, parent_id from ledger_account where ledger_id = target_ledger and id = target_account
        union all
        select parent.id, parent.parent_id from ledger_account parent
        join ancestors child on child.parent_id = parent.id where parent.ledger_id = target_ledger
    ) update sub_ledger_checkpoint_state state set dirty = true
      where state.ledger_id = target_ledger and state.account_id in (select id from ancestors);
end $$;

drop trigger tr_sub_ledger_checkpoint_line_dirty on voucher_line;
create or replace function mark_sub_ledger_checkpoints_for_line() returns trigger language plpgsql as $$
begin
    if tg_op = 'INSERT' then
        perform bump_sub_ledger_checkpoint_epoch(new.ledger_id);
        perform mark_sub_ledger_checkpoint_account_dirty(new.ledger_id, new.account_id);
        return new;
    end if;
    if tg_op = 'DELETE' then
        perform bump_sub_ledger_checkpoint_epoch(old.ledger_id);
        perform mark_sub_ledger_checkpoint_account_dirty(old.ledger_id, old.account_id);
        return old;
    end if;
    perform bump_sub_ledger_checkpoint_epoch(new.ledger_id);
    perform mark_sub_ledger_checkpoint_account_dirty(new.ledger_id, new.account_id);
    if old.ledger_id is distinct from new.ledger_id then
        perform bump_sub_ledger_checkpoint_epoch(old.ledger_id);
    end if;
    if old.ledger_id is distinct from new.ledger_id or old.account_id is distinct from new.account_id then
        perform mark_sub_ledger_checkpoint_account_dirty(old.ledger_id, old.account_id);
    end if;
    return new;
end $$;
create trigger tr_sub_ledger_checkpoint_line_dirty after insert or update or delete on voucher_line
for each row execute function mark_sub_ledger_checkpoints_for_line();

drop trigger tr_sub_ledger_checkpoint_voucher_dirty on voucher;
create or replace function mark_sub_ledger_checkpoints_for_voucher() returns trigger language plpgsql as $$
declare target_ledger uuid := coalesce(new.ledger_id, old.ledger_id);
begin
    if tg_op = 'UPDATE' and new.status is not distinct from old.status
       and new.period_id is not distinct from old.period_id and new.voucher_date is not distinct from old.voucher_date
       and new.voucher_number is not distinct from old.voucher_number and new.deleted_at is not distinct from old.deleted_at then
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
create trigger tr_sub_ledger_checkpoint_voucher_dirty after insert or update or delete on voucher
for each row execute function mark_sub_ledger_checkpoints_for_voucher();
