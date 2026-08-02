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

默认连接：`jdbc:postgresql://127.0.0.1:5432/ai-accounting`，用户名和密码均为 `postgres`。

## 常用命令

```powershell
.\mvnw.cmd test
.\mvnw.cmd verify
.\mvnw.cmd spring-boot:run
```

健康检查：`GET http://127.0.0.1:8080/actuator/health`。

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

`spring-boot:run` 会自动启用本地联调身份；直接运行打包产物时仍需显式设置 `LOCAL_USER_HEADER_ENABLED=true`。本地请求使用 `X-User-Id: <UUID>`。
启用 OIDC 时设置 `spring.security.oauth2.resourceserver.jwt.issuer-uri`，接口使用标准 Bearer JWT。

MCP 使用 `POST /mcp`。本地联调启用上述开关后，`X-User-Id` 同时建立 MCP 认证身份；Agent 可查询账套和期间、创建或复用科目、创建/校验/过账凭证并生成报表，但不能审批、关账、重新开账、反记账或管理成员。

你提供的 `postgresql+asyncpg://...` 是 Python 异步客户端格式；本项目 Spring JDBC 对应使用 `DB_URL=jdbc:postgresql://localhost:5432/ai-accounting`。
