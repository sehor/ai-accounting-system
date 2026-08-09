# Authentication and identity

## Connection

The plugin connects to the local streamable HTTP endpoint:

`http://127.0.0.1:8080/mcp`

For the `local` Spring profile, configure the MCP connector with this URL only. Do not set `bearer_token_env_var`, `ACCOUNTING_MCP_TOKEN`, or a generated access token for the default path. The service performs local auto-login on each request, so token rotation is irrelevant to normal local MCP operation.

The local request identity is resolved as follows:

1. A valid configured development Bearer token selects its configured development user.
2. Otherwise, an intentional `X-User-Id` header selects that local user.
3. When neither override resolves an identity, the service injects the fixed `super-agent` ID.

Do not send either override during normal MCP work. They exist only for deliberate local debugging. OIDC disables this local filter and auto-login path; production continues to require its normal Bearer JWT authentication.

## Default operator

- Expected local MCP operator name: `super-agent`.
- Expected local MCP operator ID: `00000000-0000-4000-8000-000000000099`.
- Expected local MCP operator type: `AGENT`.
- Expected `get_ledger_role` result: effective role `OWNER`.
- The `local` profile auto-provisions this user at service startup and stores an `EDITOR` membership in every active ledger. Authorization maps that membership to effective `OWNER` for business operations, including approval, period close/reopen, backup, and ordinary writes.
- Member lookup and member add, update, and remove operations remain forbidden. Treat `SUPER_AGENT_USER_MANAGEMENT_FORBIDDEN` as the intended boundary, not an authentication failure.
- The MCP endpoint does not accept a username/password pair as a tool argument. Never send credentials to an MCP tool.

At the start of every task:

1. Call `get_current_user`.
2. Check the exact ID, display name, `AGENT` type, and active status against the values above.
3. Call `list_ledgers`, select the requested ledger by exact name, and retain its ID.
4. Call `get_ledger` and `get_ledger_role`; expect an active ledger and effective role `OWNER`.
5. Use the requested business tool. Do not attempt ledger-member administration as `super-agent`.

If the local MCP identity does not correspond to `super-agent`, stop before writing and report:

- the returned display name and user ID;
- the selected ledger name and ID, if known;
- whether the failure is identity, membership, or role related.

Do not change users, membership, roles, tokens, headers, or server environment values unless the user explicitly asks for that administrative change.

## Local service checks

When the MCP connector is unavailable:

1. Confirm the target is exactly `127.0.0.1:8080`; do not browse for or select another accounting service.
2. Ask the user to start the local `ai-accounting-system` backend if it is not running.
3. If `get_current_user` is not the expected `super-agent`, inspect the MCP connector for a bearer-token environment setting or injected identity header. Remove the override for the normal local path, reconnect the MCP server, and call `get_current_user` again.
4. If the identity is correct but a ledger is missing, restart the local backend once so startup synchronization can provision current memberships, then call `list_ledgers` again.
5. If `get_ledger_role` is not `OWNER`, report the server/plugin version mismatch instead of changing database roles directly.
6. If a newly added MCP tool is missing after a backend restart, reconnect or reload the MCP connector so its cached tool catalog refreshes.
7. Start no replacement database and perform no direct SQL write as a workaround.

## Experience access

Accounting experience tools require an application identity with `UserType.AGENT`. Ledger-specific experience additionally requires ledger membership. An otherwise valid human or owner identity may use other MCP tools but still receive `AGENT_IDENTITY_REQUIRED` for experience operations.
