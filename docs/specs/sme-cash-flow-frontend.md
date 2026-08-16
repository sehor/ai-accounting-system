# 小企业现金流量表前端开发计划

- 状态：实施中
- 分支：`codex/frontend-cash-flow`
- 依赖后端规格：`docs/specs/sme-cash-flow-backend.md`（后端已完成）
- 适用准则：`SME/2011-17`（兼容账套中的 `SME/v1`），仅 CNY
- 法定格式：会小企 03 表（22 行，两列）
- 最后更新：2026-08-16

## 1. 背景与目标

后端已完成法定现金流量表闭环：22 行两列报表、数据质量（`dataQuality`）与定位样例、
现金流公式草稿/试算/发布/回滚、凭证现金流分类约束。本期补齐前端闭环：

1. 法定现金流量表页面：单张连续、高密度财务报表，展示 22 行两列、数据完整性状态与
   失败勾稽，样例可展开并可跳转凭证详情。
2. 凭证现金流分类：现金收支分录可选择“有效且被当前公式引用”的现金流项目，保存/校验/
   提交/记账前执行与后端一致的前端校验，错误定位到具体分录。
3. 现金流公式设置：报表公式设置支持 `CASH_FLOW` 切换，表达式编辑器支持方向、现金流
   项目和现金科目引用，余额行保留 `OPENING/CLOSING` 基准，试算预览复用正式报表的连续
   表格与数据质量告警。

本期只提供“调整公式”入口，不实现打印、导出和操作指南。

### 1.1 成功标准

- 菜单、工作区标签、页面标题出现“现金流量表”，路由沿用
  `/ledgers/:ledgerId/reports/cash-flow`。
- SME/CNY 账套渲染一张连续表格：列为“项目、行次、本年累计金额、本月金额”，四个 API
  分组转为表内灰底分节行，22 行连续展示，合计/计算行加粗并强化上边框。
- `COMPLETE` 显示“数据完整”；`INCOMPLETE` 在表格上方显示告警，分别列出本年累计和本月
  的未分类凭证数、分录数，说明相关金额未计入现金流项目行，并展开最多 10 条定位样例。
- 凭证号链接到凭证详情；已过账凭证只定位查看，不承诺直接修改。
- 失败勾稽单独展示（检查名称 + 差额），与数据完整性告警区分。
- 凭证编辑器只提供“有效且被当前公式引用”的现金流项目；外部现金收支必选，纯现金内部
  划转可空；保存、校验、提交、记账前前端校验一致并定位到分录。
- 报表公式设置切换“现金流量表”后，可完整走通创建草稿、编辑、保存、试算、发布、重置、
  版本历史与回滚；试算预览显示连续表格与数据质量告警；存在完整性或勾稽警告时沿用现有
  确认后发布机制。
- 非 SME、非 CNY 账套直接访问时展示后端返回的准则/币种错误，不回退到旧利润表接口。

## 2. 页面结构

### 2.1 报表页（`/ledgers/:ledgerId/reports/cash-flow`）

工具栏（沿用 `financial-toolbar` 体系）：

- 标题：现金流量表。
- 次级信息：小企业会计准则 · CNY。
- 公式版本标签：`公式版本 v{n}`。
- 期间选择器：复用 `PeriodSelector`（单期间），请求
  `GET /v1/ledgers/{ledgerId}/reports/statutory/cash-flow?periodCode=YYYY-MM`。
- “调整公式”按钮：跳转 `/ledgers/:ledgerId/settings/report-formulas?formula=CASH_FLOW`。

表格：一张连续表格，列固定为：

| 项目 | 行次 | 本年累计金额 | 本月金额 |

- 四个 API 分组（`OPERATING`/`INVESTING`/`FINANCING`/`BALANCES`）渲染为表内灰底分节行
  （`rowType=SECTION`），不再使用分组卡片。
- 22 行连续展示，行次列显示行号；合计/计算行（`TOTAL`/`CALCULATION`）加粗并强化上边框。
- 金额右对齐、千分位、两位小数；零值留空；负数保留负号。
- 桌面端紧凑行高（沿用 `statutory-statement-table` 32px 行高体系）；窄屏容器横向滚动，
  表头在滚动区域内保持 sticky，项目列可识别。
- 期间为空期间时显示空态提示。

### 2.2 数据完整性告警（表格上方）

