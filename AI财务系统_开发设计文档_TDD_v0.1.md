# AI 财务系统开发设计文档（TDD）v0.1

> 文档状态：修订版  
> 更新日期：2026-07-27  
> 对应文档：`AI财务系统_产品需求文档_PRD_v0.1.md`

## 1. 设计目标

本文档是开发者和 Coding Agent 的实现指南。v0.1 建设一个可部署的后端财务平台，不包含前端。

必须满足：

- 用户与账套多对多，账套是数据隔离边界。
- 首期实现小企业会计准则，多账套、多币种。
- 凭证可修改、反记账、恢复历史版本和冲销，所有操作完整审计。
- 重复单据只警告，不阻断。
- 默认不启用审批，可按账套开启。
- REST API、OpenAPI 和受限 MCP 共用同一应用服务及权限规则。
- v0.1 只提供 Mock 单据提取，不接入真实 OCR 或大模型。

## 2. 架构决策

### 2.1 技术栈

| 类别 | 选择 |
|---|---|
| 语言 | Java 21 LTS |
| 应用框架 | Spring Boot 4.1 |
| Web | Spring MVC |
| 安全 | Spring Security OAuth2 Resource Server |
| 事务写模型 | Spring Data JPA |
| 报表查询 | Spring `JdbcClient` + 参数化 SQL |
| 数据库 | PostgreSQL 17 |
| 数据库迁移 | Flyway |
| 模块约束 | Spring Modulith |
| API 文档 | springdoc-openapi 3.0.3 |
| 文件存储 | 本地文件系统，根目录由 `STORAGE_ROOT` 配置 |
| 异步任务 | PostgreSQL 任务表 |
| 测试 | JUnit 5、Spring Security Test、本机 PostgreSQL |
| 构建 | Maven Wrapper |
| 监控 | Spring Boot Actuator、Micrometer |

### 2.2 为什么采用 Spring Boot

团队使用 Java/Spring，系统核心是强事务、权限、审计和结构化报表，Spring Boot 的事务、安全、数据库迁移和测试生态更适合长期维护。AI/OCR 位于适配器边界，v0.1 又只使用 Mock，因此没有为 Python 主后端保留 FastAPI 的必要。

### 2.3 保持简单的边界

v0.1 明确不做：

- 不拆微服务，使用单体应用和单个 PostgreSQL。
- 不使用 WebFlux；当前外部调用和并发规模不需要响应式编程。
- 不引入 Redis、RabbitMQ、Kafka；异步任务由 PostgreSQL 承担。
- 不使用 PostgreSQL RLS；在应用服务和仓储查询中强制 `ledger_id`。
- 不重复保存 `tenant_id`；权限由 `user_id + ledger_id` 成员关系决定。
- 不建设通用规则引擎、工作流引擎或插件系统。
- 不为单个实现创建接口；仅 Mock 提取器因后续必然替换外部服务而保留一个窄接口。
- 不使用 Lombok、MapStruct；简单映射显式编写。
- 没有真实事件消费者前不建设 outbox。

## 3. 总体架构

采用 Spring Modulith 模块化单体。HTTP 和 MCP 只是输入适配器，业务规则集中在应用服务中。

```text
REST / MCP
    │
    ▼
Security + Ledger Access Guard
    │
    ▼
Application Services
    │
    ├── JPA：事务写入
    ├── JdbcClient：账簿与报表查询
    ├── 本地文件系统：附件对象
    └── PostgreSQL：业务数据、审计、任务队列
```

模块：

| 模块 | 职责 |
|---|---|
| `identity` | JWT 身份映射、当前用户 |
| `ledger` | 账套、成员、准则初始化、科目、期间、维度、期初余额 |
| `voucher` | 凭证、校验、审批、记账、反记账、恢复、冲销 |
| `reporting` | 余额、账簿、报表公式、受限查询 DSL |
| `documents` | 附件、重复警告、提取结果、异步任务 |
| `agent` | MCP 工具与 Agent 权限边界 |
| `audit` | 修订快照、操作日志、幂等 |
| `shared` | 错误模型、金额类型、时间与基础配置 |

