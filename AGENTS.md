## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

When the user types `/graphify`, invoke the `skill` tool with `skill: "graphify"` before doing anything else.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- Dirty graphify-out/ files are expected after hooks or incremental updates; dirty graph files are not a reason to skip graphify. Only skip graphify if the task is about stale or incorrect graph output, or the user explicitly says not to use it.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).

## Verification

### Backend (Java)

- Prefer verifying against an already running backend at `http://127.0.0.1:8080`. Do not start a second Spring Boot test context or run `mvn test` just to verify a small code change.
- To apply and verify backend changes while a service is running:
  1. Run `.\mvnw.cmd -q -DskipTests compile`. `spring-boot-devtools` watches `target/classes` and restarts the running backend automatically.
  2. Call the affected HTTP endpoint(s) on `localhost:8080`.
- When a test database is needed, run `.\start-backend-test.ps1`. By default it starts a second backend on `18080` using `ai-accounting-test` and leaves the normal `8080` backend running. Use `.\start-backend-test.ps1 -Port 8080` only when a single service with the test database is wanted.
- Run a Java test class only when HTTP verification is insufficient or the user explicitly asks for a JUnit test.

### Frontend

- Do not run repository-wide `pnpm test`, `pnpm lint`, `pnpm typecheck`, or `pnpm build` unless the user explicitly approves it for the current task.
- If no targeted verification exists, report that limitation and ask before running a broader check.
