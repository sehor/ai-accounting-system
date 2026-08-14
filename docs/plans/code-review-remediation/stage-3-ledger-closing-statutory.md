# 阶段 3：期初、结账状态与法定报表

前置：阶段 1 的追加式审计规则稳定。  
可与阶段 2 在不重叠文件的前提下独立实施。

## 目标

补齐期初余额审计，使结账状态查询成为纯读，并用稳定语义键替代科目名称驱动法定报表。

## 实施任务

### 1. 期初余额审计

- `REPLACE`、`IMPORT`、`CONFIRM` 在原事务内写 `aggregate_type=OPENING_BALANCE` 修订。
- 快照保存完整规范化明细、币种、汇率、辅助核算、确认状态、操作者和原因。
- 使用统一 fail-closed 审计序列化器。
- 继续使用当前期初余额存储，不建设批次版本表。
- 并发替换使用账套/期间锁，保证修订号和业务写入原子。

验收：连续替换、导入、确认均可还原前后值；审计失败时业务操作回滚。

### 2. 期末结账状态纯读

- `DefaultPeriodClosingService.status` 改为只读事务。
- GET 不调用 `ensureStep`；缺失步骤只在响应中表示为 `PENDING`。
- `STALE/BLOCKED` 根据当前指纹和凭证状态临时计算，不在 GET 中更新。
- 生成/重置命令负责创建或迁移持久状态，首次创建使用 UPSERT 或显式锁。
- 增加结账步骤重置命令，使用阶段 1 的来源凭证删除入口。

验收：VIEWER/AGENT 重复或并发 GET 前后数据库无 INSERT/UPDATE；并发生成只有一个有效步骤和来源凭证。

### 3. 法定报表稳定映射

- `ledger_account` 增加 `standard_account_key`；名称不参与计算，多个科目可映射同一语义键。
- 准则包账户定义和报表公式改为引用该键。
- 标准科目初始化、导入和创建时写入键；名称修改不得改变键。
- 自定义子科目通过已映射父科目汇总；自定义顶级科目需显式指定准则允许的键。
- 旧账套按准则版本、模板来源和原始编码回填。
- 无法唯一判断的遗留科目保持未映射，法定报表返回 `STATUTORY_ACCOUNT_MAPPING_REQUIRED`，禁止静默计零。

验收：任意改名后报表金额不变；歧义项明确阻断；报表与同期试算平衡表继续勾稽。

## 关键测试

- `OpeningBalanceAuditIntegrationTest`：REPLACE/IMPORT/CONFIRM、并发和失败回滚。
- `PeriodClosingIntegrationTest`：纯读 GET、并发首次生成、步骤重置。
- `StatutoryReportCalculatorTest`、`Stage4ReportingTest`：改名不变、未映射阻断、报表勾稽。

## 编排与完成条件

- 可拆为 `worker/opening-closing` 与 `worker/statutory-mapping`，但不得同时编辑共享 ledger DTO/migration。
- 会计 reviewer 复核期初快照、结账状态和报表映射后才能接受。
- 后端编译、定向测试和 18080 目标 HTTP 验证通过。