每个模块根包只暴露必要的应用服务和 DTO，实现放在 `internal` 子包。模块之间通过公开应用服务调用，不直接访问对方的 repository。

## 4. 工程结构

```text
.
├── pom.xml
├── mvnw
├── mvnw.cmd
├── src
│   ├── main
│   │   ├── java/com/example/accounting
│   │   │   ├── AccountingApplication.java
│   │   │   ├── identity/
│   │   │   ├── ledger/
│   │   │   ├── voucher/
│   │   │   ├── reporting/
│   │   │   ├── documents/
│   │   │   ├── agent/
│   │   │   ├── audit/
│   │   │   └── shared/
│   │   └── resources
│   │       ├── application.yml
│   │       ├── db/migration/
│   │       └── standards/small-business/v1/
│   └── test/java/com/example/accounting/
└── README.md
```

建议依赖仅包含所需 Starter：

- `spring-boot-starter-web`
- `spring-boot-starter-validation`
- `spring-boot-starter-security`
- `spring-boot-starter-oauth2-resource-server`
- `spring-boot-starter-data-jpa`
- `spring-boot-starter-jdbc`
- `spring-boot-starter-actuator`
- `spring-modulith-starter-core`
- PostgreSQL Driver、Flyway、springdoc
- 测试直接连接配置的本机 PostgreSQL，附件使用本地文件系统

MCP 阶段再加入 Spring AI MCP Server 依赖；此前不提前引入 Spring AI。

## 5. 数据模型

### 5.1 通用字段

账套业务表统一包含：

```text
id UUID
ledger_id UUID
created_at TIMESTAMPTZ
created_by UUID
updated_at TIMESTAMPTZ
updated_by UUID
version BIGINT
deleted_at TIMESTAMPTZ NULL
```

例外：

- `app_user` 不属于单个账套。
- 系统内置的准则模板不属于单个账套。
- `ledger` 自身没有 `ledger_id`。

UUID 由应用生成。所有时间存 UTC，接口使用 ISO 8601。

### 5.2 身份与账套

#### `app_user`

- `id`
- `issuer`
- `subject`
- `display_name`
- `email`
- `status`
- 唯一约束：`(issuer, subject)`

首次收到合法 JWT 时可按 `(issuer, subject)` 创建本地用户映射。

#### `ledger`

- `id`
- `name`
- `accounting_standard_code`
- `accounting_standard_version`
- `base_currency`
- `start_date`
- `approval_enabled`
- `status`
- 审计与版本字段

#### `ledger_membership`

- `id`
- `ledger_id`
- `user_id`
- `role`
- `status`
- 审计字段
- 唯一约束：`(ledger_id, user_id)`
- 索引：`(user_id, status)`、`(ledger_id, status)`

### 5.3 基础资料

#### `account`

- `ledger_id`
- `code`
- `name`
- `category`
- `normal_side`
- `parent_id`
- `requires_dimensions JSONB`
- `enabled`
- 唯一约束：`(ledger_id, code)`

#### `accounting_period`

- `ledger_id`
- `year`
- `month`
- `start_date`
- `end_date`
- `status`
- 唯一约束：`(ledger_id, year, month)`

#### `dimension_type` / `dimension_value`

账套可定义客户、供应商、部门、项目等辅助核算类型及其值。值编码在类型内唯一。

#### `opening_balance`

- `ledger_id`
- `period_id`
- `account_id`
- `currency`
- `dimension_key`
- `debit_original`
- `credit_original`
- `exchange_rate`
- `debit_base`
- `credit_base`
- `confirmed`

`dimension_key` 使用排序后的维度 ID 生成稳定哈希，用于唯一约束和汇总。

### 5.4 凭证

