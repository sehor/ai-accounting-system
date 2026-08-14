# 完整辅助核算规格

状态：实施中  
版本：`AUXILIARY-ACCOUNTING/1`  
日期：2026-08-14

## 1. 目标

辅助核算必须成为可验证、可追溯的会计事实，而不是凭证或期初余额上的自由文本。系统应贯通：

1. 账套维护辅助核算类型和值；
2. 科目声明允许和必填的辅助类型；
3. 期初余额与凭证行保存结构化维度组合；
4. 按“期间、科目、币种、辅助维度”查询明细和余额；
5. 已记账凭证修改、删除、历史恢复和期初确认后，科目余额与辅助余额使用同一投影水位；
6. 停用或改名不破坏历史标签和历史报表。

现有按科目投影和法定报表保持兼容。完整辅助核算通过独立投影增加能力，不修改既有报表的默认口径。

## 2. 技术栈与命令

- 后端：Java、Spring Boot、JdbcTemplate、Flyway、PostgreSQL。
- 前端：React、TypeScript、Ant Design、TanStack Query、Vitest。
- 数据边界：REST、MCP、CSV、PostgreSQL 外键和唯一约束。

定向验证命令：

```text
rtk .\mvnw.cmd -q -DskipTests compile
rtk .\mvnw.cmd -q "-Dtest=Stage2BaseDataTest,VoucherAccountControlsIntegrationTest" test
rtk .\mvnw.cmd -q "-Dtest=RollingBalanceProjectionIntegrationTest,Stage4ReportingTest" test
rtk pnpm --dir frontend test -- VoucherPages.test.tsx
rtk pnpm --dir frontend test -- SettingsPage.test.tsx
rtk pnpm --dir frontend test -- BooksPage.test.tsx
rtk graphify update .
```

不运行仓库级前端 test、lint、typecheck 或 build，除非用户明确批准。

## 3. 规范维度组合

### 3.1 结构

一个维度组合属于一个账套，由零到多个成员组成：

```json
{
  "dimensionKey": "稳定指纹",
  "kind": "STRUCTURED",
  "dimensions": [
    { "dimensionTypeId": "...", "dimensionValueId": "..." }
  ]
}
```

规则：

- 每个类型最多一个值；
- 成员按 `dimensionTypeId` 的 UUID 字符串升序排列；
- 规范串为 `v1;typeId=valueId;...`，空组合为 `v1;`；
- `dimensionKey` 为规范串 UTF-8 字节的 MD5 小写十六进制指纹；选用 PostgreSQL 核心函数可直接回填，但它只用于展示、兼容和诊断，不作为加密、授权或唯一性边界；
- 数据库唯一性使用账套内 `canonical_key`，事实与投影使用 `combination_id`；因此指纹碰撞不会合并余额；
- 组合和成员不可变。类型或值改名、停用后，历史组合仍保留创建时的编码和名称快照。

### 3.2 组合种类

- `STRUCTURED`：所有成员均已验证为本账套的有效类型和值；
- `LEGACY_UNMAPPED`：旧期初余额只有非空自由 `dimensionKey`，无法安全反推出结构化成员。

旧空键迁移为空组合。旧非空自由键保留金额并显式标为 `LEGACY_UNMAPPED`；系统不得按名称或编码猜测映射。新写入不接受 `LEGACY_UNMAPPED`。

## 4. 数据模型

### 4.1 事实表

新增：

- `dimension_combination`
  - `id`, `ledger_id`, `kind`, `canonical_key`, `dimension_key`, `created_at`
  - 唯一：`(ledger_id, canonical_key)`
- `dimension_combination_member`
  - `ledger_id`, `combination_id`, `dimension_type_id`, `dimension_value_id`
  - `dimension_type_code/name`, `dimension_value_code/name` 历史快照
  - 主键：`(combination_id, dimension_type_id)`
- `opening_balance_dimension`
  - 保留从期初余额到结构化成员的显式关系，便于备份、审计和一致性检查
  - 主键：`(opening_balance_id, dimension_type_id)`

