# 阶段 3：校验器与计算器

## 前置

阶段 2 已通过；schemaVersion 1 可持久化和读取。

## 目标

实现一套校验器和通用计算器。本阶段不切换正式报表接口。

## 校验器

`ReportFormulaValidator` 在保存、试算、发布、迁移、回滚时复用。

固定限制：名称 1~200；每行最多 100 个账户引用或组件；整份最多 2000 AST 节点；factor 仅 ±1。

校验顺序：

```java
validateShape();
validateKindAndReportType();
validateOperationWhitelist();
validateLockedStructure();
validateNamesAndLimits();
validateLineKeysAndBackwardReferences();
validateAccountOwnership();
expandReferencesAndRejectOverlap();
validateCasSideConflicts();
```

阻断问题格式：

```java
record FormulaIssue(String code, String path, String message) {}
```

科目展开：标准项返回其全部映射末级科目；具体父科目返回全部末级后代。跨账套 UUID 非法。同一表达式展开后的末级 UUID 集合不得重复。

## 统一金额源

新增内部 `FormulaAccountAmount`，包含 accountId、code、name、standardAccountKey、category 及期初/发生/期末借贷额。

Repository 查询只返回末级科目，包含停用但有历史金额的科目。父科目后代使用一次参数化递归 CTE 批量查询，避免 N+1。

## 固定行计算

```java
for (group : definition.groups) {
  for (line : group.lines) {
    primary = eval(line.expression, primarySource, calculated, PRIMARY);
    comparative = eval(line.expression, comparativeSource, calculated, COMPARATIVE);
    calculated.put(line.key, money(primary, comparative));
  }
}
checks = evaluateChecks(definition.checks, calculated);
```

```java
eval(ACCOUNT_AMOUNT) = source
  .filter(accountId in expandedReferences)
  .map(side == DEBIT ? debit-credit : credit-debit)
  .sum();

eval(LINEAR_COMBINATION) = components
  .map(previousLineValue * factor)
  .sum();
```

## CAS 动态计算

每个末级科目匹配 category 或 account reference。没有规则则不输出；多规则 side 一致则去重，side 冲突由校验器阻止。结果按科目编码排序，名称来自科目档案。

## 期间口径

- SME 资产负债表：选定期末、当年首期期初。
- SME 利润表：年初至选定期、选定单月。
- CAS 资产负债表：请求范围期末。
- CAS 利润表：请求范围发生额。

口径来自 definition.columnPolicy，不在计算器里按模板名判断。

## 验收

- 现有 SME 样本金额和勾稽结果一致。
- 现有 CAS 行、顺序和金额一致。
- 标准项、具体叶子、具体父科目取数正确。
- 覆盖跨账套、重叠取数、后向引用、非法 factor/operation、CAS side 冲突和上限测试。
- `ReportFormulaEvaluator` 不包含 `bs-30`、`bs-53` 等固定行 key。

## 交接记录

记录 validator/evaluator 入口、金额源查询和全部定向测试结果。