#### `voucher`

- `ledger_id`
- `period_id`
- `voucher_date`
- `voucher_type`
- `voucher_number`
- `summary`
- `status`
- `current_revision`
- `source_type`
- `source_id`
- `approval_required`
- `reversal_of_id`
- `reversed_by_id`
- `posted_at` / `posted_by`
- `version`
- 唯一约束：`(ledger_id, period_id, voucher_type, voucher_number)`，软删除记录除外

#### `voucher_line`

- `ledger_id`
- `voucher_id`
- `line_no`
- `account_id`
- `side`
- `currency`
- `original_amount NUMERIC(19,4)`
- `exchange_rate NUMERIC(19,8)`
- `base_amount NUMERIC(19,2)`
- `summary`
- 唯一约束：`(voucher_id, line_no)`

金额采用“借贷方向 + 正金额”，不同时保存借方金额和贷方金额。

#### `voucher_line_dimension`

- `ledger_id`
- `voucher_line_id`
- `dimension_type_id`
- `dimension_value_id`
- 唯一约束：`(voucher_line_id, dimension_type_id)`

#### `voucher_approval`

- `ledger_id`
- `voucher_id`
- `action`
- `comment`
- `actor_id`
- `created_at`

### 5.5 文档与任务

#### `document`

- `ledger_id`
- `object_key`
- `file_name`
- `content_type`
- `size_bytes`
- `sha256`
- `status`
- `duplicate_warning JSONB`

索引：`(ledger_id, sha256)`。匹配只生成警告。

#### `document_extraction`

- `ledger_id`
- `document_id`
- `provider`
- `provider_version`
- `structured_result JSONB`
- `source_references JSONB`
- `input_hash`
- `output_hash`
- `status`

不保存模型原始提示词、附件正文副本或原始模型输出。

#### `background_job`

- `ledger_id`
- `job_type`
- `aggregate_type`
- `aggregate_id`
- `payload JSONB`
- `status`
- `attempts`
- `next_run_at`
- `locked_at`
- `locked_by`
- `last_error_code`
- `last_error_message`

索引：`(status, next_run_at)`。

### 5.6 审计与幂等

#### `audit_revision`

- `ledger_id`
- `aggregate_type`
- `aggregate_id`
- `revision`
- `action`
- `actor_type`
- `actor_id`
- `reason`
- `before_data JSONB`
- `after_data JSONB`
- `trace_id`
- `created_at`

该表只追加。业务接口不得提供更新和删除能力。

#### `idempotency_record`

- `ledger_id`
- `actor_id`
- `operation`
- `idempotency_key`
- `request_hash`
- `response_status`
- `response_body JSONB`
- `created_at`
- 唯一约束：`(ledger_id, actor_id, operation, idempotency_key)`

相同键但请求哈希不同，返回 `409 IDEMPOTENCY_KEY_REUSED`。

## 6. 金额与会计规则

- Java 只使用 `BigDecimal`，禁止 `double` 和 `float`。
- 原币金额：4 位小数。
- 汇率：8 位小数。
- 本位币金额：2 位小数。
- 换算：`originalAmount × exchangeRate`，使用 `RoundingMode.HALF_UP`。
- 本位币行汇率固定为 `1`。
- 凭证按本位币借贷合计平衡。
- 零金额行不允许保存。
- 报表只纳入 `POSTED` 凭证及已确认期初余额。

准则包使用版本化 YAML 或 JSON，包含科目模板和报表公式。创建账套时复制成账套数据快照；修改系统模板不影响已有账套。

v0.1 只实现小企业会计准则 v1，不实现可编程规则语言。

## 7. 安全与账套隔离

### 7.1 认证

Spring Security 作为 OAuth2 Resource Server 验证 JWT：

- 验证签名、`iss`、`exp` 和配置的 `aud`。
- 以 `(iss, sub)` 映射本地用户。
- 不信任客户端传入的用户 ID 或角色。

