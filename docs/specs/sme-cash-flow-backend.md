# 小企业现金流量表后端开发计划

- 状态：待实施
- 目标版本：首版后端
- 适用准则：`SME/2011-17`，兼容账套中的 `SME/v1`
- 法定格式：会小企 03 表
- 最后更新：2026-08-16

## 1. 背景与目标

系统已经具备资产负债表、利润表、报表公式版本管理、现金流项目主数据，以及凭证明细的 `cash_flow_item_id`，但尚不能生成法定现金流量表。当前 SME 标准包只预置了 `OPERATING`、`INVESTING`、`FINANCING` 三个粗分类，所有模板科目也未强制现金流分类，因此现有数据不足以直接形成会小企 03 表的 22 个法定行次。

本次只开发后端：为小企业会计准则人民币账套提供法定现金流量表，接入现有报表公式草稿、预览、发布、回滚、备份恢复和凭证生命周期。前端页面、打印、Excel/PDF 导出、历史数据修复界面和 CAS 现金流量表均不在本次范围。

### 1.1 成功标准

- `GET /v1/ledgers/{ledgerId}/reports/statutory/cash-flow?periodCode=YYYY-MM` 返回完整 22 行。
- 两列固定为“本年累计金额”和“本月金额”；查询 12 月时第二列仍为 12 月本月金额。
- 金额只读取已过账凭证；草稿、已删除凭证不参与。
- 现金、银行存款、其他货币资金及其下级科目视为现金账户。
- 纯现金账户之间的内部划转不计入现金流，也不强制选择现金流项目。
- 功能上线后的外部现金收支，在凭证离开 `DRAFT` 前必须选择详细现金流项目。
- 历史未分类、旧三分类或自定义未映射项目按零处理，但 API 必须明确返回结构化数据完整性告警。
- 现金流公式支持现有草稿、预览、发布、版本列表和回滚能力。
- 不改变现有资产负债表、利润表、CAS 报表和普通凭证的计算结果。

## 2. 已确认的产品决策

| 决策项 | 结论 |
| --- | --- |
| 准则范围 | 仅 `SME/2011-17`，兼容 `SME/v1` |
| 币种 | 仅 CNY |
| 报表格式 | 财政部会小企 03 表完整 22 行 |
| 第二列 | 始终为本月金额，12 月不自动切换上年金额 |
| 历史未分类数据 | 不阻断报表，金额按零，返回完整性告警 |
| 新增凭证 | 在验证/过账前强制详细分类 |
| 自动推断 | 不根据对方科目自动推断现金流项目 |
| 公式能力 | 完整接入现有公式草稿、预览、发布和回滚体系 |
| 前端 | 本次不开发 |

## 3. 法定报表口径

### 3.1 行次与计算关系

