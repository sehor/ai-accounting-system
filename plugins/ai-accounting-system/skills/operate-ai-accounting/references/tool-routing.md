# MCP tool routing

Tool availability and schemas are authoritative. Inspect the installed `accounting` MCP tools when a name below is absent or its parameters differ.

## Identity and ledger selection

- `get_operator_context`: compact categorized MCP tool directory; returns no identity or ledger data.
- `get_ledger_context`: preferred ledger bootstrap; returns ledger, role, periods, accounts, dimension types and cash-flow items.
- `get_current_user`: verify authenticated identity.
- `list_ledgers`: find ledgers visible to the identity.
- `get_ledger`: verify one ledger's configuration and status.
- `get_ledger_role`: verify authorization for one ledger.
- `update_ledger`: rename a ledger when the user explicitly requests the exact target and new name.
- `list_periods`: resolve exact period IDs and states.

For the local `super-agent`, `get_ledger_role` returns effective role `OWNER` for business tools. This does not grant ledger-member administration.

## Accounts and base data

- `search_accounts`: preferred account lookup by code or name. Use `EXACT` when the source provides a complete code/name and `FUZZY` for partial wording; results include the matched account plus its immediate parent and children.
- `list_accounts`, `get_account`: read the full ledger account tree or retrieve an already-resolved account ID.
- `ensure_account`, `create_account`, `update_account`, `delete_account`: manage individual accounts; use deletes only with explicit authorization.
- `preview_account_import`, `get_account_import`, `decide_account_import_rows`, `commit_account_import`: reviewed account import workflow. `decide_account_import_rows` is mandatory for all multi-row imports; if it is absent, report a server contract mismatch instead of issuing repeated single-row calls.
- `export_account_template`, `export_accounts`: retrieve supported formats.
- `list_dimension_types`, `list_dimension_values`, `create_dimension_type`, `create_dimension_value`: dimension setup.
- `list_opening_balances`, `import_opening_balances`, `replace_opening_balances`, `confirm_opening_balances`: opening-balance workflow.

## Documents and extraction

- `upload_document`, `list_documents`, `get_document`, `download_document`: source-document lifecycle.
- `extract_document`, `list_document_extractions`, `get_job_status`: extraction and review.
- `create_voucher_draft_from_document`: create from a reviewed extraction when appropriate.

## Vouchers

- `list_vouchers`, `get_voucher`: read and verify.
- `create_voucher_draft`, `create_voucher`: create using exact ledger IDs and account IDs.
- `validate_voucher`, `submit_voucher`, `approve_voucher`, `reject_voucher`, `post_voucher`: advance state only as authorized.
- `update_voucher`, `reverse_voucher`, `unpost_voucher`, `delete_voucher`, `restore_voucher_revision`: corrective operations requiring exact targets and explicit scope.
- `import_kingdee_vouchers`, `export_kingdee_vouchers`: batch exchange.

Do not assume every listed state tool exists in every server version. Follow the tool schema currently exposed.

## Fixed assets

- `import_fixed_assets`: import a reviewed `.xlsx` workbook.
- If category, depreciation, disposal or detail tools are not exposed through MCP, report the required first-party REST/admin path rather than inventing a tool.

## Reports and verification

- `finance_query`: standard report query.
- `finance_query_advanced`: metric, period range, grouping and filtered queries.
- `export_report`: export a supported report.

Use trial balance after material imports or voucher batches. Use balance sheet, income statement, general ledger or sub-ledger according to the affected accounts.

## Backup and restore

- `backup_ledger`: create a recoverable ledger backup.
- `restore_ledger`: restore as a new ledger when supported.

Confirm targets and output handling. Never overwrite or discard an existing ledger as an implicit restore step.

## Accounting experience

- `search_accounting_experiences`: search only directly relevant `LEDGER` experience for an unresolved company-specific question. Use the bundled template skills for cross-ledger rules.
- `create_accounting_experience`: save confirmed `LEDGER` experience. Cross-ledger rules require a plugin-skill update.
- `update_accounting_experience`: revise using the expected version.
- `archive_accounting_experience`: soft-archive only on explicit request.

Experience operations require an `AGENT` application identity. Preserve optimistic version checks.

## Tool selection rules

1. Prefer a purpose-built MCP tool over manual REST, scripts or database access.
2. Use read tools before write tools to resolve exact IDs and current versions.
3. Use preview/decide/commit workflows when offered.
4. Prefer create-draft plus explicit state transitions when the user's request does not authorize immediate posting.
5. Treat returned status and IDs as authoritative; names alone are not enough for follow-up writes.
6. When a required capability is absent, report the missing capability and the supported first-party fallback. Do not silently switch hosts or databases.