### 7.2 授权

账套内 Controller 必须先调用统一的 `LedgerAccessService.requireRole(ledgerId, roles...)`。Repository 的账套业务查询方法必须显式包含 `ledgerId`；禁止使用只按资源 ID 查询的公共方法。

角色能力：

| 操作 | OWNER | EDITOR | REVIEWER | VIEWER | AGENT |
|---|:---:|:---:|:---:|:---:|:---:|
| 查询账套数据 | ✓ | ✓ | ✓ | ✓ | 限定工具 |
| 编辑基础资料 | ✓ | ✓ |  |  |  |
| 创建/修改凭证 | ✓ | ✓ |  |  | 仅草稿工具 |
| 审批/退回 | ✓ |  | ✓ |  |  |
| 记账/反记账 | ✓ | ✓ |  |  |  |
| 关账/重新开账 | ✓ |  |  |  |  |
| 管理成员 | ✓ |  |  |  |  |

必须编写跨账套负向测试：

- 用账套 A 的成员访问账套 B 资源。
- 在账套 A 的凭证中引用账套 B 的科目、期间、维度或附件。
- 通过猜测 UUID 读取、修改或删除其他账套对象。

## 8. API 规范

### 8.1 通用约定

- API 前缀：`/v1`
- 账套资源：`/v1/ledgers/{ledgerId}/...`
- JSON 字段使用 `camelCase`。
- UUID 使用字符串；金额和汇率使用十进制字符串。
- 列表采用 `page`、`size`，默认 20，最大 200。
- 写命令支持 `Idempotency-Key`。
- 更新请求传 `version`，JPA `@Version` 检测并发修改。
- 服务端接收或生成 `X-Trace-Id`，并在响应中返回。

错误使用 RFC 9457 Problem Details，扩展字段：

```json
{
  "type": "https://example.invalid/problems/voucher-unbalanced",
  "title": "Voucher validation failed",
  "status": 422,
  "code": "VOUCHER_UNBALANCED",
  "detail": "Debit and credit totals differ",
  "traceId": "…",
  "retryable": false,
  "fieldErrors": []
}
```

状态码：

- `400`：格式错误。
- `401`：未认证。
- `403`：无账套权限。
- `404`：当前账套内不存在。
- `409`：版本冲突、幂等键冲突或非法状态转换。
- `422`：会计规则校验失败。

### 8.2 主要接口

身份与账套：

```text
GET    /v1/me
GET    /v1/ledgers
POST   /v1/ledgers
GET    /v1/ledgers/{ledgerId}
PATCH  /v1/ledgers/{ledgerId}
GET    /v1/ledgers/{ledgerId}/members
POST   /v1/ledgers/{ledgerId}/members
PATCH  /v1/ledgers/{ledgerId}/members/{userId}
DELETE /v1/ledgers/{ledgerId}/members/{userId}
```

基础资料：

```text
GET/POST       /v1/ledgers/{ledgerId}/accounts
GET/PATCH      /v1/ledgers/{ledgerId}/accounts/{accountId}
GET            /v1/ledgers/{ledgerId}/periods
POST           /v1/ledgers/{ledgerId}/periods/{periodId}:close
POST           /v1/ledgers/{ledgerId}/periods/{periodId}:reopen
GET/POST       /v1/ledgers/{ledgerId}/dimension-types
GET/POST       /v1/ledgers/{ledgerId}/dimension-types/{typeId}/values
GET/PUT        /v1/ledgers/{ledgerId}/opening-balances
POST           /v1/ledgers/{ledgerId}/opening-balances:import-csv
POST           /v1/ledgers/{ledgerId}/opening-balances:confirm
```

凭证：