| 行次 | 项目 | 现金流项目代码/计算规则 | 方向 |
| ---: | --- | --- | --- |
| 1 | 销售产成品、商品、提供劳务收到的现金 | `SME_CF_01_SALES_RECEIPTS` | `INFLOW` |
| 2 | 收到其他与经营活动有关的现金 | `SME_CF_02_OTHER_OPERATING_RECEIPTS` | `INFLOW` |
| 3 | 购买原材料、商品、接受劳务支付的现金 | `SME_CF_03_PURCHASE_PAYMENTS` | `OUTFLOW` |
| 4 | 支付的职工薪酬 | `SME_CF_04_EMPLOYEE_PAYMENTS` | `OUTFLOW` |
| 5 | 支付的税费 | `SME_CF_05_TAX_PAYMENTS` | `OUTFLOW` |
| 6 | 支付其他与经营活动有关的现金 | `SME_CF_06_OTHER_OPERATING_PAYMENTS` | `OUTFLOW` |
| 7 | 经营活动产生的现金流量净额 | 1 + 2 - 3 - 4 - 5 - 6 | `TOTAL` |
| 8 | 收回短期投资、长期债券投资和长期股权投资收到的现金 | `SME_CF_08_INVESTMENT_RECOVERY` | `INFLOW` |
| 9 | 取得投资收益收到的现金 | `SME_CF_09_INVESTMENT_INCOME` | `INFLOW` |
| 10 | 处置固定资产、无形资产和其他非流动资产收回的现金净额 | `SME_CF_10_ASSET_DISPOSAL` | `NET` |
| 11 | 短期投资、长期债券投资和长期股权投资支付的现金 | `SME_CF_11_INVESTMENT_PAYMENTS` | `OUTFLOW` |
| 12 | 购建固定资产、无形资产和其他非流动资产支付的现金 | `SME_CF_12_ASSET_ACQUISITION` | `OUTFLOW` |
| 13 | 投资活动产生的现金流量净额 | 8 + 9 + 10 - 11 - 12 | `TOTAL` |
| 14 | 取得借款收到的现金 | `SME_CF_14_BORROWING_RECEIPTS` | `INFLOW` |
| 15 | 吸收投资者投资收到的现金 | `SME_CF_15_CAPITAL_RECEIPTS` | `INFLOW` |
| 16 | 偿还借款本金支付的现金 | `SME_CF_16_PRINCIPAL_REPAYMENTS` | `OUTFLOW` |
| 17 | 偿还借款利息支付的现金 | `SME_CF_17_INTEREST_PAYMENTS` | `OUTFLOW` |
| 18 | 分配利润支付的现金 | `SME_CF_18_PROFIT_DISTRIBUTION` | `OUTFLOW` |
| 19 | 筹资活动产生的现金流量净额 | 14 + 15 - 16 - 17 - 18 | `TOTAL` |
| 20 | 四、现金净增加额 | 7 + 13 + 19 | `TOTAL` |
| 21 | 加：期初现金余额 | 现金账户 `OPENING` | `BALANCE` |
| 22 | 五、期末现金余额 | 现金账户 `CLOSING` | `BALANCE` |

### 3.2 两列期间口径

- 主列“本年累计金额”：从选定年度第一个可用期间到 `periodCode`。
- 第二列“本月金额”：仅 `periodCode` 单月。
- 行 21 主列：年度首个期间的期初现金余额。
- 行 21 第二列：选定月的期初现金余额。
- 行 22 两列：均为选定月期末现金余额。
- 行 1—20：根据各自列的期间范围读取已过账凭证现金流。

### 3.3 勾稽检查

主列和第二列分别执行以下 5 项检查，共返回 10 项：

```text
行7  = 行1 + 行2 - 行3 - 行4 - 行5 - 行6
行13 = 行8 + 行9 + 行10 - 行11 - 行12
行19 = 行14 + 行15 - 行16 - 行17 - 行18
行20 = 行7 + 行13 + 行19
行22 = 行20 + 行21
```

数据缺失时仍执行勾稽检查。即使缺失现金流项目形成的净额恰好为零，`dataQuality` 也必须标记为 `INCOMPLETE`，不能只依赖勾稽结果判断完整性。

## 4. API 契约

### 4.1 法定报表接口

扩展现有接口：

```http
GET /v1/ledgers/{ledgerId}/reports/statutory/cash-flow?periodCode=YYYY-MM
```

响应继续使用 `StatutoryStatement`：

```json
{
  "reportType": "cash-flow",
  "templateCode": "SME-2011-17-CASH-FLOW",
  "standardCode": "SME",
  "standardVersion": "2011-17",
  "periodCode": "2026-08",
  "primaryColumn": "本年累计金额",
  "comparativeColumn": "本月金额",
  "groups": [],
  "checks": [],
  "dataQuality": {
    "status": "COMPLETE",
    "primaryUnclassifiedVoucherCount": 0,
    "primaryUnclassifiedLineCount": 0,
    "comparativeUnclassifiedVoucherCount": 0,
    "comparativeUnclassifiedLineCount": 0,
    "samples": []
  },
  "formulaCode": "CASH_FLOW",
  "formulaVersion": 1
}
```

`dataQuality` 在三种法定报表中始终存在。资产负债表和利润表返回 `COMPLETE`、零计数和空样例，避免响应结构随 `reportType` 变化。