- `dataQuality.status === 'COMPLETE'`：`Alert type="success"`，“数据完整”。
- `INCOMPLETE`：`Alert type="warning"`，文案固定为：

  > 存在未分类的现金收支，相关金额未计入下列现金流项目行。本年累计：
  > {primaryUnclassifiedVoucherCount} 张凭证 / {primaryUnclassifiedLineCount} 行；
  > 本月：{comparativeUnclassifiedVoucherCount} 张凭证 / {comparativeUnclassifiedLineCount} 行。

- 可展开“查看定位样例（最多 10 条）”，样例表格列：凭证号、日期、期间、分录行、借贷
  方向、金额、原因。原因中文映射：

  | reason | 文案 |
  | --- | --- |
  | `ITEM_MISSING` | 未填写现金流项目 |
  | `LEGACY_COARSE_ITEM` | 使用旧的三分类项目（经营性/投资性/筹资性） |
  | `ITEM_NOT_IN_FORMULA` | 项目未被当前报表公式引用 |
  | `ITEM_INACTIVE` | 项目已停用 |

- 凭证号渲染为链接，指向 `/ledgers/:ledgerId/vouchers/:voucherId`；已过账凭证详情页
  保持只读定位查看。

### 2.3 失败勾稽（单独展示）

`statement.checks` 中 `passed === false` 的检查以独立 `Alert type="error"` 展示，文案：
“勾稽检查未通过：{name}，差额 {difference}”。与数据完整性告警分开渲染，不使用同一类型。

### 2.4 错误状态

| 场景 | 展示 |
| --- | --- |
| 非 SME 账套 | 后端 `STATUTORY_REPORT_UNSUPPORTED_STANDARD`，提示“当前账套不是小企业会计准则，暂不提供法定报表” |
| 非 CNY 账套 | 后端 `STATUTORY_REPORT_CURRENCY_UNSUPPORTED`，提示“小企业会计准则法定报表首版仅支持人民币账套” |
| 期间不存在 | `PERIOD_NOT_FOUND` |
| 余额投影未就绪 | `STATUTORY_REPORT_PROJECTION_PENDING`，提示稍后刷新 |
| 公式缺失 | `STATUTORY_FORMULA_NOT_FOUND`，提示缺少已发布公式 |
| 加载中 | 骨架/Spin 状态 |
| 空期间 | 表格空态“当前期间暂无报表数据” |

直接访问不支持账套时不得回退到旧利润表/资产负债表接口。

## 3. 凭证现金流分类闭环

### 3.1 数据来源

- `GET /v1/ledgers/{ledgerId}/cash-flow-items`：账套现金流项目主数据（含 `status`）。
- `GET /v1/ledgers/{ledgerId}/report-formulas/CASH_FLOW`：已发布公式；从
  `publishedDefinition.groups[].lines[].expression` 中 `CASH_FLOW_ITEM_AMOUNT` 的
  `itemCodes` 提取“可报表项目代码”。

### 3.2 选择器规则

- 仅对“现金科目”分录显示现金流项目选择器：以科目 `cashFlowRequired === true` 判定。
- 可选项目 = `status === 'ACTIVE'` 且代码 ∈ 当前发布公式 `itemCodes`。
- 选择科目时，若科目 `defaultCashFlowItemId` 对应项目仍有效且可报表，自动带入。
- 无现金科目分录时不显示选择器。

### 3.3 前端校验（保存 / 校验 / 提交 / 记账前）

复用同一函数 `validateCashFlow(lines, accountsById, reportableItemCodes)`：

- 现金行 = 分录科目 `cashFlowRequired`。
- 无现金行 → 通过。
- 全部行均为现金科目（内部划转）→ 通过（可空）。
- 存在非现金行（复合凭证）→ 每条现金行必须选择项目，否则按行号报错：
  “第 N 条分录的现金收支必须选择现金流项目”。
- 已选项目必须 ACTIVE 且代码在公式中，否则报错：
  “第 N 条分录使用的现金流项目不在当前报表公式中（或已停用）”。

前端校验失败时以 `modal.error` 或表单级错误定位到具体分录并中止操作；后端
`CASH_FLOW_CLASSIFICATION_REQUIRED` / `CASH_FLOW_ITEM_NOT_REPORTABLE` 错误保留明确
提示，防止公式发布后项目集合变化导致静默失败。

### 3.4 范围

- 本期不新增现金流项目主数据页面。
- 本期不新增科目默认项目的设置页面。
- 不修改草稿保存的宽松行为：草稿允许暂缺项目，离开 DRAFT 前（保存后校验/提交/记账）必须完整。

## 4. 现金流公式设置

### 4.1 切换与生命周期