```text
GET/POST       /v1/ledgers/{ledgerId}/vouchers
GET/PATCH      /v1/ledgers/{ledgerId}/vouchers/{voucherId}
DELETE         /v1/ledgers/{ledgerId}/vouchers/{voucherId}
POST           /v1/ledgers/{ledgerId}/vouchers/{voucherId}:restore-deleted
POST           /v1/ledgers/{ledgerId}/vouchers/{voucherId}:validate
POST           /v1/ledgers/{ledgerId}/vouchers/{voucherId}:submit
POST           /v1/ledgers/{ledgerId}/vouchers/{voucherId}:approve
POST           /v1/ledgers/{ledgerId}/vouchers/{voucherId}:reject
POST           /v1/ledgers/{ledgerId}/vouchers/{voucherId}:post
POST           /v1/ledgers/{ledgerId}/vouchers/{voucherId}:unpost
POST           /v1/ledgers/{ledgerId}/vouchers/{voucherId}:reverse
GET            /v1/ledgers/{ledgerId}/vouchers/{voucherId}/revisions
POST           /v1/ledgers/{ledgerId}/vouchers/{voucherId}/revisions/{revision}:restore
```

文档与任务：

```text
POST           /v1/ledgers/{ledgerId}/documents
GET            /v1/ledgers/{ledgerId}/documents/{documentId}
POST           /v1/ledgers/{ledgerId}/documents/{documentId}:extract
GET            /v1/ledgers/{ledgerId}/documents/{documentId}/extractions
POST           /v1/ledgers/{ledgerId}/documents/{documentId}:create-voucher-draft
GET            /v1/ledgers/{ledgerId}/jobs/{jobId}
```

账簿、报表与审计：

```text
GET  /v1/ledgers/{ledgerId}/reports/trial-balance
GET  /v1/ledgers/{ledgerId}/reports/general-ledger
GET  /v1/ledgers/{ledgerId}/reports/sub-ledger
GET  /v1/ledgers/{ledgerId}/reports/balance-sheet
GET  /v1/ledgers/{ledgerId}/reports/income-statement
POST /v1/ledgers/{ledgerId}/finance-query
GET  /v1/ledgers/{ledgerId}/audit
```

## 9. 凭证状态、事务与修订

### 9.1 状态机

未开启审批：

```text
DRAFT → VALIDATED → POSTED
```

开启审批：

```text
DRAFT → VALIDATED → SUBMITTED → APPROVED → POSTED
```

补充转换：

- `SUBMITTED → DRAFT`：退回。
- `POSTED → DRAFT`：反记账。
- 恢复历史修订：创建新修订并进入 `DRAFT`。
- 冲销：创建引用原凭证的反向草稿；冲销凭证记账后，原凭证展示为 `REVERSED`。

状态转换集中在 `VoucherApplicationService`，Controller 和 MCP 不自行改变状态。

### 9.2 事务

下列操作各使用一个数据库事务：

- 创建或修改凭证及其行和维度。
- 校验并保存校验结果。
- 审批或退回。
- 记账及新增审计修订。
- 反记账及新增审计修订。
- 恢复历史版本。
- 创建冲销凭证。

记账不预生成冗余总账表。v0.1 直接以已记账凭证行为事实表查询；出现实际性能瓶颈后再增加汇总表。

### 9.3 修改与恢复

- 每次业务修改递增 `voucher.current_revision`。
- 修改前后快照写入 `audit_revision`。
- 反记账保留原修订并创建新的草稿修订。
- 恢复修订时复制目标快照，生成新的最大修订号，不回退计数器。
- 软删除只修改业务记录状态，审计记录保持可查。
- 反记账、恢复、关账后重开等高影响操作要求提供 `reason`。

## 10. 报表与查询 DSL

报表用 `JdbcClient` 执行版本控制的参数化 SQL。所有 SQL 都必须以 `ledger_id = :ledgerId` 为第一层范围条件。

受限查询 DSL：

```json
{
  "metric": "BALANCE",
  "periodFrom": "2026-01",
  "periodTo": "2026-06",
  "groupBy": ["ACCOUNT", "MONTH"],
  "filters": {
    "accountCodes": ["1001", "1122"],
    "currency": "CNY"
  }
}
```

