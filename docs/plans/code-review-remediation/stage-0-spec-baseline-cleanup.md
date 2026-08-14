# 阶段 0：规格、性能基线与低风险清理

## 目标

先消除规格矛盾、删除确定无用代码，并建立后续性能优化所需的可重复基线。本阶段不改会计业务行为。

## 实施任务

### 1. 规格对齐

- 仅修改 PRD 第 55 行为：

  > Agent 仅能通过固定 MCP 工具记账；启用审批时，可按账套配置和既定自动流程完成审批与记账，不强制要求人类审批。

- 不改其他 Agent 权限、MCP 工具或相关测试。
- 在相关规格中明确后续阶段采用的规则：
  - 普通凭证可物理删除，但审计只追加并永久保留；
  - 来源生成凭证由来源模块拥有；
  - 固定资产参数只允许当前开放期间立即生效；
  - 固定资产处置只能整体撤销；
  - 法定报表不使用科目显示名称分类；
  - GET 结账状态不得写数据库。

验收：PRD/TDD/专题规格不再同时声称“删除凭证时删除审计”和“审计不可删除”。

### 2. 删除旧凭证列表

- 删除 `frontend/src/pages/VoucherPages.tsx` 中无消费者的 `VoucherListPageLegacy`。
- 清理仅由旧实现使用的 import/helper。
- 保留正式 `VoucherListPage.tsx` 的路由和导出。

验收：`rtk rg -n "VoucherListPageLegacy" frontend/src` 无命中；相关定向 Vitest 通过。

### 3. 修正并采集性能基线

- `AccountingProjectionWorkloadBenchmarkTest` 将 worker drain/catch-up 单独计时；voucher create/update 指标不得隐含 worker 耗时。
- 统一测试默认凭证数与 benchmark 文档口径。
- 记录：
  - 投影：20/60/120 期，100/1000 科目，1k/10k/100k 维度组合；
  - 明细账：10k/100k/1m 命中行，page 1/page 100；
  - 审计：10k/100k 行的 TTFB、payload 和执行计划；
  - 凭证：2/50/500 行 SQL 次数和事务时长；
  - 前端：初始 gzip、路由 chunk、冷缓存 LCP 和编辑器 waterfall。

不得把已有 PostgreSQL 16 查询 benchmark 当作 HTTP 或 worker 传播结果。

## 编排建议

- `mechanic/spec-cleanup`：PRD/规格精确修改和旧前端实现删除。
- `explorer/performance-baseline`：只读核对 benchmark 入口、口径和输出。
- 主代理负责执行必要基线并归档数据。

## 验证和交接

- 后端 benchmark 若需数据库，只使用现有测试环境，不启动服务。
- 前端只运行与旧列表相关的定向 Vitest；本阶段不运行全量 build。
- 交接必须附基线结果路径，供阶段 4/5 直接比较。