样例最多返回 10 条，按期间、凭证日期、凭证号、行次排序：

```json
{
  "voucherId": "uuid",
  "voucherNumber": "记-12",
  "periodCode": "2026-08",
  "voucherDate": "2026-08-15",
  "lineNo": 1,
  "side": "DEBIT",
  "baseAmount": 1000.00,
  "reason": "ITEM_MISSING"
}
```

`reason` 取值：

- `ITEM_MISSING`：未填写现金流项目。
- `LEGACY_COARSE_ITEM`：使用旧 `OPERATING`、`INVESTING`、`FINANCING`。
- `ITEM_NOT_IN_FORMULA`：项目未被当前发布公式引用。
- `ITEM_INACTIVE`：项目已停用。

### 4.2 错误语义

- 非 SME：`422 STATUTORY_REPORT_UNSUPPORTED_STANDARD`
- 非 CNY：`422 STATUTORY_REPORT_CURRENCY_UNSUPPORTED`
- 期间不存在：`404 PERIOD_NOT_FOUND`
- 余额投影未就绪：`409 STATUTORY_REPORT_PROJECTION_PENDING`
- 公式缺失：`500 STATUTORY_FORMULA_NOT_FOUND`
- 未知报表类型：`404 STATUTORY_REPORT_NOT_FOUND`
- 新凭证现金行未分类：`422 CASH_FLOW_CLASSIFICATION_REQUIRED`
- 使用当前公式不接收的项目：`422 CASH_FLOW_ITEM_NOT_REPORTABLE`

### 4.3 公式管理接口

现有公式 API 的代码白名单增加 `CASH_FLOW`：

```http
GET    /v1/ledgers/{ledgerId}/report-formulas/CASH_FLOW
POST   /v1/ledgers/{ledgerId}/report-formulas/CASH_FLOW/draft
PUT    /v1/ledgers/{ledgerId}/report-formulas/CASH_FLOW/draft
POST   /v1/ledgers/{ledgerId}/report-formulas/CASH_FLOW:preview
POST   /v1/ledgers/{ledgerId}/report-formulas/CASH_FLOW:publish
POST   /v1/ledgers/{ledgerId}/report-formulas/CASH_FLOW:rollback
```

预览只接受单月 `periodCode`，计算口径与正式报表一致。历史分类缺失体现在预览 statement 的 `dataQuality` 中，不阻止预览或发布；公式勾稽失败仍沿用现有警告确认流程。

## 5. 公式模型设计

保持 `schemaVersion=1`，以向后兼容的方式增加表达式能力，不迁移现有资产负债表和利润表 JSON。

```java
static final String REPORT_CASH_FLOW = "CASH_FLOW";

record AccountAmountExpression(
        String operation,
        String side,
        List<AccountReference> accounts,
        AmountBasis basis // nullable；为空时使用 columnPolicy
) implements LineExpression {}

record CashFlowItemAmountExpression(
        CashFlowDirection direction,
        List<String> itemCodes,
        List<AccountReference> cashAccounts
) implements LineExpression {}

enum CashFlowDirection {
    INFLOW,
    OUTFLOW,
    NET
}
```

现金流项目表达式示例：

```json
{
  "type": "CASH_FLOW_ITEM_AMOUNT",
  "direction": "OUTFLOW",
  "itemCodes": ["SME_CF_05_TAX_PAYMENTS"],
  "cashAccounts": [
    {"type": "STANDARD_ACCOUNT_KEY", "value": "ASSET.CASH"},
    {"type": "STANDARD_ACCOUNT_KEY", "value": "ASSET.BANK_DEPOSIT"},
    {"type": "STANDARD_ACCOUNT_KEY", "value": "ASSET.OTHER_MONETARY_FUNDS"}
  ]
}
```

行 21、22 使用行级余额基准：

