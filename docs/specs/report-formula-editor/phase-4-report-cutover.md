# 阶段 4：正式报表切换

## 前置

阶段 3 已通过；新计算器与旧结果等价。

## 目标

让 SME/CAS 正式资产负债表和利润表只读取当前发布快照，同时保持现有 URL 与响应主体兼容。

## 改动

`DefaultReportingService` 的两类路径统一为：

```java
snapshot = formulaRepository.current(ledgerId, formulaCode);
definition = parser.parse(snapshot.formulaJson);
validator.requireValid(definition, ledgerId);
source = loadAmounts(definition.columnPolicy, requestedPeriod);
result = evaluator.evaluate(definition, source);
return addFormulaMetadata(result, formulaCode, snapshot.publishedVersion);
```

必须删除 SME 正式计算路径中的：

```java
standards.formula("SME", version, formulaCode)
```

标准包不得成为正式报表的运行时回退。

保持现有行为：

- SME 仅支持当前已有报表类型、标准和币种限制。
- 余额投影未就绪仍返回现有 409。
- CAS URL 和 `code/name/amount` 行结构不变。
- SME groups、lines、checks 结构不变。

响应只增加可选 `formulaCode`、`formulaVersion`；保留兼容构造器，避免大量无关测试改动。

## 清理

`StatutoryReportCalculator` 应删除，或变成无业务逻辑的 evaluator 委托。不得保留固定行数、固定 check key 和 operation switch 的第二套实现。

## 验收

- 修改 classpath 标准包但不修改账套快照，已有账套正式报表不变化。
- 修改 snapshot 后 SME/CAS 正式接口使用新定义。
- SME/CAS 原有 HTTP 回归测试通过。
- 响应包含正确 formulaVersion，旧字段未改型。
- 代码搜索确认正式路径不再调用 `AccountingStandardCatalog.formula`。

## 验证

先运行 `\.\mvnw.cmd -q -DskipTests compile`，优先调用已运行的 `127.0.0.1:8080` 四个报表接口；JUnit 仅运行 reporting 相关类。

## 交接记录

记录被替换的旧入口、HTTP 验证结果和兼容性测试结果。
