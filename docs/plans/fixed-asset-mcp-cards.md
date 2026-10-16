# Fixed asset cards through MCP

Objective: expose existing fixed-asset category and card services so agents can query existing cards and create/update cards with department, acquisition voucher and accounting overrides. No schema or dependency changes; creating a card must not create acquisition accounting entries.

Implementation: add eight authenticated/audited FinanceMcpTools adapters; preserve service authorization, account/department/voucher validation, uniqueness and optimistic locking. Validate record constraints at the MCP boundary. Keep the operator catalog and whitelist test synchronized.

Acceptance: list/category/card readback works over the running local MCP endpoint; malformed requests fail before service writes; existing service errors remain unchanged; card creation returns all supplied accounting fields and creates no additional voucher. Run focused adapter tests, compile, then verify through the existing localhost service. Do not run a second Spring Boot test context or broad frontend checks.
