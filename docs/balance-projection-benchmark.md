# 科目余额投影基准测试

基准测试不使用共享账套。每次运行都会：

1. 连接 `DB_URL` 指定的 PostgreSQL；
2. 创建随机名称 `bench_balance_<uuid>` schema；
3. 使用项目全部 Flyway 迁移创建表结构；
4. 生成默认 1,000,000 条 `voucher_line` 和对应的已过账凭证；
5. 创建等价的 `account_period_balance` 投影数据；
6. 分别测量 legacy 明细汇总、projection 查询和 status 检查后 fallback 查询；
7. 校验三路结果行数与借贷净额一致；
8. 删除整个测试 schema。

## 执行

```powershell
rtk powershell -NoProfile -Command ".\tools\run-balance-projection-benchmark.ps1"
```

小规模冒烟：

```powershell
rtk powershell -NoProfile -Command ".\tools\run-balance-projection-benchmark.ps1 -VoucherLines 10000 -Warmups 2 -Iterations 10"
```

输出为 JSON，包含每一路的 `p50Ms`、`p95Ms`、`p99Ms`、最小/最大耗时、平均耗时、结果行数和 checksum。数据库连接通过 `TEST_DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 环境变量配置；默认连接本机 `ai-accounting-test`。

设置 `BENCHMARK_EXPLAIN=true` 可在采样后输出 legacy 与 projection 查询的 `EXPLAIN (ANALYZE, BUFFERS)`，用于定位扫描和临时文件 I/O。

正常完成时只删除本次 schema。如果上一次进程被强制终止，可在确认没有其他基准运行时增加 `-CleanupStale`，清理此前遗留的 `bench_balance_*` schema；该操作会删除这些专用基准 schema。

该基准在 PostgreSQL 16.10 上可执行；最终验收仍应在目标 PostgreSQL 17 环境重复运行。基准只测数据库查询耗时，不代表完整 HTTP API P95。

## 本机实测记录

2026-08-07，本机 PostgreSQL 16.10、1,000,000 条 `voucher_line`、100 个科目、5 次预热、30 次采样：

| 路径 | P50 | P95 | P99 |
| --- | ---: | ---: | ---: |
| legacy 明细汇总 | 2154ms | 2718ms | 3359ms |
| projection 余额表 | 0ms | 1ms | 1ms |
| fallback（状态检查 + 明细汇总） | 1504ms | 2086ms | 2761ms |

三路返回均为 100 行，借贷净额 checksum 一致。该记录仅用于本机基线，不替代 PostgreSQL 17 和完整 HTTP API 验收。