```json
{
  "type": "ACCOUNT_AMOUNT",
  "operation": "ACCOUNT_BALANCE",
  "side": "DEBIT",
  "basis": "OPENING",
  "accounts": [
    {"type": "STANDARD_ACCOUNT_KEY", "value": "ASSET.CASH"},
    {"type": "STANDARD_ACCOUNT_KEY", "value": "ASSET.BANK_DEPOSIT"},
    {"type": "STANDARD_ACCOUNT_KEY", "value": "ASSET.OTHER_MONETARY_FUNDS"}
  ]
}
```

### 5.1 公式验证规则

- `CASH_FLOW_ITEM_AMOUNT` 只能用于 `reportType=CASH_FLOW`。
- `direction`、`itemCodes`、`cashAccounts` 必填且非空。
- 同一现金流项目代码不能被两条明细行重复引用。
- 标准模板只能引用标准包内项目代码和标准科目键。
- 用户公式允许引用本账套具体科目 ID。
- `basis` 只能用于 `ACCOUNT_BALANCE`，可选值为 `OPENING` 或 `CLOSING`。
- `CASH_FLOW` 固定保持 22 个行键、行次、分组和勾稽关系；草稿只允许修改名称和表达式。
- 公式具体科目引用索引必须收集 `cashAccounts` 中的 `ACCOUNT_ID` 引用。

## 6. 数据流与关键伪代码

### 6.1 报表服务

```java
StatutoryStatement cashFlowStatement(actorId, ledgerId, periodCode) {
    requireView(actorId, ledgerId);
    requireSmeCnyLedger(ledgerId);

    PeriodRange selected = PeriodRange.single(periodCode);
    validateRange(ledgerId, selected);

    String firstPeriod = reports.firstPeriodOfYear(ledgerId, periodCode);
    if (firstPeriod == null) throw PERIOD_NOT_FOUND;

    PeriodRange yearToDate = new PeriodRange(firstPeriod, periodCode);
    requireStatutoryProjection(ledgerId, yearToDate);
    requireStatutoryProjection(ledgerId, selected);

    FormulaSnapshot snapshot = formulas.findSnapshot(ledgerId, "CASH_FLOW")
        .orElseThrow(STATUTORY_FORMULA_NOT_FOUND);
    ReportFormulaDefinition definition = parser.parse(snapshot.formulaJson());
    validator.requireValid(definition, ledgerId);

    Set<UUID> cashAccountIds = resolver.expandToLeafIds(
        ledgerId,
        unionCashAccountReferences(definition)
    );

    List<FormulaAccountAmount> primaryBalances =
        reports.formulaAccountAmounts(ledgerId, yearToDate, false);
    List<FormulaAccountAmount> monthlyBalances =
        reports.formulaAccountAmounts(ledgerId, selected, false);

    CashFlowSource primaryFlows = reports.cashFlowAmounts(
        ledgerId, yearToDate, cashAccountIds, referencedItemCodes(definition));
    CashFlowSource monthlyFlows = reports.cashFlowAmounts(
        ledgerId, selected, cashAccountIds, referencedItemCodes(definition));

    DataQuality primaryQuality = reports.cashFlowClassificationQuality(
        ledgerId, yearToDate, cashAccountIds, referencedItemCodes(definition));
    DataQuality monthlyQuality = reports.cashFlowClassificationQuality(
        ledgerId, selected, cashAccountIds, referencedItemCodes(definition));

    StatutoryStatement statement = evaluator.evaluateFixedLines(
        ledgerId,
        definition,
        primaryBalances,
        monthlyBalances,
        primaryFlows,
        monthlyFlows,
        metadata("cash-flow", "本年累计金额", "本月金额")
    );

    return withFormulaAndQuality(
        statement,
        snapshot.publishedVersion(),
        merge(primaryQuality, monthlyQuality)
    );
}
```

### 6.2 表达式求值

