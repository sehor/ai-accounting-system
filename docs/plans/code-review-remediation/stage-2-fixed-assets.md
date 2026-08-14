# 阶段 2：固定资产一致性

前置：阶段 1 的 `deleteGenerated` 和生成凭证保护已完成。

## 目标

使固定资产处置、来源凭证、资产状态和处置月折旧始终作为一个完整业务单元变化。

## 实施任务

### 1. 处置生命周期

新增 Flyway migration：

- `fixed_asset_disposal.status`：`ACTIVE/CANCELLED`；
- `cancelled_at`、`cancelled_by`、`cancellation_reason`；
- 原唯一约束改为仅限制同一资产存在一条 `ACTIVE` 处置。

新增 API：

- `POST /v1/ledgers/{ledgerId}/fixed-assets/{assetId}:cancel-disposal`
- 请求：`{ "reason": string, "expectedVersion": number }`
- 响应：更新后的固定资产。

撤销规则：

- 关联处置期间必须开放；关闭期间提示先重新开放。
- 处置记录及折旧、清理、结算来源凭证必须完整匹配，否则返回数据完整性错误，不自动猜测。
- 同一事务内使用阶段 1 的来源删除入口：删除全部来源凭证、冲减投影、恢复资产 `ACTIVE`、标记处置 `CANCELLED`、写审计。
- 通用接口删除任意一张处置凭证必须失败。

### 2. 当前期参数变更

- 请求/OpenAPI 将 `effectivePeriodId` 改为 `changePeriodId`。
- migration 将 `fixed_asset_change.effective_period_id` 改为 `change_period_id`，历史值原样保留。
- 会计参数变更要求 changePeriod 为当前开放期间并要求 reason。
- 未来、过去或关闭期间返回明确 422/409；不建设参数版本表。
- 已有使用历史时，原值和启用日期的锁定规则保持不变。

### 3. 处置月折旧

- 口径固定为“处置当月仍计提，次月停止”。
- 折旧候选集合包含目标期间内处置的资产，不能只查当前 `ACTIVE`。
- 折旧指纹纳入处置期间、状态和实际参与资产集合。
- 重新生成不得移除处置月折旧或改变处置时账面价值。
- 为普通折旧补充来源级取消命令，开放期间内原子取消 run、lines、voucher 并保留审计。

## 关键测试

- 完整处置后，分别尝试删除折旧/清理/结算凭证，全部返回来源保护错误。
- 整体撤销后所有来源凭证消失、投影净额恢复、资产可再次处置、原处置保留为 CANCELLED。
- 任一来源凭证或处置记录缺失时撤销失败且无部分写入。
- 本期参数变更生效；未来、过去和关闭期间拒绝。
- 生成当月折旧 → 处置 → 重新生成 → 结账，折旧、累计折旧、账面价值和总账一致。

优先扩展：`FixedAssetCalculationTest`、`FixedAssetDepreciationDimensionsIntegrationTest`，并增加处置撤销集成测试。

## 编排与完成条件

- `worker/fixed-asset` 独占 fixedasset 服务、repository、controller、migration 和测试。
- `reviewer/accounting-integrity` 必须复核处置事务、账面价值和折旧口径。
- 后端编译、定向测试、18080 目标 HTTP 验证通过后交接。