允许指标：

- `DEBIT`
- `CREDIT`
- `NET`
- `BALANCE`

允许分组：

- `ACCOUNT`
- `MONTH`
- `CURRENCY`
- `DIMENSION`

实现使用枚举白名单选择预定义 SQL 片段，所有值仍通过绑定参数传入。拒绝未知字段、未知枚举、任意表达式和 SQL 文本。

资产负债表和利润表按账套的报表公式快照聚合科目余额。公式只支持科目范围、借贷方向和加减运算，不执行脚本。

## 11. 附件与异步任务

### 11.1 上传

- 允许 PDF、JPEG、PNG。
- 最大 20 MB，服务端和反向代理同时限制。
- 上传时流式计算 SHA-256，禁止一次性载入内存。
- 对象键使用不可猜测 UUID，不使用原始文件名作为路径。
- 文件写入本地文件系统，数据库保存对象键与元数据。
- 替换文件创建新对象，不原位覆盖。
- 同账套哈希或业务字段疑似重复时返回 `warnings`，仍创建记录。

### 11.2 PostgreSQL 任务队列

Worker 在同一应用中定时领取任务：

```sql
SELECT id
FROM background_job
WHERE status IN ('QUEUED', 'RETRYING')
  AND next_run_at <= now()
ORDER BY next_run_at, created_at
FOR UPDATE SKIP LOCKED
LIMIT :batchSize
```

领取后快速提交锁事务，再执行任务。状态：

`QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED`、`RETRYING`、`NEEDS_HUMAN`。

失败最多重试 3 次，间隔 1、5、30 分钟。业务校验失败不自动重试；临时网络或存储错误可重试。

### 11.3 Mock 提取

定义最小接口：

```java
public interface DocumentExtractor {
    ExtractionResult extract(DocumentRef document);
}
```

v0.1 只有 `MockDocumentExtractor`，按固定测试样本或文件元数据返回结构化结果。真实服务接入时新增实现，不改变文档、任务和凭证草稿流程。

## 12. MCP 设计

MCP 使用与 REST 相同的 JWT 身份、`LedgerAccessService` 和应用服务。

固定工具：

- `list_ledgers`
- `finance_query`
- `get_voucher`
- `create_voucher_draft`
- `validate_voucher`
- `upload_document`
- `extract_document`
- `get_job_status`
- `create_voucher_draft_from_document`

约束：

- 每次调用必须确定当前用户和账套。
- 工具参数使用明确 JSON Schema，不接受任意 SQL。
- MCP 不暴露记账、反记账、审批、关账、重新开账或成员管理。
- 调用、参数哈希、结果哈希、操作者和 `traceId` 写入审计。

## 13. 幂等、并发与错误处理

- 创建凭证、上传文档、任务提交、记账、反记账、恢复和冲销支持幂等键。
- 幂等记录和业务写入在同一事务内完成。
- 可变聚合根使用 JPA `@Version`。
- 并发版本冲突返回 `409 RESOURCE_VERSION_CONFLICT`。
- 唯一约束冲突转换为稳定业务错误，不暴露 SQL 或表名。
- 所有外部错误先映射为内部错误码；日志保留根因，响应不返回堆栈。

## 14. 可观测性与保留

### 14.1 可观测性

- Actuator 提供 health、readiness、liveness 和 metrics。
- Micrometer 记录请求耗时、错误数、数据库连接、任务状态、文件存储调用和凭证命令耗时。
- 结构化日志包含 `traceId`、`userId`、`ledgerId` 和业务对象 ID。
- 日志不得包含 JWT、附件正文、完整财务报表或原始模型输入输出。

### 14.2 保留

- 活跃账套业务数据、附件和审计不自动清理。
- 停用或删除账套满 10 年后，才允许通过受控清理任务物理清除。
- 清理前导出，清理操作本身记录批次、范围、操作者和结果。
- v0.1 不实现自动清理调度，只保留明确的保留口径。

