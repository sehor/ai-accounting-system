create unique index uk_voucher_reversal_of
    on voucher (ledger_id, reversal_of_id)
    where reversal_of_id is not null and deleted_at is null;
