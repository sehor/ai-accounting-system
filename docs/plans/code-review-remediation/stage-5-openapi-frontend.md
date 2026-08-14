# 阶段 5：OpenAPI 合同与前端性能

前置：阶段 1-4 的所有公开后端 API 已稳定。  
这是最后阶段，允许运行已获批准的全量 `pnpm typecheck` 和 `pnpm build`。

## 目标

让生成的 OpenAPI 类型成为唯一 HTTP 边界，并完成审计分页、路由拆包和凭证编辑器请求合并。

## 实施任务

### 1. 修复 OpenAPI schema

- 为公开嵌套 DTO 指定唯一 schema 名，重点处理 `Line/Create/Page/Statement/Dimension` 冲突。
- Voucher 请求行和响应行必须是不同 schema；响应行包含 `id`、`lineNo`、`baseAmount`。
- required/nullable 与实际 JSON 一致。
- BigDecimal 等十进制字段的 Jackson 输出和 OpenAPI 都为字符串。
- 增加真实 OpenAPI 回归测试，不再用直接 Java service 测试冒充 HTTP 合同测试。

### 2. 类型化客户端

- 使用 `generated.ts` 的 `paths/components`；可引入 `openapi-fetch`。
- 禁止页面继续使用可任意声明响应类型的 `apiFetch<T>`。
- `types.ts` 只保留 UI view-model；transport → view-model 显式处理金额和 nullable 字段。
- 迁移顺序：
  1. voucher；
  2. ledger/account/period/dimension；
  3. reporting/books；
  4. fixed assets；
  5. documents/audit/admin/backup。
- 每批先修后端 schema、再生成、再迁移消费者；不同 worker 不并发编辑共享 client。
- OpenAPI URL 可配置，本地生成/验证使用 `http://127.0.0.1:18080/v1/openapi.json`。

验收：业务页面不再导入手写 transport DTO；后端字段变化会在生成或 TypeScript 阶段失败。

### 3. 审计页面分页

- 消费阶段 4 的 `{items,nextCursor,hasMore}`。
- 使用受控分页/加载更多，不再一次拉取全账套审计。
- 筛选 aggregateType/aggregateId 时重置 cursor 和缓存。

### 4. 路由拆包

- `App.tsx` 页面改用 `React.lazy`，配置统一 `Suspense` fallback。
- 保证路由错误、加载中和权限状态行为不变。

验收：每个页面独立 chunk，初始 gzip 比阶段 0 基线下降 >= 30%，LCP 不回退。

### 5. 凭证编辑器请求合并

- 增加批量维度值 API，一次接收所需 dimension type IDs，返回按类型分组的数据。
- 编辑器从 O(T) 独立请求改为一次批量请求。
- React Query 缓存键包含 ledgerId 和排序后的完整类型集合。

验收：维度请求数降为 1，编辑器可交互时间比基线下降 >= 25%。

## 验证

- 定向 Vitest：Voucher、Audit、Fixed Asset、Period Closing、Reports、App 路由。
- 执行：
  - `rtk pnpm typecheck`
  - `rtk pnpm build`
- 检查生成文件 diff，确保运行时 OpenAPI 与 `generated.ts` 一致。
- HTTP 只使用现有 18080，不启动服务。
- 运行 `git diff --check` 和 `rtk graphify update .`。

## 编排与最终交接

- `mechanic/openapi-schema-names` 负责明确的 DTO 注解和生成配置。
- `worker/typed-api-client` 独占 generated client 和公共 client 文件。
- 页面迁移按领域串行或在不共享文件时并行。
- `worker/frontend-performance` 在类型迁移稳定后处理 lazy route、audit 和 editor。
- 最终 reviewer 检查：运行时 JSON、OpenAPI、生成类型、页面调用四者一致。

最终交接必须汇总全部阶段验收、API 破坏性变化、第三方迁移说明、未达成性能门槛和回滚开关。

