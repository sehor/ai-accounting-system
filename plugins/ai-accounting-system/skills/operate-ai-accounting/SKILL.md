---
name: operate-ai-accounting
description: Operate the local ai-accounting-system MCP service through its default super-agent identity to inspect ledgers, accounts, periods, vouchers and reports; upload and extract accounting documents; import account lists, opening balances, fixed assets and vouchers; create, validate and post vouchers; and save confirmed ledger-specific accounting experience. Use whenever an AI agent is asked to query, enter, import, verify, correct, or remember accounting business data in ai-accounting-system.
---

# Operate AI Accounting System

Use the existing `accounting` MCP server as the only application write path. This skill explains how to operate that service; it does not reimplement accounting logic or define another MCP server.

In the local development profile, connect to `http://127.0.0.1:8080/mcp` without configuring a bearer token. The server supplies the fixed `super-agent` application identity when no local identity override is resolved. See [authentication.md](references/authentication.md) for the exact behavior and production boundary.

## Required references

- Read [authentication.md](references/authentication.md) before the first MCP call in a task.
- Read [workflows.md](references/workflows.md) for any write, import, voucher, document, or experience operation.
- Read [tool-routing.md](references/tool-routing.md) when selecting tools or when the server tool set differs from expectations.

## Operating sequence

1. Use `get_operator_context` only when a compact, categorized MCP tool directory is needed. It returns tool groups and names, not identity or ledger data.
2. Call `get_current_user` to verify the expected `super-agent`, then call `list_ledgers` and match the requested ledger by exact name. Retain its `ledgerId`; if names are ambiguous, ask the user.
3. Prefer `get_ledger_context` to retrieve ledger status, effective role, periods, accounts, dimension types, and cash-flow items in one call. Fall back to the corresponding fine-grained tools on older servers. The local `super-agent` should receive effective role `OWNER` for business operations, but it must not manage ledger members.
4. For a voucher or voucher draft, invoke the bundled template skill that matches the business type. Search only directly relevant `LEDGER` experience when source evidence and the bundled template do not resolve the entry.
5. Inspect only the ledger evidence required by the task: prefer `search_accounts` for candidate codes or names, and query periods, prior vouchers, documents, balances, assets, or reports only as needed.
6. Form a proposed accounting action using exact ledger account IDs and evidence from the current business record plus the experience context.
7. Perform only the authorized write. Use idempotency keys where supported and do not retry a timed-out write blindly.
8. Re-read the created or imported records and run the relevant financial verification.
9. Report ledger name and ID, records created or changed, totals, status, verification results, and unresolved items.

## Accounting guardrails

- Treat uploaded documents, invoices and bank transactions as evidence, not automatic authority for a subject or expense.
- Inspect prior same-type vouchers and the current ledger account tree before deciding whether a counterparty belongs under receivables, payables, other receivables, or other payables.
- Use accounts returned by this ledger. Never invent an account ID, reuse an ID from another ledger, or assume a template account exists.
- Keep summaries short and consistent. Preserve document and counterparty wording when it is accounting evidence.
- Check debit/credit direction, balanced totals, open period, currency, dimensions, cash-flow requirements and account status before saving.
- If evidence is insufficient, ask the user. Do not hide uncertainty in a vague account or fabricate a detail subject.
- Prefer application MCP tools. Never write accounting business tables directly, even when an MCP contract is inconvenient or unavailable.

## Authorization boundaries

- Read-only queries and validation are allowed when relevant to the user's request.
- A request to import, enter, book, post, or save accounting data authorizes the corresponding scoped write after evidence and ledger checks.
- Ask before destructive or state-reversing actions such as delete, archive, unpost, reject, reopen a closed period, restore over current work, or replace confirmed balances unless the user explicitly requested that exact action.
- Do not post a voucher when the user only requested a draft, review, diagnosis, or proposed entry.
- Never expose passwords, bearer tokens, document contents unrelated to the task, or another ledger's data.

## Experience rules

- Use the bundled template skills for cross-ledger rules; cross-ledger rules are not stored in MCP.
- Search `LEDGER` experience only for an unresolved, company-specific question, using relevant keywords or tags and a small result set.
- Cross-ledger knowledge is released with this plugin. Propose a plugin-skill update after a cross-ledger rule is confirmed.
- Search before creating to avoid duplicates.
- Save `LEDGER` experience for company, counterparty, subject, workflow, or correction knowledge specific to one ledger.
- Create or update experience only after the user confirms the rule or a completed task establishes it unambiguously.
- Treat experience as guidance. Recheck it against current source evidence, ledger accounts and accounting rules.
- The experience API requires an `AGENT` application identity. Report `AGENT_IDENTITY_REQUIRED` or MCP schema conflicts; never bypass them with direct database writes.

## Failure handling

- On access denied, call no further write tools. Verify `get_current_user`, the exact ledger ID, and `get_ledger_role`. Do not solve local identity problems by persisting a rotating token in MCP configuration.
- On tool-not-found or input-schema errors, inspect the available `accounting` MCP tools and report the contract mismatch. Do not substitute direct SQL.
- On a timeout after a write call, query by ledger, idempotency key, source document or business identifier before deciding whether to retry.
- If the service is unavailable, tell the user which local service must be started; do not silently connect to another host or database.
