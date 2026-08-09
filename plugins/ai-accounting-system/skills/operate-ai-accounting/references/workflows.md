# Accounting workflows

## Contents

1. Read and diagnose
2. Create or restore a ledger
3. Import base data
4. Process accounting documents
5. Create and post vouchers
6. Import vouchers
7. Fixed assets
8. Verify and report
9. Save accounting experience

## 1. Read and diagnose

Use the smallest read set that answers the question:

1. Resolve the exact ledger and retain its ID.
2. Read accounts and periods only when relevant.
3. Read the prior same-type vouchers or sub-ledger when accounting treatment is at issue.
4. Use reports for totals and balances; do not infer balances from a partial voucher list.
5. Return the evidence and conclusion. Do not mutate data during diagnosis. Do not load accounting experience during diagnosis unless the requested outcome is a voucher or voucher draft.

## 2. Create or restore a ledger

Ledger creation and membership administration may not be exposed through the MCP server. Use only available first-party tools. If creation is absent, report that the approved REST/admin path is required; do not invent an MCP tool.

After creation or restore, verify:

- exact ledger name and ID;
- accounting standard and version;
- base currency and start date;
- status and approval setting;
- current user's membership role.

Never overwrite an existing ledger during restore. Prefer a new restored ledger and verify it before handoff.

## 3. Import base data

Use this order because later records depend on earlier IDs:

1. Account list.
2. Dimensions and dimension values.
3. Accounting periods.
4. Opening balances, then confirmation.
5. Fixed-asset categories and cards.
6. Vouchers and attachments.

For account imports:

1. Preview the workbook.
2. Review every conflict, mapping, create or update decision.
3. Submit all row decisions with exactly one `decide_account_import_rows` call so validation and updates are atomic. If that batch tool is unavailable, stop and report a server contract mismatch; never loop over single-row calls.
4. Commit only the reviewed import.
5. List accounts and compare source count, imported count and missing codes.

For opening balances:

1. Confirm the source cutoff period and target opening period.
2. Map by exact account code and required dimensions.
3. Verify debit and credit totals before confirmation.
4. Confirm only after the imported rows and totals match the source.
5. Re-read balances and confirmation status.

## 4. Process accounting documents

1. Upload the original document with the correct ledger ID and file name.
2. Trigger extraction and check job or extraction status until terminal.
3. Inspect all extracted fields and source evidence. Do not trust extraction blindly.
4. Do not load accounting experience merely to upload or extract a document. For a voucher or voucher draft, use the matching bundled template skill; search ledger-specific experience separately only if needed.
5. Map each proposed line to an exact account ID, side, amount, summary, currency, dimensions and cash-flow item when required, using the experience only as guidance.
6. Present unresolved fields or accounting judgments to the user.
7. Create a voucher draft only when the requested workflow authorizes it.
8. Link or retain the source document according to available tools.

## 5. Create and post vouchers

Before creation, verify:

- ledger ID and open period ID;
- voucher date, type and number;
- concise summary;
- exact account IDs from the selected ledger;
- debit equals credit;
- currency and exchange rate;
- required dimensions, quantities and cash-flow items;
- whether approval is enabled.

Immediately before mapping voucher lines or creating a voucher draft, use the matching bundled template skill. Search `LEDGER` experience only when a company-specific issue remains unresolved.

Use an idempotency key derived from a stable source identifier when the tool supports one. After creation:

1. Read the voucher back.
2. Validate it when a separate validation tool exists.
3. Respect the requested state: draft, submitted, approved or posted.
4. If posting was authorized, post and re-read status.
5. Run the affected report or ledger query to verify the accounting result.

Do not assume a tool named `create_*draft*` leaves a draft; inspect its returned status because server behavior may evolve.

## 6. Import vouchers

1. Inspect the workbook format and source totals.
2. Verify all referenced account codes exist in the ledger.
3. Check every source voucher balances before import.
4. Use a content-derived idempotency key when supported.
5. Import once, then list vouchers and compare counts, lines, totals and statuses.
6. Run trial balance and relevant statements after import.

On a timeout, search for the idempotency key or imported vouchers before retrying.

## 7. Fixed assets

Before import, resolve required asset, accumulated depreciation, clearing, impairment, gain/loss and expense accounts from the ledger. Verify categories and dimensions.

After import, compare:

- card count;
- asset codes;
- original cost;
- accumulated depreciation;
- opening net book value;
- category and department mappings.

Do not generate depreciation or disposal entries unless the user explicitly requests that operation and the target period is verified.

## 8. Verify and report

Use at least the checks relevant to the write:

- source record count versus created record count;
- debit/credit balance;
- opening balance confirmation;
- voucher status and line count;
- account or dimension mapping completeness;
- fixed-asset totals;
- trial balance;
- balance sheet and income statement;
- document/extraction linkage;
- unusual rows and unresolved judgments.

Report exact ledger name and ID. Separate verified facts, assumptions and items requiring user review.

## 9. Save accounting experience

Save experience only after user confirmation or an unambiguous completed correction.

1. Search existing experience by ledger, keywords and tags.
2. Save confirmed company-specific knowledge as `LEDGER` experience with the exact ledger ID. Propose a plugin-skill update for a cross-ledger rule.
3. Prefer updating the matching active experience over creating a duplicate.
4. Use a short title, complete actionable content and useful search tags.
5. Re-read the saved experience and report its ID, scope and version.

If the MCP input schema cannot represent a valid `GENERAL` request, report the server contract defect. Do not write the experience table directly.
