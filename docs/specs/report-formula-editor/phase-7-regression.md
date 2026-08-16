# 阶段 7：回归、OpenAPI 与交付

## 前置

阶段 1~6 全部验收通过。

## 目标

验证迁移、兼容、安全和完整用户流程，更新生成物与知识图谱。

## 后端回归矩阵

- SME/CAS 迁移前后未编辑报表金额一致。
- SME 固定行、列标题、勾稽结果一致。
- CAS 行集合、顺序、名称、金额一致。
- 标准项、具体叶子、父科目后代、停用科目取数正确。
- 跨账套、重叠取数、后向引用、非法 operation/factor、超限输入被拒绝。
- 双用户并发保存/发布返回 409。
- 未试算和未确认告警无法发布。
- 发布原子性：故障时 snapshot/revision/audit 均不出现半成品。
- 历史回滚生成新版本。
- 备份恢复保留 snapshot、revision、reference、draft。

建议定向类：

```text
ReportFormulaValidatorTest
ReportFormulaEvaluatorTest
ReportFormulaServiceTest
ReportFormulaControllerTest
ReportFormulaMigrationTest
Stage4ReportingTest
LedgerBackupServiceTest
```

## 前端回归矩阵

- 两类编辑器、只读角色、dirty 状态、试算、告警确认、冲突、重置、版本分页和回滚。
- 320px、768px、1440px 布局。
- 键盘和 axe。
- ReportsPage 原有 SME/CAS 展示回归。

## 验证方式

后端先执行：

```powershell
.\mvnw.cmd -q -DskipTests compile
```

优先通过已运行的 `127.0.0.1:8080` 验证完整 HTTP 生命周期；只运行相关 JUnit 类。

前端生成与定向测试：

```powershell
pnpm --dir frontend api:generate
pnpm --dir frontend test -- ReportFormula
pnpm --dir frontend test -- ReportsPage
```

仓库级 `pnpm test/lint/typecheck/build` 需用户单独批准。

最后执行：

```powershell
graphify update .
```

## 最终完成标准

- 正式报表运行时不再读取 classpath 公式。
- SME/CAS 均使用当前发布 snapshot。
- 草稿、试算、发布、版本、回滚和权限完整工作。
- 原报表接口保持兼容，formulaVersion 正确。
- 迁移、备份、OpenAPI 生成类型、定向测试和 Graphify 均同步。

## 最终交接

提交一份短报告：修改文件分组、数据库迁移版本、API 清单、验证命令与结果、未运行的全量检查及原因、已知风险。不要复制阶段文档内容。
