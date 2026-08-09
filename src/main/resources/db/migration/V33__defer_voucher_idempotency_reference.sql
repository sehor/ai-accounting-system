alter table voucher_idempotency
    drop constraint if exists fk_voucher_idempotency_voucher;

alter table voucher_idempotency
    add constraint fk_voucher_idempotency_voucher foreign key (ledger_id, voucher_id)
        references voucher (ledger_id, id) on delete cascade deferrable initially deferred;
