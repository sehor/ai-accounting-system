-- During the rolling-release window an older binary may still write only
-- dimension_key and leave the new combination pointer null. Preserve the
-- original idempotency boundary for those rows until pointer coverage is 100%.
create unique index uk_opening_balance_legacy_key
    on opening_balance (ledger_id, period_id, account_id, currency, dimension_key)
    where dimension_combination_id is null;