```java
BigDecimal evaluate(expression, columnContext) {
    if (expression instanceof CashFlowItemAmountExpression cashFlow) {
        BigDecimal debit = sumDebit(cashFlow.itemCodes(), columnContext.cashFlows());
        BigDecimal credit = sumCredit(cashFlow.itemCodes(), columnContext.cashFlows());

        return switch (cashFlow.direction()) {
            case INFLOW -> debit.subtract(credit);
            case OUTFLOW -> credit.subtract(debit);
            case NET -> debit.subtract(credit);
        };
    }

    if (expression instanceof AccountAmountExpression account) {
        AmountBasis basis = account.basis() != null
            ? account.basis()
            : columnContext.defaultBasis();
        return evaluateAccountAmount(account, basis, columnContext.accountAmounts());
    }

    if (expression instanceof LinearCombinationExpression combination) {
        return combination.components().stream()
            .map(component -> calculated(component.lineKey())
                .multiply(BigDecimal.valueOf(component.factor())))
            .reduce(ZERO, BigDecimal::add);
    }

    throw unsupportedExpression(expression);
}
```

红字和冲销金额沿用 `base_amount` 的正负号参与计算，不对凭证明细取绝对值。

### 6.3 现金流聚合查询

在 `ReportingRepository` 增加现金流金额和完整性查询。核心 SQL 结构：

```sql
with cash_lines as (
    select
        voucher.id as voucher_id,
        voucher.voucher_number,
        period.period_code,
        voucher.voucher_date,
        line.id as line_id,
        line.line_no,
        line.side,
        line.base_amount,
        item.code as item_code,
        item.status as item_status
    from voucher
    join accounting_period period
      on period.id = voucher.period_id
     and period.ledger_id = voucher.ledger_id
    join voucher_line line
      on line.voucher_id = voucher.id
     and line.ledger_id = voucher.ledger_id
    left join cash_flow_item item
      on item.id = line.cash_flow_item_id
     and item.ledger_id = line.ledger_id
    where voucher.ledger_id = :ledgerId
      and voucher.status = 'POSTED'
      and period.period_code between :periodFrom and :periodTo
      and line.account_id in (:cashAccountIds)
),
external_cash_lines as (
    select cash.*
    from cash_lines cash
    where exists (
        select 1
        from voucher_line other
        where other.ledger_id = :ledgerId
          and other.voucher_id = cash.voucher_id
          and other.account_id not in (:cashAccountIds)
    )
)
select
    item_code,
    sum(case when side = 'DEBIT' then base_amount else 0 end) as debit_amount,
    sum(case when side = 'CREDIT' then base_amount else 0 end) as credit_amount
from external_cash_lines
where item_code in (:formulaItemCodes)
  and item_status = 'ACTIVE'
group by item_code;
```

完整性查询复用同一现金行范围，但筛选以下情况：项目为空、旧粗分类、项目停用、项目代码不在当前公式中。样例查询和计数查询必须共享同一判定函数或 SQL 片段，避免计数与样例不一致。

新增 Flyway 索引：

```sql
create index ix_voucher_line_cash_flow_report
    on voucher_line (ledger_id, account_id, voucher_id, cash_flow_item_id);
```

## 7. 凭证生命周期约束

不改变草稿保存行为。在 `DRAFT -> VALIDATED` 以及最终 `POSTED` 前执行现金流分类校验；`submit` 保留防御性复查。

```java
void requireCashFlowClassification(UUID ledgerId, UUID voucherId) {
    if (!isSmeLedger(ledgerId)) return;

    Set<UUID> cashAccountIds = resolvePublishedCashFlowCashAccounts(ledgerId);
    List<VoucherLine> lines = vouchers.lines(ledgerId, voucherId);

    List<VoucherLine> cashLines = lines.stream()
        .filter(line -> cashAccountIds.contains(line.accountId()))
        .toList();
    if (cashLines.isEmpty()) return;

    boolean hasNonCashLine = lines.stream()
        .anyMatch(line -> !cashAccountIds.contains(line.accountId()));
    if (!hasNonCashLine) {
        // 库存现金、银行存款、其他货币资金之间的内部划转。
        return;
    }

    Set<String> reportableCodes = publishedCashFlowItemCodes(ledgerId);
    for (VoucherLine line : cashLines) {
        if (line.cashFlowItemId() == null) {
            throw cashFlowClassificationRequired(line.lineNo());
        }
        CashFlowItem item = requireActiveCashFlowItem(line.cashFlowItemId());
        if (!reportableCodes.contains(item.code())) {
            throw cashFlowItemNotReportable(line.lineNo(), item.code());
        }
    }
}
```

