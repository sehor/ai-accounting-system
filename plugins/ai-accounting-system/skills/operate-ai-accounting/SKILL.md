---
name: operate-ai-accounting
description: "Operate the ai-accounting-system MCP service for ledgers, accounts, documents, vouchers, imports, reports, and ledger-specific accounting experience. Use for any accounting-system query or mutation; coordinate ledger selection, evidence lookup, optional business-specific skills, and the requested MCP operation."
---

# Operate AI Accounting System

Use the `accounting` MCP service for all system reads and writes. Never write accounting tables directly.

## Route the task

1. Resolve the exact ledger, then use `get_ledger_context` for its IDs and current configuration.
2. Base accounting treatment on source evidence, the ledger's accounts, and relevant prior vouchers. Load a bundled skill only for its matching business type:
   - Personal expense reimbursements: `process-personal-reimbursements`
   - Bank transactions: `process-bank-statements`
   - Payroll accrual: `accrue-payroll`
   - Month-end VAT: `handle-month-end-vat`
   - Simplified month-end sales or service cost: `close-month-end-sales-cost`
   - Received invoices: `process-received-invoices`
   - Issued invoices: `process-issued-invoices`
   - Create or verify an accounting account: `create-accounting-account`
3. Resolve material uncertainties for the entire requested bookkeeping batch before any accounting-system mutation. Ask the user about unresolved amounts, periods, accounts, counterparties, tax treatment, or conflicting evidence, then wait for an explicit answer. Continue read-only checks while waiting, but do not post the clear portion first, create system drafts, reclassify existing vouchers, or split the batch to bypass the unanswered question. If the user is unavailable or the turn must end without an answer, abandon execution of this batch and report that it remains unbooked with the pending questions. Silence, elapsed time, and a preselected option are not confirmation. Resume only after the user answers, rechecking current vouchers to prevent duplicates. Honor an explicit user instruction to process a specified independent subset.
4. Call the MCP operation that matches the requested end state and report the returned IDs, status, and unresolved items.

## Handle spreadsheet source materials

- For `.xlsx`, `.csv`, and `.tsv` source files or intermediates, load the `Spreadsheets` skill and use `@oai/artifact-tool` exclusively to read, inspect, transform, render, or create workbooks.
- Send a workbook directly to an `accounting` MCP import operation when its installed schema accepts that exact format and no preprocessing is needed. The MCP operation remains responsible for validation and accounting-system writes.
- Treat legacy binary `.xls` as unsupported by `@oai/artifact-tool`. Pass it through unchanged only when the exact `accounting` MCP import operation explicitly accepts `.xls`; otherwise stop and ask for conversion to `.xlsx`.
- Never install, download, import, or invoke `openpyxl`, `pandas`, `xlrd`, `xlsxwriter`, or another spreadsheet library or ad hoc Excel parser. Never create a local dependency directory for spreadsheet processing.
- Export newly created or transformed workbooks as `.xlsx`. Keep spreadsheet calculations auditable with workbook formulas when applicable.

## Reusable source processor

- Use the bundled [source processor](../../scripts/README.md) for supported bank and issued/received invoice XLSX inputs. Resolve `../../scripts/process-sources.mjs` relative to this skill, use the bundled Node runtime, and pass absolute input paths. Ordinary tasks must call the existing utility; do not create task-specific reading, normalization, classification, or verification scripts.
- Read all bank files for the same ledger/period in one `bank` invocation. The adapters normalize five supported formats, preserve provenance, validate amounts/balances, identify duplicates, and pair internal transfers. Keep both source references for a paired transfer but create only one accounting event. Read invoice summaries with `invoices`; retain multi-line invoices and review red statuses and buyer differences.
- Reuse the returned rows for the helper's `classify` and `reconcileVouchers` functions, or supply confirmed rules, current accounts, and current MCP vouchers in a JSON stdin request. Keep data in memory/stdin/stdout. Use the CLI if a restricted persistent runtime cannot load the bundled workbook library. Use `--summary` for compact validation, and explicit `--output` only for a requested file deliverable.
- Review unresolved classifications and all source issues before posting. The helper suggests transaction types; account selection needs confirmed ledger rules and active leaf accounts. Voucher reconciliation checks equality and bank totals, while MCP existing-voucher/reference/idempotency checks remain necessary to prevent offsetting duplicate or missing postings. The helper never writes to the accounting system.
- When a format is unsupported, inspect its headers and extend the permanent adapter with a regression test as plugin maintenance. Do not replace the shared utility with a one-off parser or silently guess a column mapping. For source workbook edits/exports not handled by this read-only helper, follow the spreadsheet skill using the task temporary directory.

## Working files

- Keep parsed rows, classifications, reconciliations, and voucher payloads in memory when the available runtime can retain them. Read each source once per processing pass and reuse the result.
- When files are necessary, allocate one task-specific directory under the system temporary directory (for example, `fs.mkdtemp(path.join(os.tmpdir(), "ai-accounting-"))`). Run helpers and intermediate export/render/verification there, using absolute source and staging paths, so previews and automatic `.inspect.ndjson` sidecars also stay there. Keep scripts, JSON intermediates, dependency links, and debug output out of the source folder.
- Reuse the bundled source processor for reading, classification, and verification; do not write another working driver for each task. Keep required spreadsheet checks; render previews only when a workbook is created or edited, and save those previews in the task temporary directory.
- For bookkeeping-only requests, return voucher numbers, status, and reconciliation totals. Create a final workbook or separate audit file only when requested or needed as a reviewable deliverable; copy only the verified deliverable from staging to the user's chosen destination, otherwise `results/`. A support file does not become a deliverable merely because it helped verification.
- When interruption recovery needs disk state, keep one compact checkpoint in the task temporary directory: source paths/hashes and row or invoice references, ledger/period IDs, confirmed decisions, idempotency keys, returned voucher IDs, and unresolved items. Re-read system state before retrying writes; memory or a checkpoint is not proof that a voucher was posted.
- After successful verification, remove only temporary files created by this task, after checking their resolved paths are inside its temporary directory. Keep the checkpoint while work is paused or needs recovery. Preserve source files, final deliverables, and pre-existing files; clean up an earlier task's files only when the user requests it.

## System-specific constraints

- Voucher mutation has one accounting lock only: a voucher cannot be updated or deleted when its accounting period is `CLOSED`.
- In every non-closed period, call `update_voucher` or `delete_voucher` directly for manual, generated, imported, draft, validated, approved, or posted vouchers. Do not require unposting, reversal, or a compensating voucher. Deletion is permanent and the service updates the balance projection in the same transaction.
- The local profile normally authenticates as the fixed `super-agent`. Verify it before a write; read [authentication.md](references/authentication.md) only for identity details or failures.
- `super-agent` has effective `OWNER` access to business operations but cannot administer ledger members.
- For account imports, use `preview_account_import`, submit all row decisions in one `decide_account_import_rows` call, then `commit_account_import`. Do not emulate a missing batch step with repeated single-row writes.
- Store only confirmed, company-specific rules as `LEDGER` experience. Cross-ledger rules belong in these plugin skills; experience operations require an `AGENT` identity.
- Treat the installed MCP tool names and input schemas as authoritative. Report a missing capability or contract mismatch instead of switching to REST, SQL, another host, or another database.
