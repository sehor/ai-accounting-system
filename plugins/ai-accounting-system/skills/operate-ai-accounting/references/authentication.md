# Authentication and identity

The plugin connects to `http://127.0.0.1:8080/mcp`. Under the `local` Spring profile, omit Bearer-token and `X-User-Id` overrides so the service selects:

- name: `super-agent`
- ID: `00000000-0000-4000-8000-000000000099`
- type: `AGENT`
- effective ledger role: `OWNER`

Call `get_current_user` before a write. If the identity differs, stop and report it; do not change credentials, headers, memberships, or server configuration unless explicitly asked.

`super-agent` may perform ledger business operations but may not list or mutate ledger members. `SUPER_AGENT_USER_MANAGEMENT_FORBIDDEN` is expected for member administration.

If the connector is unavailable, verify the configured local URL and ask for the backend or connector to be restarted. If tools remain missing after restart, reload the connector's cached catalog. Never substitute another service or direct database access.

Production does not use local auto-login and still requires its normal Bearer JWT authentication. Accounting experience tools additionally require an `AGENT` identity and ledger membership for `LEDGER` scope.
