# Agent 做账经验库

Agent 通过 MCP 显式保存和检索两类经验：`GENERAL` 为当前部署内所有 AGENT 共享，`LEDGER` 绑定单个账套。查询账套时会合并两类经验；通用经验不会进入账套备份，账套经验随 v2 账套备份恢复。

## MCP 工具

- `create_accounting_experience`：创建经验，标题、正文和标签经过长度及作用域校验。
- `search_accounting_experiences`：按账套、关键词、标签和分页查询有效经验。
- `update_accounting_experience`：使用 `expectedVersion` 乐观更新内容和标签。
- `archive_accounting_experience`：使用 `expectedVersion` 软归档经验。

只有 `UserType.AGENT` 可以使用这些工具；访问账套经验还要求当前用户是该账套的有效成员。经验正文是 agent 提供的普通业务参考文本，调用方仍需结合账套数据和会计规则复核。
