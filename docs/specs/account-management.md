# 科目管理规格

状态：实施中  
格式版本：`ACCOUNT-EXCHANGE/1`

## 1. 范围与兼容性

一期交付准则模板、树状科目、编码规则、控制属性、辅助核算、安全锁、标准/金蝶科目交换、管理界面及凭证联动。二期在同一导入预检模型上增加确定性清洗、AI 映射建议、置信度和人工确认。

现有 REST、MCP、凭证、科目 UUID 和编码保持兼容。已有账套不因模板升级而改变；模板在建账时克隆为账套快照。历史报表必须继续包含有余额或凭证的停用科目。

## 2. 准则包

准则包存放在 `src/main/resources/accounting-standards/<code>/<version>.json`：

- `SME/2011-17`：小企业会计准则，2013-01-01 起施行。
- `CAS/2006-18`：企业会计准则应用指南。

每个 JSON 包包含元数据、科目树、报表公式、现金流项目和默认辅助类型。建账请求必须引用已安装版本；未知版本返回 `ACCOUNTING_STANDARD_NOT_FOUND`。模板升级仅增加新版本。

默认辅助类型为 `CUSTOMER`、`SUPPLIER`、`DEPARTMENT`、`PERSON`、`PROJECT`，账套可增加自定义类型。

## 3. 科目树与编码

账套编码规则由 `separator`、`level2Width`、`level3Width`、`level4Width` 组成。默认 `.` 和 `2/2/2`，即 `4-2-2-2`；分隔符只能是 `.` 或 `-`，各子级段宽 1–8，总编码不超过 32 字符。

- 一级科目必须是四位数字，允许用户新增。
- 最大四级；子科目编码必须是父编码加分隔符和当前级定长数字段。
- 子科目继承父级类别与正常余额方向。
- 有子科目的科目不是末级，不能用于新凭证或期初余额。
- 已有任意凭证或余额引用的末级科目不能新增子科目。
- 账套出现二级科目后编码规则锁定，修改返回 `ACCOUNT_CODE_RULE_LOCKED`。
- MCP `ensure_account` 从完整编码推断父级；父级不存在时拒绝。

旧数据保留 UUID 与编码。迁移按 `.`、`-` 和最长有效前缀推断父级；无法推断的非四位根科目标记 `legacy_code=true`。遗留科目仍可记账和停用，但已使用遗留科目的核心属性锁定。

## 4. 属性、辅助核算与凭证

科目核心属性为编码、父级、类别、正常余额方向、现金流控制、数量核算和辅助绑定：

- `cashFlowRequired`：凭证完成态是否必填现金流项目。
- `defaultCashFlowItemId`：默认现金流项目。
- `quantityEnabled`、`unitName`：固定单位的数量金额核算。
- `dimensionRequirements`：科目绑定的辅助类型及 `required`。

`dimension_type.required` 仅是新增绑定的默认值，不再是全账套强制规则。

凭证行可包含 `cashFlowItemId`、`quantity`、`unitPrice` 和多维辅助值。草稿允许缺少控制项；校验、提交、审批和记账时必须完整。数量与单价必须为正，金额必须等于数量乘单价并按币种金额精度舍入。

## 5. 安全锁、状态与审计

- 存在已记账凭证或已确认期初余额后，科目核心属性和辅助绑定锁定。
- 名称和启停状态始终可修改；修改请求必须携带 `expectedVersion`。
- 模板科目的编码、父级、类别和余额方向始终不可修改。
- 仅非模板、无子科目、且无凭证或余额引用的科目可物理删除。
- 禁用父科目前必须先禁用全部后代；启用子科目前必须先启用全部祖先。
- 创建、修改、启停、删除和批量提交写入 `audit_revision`，`aggregate_type='ACCOUNT'`，保存前后 JSON 快照。

OWNER/EDITOR 可写，REVIEWER/VIEWER 只读；AGENT 仅能调用受审计的兼容 `ensure_account`。

## 6. REST API

准则：

- `GET /v1/accounting-standards`
- `GET /v1/accounting-standards/{code}/versions/{version}`

编码规则：

- 建账 `POST /v1/ledgers` 可传 `accountCodeRule`
- `PUT /v1/ledgers/{ledgerId}/account-code-rule`

科目：

