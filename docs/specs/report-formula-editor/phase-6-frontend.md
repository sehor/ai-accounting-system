# 阶段 6：前端公式编辑器

## 前置

阶段 5 已通过；OpenAPI 已暴露公式端点。

## 目标

在账套设置中提供结构化公式编辑、试算、发布和版本回滚，不提供 JSON 文本编辑。

## 组件

在 Settings Tabs 增加 `report-formulas`。拆分组件，避免继续扩大 SettingsPage：

```text
ReportFormulaSettingsTab.tsx
FormulaFixedLineEditor.tsx
FormulaDetailRuleEditor.tsx
FormulaExpressionEditor.tsx
FormulaPreviewPane.tsx
FormulaVersionDrawer.tsx
```

每个组件目标少于 200 行。远程状态用 React Query，本地未保存表单用组件 state/Ant Form，不新增全局 store。

## SME 编辑器

每行显示锁定行号、可编辑名称和表达式。表达式支持：

- 标准科目项。
- 具体账套科目；父科目标识“包含下级”。
- 借贷方向。
- 前序行加减；下拉选项只能包含当前行之前的行。

停用科目显示标签但保留已有引用。

## CAS 编辑器

编辑类别、标准项/具体科目补充选择和借贷方向。side 冲突在规则旁显示阻断错误。动态报表行名称仍取科目档案。

## 状态机

```text
NO_DRAFT -> 创建草稿
EDITING_DIRTY -> 保存草稿
SAVED_NOT_PREVIEWED -> 试算
PREVIEWED_OK -> 发布
PREVIEWED_WARNING -> 确认告警后发布
CONFLICT -> 用户确认后刷新
```

本地 dirty 时禁用试算；服务器 previewedDraftVersion 不等于 draftVersion 时禁用发布。409 冲突弹窗不得自动覆盖服务器草稿。

## 查询与失效

```text
['report-formula', ledgerId, code]
['report-formula-versions', ledgerId, code, page]
['accounts', ledgerId]
```

发布/回滚后失效公式、版本和 `['report', ledgerId]`。正式报表页显示“公式版本 vN”辅助标签。

## 布局与可访问性

桌面端左编辑、右预览；窄屏上下排列并允许表格横向滚动。所有输入有可见 label，错误不只靠颜色表达，按钮和版本抽屉支持键盘操作。

## 验收

- OWNER/EDITOR 可编辑，其他角色完整只读。
- 无需手写 JSON 可完成两类公式修改。
- dirty、试算、告警确认、发布按钮状态正确。
- 409 冲突要求用户选择刷新，不丢失时静默覆盖。
- 标准重置只改草稿；版本回滚存在草稿时显示阻塞。
- 发布后正式报表刷新并显示新版本。
- 定向 Vitest 与公式页面 axe 测试通过。

## 交接记录

记录组件、query key、生成类型版本和定向前端测试结果。
