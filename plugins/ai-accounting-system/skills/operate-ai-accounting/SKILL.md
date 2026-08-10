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
3. Ask the user when company-specific treatment remains ambiguous; never invent an account, detail account, or business purpose.
4. Call the MCP operation that matches the requested end state and report the returned IDs, status, and unresolved items.

## System-specific constraints

- The local profile normally authenticates as the fixed `super-agent`. Verify it before a write; read [authentication.md](references/authentication.md) only for identity details or failures.
- `super-agent` has effective `OWNER` access to business operations but cannot administer ledger members.
- For account imports, use `preview_account_import`, submit all row decisions in one `decide_account_import_rows` call, then `commit_account_import`. Do not emulate a missing batch step with repeated single-row writes.
- Store only confirmed, company-specific rules as `LEDGER` experience. Cross-ledger rules belong in these plugin skills; experience operations require an `AGENT` identity.
- Treat the installed MCP tool names and input schemas as authoritative. Report a missing capability or contract mismatch instead of switching to REST, SQL, another host, or another database.
