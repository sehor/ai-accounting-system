-- Cash flow statement aggregation scans voucher lines by ledger, cash account,
-- voucher and cash flow item.  The statutory cash flow queries filter on
-- (ledger_id, account_id) within a posted-period range and later group by item
-- code, so a leading (ledger_id, account_id) index bounds the scan.
create index ix_voucher_line_cash_flow_report
    on voucher_line (ledger_id, account_id, voucher_id, cash_flow_item_id);
