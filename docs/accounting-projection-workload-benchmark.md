# 分录投影与账簿查询基准测试

该基准使用 `ai-accounting-test`，并由 Spring 测试上下文为每次运行创建、迁移和清理独立 schema。测试在 20 个会计期间中生成已过账分录，在第 10 期采样已记账凭证创建和修改耗时；凭证事务只写事实与 outbox，随后显式排空 worker 以分别验证异步传播结果。

```powershell
.\tools\run-accounting-projection-workload-benchmark.ps1
```

默认负载为 20 期、每期 500 张分录、5 次预热、30 次采样。可通过 `-VouchersPerPeriod`、`-Warmups`、`-Iterations` 和 `-TestDbUrl` 调整。

基准输出的路径含义：

- `posted-create-and-project`、`posted-update-and-project`：真实 `VoucherService` 调用；计时范围是事实与 outbox 的同事务提交，投影传播由随后排空的 worker 完成。
- `trial-balance-projection`、`trial-balance-with-parents-projection`：余额投影读取。
- `general-ledger-book-projection`：期初、范围发生额和期末从余额快照读取。
- `sub-ledger-book-projection`：期初从余额快照读取，只扫描所选期间范围内的 `voucher_line`。

当前 `account_period_balance` 按“账套、期间、科目”保存期初借/贷、当期发生借/贷、期末借/贷六字段，并物化所有父科目。修改第 10 期已记账凭证后，worker 会从第 10 期重建到最后期间，基准以未来期间投影校验确认传播已经发生。只要会计期间不是 `CLOSED`，已过账凭证也可直接修改或物理删除；服务在同一事务中同步写入余额投影差额或冲减事件。

完整性能验收应使用长历史数据，并对总账、明细账、试算平衡表的快路径执行 `EXPLAIN (ANALYZE, BUFFERS)`：总账和试算平衡表不得访问 `voucher_line`，明细账只允许访问 `periodFrom..periodTo`。同时记录查询及 worker 传播的 p50/p95；本脚本输出查询与凭证事务的 p50/p95，数据库执行计划需随目标环境的数据规模单独归档。
