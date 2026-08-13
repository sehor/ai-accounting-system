# AI 财务系统

后端财务平台，技术基线见 `AI财务系统_开发设计文档_TDD_v0.1.md`。

## 当前本地环境决策

- 数据库使用本机 PostgreSQL，项目数据库为 `ai-accounting`。
- 附件使用本地文件系统，根目录由 `STORAGE_ROOT` 配置。
- 当前附件使用本地文件系统，暂不接入外部对象存储；后续如有部署需要再单独增加适配器。

## 启动前准备

1. 安装并启用 Java 21 和 PostgreSQL。
2. 创建数据库 `ai-accounting`。
3. 按需设置 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`STORAGE_ROOT`。

真实票据提取需设置 `APP_DOCUMENTS_EXTRACTOR_URL`（HTTPS）和可选的
`APP_DOCUMENTS_EXTRACTOR_API_KEY`。未配置时提取接口返回明确错误，不生成模拟数据。

默认运行连接：`jdbc:postgresql://127.0.0.1:5432/ai-accounting`，用户名和密码均为 `postgres`。
Spring 集成测试和余额基准测试默认使用 `jdbc:postgresql://127.0.0.1:5432/ai-accounting-test`；如需覆盖测试库连接，设置 `TEST_DB_URL`。

## 常用命令

```powershell
.\mvnw.cmd test
.\mvnw.cmd verify
.\mvnw.cmd spring-boot:run
```

健康检查：`GET http://127.0.0.1:8080/actuator/health`。

### 后端热更新

`pom.xml` 已加入 `spring-boot-devtools`。使用 `.\mvnw.cmd spring-boot:run` 或 `.\start-backend.ps1` 启动后：

```powershell
.\mvnw.cmd -q -DskipTests compile
```

编译完成后，devtools 会检测到 `target/classes` 变化并自动重启后端，不需要手动重启服务。生产打包时该依赖会被 Spring Boot Maven 插件默认排除，不会进入最终 jar。

### 快速启动或重启本地服务

```powershell
.\start-backend.ps1
.\start-frontend.ps1
```

脚本会只停止分别占用 `8080` 和 `5173` 端口的进程，再启动对应服务并等待可用。运行日志写入 `artifacts/dev-logs/`。

需要让 `8080` 后端连接测试数据库时，使用：

```powershell
.\start-backend-test.ps1
```

该脚本会让运行在 `8080` 的后端使用 `ai-accounting-test`，便于直接通过 HTTP 调用 `localhost:8080` 验证，不需要每次用 `mvn test` 另起一套测试上下文。

数据库集成测试直接连接配置的本机 PostgreSQL。

## 第一版 API

第一版先提供身份与账套协作闭环：

- `GET /v1/me`
- `GET/POST /v1/ledgers`
- `GET /v1/ledgers/{ledgerId}`
- `GET/POST /v1/ledgers/{ledgerId}/members`
- `PATCH/DELETE /v1/ledgers/{ledgerId}/members/{userId}`
- `GET /v1/ledgers/{ledgerId}/accounts`
- `GET /v1/ledgers/{ledgerId}/periods`
- `POST /v1/ledgers/{ledgerId}/periods/{periodId}:close`
- `POST /v1/ledgers/{ledgerId}/periods/{periodId}:reopen`
- `GET/POST /v1/ledgers/{ledgerId}/dimension-types`
- `GET/POST /v1/ledgers/{ledgerId}/dimension-types/{typeId}/values`
- `GET/PUT /v1/ledgers/{ledgerId}/opening-balances`
- `POST /v1/ledgers/{ledgerId}/opening-balances:import-csv`
- `POST /v1/ledgers/{ledgerId}/opening-balances:confirm`
- `GET /v1/admin/users`
- `DELETE /v1/admin/users/{userId}`
- `POST /v1/admin/users/{userId}:restore`
- `GET /v1/admin/ledgers`
- `DELETE /v1/admin/ledgers/{ledgerId}`
- `POST /v1/admin/ledgers/{ledgerId}:restore`

`spring-boot:run` 会自动启用本地联调身份；直接运行打包产物时仍需显式设置 `LOCAL_USER_HEADER_ENABLED=true`。本地请求使用 `X-User-Id: <UUID>`。
启用 OIDC 时使用 `oidc` profile，并设置 `OIDC_ISSUER_URI`，接口和 MCP 使用标准 Bearer JWT：

```powershell
$env:OIDC_ISSUER_URI="https://your-oidc-provider.example.com/realms/your-realm"
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=oidc
```

本地开发时 Codex 直接使用固定 dev Bearer Token：

```powershell
$env:ACCOUNTING_MCP_TOKEN="dev-admin-token"
codex mcp remove accounting
codex mcp add accounting --url http://127.0.0.1:8080/mcp --bearer-token-env-var ACCOUNTING_MCP_TOKEN
```

`local` profile 会自动创建 `super-agent`（`UserType.AGENT`），将其关联到全部账套，并在 MCP 请求未携带认证头时默认使用该用户。该用户拥有完整业务权限（权限检查等效 `OWNER`），但不能查找、添加、修改或删除账套成员。该开发便利功能不会在 OIDC profile 中启用。

`local` profile 同时将固定本地用户 `admin`（默认 ID `a2757c7a-fb97-4979-8f4f-abe3e401dacc`）设为平台管理员。`admin` 对全部活动账套等效 `OWNER`，并可在前端“平台管理”页面查看、删除和恢复全部用户与账套，以及把任意账套按 `OWNER`、`EDITOR`、`REVIEWER` 或 `VIEWER` 角色分配给同事。删除使用现有 `status`/`deleted_at` 软删除；`admin` 自身和 `super-agent` 不可删除。可通过 `PLATFORM_ADMIN_USER_ID` 和 `PLATFORM_ADMIN_USERNAME` 覆盖默认身份。

生产环境才切换为 OIDC Bearer JWT。

MCP 使用 `POST /mcp`。本地联调启用上述开关后，`X-User-Id` 同时建立 MCP 认证身份；Agent 可使用除账套创建、成员管理和审计日志查询外的 REST 能力，包括账套设置、科目、期间、期初、维度、凭证、附件、导入导出、备份恢复和报表查询。文件导出工具返回 `fileName`、`contentType`、`base64Content` 和 `byteLength`。

MCP 的 `update_voucher` 和 `delete_voucher` 与 REST 使用相同规则：仅 `CLOSED` 会计期间禁止操作；开放期间内，手工、自动生成和导入凭证均可修改或删除。已记账凭证的修改、删除会在同一事务中同步余额投影。

固定资产 Excel 可通过 `import_fixed_assets(ledgerId, fileName, base64Content)` 导入；文件格式、大小限制、类别编码和行校验规则与 REST 固定资产导入接口一致，存在任意行错误时整批不提交。

你提供的 `postgresql+asyncpg://...` 是 Python 异步客户端格式；本项目 Spring JDBC 对应使用 `DB_URL=jdbc:postgresql://localhost:5432/ai-accounting`。
## Agent 做账经验

MCP 提供 `create_accounting_experience`、`search_accounting_experiences`、`update_accounting_experience` 和 `archive_accounting_experience`，全部仅处理账套经验。账套经验随账套备份恢复；跨账套做账规则随插件 Skill 发布。只有 `UserType.AGENT` 且具有对应账套成员关系的调用方可以访问账套经验。
