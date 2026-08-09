alter table app_user
    add column user_type varchar(16) not null default 'HUMAN';

update app_user u
set user_type = 'AGENT', updated_at = now()
where exists (
    select 1
    from ledger_membership m
    where m.user_id = u.id and m.role = 'AGENT' and m.deleted_at is null
);

alter table app_user
    add constraint ck_app_user_type check (user_type in ('HUMAN', 'AGENT'));