约束说明：

- 项目必须填在现金行，而不是非现金对方行。
- 多条现金行必须逐行分类。
- 一张凭证只包含现金账户时视为内部划转。
- 一张复合凭证同时包含内部划转和费用等非现金行时无法可靠自动配对，因此所有现金行都按外部现金行处理；业务上应拆分凭证。
- 不改变现有 `cash_flow_required` 通用科目控制，避免破坏已有自定义控制。

## 8. 标准包、迁移与兼容

### 8.1 新账套

SME 标准包新增 16 个详细项目和 `CASH_FLOW` 公式。新账套不再创建三个粗分类项目。现金账户默认引用以下稳定标准键：

- `ASSET.CASH`
- `ASSET.BANK_DEPOSIT`
- `ASSET.OTHER_MONETARY_FUNDS`

### 8.2 现有账套

建立幂等 `CashFlowTemplateProvisioner`：

```java
void provision(UUID ledgerId, AccountingStandard.Package standard) {
    if (!"SME".equalsIgnoreCase(standard.code())) return;

    for (CashFlowItem template : standard.cashFlowItems()) {
        repository.insertTemplateIfAbsent(
            ledgerId, template.code(), template.name());
    }

    repository.deactivateTemplateItems(
        ledgerId, Set.of("OPERATING", "INVESTING", "FINANCING"));

    if (formulas.findSnapshot(ledgerId, "CASH_FLOW").isEmpty()) {
        formulas.createSnapshotWithPublishedVersion(
            ledgerId, "CASH_FLOW", "现金流量表", "FIXED_LINES",
            standardCashFlowFormulaJson, null);
    }
}
```

迁移要求：

- 使用唯一约束及 `insert ... on conflict` 保证多实例启动安全。
- 只停用 `is_template=true` 的旧三分类，不删除记录，不破坏历史外键。
- 不修改同名自定义项目。
- 新保留代码若与非模板自定义项目冲突，阻止应用就绪并记录账套 ID 和冲突代码，禁止静默覆盖。
- 已存在用户现金流公式时不得覆盖。
- CAS 账套不新增、不停用任何现金流项目。
- 启动迁移连续执行两次不得产生额外记录或版本。

### 8.3 备份恢复

- 备份格式版本增加 1。
- 新备份自然包含详细现金流项目、凭证明细引用、`CASH_FLOW` snapshot 和 revisions。
- 恢复旧版本备份后执行幂等补齐。
- 恢复包含用户现金流公式的新备份时保留其发布版本，不用标准模板覆盖。
- 旧测试中“公式数量为 2”的断言调整为 3。

## 9. 实施任务

- [ ] 任务 1：扩展公式契约和标准包
  - 内容：增加 `CASH_FLOW`、新表达式、方向枚举、行级余额基准、16 个项目和 22 行公式。
  - 验收：标准包可加载、转换和验证；原有两套公式 JSON 不变。
  - 验证：`AccountingStandardCatalogTest`、`StandardFormulaValidatorTest`、`StandardFormulaConverterTest`。

- [ ] 任务 2：实现幂等模板与公式补齐
  - 内容：新增 provisioner，接入新账套初始化和启动迁移，停用 SME 旧模板三分类。
  - 验收：执行两次结果一致；CAS 不变；冲突时明确失败。
  - 验证：`ReportFormulaMigrationTest` 和账套初始化集成测试。

- [ ] 任务 3：实现现金流仓储查询
  - 内容：新增现金流聚合、内部划转排除、完整性计数与样例查询，并添加索引。
  - 验收：期间、凭证状态、现金账户、项目代码和冲销金额处理正确。
  - 验证：新增 `JdbcReportingRepository` 定向集成测试。

