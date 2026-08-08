update voucher
set status = 'POSTED'
where status = 'REVERSED';

alter table voucher drop constraint ck_voucher_status;

alter table voucher add constraint ck_voucher_status
    check (status in ('DRAFT', 'VALIDATED', 'SUBMITTED', 'APPROVED', 'POSTED', 'DELETED'));

create index if not exists ix_voucher_posted_period_date
    on voucher (ledger_id, period_id, voucher_date, voucher_number, id)
    where deleted_at is null and status = 'POSTED';
