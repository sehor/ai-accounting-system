alter table voucher_line drop constraint ck_voucher_line_amount;
alter table voucher_line add constraint ck_voucher_line_amount check (
    original_amount <> 0 and exchange_rate > 0 and base_amount <> 0
);

alter table account_period_balance drop constraint ck_account_period_balance_amounts;

-- Existing projection rows have already lost the selected direction. Force the
-- asynchronous projector to rebuild them from opening balances and voucher lines.
delete from account_period_balance;
update balance_projection_state
set last_applied_event_id = 0,
    status = 'READY',
    attempts = 0,
    next_attempt_at = now(),
    updated_at = now()
where coalesce(last_enqueued_event_id, 0) > 0;