- [ ] 任务 4：扩展公式求值器
  - 内容：支持 `CASH_FLOW_ITEM_AMOUNT`、方向换算和行级 OPENING/CLOSING。
  - 验收：22 行两列及 10 个勾稽检查全部正确；旧报表无回归。
  - 验证：`ReportFormulaEvaluatorTest`、`ReportFormulaValidatorTest`。

- [ ] 任务 5：接入报表服务和 REST API
  - 内容：接受 `cash-flow`，构造年累计和本月数据源，返回 `dataQuality`。
  - 验收：成功与错误响应符合第 4 节契约。
  - 验证：`DefaultReportingServiceTest`、`Stage4ReportingTest`、控制器测试。

- [ ] 任务 6：接入凭证生命周期
  - 内容：在验证、提交和过账前检查详细现金流分类，豁免纯内部划转。
  - 验收：新外部现金凭证不能缺分类过账；非现金凭证不受影响。
  - 验证：`VoucherAccountControlsIntegrationTest` 及新增现金流分类场景。

- [ ] 任务 7：接入公式编辑器后端
  - 内容：支持新表达式的草稿合并、预览、发布、具体科目引用索引和回滚。
  - 验收：现金流公式可以完整走通版本生命周期。
  - 验证：`ReportFormulaServiceTest`、`ReportFormulaControllerTest`。

- [ ] 任务 8：升级备份恢复并完成验收
  - 内容：升级备份格式，验证新旧备份恢复和公式保留。
  - 验收：旧备份补齐默认现金流能力，新备份保留用户公式版本。
  - 验证：`LedgerBackupServiceTest`、编译、HTTP 冒烟测试、知识图谱更新。

任务按顺序实施。任务 3 和任务 4 可在任务 1、2 完成后并行，但任务 5 必须等待二者完成；任务 6、7 可在任务 5 契约稳定后并行；任务 8 最后执行。

## 10. 测试场景

### 10.1 公式与标准包

- SME 标准包包含 16 个详细项目和 `CASH_FLOW`。
- 空项目列表、空现金账户、非法方向、重复项目代码被拒绝。
- 标准模板引用未知项目代码或标准科目键被拒绝。
- 用户公式引用其他账套科目被拒绝。
- `basis` 用在非余额表达式时被拒绝。
- 旧 schema-1 资产负债表和利润表仍能解析与发布。

### 10.2 求值器

- 借方现金为流入，贷方现金为流出。
- `OUTFLOW` 返回正数支出，汇总行按公式减除。
- `NET` 允许正负净额。
- 红字、冲销金额正确抵减。
- 行 7、13、19、20、22 的两列结果和差额正确。
- 行 21、22 分别读取每列范围的期初和期末余额。

### 10.3 仓储

- 只统计 `POSTED`，排除草稿和已删除凭证。
- 年累计和本月期间边界正确。
- 标准现金科目的下级叶子科目被纳入。
- 纯现金账户内部划转被排除。
- 缺失、旧分类、停用和未映射项目进入质量统计但不进入法定行。
- 样例和总数使用相同判定条件。

### 10.4 服务与控制器

- SME/CNY 返回 22 行、两列、公式版本和数据质量。
- CAS、非 CNY、无权限、非法期间和投影未就绪返回约定错误。
- 历史缺失分类时返回 200 和 `INCOMPLETE`。
- 完整分类时返回 `COMPLETE`，行 22 勾稽通过。
- 资产负债表和利润表新增 `dataQuality` 后无回归。

### 10.5 凭证

- 草稿允许暂缺现金流项目。
- 外部现金收支验证时缺项目返回 422。
- 使用旧粗分类或公式不接收的项目返回 422。
- 纯现金/银行内部划转无项目也可过账。
- 非现金凭证不受影响。
- 复合现金凭证要求每条现金行分类。

### 10.6 迁移与备份

- 旧 SME 账套补齐详细项目和 `CASH_FLOW` 版本 1。
- 三个旧模板项目停用但历史外键有效。
- 重复迁移幂等。
- CAS 数据完全不变。
- 旧备份恢复后自动补齐。
- 新备份恢复后保留用户公式版本。