扩展：

- `voucher_line.dimension_combination_id`：与既有 `voucher_line_dimension` 原子双写；
- `opening_balance.dimension_combination_id`：替代自由文本作为唯一性和汇总事实；
- 既有 `opening_balance.dimension_key` 保留为兼容字段，响应值由组合生成；
- 既有 `voucher_line_dimension` 保留，避免破坏凭证 REST/MCP/备份合同。

所有外键和查询必须同时限定 `ledger_id`，防止跨账套 UUID 猜测。

### 4.2 辅助余额投影

新增 `dimension_period_balance`，主键至少包含：

```text
(ledger_id, period_id, account_id, dimension_combination_id, currency)
```

仅保存叶子科目。字段包含原币与本位币的期初借贷、期间借贷、期末借贷，并保留经营活动借贷发生额。父科目需要维度筛选时在查询层沿科目路径汇总，既有 `account_period_balance` 继续负责默认父级科目报表。

投影事实来源：

- 已确认 `opening_balance`；
- `status='POSTED' and deleted_at is null` 的 voucher/voucher_line；
- 对应的不可变维度组合。

重建起点的键集合必须是“起点前一期 `dimension_period_balance` 的
`(leaf_account_id, combination_id, currency)`”与“起点及以后已确认期初、已记账凭证事实”的并集；
递归必须携带每个键至后续期间，即使期间发生额为零。原币与本位币分别从事实累计，绝不得由本位币和汇率反推原币。

DEBIT/CREDIT 字段严格为对应 side 金额的带符号和，不得使用 `abs`、`greatest` 或改换方向。
`closing = openingDebit - openingCredit + periodDebit - periodCredit`，展示方向只在查询层由净额推导。
`operating*` 使用相同的带符号规则，且只累计 `voucher.accounting_role='OPERATING'`；期初及
`PROFIT_LOSS_TRANSFER` 的经营发生额为零。

## 5. 写入与状态规则

### 5.1 期初余额

`OpeningBalanceLine` 新增可选 `dimensions`，保留 `dimensionKey` 兼容字段：

- 客户端应发送结构化 `dimensions`；
- `dimensionKey` 为空且 `dimensions` 为空表示空组合；
- 新请求携带非空自由 `dimensionKey` 且无结构化成员时返回 422；
- 服务端校验科目允许的维度类型、必填项、值的账套归属与 ACTIVE 状态；
- 唯一性改为账套、期间、科目、币种和组合；
- 仅首个会计期间允许录入；确认后仍保持不可修改。

CSV 新合同增加结构化列 `dimensionValues`，格式为 `TYPE_CODE=VALUE_CODE|...`。旧七列表头继续接受，但非空自由 `dimensionKey` 只用于读取旧数据，不允许创建新的未映射事实。

### 5.2 凭证

凭证请求继续使用现有 `dimensions[{dimensionTypeId, dimensionValueId}]`。保存凭证行时：

1. 校验账套、科目绑定、同类型唯一；
2. 解析或并发安全地创建不可变组合；
3. 原子写入 `voucher_line_dimension` 与 `dimension_combination_id`；
4. 草稿可缺必填项；validate/submit/approve/post 继续要求完整；
5. 修改、删除、恢复已记账凭证继续发布现有投影事件。

历史读取允许停用值；新增或替换成员只允许 ACTIVE 值。更新或恢复既有凭证时，若完整维度组合与变更前相同，
可以保留停用成员；新增凭证、或对既有组合新增/替换任一成员时，所有新选成员必须 ACTIVE。实现必须在删除重插分录前
保存并比较变更前 combination pointer，不能丢失“组合未改变”的判断。

### 5.3 主数据生命周期

- 类型和值不做物理删除；
- PATCH 采用部分更新，允许改名和 ACTIVE/INACTIVE 状态；编码保持不可变；
- 停用只阻止新组合，不影响历史组合、凭证、期初和报表读取；
- 修改需写只追加审计，保存前后结构化快照；
- OWNER/EDITOR 可写，其余账套角色只读。

