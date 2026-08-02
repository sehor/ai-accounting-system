# 账套备份与恢复规格

状态：实施中  
格式版本：`AI-ACCOUNTING-LEDGER-BACKUP/1`

## 目标

账套 OWNER 可以把一个账套的业务数据和附件下载为单个 `.aibackup` 文件。已登录用户可以上传该文件并恢复为一个新的账套；恢复不得覆盖或修改任何现有账套。

## 范围

备份包含账套设置、会计期间、科目与现金流项目、辅助核算、期初余额、凭证及分录、审批与期间审计、报表公式、审计修订、Agent 审计、附件元数据、附件内容和提取结果。

备份不包含成员关系、用户资料、幂等记录、后台任务、科目导入预检记录等运行态数据。恢复后的账套只建立一个 OWNER：执行恢复的当前用户；历史记录中的用户引用统一映射为该用户。

## API 合同

- `GET /v1/ledgers/{ledgerId}/backup`
  - 仅 OWNER。
  - 返回 `application/vnd.ai-accounting.ledger-backup+zip`。
  - 文件名为 `ledger-{ledgerId}.aibackup`。
- `POST /v1/ledger-restores`
  - 已登录用户可调用。
  - `multipart/form-data`：`file` 必填，`name` 可选。
  - 成功返回 `201` 和新账套 `Ledger`；所有业务 UUID 重新生成。

错误沿用 RFC Problem Details，使用稳定错误码：`LEDGER_BACKUP_INVALID`、`LEDGER_BACKUP_TOO_LARGE`、`LEDGER_BACKUP_CONTENT_MISSING`、`LEDGER_RESTORE_FAILED` 和现有权限错误码。

## 文件格式与安全边界

ZIP 仅允许：

- `manifest.json`：格式版本、生成时间、源账套、`data.json` SHA-256、附件清单。
- `data.json`：白名单表和白名单列的 JSON 数据。
- `attachments/{documentId}`：附件原始字节。

上传包最大 100 MiB，解压后总量最大 100 MiB，最多 1,000 个条目，单附件最大 20 MiB。拒绝重复条目、未知条目、目录穿越、缺失附件、大小或 SHA-256 不匹配、未知格式版本和非白名单数据。校验用于发现损坏，不作为来源签名。

恢复在单个数据库事务内完成。附件只写入规范化后的本地存储根目录，并在事务回滚时删除；数据库提交前任何校验或外键失败都会使恢复整体失败。

## 前端

账套设置新增“备份与恢复”页签：

- OWNER 可下载当前账套备份。
- 已登录用户可选择 `.aibackup` 文件、可选填写新账套名称并恢复。
- 显示上传限制、恢复为新账套以及成员不会复制的提示，并提供加载、成功和错误反馈。

## 命令与项目位置

- 后端构建/测试：`.\mvnw.cmd test`
- 前端检查：`pnpm lint`、`pnpm test`、`pnpm build`
- 后端代码：`src/main/java/com/example/accounting/ledger/`
- 后端测试：`src/test/java/com/example/accounting/ledger/`
- 前端：`frontend/src/pages/`

遵循现有 Spring JDBC、RFC Problem Details、Ant Design 和 React Query 约定；不新增依赖，不新增数据库表或 Flyway 迁移。

## 实施任务

1. 先添加后端恢复往返、权限和恶意 ZIP 的失败测试。
2. 实现版本化归档、白名单数据导出、完整性校验、UUID 重映射及事务恢复。
3. 暴露下载/上传 REST 接口并运行后端定向测试。
4. 添加设置页签及前端交互测试。
5. 运行后端、前端构建与测试，并更新代码图。

## 验收条件

- 有业务数据的账套备份后可恢复为新账套，源账套保持不变。
- 新账套的核心业务行数和内容与源账套一致，所有账套内 UUID 与源账套不同。
- 附件字节、大小和 SHA-256 一致，恢复后的对象键重新生成。
- 非 OWNER 无法下载；无效或超限备份不会创建账套或残留文件。
- 现有接口保持兼容，数据库结构不变，相关自动化测试和构建通过。