## 11. 验证命令

遵循项目约定，先运行定向测试，不运行前端全量检查：

```powershell
rtk .\mvnw.cmd -q -Dtest=AccountingStandardCatalogTest,ReportFormulaValidatorTest,ReportFormulaEvaluatorTest test
rtk .\mvnw.cmd -q -Dtest=DefaultReportingServiceTest,Stage4ReportingTest,VoucherAccountControlsIntegrationTest test
rtk .\mvnw.cmd -q -Dtest=ReportFormulaMigrationTest,ReportFormulaControllerTest,LedgerBackupServiceTest test
rtk .\mvnw.cmd -q -DskipTests compile
```

编译后优先使用运行中的 `http://127.0.0.1:8080` 验证接口。需要隔离数据库时运行 `\.\start-backend-test.ps1`，使用默认端口 `18080`。代码修改完成后执行：

```powershell
rtk graphify update .
```

## 12. 代码与模块边界

- 报表公开契约放在 `reporting` 包，现金流 SQL 留在 `reporting.internal.persistence`。
- 公式定义与标准转换放在 `ledger.formula`，运行时求值放在 `reporting.formula`。
- 凭证服务只负责离开草稿前的完整性约束，不承担报表金额计算。
- 标准包是法定行、项目代码和默认公式的唯一来源，服务层不得硬编码 22 行名称。
- 报表服务只编排期间、权限、投影和数据源，不内嵌 SQL。
- 所有金额统一保留两位小数，沿用本位币 `base_amount` 和 `HALF_UP`。

## 13. Always / Ask First / Never

### Always

- 修改公式结构时同步更新标准验证器、账套验证器、草稿解析和测试。
- 迁移必须幂等，并保留用户发布版本和历史外键。
- 新接口字段同步更新 OpenAPI。
- 在最终验收前运行定向测试、编译、HTTP 冒烟验证和 `graphify update .`。

### Ask First

- 扩展到 CAS 现金流量表。
- 增加年度“上年金额”模式。
- 增加历史凭证批量自动归类或修改已过账凭证的能力。
- 改变现金及现金等价物标准科目范围。
- 新增第三方依赖或执行仓库级全量前端检查。

### Never

- 不得把未分类历史现金流静默当成完整数据。
- 不得按科目名称或用户可编辑名称识别现金账户。
- 不得根据对方科目自动推断并静默写入现金流项目。
- 不得删除旧现金流项目或破坏历史凭证外键。
- 不得在启动迁移中覆盖用户已发布公式。
- 不得为生成现金流量表回退到未过账凭证或绕过账套权限。

## 14. 风险与缓解

| 风险 | 缓解措施 |
| --- | --- |
| 历史凭证缺少详细分类 | 金额按零但返回 `INCOMPLETE`、计数和定位样例 |
| 旧三分类继续被新凭证选择 | SME 模板项目停用；过账前只接受当前公式引用代码 |
| 现金内部划转被重复统计 | 查询和凭证校验均排除纯现金账户凭证 |
| 用户公式重复引用同一项目 | 发布前验证项目代码唯一归属 |
| 公式遗漏已有项目导致金额消失 | 预览和正式报表都返回数据质量告警 |
| 多实例同时执行补齐 | 数据库唯一约束和 `ON CONFLICT` 幂等写入 |
| 新查询影响大账套性能 | 添加定向索引；按账套、期间、现金账户和已过账状态过滤 |
| 旧备份恢复后缺少新模板 | 提升备份版本并在旧版本恢复后执行 provisioner |

## 15. 参考资料

- 财政部：《[小企业会计准则——会计科目、主要账务处理和财务报表](https://kjs.mof.gov.cn/zhengcefabu/201111/P020111118325852734144.pdf)》，其中“（四）小企业现金流量表格式及编制说明”为会小企 03 表法定行次和勾稽关系来源。
- 项目现有法定报表规格：`docs/specs/sme-statutory-reports.md`。