- 报表公式设置页的 `Segmented` 增加“现金流量表”（`CASH_FLOW`）。
- 通过 URL `?formula=CASH_FLOW` 预选；支持已有草稿、保存、试算、发布、重置、版本历史
  和回滚（复用现有 `ReportFormulaSettingsTab` 流程）。
- `CASH_FLOW` 为 `FIXED_LINES` 结构：22 行、行次、分组和勾稽关系锁定，只允许修改项目
  名称与受支持的取数公式（沿用现有“行号、分组、顺序、勾稽规则锁定”提示）。

### 4.2 表达式编辑器

- `CASH_FLOW_ITEM_AMOUNT`：方向（`INFLOW` 流入 / `OUTFLOW` 流出 / `NET` 净额）、
  现金流项目多选（`itemCodes`）、现金科目引用（`cashAccounts`，支持标准科目键与账套科目）。
- `ACCOUNT_AMOUNT`：余额行（行 21/22）编辑时保留并展示 `basis`（`OPENING` 期初 /
  `CLOSING` 期末），不得在编辑中丢失该字段。
- 前序行加减（`LINEAR_COMBINATION`）继续可用（合计行）。

### 4.3 试算预览

- 试算结果复用正式报表的连续表格（同一组件）。
- 数据质量告警（`statement.dataQuality`）在试算预览中同样展示。
- 存在完整性或勾稽警告时沿用现有“确认发布带警告的版本”机制。

## 5. 技术实现要点

- 新增共享组件（放在 `frontend/src/components/` 或 `pages/` 内部）：
  - 连续表格组件：输入 `StatutoryStatement`，输出分节行连续表格；报表页与公式试算预览共用。
  - 数据质量告警组件：输入 `DataQuality` 与 `ledgerId`，输出状态与样例展开。
- `frontend/src/features/reportFormulas/types.ts`：
  - `FormulaDefinition.reportType` 增加 `'CASH_FLOW'`。
  - `LineExpression` 增加 `CashFlowItemAmountExpression`（`direction`、`itemCodes`、`cashAccounts`）。
  - `AccountAmountExpression` 增加可选 `basis: 'OPENING' | 'CLOSING'`。
  - `FormulaCheck` 增加 `column` 与 `rightComponents`。
- `frontend/src/api/generated.ts` 从测试后端 OpenAPI 重新生成，纳入
  `StatutoryStatement.dataQuality`、`QualitySample`、`LedgerCashFlowItem` 等类型。
- 凭证编辑器新增现金流项目列与校验；`AppShell` 报表菜单在 SME 账套显示“现金流量表”。

## 6. 测试计划

- 组件测试（Vitest）：
  - 菜单/工作区标签/路由/“调整公式”跳转。
  - 4 个分组、22 行、两列标题、金额格式、合计样式与横向滚动。
  - `COMPLETE`/`INCOMPLETE`、四种原因、样例展开、凭证跳转、失败勾稽。
  - SME/CNY、非 SME、非 CNY、无期间、投影等待、接口错误。
  - 凭证：外部现金收支必选、复合凭证逐现金行校验、纯现金内部划转豁免、默认项目带入、
    停用/公式外项目过滤、后端 422 错误提示。
  - 公式编辑器：`CASH_FLOW` 切换、表达式无损解析、试算数据质量、发布确认、版本回滚。
- 定向验证：运行相关 Vitest 文件，不执行仓库级前端全量检查。
- 新增单独的现金流 Playwright 冒烟：进入报表、切换期间、查看告警、跳转凭证、进入公式调整。
- 全量 `typecheck/build` 仅在获得明确批准后执行。

## 7. 假设与边界

- 页面只支持后端现有的 SME/CNY 月报口径，不增加报表频率选择。
- 参考图用于确定连续表格、列结构和信息密度；颜色、字体、按钮与间距继续遵循项目现有
  Ant Design 体系。
- 本期“调整”指现金流公式设置；打印和导出另立需求。
- 不修改后端 API。

## 8. 验收命令

```powershell
# 后端已运行在 18080（测试库）时重新生成 OpenAPI 类型
pnpm api:generate

# 定向 Vitest（示例）
pnpm vitest run src/pages/ReportsPage.test.ts src/pages/VoucherPages.test.tsx src/pages/ReportFormulaSettingsTab.test.tsx

# 现金流 Playwright 冒烟（需要后端 + 前端 dev server）
pnpm test:e2e -- e2e/cash-flow.spec.ts

# 完成后更新知识图谱并提交
graphify update .
git add -A && git commit -m "feat: SME cash flow statement frontend"
```