## 15. 测试策略

不使用 H2。数据库相关测试直接连接配置的本机 PostgreSQL，附件集成测试使用本地文件系统。

最小测试层次：

1. 领域单元测试：金额换算、借贷平衡、状态转换、恢复修订、冲销。
2. Repository 集成测试：约束、软删除、账套范围、任务领取并发。
3. API 集成测试：JWT、角色权限、Problem Details、幂等和乐观锁。
4. 报表金样测试：固定凭证集对应固定余额表和法定报表结果。
5. 跨账套安全测试：读取、写入、关联引用全部拒绝。
6. 文件/任务测试：上传、哈希、重复警告、重试和 Mock 提取。
7. Modulith 校验：禁止越过模块公开边界。

每个业务功能至少包含正常路径、非法状态、权限不足和跨账套四类测试；纯查询功能可省略不适用的状态测试。

## 16. 具体开发计划

以下阶段按顺序执行。每个阶段通过验收后再进入下一阶段。

### 阶段 0：工程基础

任务：

1. 创建 Java 21、Spring Boot 4.1 Maven Wrapper 工程。
2. 加入 Web、Validation、Security、JPA、JDBC、Actuator、Flyway、PostgreSQL 和测试依赖。
3. 建立模块包、Modulith 边界测试和统一编码格式。
4. 配置本机 PostgreSQL、Flyway 和本地文件存储。
5. 实现 `ProblemDetail` 异常映射、`traceId` 过滤器和基础审计上下文。

验收：

- 应用可连接本地 PostgreSQL 启动。
- Flyway 空基线成功。
- 健康检查可用。
- `verify` 在配置的本机 PostgreSQL 上通过。

### 阶段 1：身份、账套与隔离

任务：

1. 创建 `app_user`、`ledger`、`ledger_membership` 迁移。
2. 配置 JWT 验证和当前用户映射。
3. 实现账套创建、列表和成员管理。
4. 实现 `LedgerAccessService` 和角色检查。
5. 为 Repository 建立必须携带 `ledgerId` 的查询约定。
6. 完成跨账套负向测试。

验收：

- 用户与账套多对多可用。
- 创建人自动成为 OWNER。
- 无成员关系或角色不足时返回正确错误。
- 无法通过其他账套的 UUID 访问资源。

### 阶段 2：准则包与基础资料

任务：

1. 编写小企业会计准则 v1 科目模板和报表公式。
2. 创建科目、期间、维度、期初余额迁移。
3. 创建账套时复制准则快照并生成月度期间。
4. 实现科目、期间、维度 API。
5. 实现期初余额手工维护、CSV 导入、校验与确认。
6. 实现关账和重新开账审计。

验收：

- 新账套自动获得正确科目与报表公式。
- CSV 错误可定位到行和字段。
- 期初余额借贷校验正确。
- 关闭期间禁止会计写操作，OWNER 可带原因重新打开。

### 阶段 3：凭证核心

任务：

1. 创建凭证、行、维度、审批、审计修订和幂等表。
2. 实现草稿 CRUD 和乐观锁。
3. 实现金额换算及科目、期间、维度、借贷平衡校验。
4. 实现账套审批开关及提交、审批、退回。
5. 实现记账与反记账事务。
6. 实现历史修订列表与恢复。
7. 实现软删除、恢复删除和冲销。

验收：

- 人民币和外币凭证均能正确校验。
- 审批关闭和开启两条路径都可完成记账。
- 已记账凭证能反记账、修改、再次记账。
- 恢复历史版本生成新修订，不覆盖历史。
- 冲销后账簿净影响正确。
- 重复幂等请求只产生一个结果。

### 阶段 4：账簿与报表

任务：

