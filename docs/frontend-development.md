# 前端开发说明

前端位于 `frontend/`，技术栈为 React、Vite、TypeScript、Ant Design、React Router 和 TanStack Query。

## 本地启动

后端数据库配置只放在项目根目录 `.env`：

```powershell
Copy-Item .env.example .env
```

按本机 PostgreSQL 修改 `.env`，不要写入 Windows 系统环境变量。前端配置只保存公开配置：

```powershell
Copy-Item frontend/.env.example frontend/.env.local
```

开发时启动后端和前端：

```powershell
.\mvnw.cmd spring-boot:run
cd frontend
pnpm install
pnpm dev
```

Vite 将 `/v1` 和 `/actuator` 转发到 `http://127.0.0.1:8080`。本地登录使用前端 `.env.local` 的 `VITE_LOCAL_AUTH_ENABLED=true`，输入已有用户 UUID；生产构建只显示 OIDC 登录。

## 质量门禁

```powershell
pnpm lint
pnpm typecheck
pnpm test
pnpm test:e2e
```

`pnpm build` is excluded from the default checks because it is slow. Run it only after the user explicitly confirms.

真实后端业务 E2E 和 axe 扫描：

```powershell
pnpm test:e2e:axe
pnpm test:e2e:business
```

`test:e2e:business` 要求后端已启动，并在当前进程开启本地开发认证；例如：

```powershell
..\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--spring.datasource.password=<本机密码> --app.security.local-user-header-enabled=true"
```

期初余额 CSV 表头固定为：

```text
periodCode,accountCode,currency,dimensionKey,debitOriginal,creditOriginal,exchangeRate
```

借方和贷方金额按 CSV 原值保存，允许负数且不自动转移方向；同一行仍只能有一侧为非零值。

设置页支持期初余额逐行编辑、CSV 导入、借贷合计和最终确认；辅助核算页支持类型和值维护；凭证详情的历史版本区域支持恢复指定修订。

后端 OpenAPI 变化后，后端运行在 8080 时执行：

```powershell
pnpm api:generate
```

生成文件为 `frontend/src/api/generated.ts`。金额在表单中保持字符串，界面合计使用 `decimal.js`，最终校验以服务端为准。
