# AI 财务系统

后端财务平台，技术基线见 `AI财务系统_开发设计文档_TDD_v0.1.md`。

## 当前本地环境决策

- 数据库使用本机 PostgreSQL，不依赖 Docker。
- 附件使用本地文件系统，根目录由 `STORAGE_ROOT` 配置。
- 当前不引入 MinIO、S3 或其他对象存储；后续如有部署需要再单独增加适配器。

## 启动前准备

1. 安装并启用 Java 21 和 PostgreSQL。
2. 创建数据库 `accounting`。
3. 按需设置 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`STORAGE_ROOT`。

默认连接：`jdbc:postgresql://127.0.0.1:5432/accounting`，用户名和密码均为 `postgres`。

## 常用命令

```powershell
.\mvnw.cmd test
.\mvnw.cmd verify
.\mvnw.cmd spring-boot:run
```

健康检查：`GET http://127.0.0.1:8080/actuator/health`。

本项目不使用 H2 或 Testcontainers；数据库集成测试直接连接配置的本地 PostgreSQL。
