delete from accounting_experience where scope = 'GENERAL';

drop index ix_accounting_experience_scope_status_updated;

alter table accounting_experience
    drop constraint ck_accounting_experience_scope_ledger,
    drop constraint ck_accounting_experience_scope;

alter table accounting_experience
    alter column ledger_id set not null,
    add constraint ck_accounting_experience_scope check (scope = 'LEDGER');