## 6. 投影与一致性

现有 `balance_projection_event` 继续作为账套/期间脏标记，不扩展事件行维度。当前 projector 本就从原始事实重建，而不是应用事件金额，因此：

1. `BalanceSnapshotRebuilder` 在同一事务中重建 `account_period_balance` 和 `dimension_period_balance`；
2. 两张投影都成功后才推进 `last_applied_event_id`；
3. 失败时共享 FAILED/重试状态，不允许一张 READY、另一张旧数据；
4. reopen 同时清除两张表的 finalized 标记；
5. rebuild job 从受影响最早期间向后重建两张表。

每次重建必须检查：

- 对每个叶子科目和期间，辅助投影按组合和币种汇总后的本位币金额等于科目投影；
- 组合成员与 `voucher_line_dimension` 一致；
- 期初/凭证事实金额与辅助投影一致；
- 任一差异阻止关账，返回结构化投影对账错误。

## 7. 查询合同

### 7.1 Finance Query

保持 `POST /v1/ledgers/{ledgerId}/finance-query` 及既有字段。新增：

- `filters.dimensionValues[] = {dimensionTypeId, dimensionValueId}`；多个过滤条件为 AND；
- `dimensionGroupTypeIds[]`：`groupBy` 含 `DIMENSION` 时指定分组类型；
- `FinanceQueryLine` 增加可选 `dimensionKey`、`dimensions`、`currency`、`periodCode`、`accountCode`；原 `groupKey/amount` 保持。

`DEBIT/CREDIT/NET/BALANCE` 与 `amount` 默认且始终以本位币计量；`filters.currency` 只限制来源分录币种，
不改变 `amount` 的计量单位。若未来增加 `originalAmount/originalMetric`，必须要求且只允许一个币种，并在响应中显式返回
`originalCurrency`；禁止跨币种汇总原币。按 `CURRENCY` 分组时每组的 `amount` 仍为本位币。

所有动态 SQL 片段只能来自服务端枚举白名单；用户值全部使用 JDBC 参数。

### 7.2 维度账簿

既有 general-ledger/sub-ledger 默认接口和响应不变。新增：

```text
POST /v1/ledgers/{ledgerId}/books/dimension-ledger:query
```

请求包含期间范围、科目、币种、维度过滤、分组类型和分页。响应包含：

- 结构化组合和历史标签；
- 明确区分原币与本位币的期初、借方、贷方、方向、运行余额；运行余额不可跨币种；
- 可追溯的 voucherId、voucherNumber、voucherDate、lineNo；
- `projectionStatus` 和 legacy 映射警告。

排序稳定为 `voucher_date, voucher_number, line_no, voucher_line_id`。

投影非 READY 时，整个余额请求返回 409；不得在同一响应中混用事实和旧投影。凭证明细仍可从权威事实读取，但必须明确 `projectionStatus`。

## 8. 前端

- 凭证编辑器按科目动态显示辅助选择器；
- 期初余额用结构化选择器替代自由“维度键”输入；
- 设置页支持类型和值改名、停用和恢复，并显示历史使用提示；
- 账簿页增加维度过滤、分组和 legacy 警告；
- 加载、失败、空数据和只读角色均有明确状态；
- 所有交互元素具有可访问名称，不能只靠颜色表达状态。

## 9. 迁移与兼容

采用扩展后收紧的迁移：

1. 扩展 schema，新增组合、成员、辅助投影和可空 pointer；
2. 部署同时写旧明细与 pointer、并在读取空 pointer 时回退旧明细的代码；迁移期保留旧期初写入的完整
   `ON CONFLICT (ledger_id, period_id, account_id, currency, dimension_key)` 唯一边界，并在数据库写入前将旧
   空 key/legacy key 原子桥接到同一组合，避免旧、新二进制产生语义重复；
