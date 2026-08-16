# 阶段 5：草稿、发布与版本 API

## 前置

阶段 4 已通过；正式报表只读当前发布快照。

## 目标

提供完整的公式工作区、草稿、试算、发布、历史、标准重置和回滚 API。

## 端点

```text
GET    /v1/ledgers/{ledgerId}/report-formulas/{code}
POST   /v1/ledgers/{ledgerId}/report-formulas/{code}/draft
PUT    /v1/ledgers/{ledgerId}/report-formulas/{code}/draft
DELETE /v1/ledgers/{ledgerId}/report-formulas/{code}/draft
POST   /v1/ledgers/{ledgerId}/report-formulas/{code}/draft:reset
POST   /v1/ledgers/{ledgerId}/report-formulas/{code}/draft:preview
POST   /v1/ledgers/{ledgerId}/report-formulas/{code}:publish
GET    /v1/ledgers/{ledgerId}/report-formulas/{code}/versions?page=1&pageSize=20
GET    /v1/ledgers/{ledgerId}/report-formulas/{code}/versions/{version}
POST   /v1/ledgers/{ledgerId}/report-formulas/{code}/versions/{version}:rollback
```

code 只允许 `BALANCE_SHEET`、`INCOME_STATEMENT`、`CASH_FLOW`。版本 pageSize 最大 100。

## 编辑请求边界

SME PUT 只接收 `lineKey/name/expression`；服务端覆盖到已有完整模板，客户端不能提交行号、分组、顺序、columnPolicy、checks。

CAS PUT 只接收 rules。每次成功保存 draftVersion+1，并清空试算标记。

## 试算与发布

- SME preview 接收 periodCode；CAS 接收 periodFrom/periodTo。
- 结构/引用问题返回 blockingIssues，不标记成功试算。
- 勾稽不平返回 warnings，并设置 `previewHasWarnings=true`。

发布事务：

```java
require OWNER or EDITOR;
snapshot = lockSnapshot();
require snapshot.version == expectedPublishedVersion;
draft = lockDraft();
require draft.version == expectedDraftVersion;
require draft.lastPreviewedVersion == draft.version;
require !draft.hasWarnings || acknowledgeWarnings;
validator.requireValid(draft.definition);
insertPublishedVersion(snapshot.version + 1);
replaceExactAccountReferences();
updateSnapshot();
deleteDraft();
writeAuditRevision();
```

任一步失败必须整体回滚。

## 回滚与重置

- reset 只覆盖现有草稿并使 draftVersion+1，之后必须重新试算。
- rollback 存在草稿时返回 409；否则把历史定义复制为新发布版本，例如 v5 回滚 v2 生成 v6。

## 权限与错误

读取沿用所有报表查看角色；写入仅 OWNER/EDITOR。

稳定错误码：

```text
REPORT_FORMULA_NOT_FOUND
REPORT_FORMULA_DRAFT_NOT_FOUND
REPORT_FORMULA_VERSION_CONFLICT
REPORT_FORMULA_PREVIEW_REQUIRED
REPORT_FORMULA_WARNING_ACK_REQUIRED
REPORT_FORMULA_DRAFT_EXISTS
REPORT_FORMULA_INVALID
REPORT_FORMULA_REFERENCE_INVALID
REPORT_FORMULA_PERIOD_INVALID
```

## 验收

- 完整 HTTP 流程可执行：创建草稿→保存→试算→发布→正式报表版本更新。
- 旧 draft/published version 返回 409，不自动覆盖。
- 未试算、未确认告警不能发布。
- 回滚生成新版本并记录 `source=ROLLBACK`。
- audit_revision 包含保存、发布、丢弃、重置、回滚的操作者和前后快照。
- VIEWER/REVIEWER/AGENT 写入返回 403。

## 交接记录

记录 OpenAPI DTO 名称、端点测试和权限/并发/事务测试结果。
