# 阶段 4：后端性能

前置：阶段 0 已归档基线。  
本阶段每个优化必须和基线对比；未达到门槛不能仅凭静态推断宣布完成。

## 任务 A：审计游标分页

- `GET /v1/ledgers/{ledgerId}/audit?limit=&cursor=&aggregateType=&aggregateId=`。
- limit 默认 50、最大 200；非法 cursor 返回 422。
- 按 `(created_at DESC, id DESC)` 排序，响应 `{items,nextCursor,hasMore}`，不计算全量 count。
- cursor 为不透明编码，内部保存最后一项的 createdAt/id。
- 增加 `(ledger_id, created_at DESC, id DESC)` 索引；按执行计划决定是否补 aggregate 过滤索引。
- 前端迁移留给阶段 5，本阶段先完成 API/repository/测试。

验收：100k 行 p95 < 200ms，单响应 <= 200 行/200KB，无全量显式排序。

## 任务 B：余额投影按期间批处理

- 将无效的 `maxEvents/maxEventLines` 改为 `maxPeriods` 和配置 `accounting.balance.propagation-period-batch-size`。
- 账户/维度 rebuilder 支持 `[fromPeriod, throughPeriod]`，同一事务删除重建。
- worker 每批重新选择最早脏期，最多处理 B 个连续期间，只推进本批水位。
- 每批必须重新查询最早脏期；批间更早写入时不能从旧 through+1 继续。
- 保留旧全尾部算法开关一个发布周期。
- 指标增加 processed periods、重建行数、oldest pending age、remaining dirty periods。

验收：每事务期间数 <= B；账户/维度任一失败时投影和水位整批回滚；最终 checksum 与旧算法一致；锁持有 p95 降低 >= 70%，单批 p95 目标 < 500ms。

## 任务 C：事件清理分批化

- `cleanupAppliedEvents(cutoff,batchSize)` 用 CTE 选择最多 1000 个事件 ID，再删除并级联事件行。
- 增加 `(created_at,id)` 索引。
- 调度器一次最多执行固定批数，每批独立事务；暴露删除数、积压和最老事件年龄。

验收：不删除未应用/FAILED 事件；单事务 <= 1000 个事件且 p95 < 500ms。

## 任务 D：明细账扫描

- 将总借方/贷方并入当前窗口查询，删除第二次 voucher_line 范围扫描。
- 保持分页总数、期初期末和逐行余额不变。
- 若单扫描后 1m 行深页 p95 仍比首页高 20%以上或发生磁盘 sort spill，则继续实施按科目/日期累计 checkpoint + keyset；达到门槛才结束。

## 任务 E：凭证批量写入

- 一次预取唯一科目、控制项、维度类型和值。
- 服务层预生成 line UUID，分录和维度使用 JDBC batch。
- 借贷、数量单价、币种金额和控制项在规范化数据上一次校验；保留最终数据库不变量检查。
- 不新增凭证行数上限。

验收：500 行 SQL 数下降 >= 80%，事务 p95 下降 >= 50%；幂等、审计、投影、辅助核算和失败回滚不变。

## 关键测试

- worker B=2、5 个未来期：每次最多推进两个期间，最终全部 fresh。
- 批间写入更早期：下一批回到新的最早脏期。
- 两 worker 可处理不同账套，同账套不得并发。
- 账户/维度第二阶段失败时整批回滚。
- 审计 100k 行翻页无重复/遗漏。
- 清理不删除未应用或失败事件。
- 500 行凭证与旧实现产生相同分录、维度、审计和投影。

优先扩展：`RollingBalanceProjectionIntegrationTest`、`AccountingProjectionWorkloadBenchmarkTest`，新增审计分页和 cleanup 集成测试。

## 编排与发布

- `worker/projection-performance` 独占 projection/rebuilder 文件。
- `worker/audit-write-performance` 负责分页、cleanup、voucher batch，避免与阶段 1 同时编辑。
- `reviewer/data-integrity` 复核投影水位、锁和批量写入事务。
- 投影范围批处理先开关灰度，观察锁等待、积压、失败重试、checksum、WAL 和临时文件。
- 手工 balance rebuild job 的跨事务批处理不在本阶段。