3. 按既有 `voucher_line_dimension` 回填 STRUCTURED 组合，期初空键回填空组合，非空键回填 `LEGACY_UNMAPPED`；
4. 校验历史回填覆盖率、新写入 pointer 覆盖率及事实一致性均为 100%；
5. 停止旧二进制后，将 pointer 改为 NOT NULL 并启用账套复合外键；
6. 从每个账套首期重建辅助投影；
7. 删除空 pointer 读取回退；旧事实表仍按兼容期保留；
8. 备份格式升级版本并增加组合、组合成员和结构化期初事实表；恢复仍接受既有 V1/V2 清单，投影恢复后重建而不作为备份权威数据。
   V3 恢复必须在 UUID 重映射后按恢复后的成员重建 STRUCTURED canonical key 与指纹；V1/V2 回填必须优先
   保留数据库兼容桥已生成的 pointer，不得把空组合指纹再次包装为 legacy key。

迁移不得改写已存在的 Flyway 文件。旧 REST/MCP 字段、错误结构、UUID、凭证状态流和默认科目报表保持兼容。

## 10. 威胁模型

主要资产：跨账套隔离、余额完整性、历史标签、审计证据和投影水位。

- Spoofing/Elevation：每个 endpoint 继续通过账套成员角色鉴权；
- Tampering：类型和值必须与账套、科目绑定和组合成员一致，数据库使用复合外键；
- Repudiation：主数据、期初、凭证和 legacy 映射写只追加审计；
- Disclosure：查询始终限定 ledger_id，不通过 UUID 存在性泄露其他账套；
- DoS：维度成员数量设上限，列表和账簿分页，查询期间范围设上限；
- Injection：动态分组仅用枚举白名单，值全部参数化。

`dimensionKey` 不是安全令牌，也不作为唯一性或授权判断。

## 11. 代码位置与风格

- 规格与 ADR：`docs/specs/`、`docs/decisions/`；
- Flyway：`src/main/resources/db/migration/`，只新增版本；
- 共享组合解析：`src/main/java/com/example/accounting/shared/accounting/`；
- 写入合同：`ledger`、`voucher`；
- 投影和查询：`reporting`；
- 前端与定向测试：`frontend/src/pages/`。

接口使用不可变 record，外部输入在 Controller/Bean Validation 和服务边界校验；JdbcTemplate SQL 使用文本块与位置参数。示例：

```java
public record DimensionSelection(
        @NotNull UUID dimensionTypeId,
        @NotNull UUID dimensionValueId) {
}
```

## 12. 验收标准

- [ ] 同一组维度无论输入顺序如何都解析到同一组合和稳定 `dimensionKey`；
- [ ] 跨账套类型/值、重复类型、科目未绑定值和停用新值均被拒绝；
- [ ] 期初同科目/币种可按不同组合并存，确认后不可修改；
- [ ] 凭证创建、修改、删除、恢复后辅助投影与科目投影一致；
- [ ] 多期间滚动、负数方向、外币原币/本位币均正确；
- [ ] Finance Query 支持维度 AND 过滤及 DIMENSION 分组；
- [ ] 维度账簿返回期初、明细、发生额和运行余额，排序与分页稳定；
- [ ] 停用/改名后历史报表仍显示快照标签；
- [ ] legacy 非空期初键不会被猜测映射，并在查询中明确警告；
- [ ] 投影不一致或未就绪时阻止关账和辅助余额查询；
- [ ] 备份恢复后结构化事实完整，辅助投影可重建；
- [ ] 现有无维度凭证、期初、科目报表和 REST/MCP 合同回归通过。

## 13. 边界

始终执行：账套复合外键、参数化 SQL、输入数量上限、只追加审计、定向测试、迁移后对账。  
需另行确认：改变凭证状态机、允许修改已确认期初、改变法定报表默认口径、删除历史维度资料。  
禁止：猜测 legacy 映射、信任客户端 dimensionKey、跨账套查找、静默混用事实和陈旧投影、修改已执行的 Flyway 迁移。