1. 用 `JdbcClient` 实现科目余额表、总账和明细账。
2. 实现资产负债表和利润表公式计算。
3. 实现查询 DSL 的枚举白名单、参数校验和参数化 SQL。
4. 添加报表金样数据和结果断言。
5. 为常用过滤条件增加必要索引并记录执行计划。

验收：

- 只统计已记账凭证和已确认期初余额。
- 反记账、重新记账和冲销后结果立即正确。
- 所有查询都绑定 `ledgerId`。
- DSL 无法传入任意 SQL 或未知字段。

### 阶段 5：附件与异步任务

任务：

1. 创建文档、提取结果和任务表。
2. 实现本地文件存储流式上传、类型/大小校验和 SHA-256。
3. 实现重复警告，不设置阻断分支。
4. 实现 `SKIP LOCKED` 任务领取、状态转换和重试。
5. 实现 Mock 提取器和结构化结果保存。
6. 实现从提取结果创建凭证草稿。

验收：

- 20 MB 限制和文件类型校验有效。
- 重复文件仍能进入后续流程并记录用户选择。
- 两个 Worker 不会同时执行同一任务。
- 失败按 1、5、30 分钟重试，最多 3 次。
- 不保存原始模型输入输出。

### 阶段 6：MCP

任务：

1. 加入 Spring AI MCP Server 所需最小依赖。
2. 将白名单工具映射到已有应用服务。
3. 为每个工具定义严格 JSON Schema、角色和审计。
4. 添加 Agent 禁止高风险操作的安全测试。

验收：

- MCP 与 REST 返回一致的业务结果和错误码。
- Agent 只能访问其有权限的账套。
- 不存在记账、反记账、审批、关账或成员管理工具。

### 阶段 7：交付加固

任务：

1. 补齐 OpenAPI 描述、示例和稳定错误码清单。
2. 完成全部隔离、权限、幂等、并发和恢复测试。
3. 验证数据库备份恢复、本地文件备份和 Flyway 升级。
4. 运行常用报表与凭证操作的基准测试。
5. 完成自托管配置、环境变量清单和启动检查。

验收：

- 全量 `verify` 通过。
- OpenAPI 可生成并覆盖所有公开 REST 接口。
- 新环境可按说明完成数据库迁移并启动。
- 备份恢复后凭证、附件引用和审计记录一致。
- 性能达到 PRD 目标，或记录可复现的偏差与处理结论。

## 17. 开发执行约定

常用命令：

```powershell
.\mvnw.cmd spring-boot:run
.\mvnw.cmd test
.\mvnw.cmd verify
```

Coding Agent 每次实现一个可独立验收的任务：

1. 先确认 PRD、本文档和现有迁移。
2. 先写最小失败测试，再实现业务逻辑。
3. 数据结构变化必须新增 Flyway 迁移，不修改已发布迁移。
4. 公开 API 变化同步更新 OpenAPI 测试。
5. 完成后运行相关测试和 `verify`，说明未运行的检查。
6. 不顺带添加未列入当前阶段的框架或抽象。

## 18. 完成定义

一个任务只有同时满足以下条件才算完成：

- 行为符合 PRD 和本文档。
- 权限和账套隔离在应用服务与查询中生效。
- 数据库约束、事务、审计、幂等按风险落实。
- 正常路径和关键失败路径有自动化测试。
- Flyway、OpenAPI 和配置随实现同步。
- 不记录密钥、JWT、附件内容或原始模型输入输出。
- `.\mvnw.cmd verify` 通过。

## 19. 实现参考

- [Spring Boot 系统要求](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring Framework 事务管理](https://docs.spring.io/spring-framework/reference/data-access/transaction.html)
- [Spring Security JWT Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
- [Spring Modulith](https://docs.spring.io/spring-modulith/reference/1.4/fundamentals.html)
- [Spring JdbcClient](https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html)
- [Spring Framework Problem Details](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html)
- [Spring AI MCP](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html)
- [springdoc-openapi](https://springdoc.org/v4/index.html)
