# AI 财务系统

后端财务平台，技术基线见 `AI财务系统_开发设计文档_TDD_v0.1.md`。

## 当前本地环境决策

- 数据库使用本机 PostgreSQL，项目数据库为 `ai_accounting`。
- 附件使用本地文件系统，根目录由 `STORAGE_ROOT` 配置。
- 当前附件使用本地文件系统，暂不接入外部对象存储；后续如有部署需要再单独增加适配器。

## 启动前准备

1. 安装并启用 Java 21 和 PostgreSQL。
2. 创建数据库 `ai_accounting`。
3. 按需设置 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`STORAGE_ROOT`。

默认连接：`jdbc:postgresql://127.0.0.1:5432/ai_accounting`，用户名和密码均为 `postgres`。

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

本地联调可设置 `LOCAL_USER_HEADER_ENABLED=true`，然后在请求中传入 `X-User-Id: <UUID>`。
启用 OIDC 时设置 `spring.security.oauth2.resourceserver.jwt.issuer-uri`，接口使用标准 Bearer JWT。

你提供的 `postgresql+asyncpg://...` 是 Python 异步客户端格式；本项目 Spring JDBC 对应使用 `DB_URL=jdbc:postgresql://localhost:5432/ai_accounting`。