- `GET /v1/ledgers/{ledgerId}/accounts`
- `GET /v1/ledgers/{ledgerId}/accounts/{accountId}`
- `POST /v1/ledgers/{ledgerId}/accounts`
- `PATCH /v1/ledgers/{ledgerId}/accounts/{accountId}`
- `DELETE /v1/ledgers/{ledgerId}/accounts/{accountId}`

列表保持数组响应。科目响应增加树、锁定、版本、控制属性和辅助绑定字段。`PATCH.dimensionRequirements` 表示整体替换。

现金流与交换：

- `GET /v1/ledgers/{ledgerId}/cash-flow-items`
- `GET /v1/ledgers/{ledgerId}/account-import-template?format=STANDARD|KINGDEE`
- `GET /v1/ledgers/{ledgerId}/account-export?format=STANDARD|KINGDEE`
- `POST /v1/ledgers/{ledgerId}/account-imports`
- `GET /v1/ledgers/{ledgerId}/account-imports/{importId}`
- `PUT /v1/ledgers/{ledgerId}/account-imports/{importId}/rows/{rowNo}`
- `POST /v1/ledgers/{ledgerId}/account-imports/{importId}:commit`

## 7. Excel 合同

仅接受 `.xlsx`，最大 10 MiB，最多 10,000 个科目。上传只生成预检，不修改账套。

标准工作簿包含：

- `Metadata`：格式版本、准则、编码规则。
- `Accounts`：编码、名称、父编码、类别、余额方向、状态、现金流控制、数量核算、单位。
- `DimensionTypes`：辅助类型编码和名称。
- `AccountDimensions`：科目编码、辅助类型编码、是否必填。

不计算公式，不跟随外部链接。公式单元格、未知表头、重复编码、跨账套引用和锁定冲突均作为预检错误。以 `= + - @` 开头的导出文本前加单引号，防止表格公式注入。

同编码默认不覆盖；每行必须选择 `CREATE`、`UPDATE`、`MAP` 或 `SKIP`。提交时重新检查账套版本；变化返回 `ACCOUNT_IMPORT_STALE`。任一未处理错误阻止提交，提交在一个事务内全部成功或回滚。金蝶科目适配不改变现有金蝶凭证交换。

## 8. AI 清洗与人工审核

导入行保存原值、清洗值、建议目标、置信度、问题、用户操作和确认状态。先执行确定性清洗：空白、全半角、分隔符、重复行、常见列名和精确编码匹配；配置了 HTTPS AI 服务时才补充名称语义建议。

外部 AI 响应按严格 JSON 结构、账套目标集合和数值范围校验。工作簿文本视为不可信数据，不作为指令。AI 只给建议，任何建议都不能自动提交；未配置、超时或非法响应时保留规则清洗和人工映射。

## 9. 威胁模型

信任边界：REST JSON、multipart XLSX、外部 AI 响应和当前用户/账套边界。保护资产：账套隔离、财务主数据、历史凭证与余额、审计证据。

主要滥用场景及控制：

- 猜测跨账套 UUID：每次查询和写入同时限定 `ledger_id`，每个端点重新鉴权。
- 越权修改：仅 OWNER/EDITOR；AGENT 仅兼容的 `ensure_account`。
- 并发覆盖与陈旧预检：科目 `expectedVersion` 和导入账套版本检查。
- XLSX zip bomb、超大文件/行、公式与外链：大小/行数限制、POI 压缩比保护、不执行公式、不读取外链。
- 恶意单元格与提示注入：文本长度限制、公式注入转义、AI 请求将单元格作为数据，响应严格校验。
- 部分提交与审计缺失：数据库事务、逐行错误阻断、写入前后快照。

## 10. 验收

后端覆盖迁移、编码边界、层级/环路、末级限制、模板快照、权限、并发、安全锁、状态祖先规则、删除、辅助必填、数量金额、现金流和父级汇总。导入覆盖格式、公式、重复、锁定、陈旧、回滚、标准往返、金蝶样本和边界。AI 覆盖未配置、超时、非法/低置信度响应、恶意文本、人工覆盖和幂等提交。前端覆盖树交互、锁定态、导入冲突、凭证动态字段、键盘和 axe。

回归命令：

```text
.\mvnw.cmd test
pnpm lint
pnpm test
pnpm build
pnpm test:e2e:business
pnpm test:e2e:axe
pnpm api:generate
graphify update .
```
