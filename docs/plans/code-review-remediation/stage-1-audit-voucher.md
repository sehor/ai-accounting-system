# 阶段 1：审计与凭证所有权

前置：阶段 0 完成。  
后续依赖：阶段 2 必须在本阶段内部来源接口稳定后开始。

## 目标

保证审计不可被业务删除或序列化失败静默破坏，并建立普通凭证与来源生成凭证的明确修改边界。

## 实施任务

### 1. 审计快照 fail-closed

- 注入 Spring 管理的 `ObjectMapper` 或新增 `AuditSnapshotSerializer`。
- 固定资产 `before/after` 在业务更新提交前完成序列化。
- 序列化失败抛出明确业务异常并回滚，禁止返回 `{}`。
- 新增 `DefaultFixedAssetServiceAuditSnapshotTest`：失败 mapper 下资产和 change 均不落库。

### 2. 普通凭证删除保留审计

- 删除 `JdbcVoucherRepository.deleteVoucher` 中删除 `audit_revision` 的 SQL。
- 删除前在同一事务追加 `DELETE` 修订：完整凭证放在 `before_data`，`after_data` 保存墓碑。
- 删除后的审计仍可按 `aggregateType=VOUCHER, aggregateId=<id>` 查询。
- 已经被旧逻辑删除的历史审计无法恢复，写入迁移说明。

验收：普通已记账凭证删除后，业务查询不可见、余额投影冲减、此前修订和最终 DELETE 都存在。

### 3. 来源生成凭证保护

- 通用 update/restore/delete 遇到 `source_type/source_id` 非空时返回 HTTP 409，code 为 `GENERATED_VOUCHER_MANAGED_BY_SOURCE`。
- 保留 `createGenerated`/`replaceGenerated` 的来源类型和来源 ID 校验。
- 新增内部入口：

  `deleteGenerated(actorId, ledgerId, voucherId, sourceType, sourceId, expectedVersion, reason)`

  仅允许来源服务调用，并复用普通删除的余额投影和 DELETE 审计逻辑。

- 为现有能力定义来源命令接口，具体实现可在后续阶段完成：
  - 固定资产折旧取消；
  - 固定资产处置撤销；
  - 期末结账步骤重置。

## 关键测试

- 普通草稿和已记账凭证仍可按现有规则删除。
- 来源生成凭证的通用修改、恢复、删除全部返回稳定 409。
- 来源类型或来源 ID 不匹配时，内部更新/删除失败。
- DELETE 审计与余额冲减同事务；任一步失败全部回滚。
- 审计表没有 voucher 外键，也不得新增级联删除关系。

优先扩展：`Stage3VoucherTest`，新增审计序列化定向测试。

## 编排与文件所有权

- `worker/audit-voucher` 独占 voucher/audit 服务、repository 和测试。
- `reviewer/accounting-integrity` 在任务完成后只读复核事务边界、审计快照和来源校验。
- 本阶段不编辑固定资产处置流程或期末结账流程，避免与后续 worker 冲突。

## 完成条件

- 后端编译、定向测试和 18080 普通凭证删除 HTTP 验证通过。
- 来源凭证保护错误合同稳定，供阶段 2/3 使用。

