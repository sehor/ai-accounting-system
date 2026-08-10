# 分录投影与账簿查询基准测试

该基准使用 `ai-accounting-test`，并由 Spring 测试上下文为每次运行创建、迁移和清理独立 schema。测试在 20 个会计期间中生成已过账分录，在第 10 期采样分录创建和修改的端到端耗时；每次调用返回前，余额投影已同步完成。

```powershell
.\tools\run-accounting-projection-workload-benchmark.ps1
```

默认负载为 20 期、每期 500 张分录、5 次预热、30 次采样。可通过 `-VouchersPerPeriod`、`-Warmups`、`-Iterations` 和 `-TestDbUrl` 调整。

基准输出的路径含义：

- `posted-create-and-project`、`posted-update-and-project`：真实 `VoucherService` 调用及其同步投影更新。
- `trial-balance-projection`、`trial-balance-with-parents-projection`：余额投影读取。
- `general-ledger-book-fact-query`、`sub-ledger-book-fact-query`：当前实现直接读取 `voucher_line` 事实表，不读取余额投影。

当前数据模型的 `account_period_balance` 按“账套、期间、科目”保存当期发生额。修改第 10 期分录只更新第 10 期投影行，后续期间不会被物化改写；如果产品需要累计期末余额随前期修改逐期传播，需要另行实现该投影模型。已过账分录也不支持直接删除，必须先提供冲销或更正工作流后才能测量等价的删除操作。
