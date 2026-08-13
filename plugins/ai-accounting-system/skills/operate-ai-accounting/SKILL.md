---
name: operate-ai-accounting
description: "Operate the ai-accounting-system MCP service for ledgers, accounts, documents, vouchers, imports, reports, and ledger-specific accounting experience. Use for any accounting-system query or mutation; coordinate ledger selection, evidence lookup, optional business-specific skills, and the requested MCP operation."
---

# Operate AI Accounting System

Use the `accounting` MCP service for all system reads and writes. Never write accounting tables directly.

## Route the task

1. Resolve the exact ledger, then use `get_ledger_context` for its IDs and current configuration.
2. Base accounting treatment on source evidence, the ledger's accounts, and relevant prior vouchers. Load a bundled skill only for its matching business type:
   - Bank transactions: `process-bank-statements`
   - Payroll accrual: `accrue-payroll`
   - Month-end VAT: `handle-month-end-vat`
   - Simplified month-end sales or service cost: `close-month-end-sales-cost`
   - Received invoices: `process-received-invoices`
   - Issued invoices: `process-issued-invoices`
   - Create or verify an accounting account: `create-accounting-account`
3. Ask the user when company-specific treatment remains ambiguous; never invent an account, detail account, or business purpose.
4. Call the MCP operation that matches the requested end state and report the returned IDs, status, and unresolved items.

## Handle spreadsheet source materials

- For `.xlsx`, `.csv`, and `.tsv` source files or intermediates, load the `Spreadsheets` skill and use `@oai/artifact-tool` exclusively to read, inspect, transform, render, or create workbooks.
- Send a workbook directly to an `accounting` MCP import operation when its installed schema accepts that exact format and no preprocessing is needed. The MCP operation remains responsible for validation and accounting-system writes.
- Treat legacy binary `.xls` as unsupported by `@oai/artifact-tool`. Pass it through unchanged only when the exact `accounting` MCP import operation explicitly accepts `.xls`; otherwise stop and ask for conversion to `.xlsx`.
- Never install, download, import, or invoke `openpyxl`, `pandas`, `xlrd`, `xlsxwriter`, or another spreadsheet library or ad hoc Excel parser. Never create a local dependency directory for spreadsheet processing.
- Export newly created or transformed workbooks as `.xlsx`. Keep spreadsheet calculations auditable with workbook formulas when applicable.

## System-specific constraints

- Voucher mutation has one accounting lock only: a voucher cannot be updated or deleted when its accounting period is `CLOSED`.
- In every non-closed period, call `update_voucher` or `delete_voucher` directly for manual, generated, imported, draft, validated, approved, or posted vouchers. Do not require unposting, reversal, or a compensating voucher. Deletion is permanent and the service updates the balance projection in the same transaction.
- The local profile normally authenticates as the fixed `super-agent`. Verify it before a write; read [authentication.md](references/authentication.md) only for identity details or failures.
- `super-agent` has effective `OWNER` access to business operations but cannot administer ledger members.
- For account imports, use `preview_account_import`, submit all row decisions in one `decide_account_import_rows` call, then `commit_account_import`. Do not emulate a missing batch step with repeated single-row writes.
- Store only confirmed, company-specific rules as `LEDGER` experience. Cross-ledger rules belong in these plugin skills; experience operations require an `AGENT` identity.
- Treat the installed MCP tool names and input schemas as authoritative. Report a missing capability or contract mismatch instead of switching to REST, SQL, another host, or another database.
