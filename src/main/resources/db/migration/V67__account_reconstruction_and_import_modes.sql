-- Support full account reconstruction and import modes (APPEND / OVERWRITE).

create or replace function enforce_account_delete()
returns trigger language plpgsql as $$
begin
    if current_setting('accounting.allow_account_reconstruction', true) = 'on' then
        return old;
    end if;
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

alter table account_import
    drop constraint if exists uk_account_import_idempotency,
    add column if not exists mode varchar(16) not null default 'APPEND',
    add column if not exists inserted_count integer not null default 0,
    add column if not exists deleted_count integer not null default 0,
    add column if not exists target_rule_level2_width smallint,
    add column if not exists target_rule_level3_width smallint,
    add column if not exists target_rule_level4_width smallint,
    add column if not exists code_rule_sources_json jsonb,
    add column if not exists blockers_json jsonb not null default '[]'::jsonb;

alter table account_import
    drop constraint if exists ck_account_import_mode,
    add constraint ck_account_import_mode check (mode in ('APPEND', 'OVERWRITE'));
