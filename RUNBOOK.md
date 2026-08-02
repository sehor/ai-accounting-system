# v0.1 运行手册

```powershell
$env:DB_URL="jdbc:postgresql://localhost:5432/ai-accounting"
$env:DB_USERNAME="postgres"
$env:DB_PASSWORD="pzr123"
$env:STORAGE_ROOT="./data/files"
.\mvnw.cmd spring-boot:run
```

`spring-boot:run` 自动启用仅供本地开发的 `local` profile。Flyway 启动时自动迁移 `ai-accounting`。OpenAPI JSON 为 `/v1/openapi.json`，MCP 默认使用 Streamable HTTP。

生产环境使用 OIDC JWT，关闭 `LOCAL_USER_HEADER_ENABLED`，并通过部署环境注入数据库密码、附件目录和 MCP 协议配置。

备份检查：

```powershell
pg_dump --format=custom --file=ai-accounting.backup --dbname="postgresql://postgres:pzr123@localhost:5432/ai-accounting"
pg_restore --list ai-accounting.backup
```
