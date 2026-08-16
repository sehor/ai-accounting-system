# 阶段 1：领域契约与标准包

## 目标

定义唯一的公式 JSON schema，并把 SME/CAS 标准包转换到该 schema。本阶段不改数据库和正式报表调用链。

## 领域类型

新增内部类型，数据库最终保存其 JSON：

```java
record ReportFormulaDefinition(
    int schemaVersion,          // 1
    String kind,                // FIXED_LINES | ACCOUNT_DETAIL
    String reportType,          // BALANCE_SHEET | INCOME_STATEMENT
    String templateCode,
    ColumnPolicy columnPolicy,
    List<FormulaGroup> groups,
    List<DetailRule> rules,
    List<FormulaCheck> checks
) {}
```

SME 行与表达式：

```java
record FormulaLine(
    String key, int lineNo, int indent, String rowType,
    String name, LineExpression expression
) {}

// type=ACCOUNT_AMOUNT
record AccountAmountExpression(
    String operation,           // ACCOUNT_BALANCE | ACCOUNT_ACTIVITY
    String side,                // DEBIT | CREDIT
    List<AccountReference> accounts
) {}

// type=LINEAR_COMBINATION
record LinearCombinationExpression(List<LineComponent> components) {}
record LineComponent(String lineKey, int factor) {} // factor 仅 ±1
```

科目引用：

```java
record AccountReference(
    String type,                // STANDARD_ACCOUNT_KEY | ACCOUNT_ID
    String value
) {}
```

CAS 规则：

```java
record DetailRule(
    String key,
    String side,
    List<String> categories,
    List<AccountReference> accounts
) {}
```

勾稽规则：

```java
record FormulaCheck(
    String code, String name,
    String leftLineKey, String rightLineKey
) {}
```

## 标准包转换

- SME 两张表转换为 `FIXED_LINES`，保留现有 53/32 行、期间口径和公式结果。
- 当前 Java 中的资产平衡检查写入 `checks`。
- CAS 两张表转换为 `ACCOUNT_DETAIL`，原 category 数组映射为规则，保持现有借贷方向。
- 标准模板只使用 `STANDARD_ACCOUNT_KEY`，不写账套 UUID。

`AccountingStandardCatalog` 启动校验至少检查：schemaVersion、kind/reportType 组合、标准科目 key 存在、行 key 唯一、组件引用存在、操作白名单。

## 边界

- 不改 `report_formula_snapshot`。
- 不新增 Controller。
- 不改变现有报表结果或 API。

## 验收

- SME/CAS 标准包均能启动加载。
- 四个公式均可反序列化为领域类型。
- 标准包测试覆盖重复行 key、非法标准科目、非法操作和错误 kind。
- 现有标准包加载测试通过。

## 交接记录

完成后记录领域类型路径、四个公式的 kind/reportType，以及定向测试结果。
