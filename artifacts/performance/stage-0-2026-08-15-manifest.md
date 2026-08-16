# Stage 0 performance baseline — 2026-08-15

## Scope and environment

- Source commit before the uncommitted stage 0 changes: `270959e35c2138b50d3794bddb00f11c4bf7c9bd`.
- Database: existing `127.0.0.1:5432/ai-accounting-test`, PostgreSQL 16.10. Each benchmark created, migrated, and cleaned an isolated schema.
- Benchmark test runtime: Java 21 as reported by the Maven test process.
- Frontend observation runtime: Node 22.16.0, pnpm 9.12.3.
- Existing test service: `GET http://127.0.0.1:18080/actuator/health` returned HTTP 200 and `UP`.
- No service was started for this baseline. The benchmark test contexts did not listen on an HTTP port.

## Reproduction commands

```powershell
rtk powershell -NoProfile -Command ".\tools\run-accounting-projection-workload-benchmark.ps1 -Periods 20 -VouchersPerPeriod 500 -Warmups 5 -Iterations 30 -TestDbUrl 'jdbc:postgresql://127.0.0.1:5432/ai-accounting-test'"
rtk powershell -NoProfile -Command ".\tools\run-balance-projection-benchmark.ps1 -VoucherLines 10000 -Warmups 2 -Iterations 10 -TestDbUrl 'jdbc:postgresql://127.0.0.1:5432/ai-accounting-test'"
```

Machine-readable results:

- `artifacts/performance/stage-0-2026-08-15-accounting-workload.json`
- `artifacts/performance/stage-0-2026-08-15-balance-10k.json`

## Workload results

| Metric | Samples | p50 | p95 | Mean |
| --- | ---: | ---: | ---: | ---: |
| Posted create transaction | 30 | 15 ms | 17 ms | 14.67 ms |
| Posted create worker catch-up | 30 | 183 ms | 242 ms | 190.43 ms |
| Posted update transaction | 30 | 7 ms | 14 ms | 7.70 ms |
| Posted update worker catch-up | 30 | 181 ms | 193 ms | 185.60 ms |
| Trial balance projection | 30 | 1 ms | 6 ms | 2.17 ms |
| Trial balance with parents projection | 30 | 1 ms | 2 ms | 1.17 ms |
| General ledger projection | 30 | 2 ms | 3 ms | 2.10 ms |
| Sub-ledger projection | 30 | 10 ms | 13 ms | 10.27 ms |

`futurePeriodProjectionChanged=true`. Transaction metrics end when the voucher fact and outbox transaction returns. Worker metrics separately time the explicit catch-up that follows the corresponding write. The worker result is the pre-stage-4 full-tail rebuild algorithm; it is not an HTTP or query metric.

## Direct JDBC query results

The 10k-row smoke run used 100 accounts and measured direct JDBC SQL only:

| Path | Samples | p50 | p95 | Mean |
| --- | ---: | ---: | ---: | ---: |
| Legacy aggregation | 10 | 12 ms | 13 ms | 12.00 ms |
| Projection | 10 | 1 ms | 1 ms | 0.80 ms |
| Freshness check plus fallback | 10 | 12 ms | 14 ms | 12.50 ms |

The existing PostgreSQL 16.10 1m-row query result remains in `artifacts/performance/balance-projection-explain.out.log`: legacy p95 1449 ms, projection p95 1 ms, fallback p95 2279 ms. It is historical direct-JDBC evidence only and must not be represented as HTTP latency or worker propagation time.

## Frontend observation

Stage 0 forbids a repository-wide frontend build, so the existing unverified `frontend/dist` output was inspected without rebuilding it:

| Asset | Raw bytes | gzip bytes | Timestamp |
| --- | ---: | ---: | --- |
| `index-Co4tM29c.js` | 1,526,051 | 480,610 | 2026-08-02 15:49:20 |
| `index-DNoESK-A.css` | 735 | 467 | 2026-08-02 15:49:20 |

Only one JavaScript asset exists, so this output has no observable route chunks. Because its source commit and build flags are unknown, these sizes are an observation, not a reproducible acceptance baseline.

## Required matrix coverage and explicit gaps

| Required area | Stage 0 evidence | Status |
| --- | --- | --- |
| Projection: 20/60/120 periods, 100/1000 accounts, 1k/10k/100k dimension combinations | 20 periods and the current standard ledger workload captured; the benchmark has no parameters for account or dimension cardinality | Partial; harness gap |
| Detail ledger: 10k/100k/1m matching rows, page 1/page 100 | Current workload captures one sub-ledger projection query; historical SQL benchmark is balance aggregation, not detail pagination | Missing; no dedicated harness |
| Audit: 10k/100k rows, TTFB, payload, execution plan | The existing 18080 service is healthy, but no identified 10k/100k audit fixtures or safe account credentials were available | Skipped; data/identity prerequisite missing |
| Voucher: 2/50/500 lines, SQL count and transaction duration | Current workload captures two-line transaction duration only; no SQL-count instrumentation or 50/500-line fixture exists | Partial; instrumentation gap |
| Frontend: initial gzip, route chunks, cold-cache LCP, editor waterfall | Existing bundle sizes recorded without rebuilding; no route chunks; no frontend is served on the authorized 18080 endpoint | Partial; reproducible build/browser target missing |

These gaps are not filled with the existing PostgreSQL query benchmark because it does not cross the HTTP controller boundary and does not exercise asynchronous worker propagation. Stages 4 and 5 should add the missing parameterized harnesses before claiming improvements for those cells.

## Verification

- `rtk pnpm --dir frontend exec vitest run src/pages/VoucherPages.test.tsx`: 19 tests passed.
- `rtk .\mvnw.cmd -q -DskipTests test-compile`: passed.
- Accounting workload benchmark: 1 test passed, build success, 208.8 seconds test time.
- Balance query smoke benchmark: 1 test passed, build success, 6.733 seconds test time.
- `rtk rg -n "VoucherListPageLegacy" frontend/src`: no matches.
- `rtk git diff --check`: passed.
