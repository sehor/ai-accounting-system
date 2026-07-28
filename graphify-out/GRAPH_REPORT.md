# Graph Report - ai-accouting-system  (2026-07-28)

## Corpus Check
- 145 files · ~37,745 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1146 nodes · 3800 edges · 76 communities (60 shown, 16 thin omitted)
- Extraction: 97% EXTRACTED · 3% INFERRED · 0% AMBIGUOUS · INFERRED: 132 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `b1ca2710`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- [[_COMMUNITY_Community 0|Community 0]]
- [[_COMMUNITY_Community 1|Community 1]]
- [[_COMMUNITY_Community 2|Community 2]]
- [[_COMMUNITY_Community 3|Community 3]]
- [[_COMMUNITY_Community 4|Community 4]]
- [[_COMMUNITY_Community 5|Community 5]]
- [[_COMMUNITY_Community 6|Community 6]]
- [[_COMMUNITY_Community 7|Community 7]]
- [[_COMMUNITY_Community 8|Community 8]]
- [[_COMMUNITY_Community 9|Community 9]]
- [[_COMMUNITY_Community 10|Community 10]]
- [[_COMMUNITY_Community 11|Community 11]]
- [[_COMMUNITY_Community 15|Community 15]]
- [[_COMMUNITY_Community 21|Community 21]]
- [[_COMMUNITY_Community 25|Community 25]]
- [[_COMMUNITY_Community 26|Community 26]]
- [[_COMMUNITY_Community 27|Community 27]]
- [[_COMMUNITY_Community 28|Community 28]]
- [[_COMMUNITY_Community 29|Community 29]]
- [[_COMMUNITY_Community 30|Community 30]]
- [[_COMMUNITY_Community 31|Community 31]]
- [[_COMMUNITY_Community 32|Community 32]]
- [[_COMMUNITY_Community 33|Community 33]]
- [[_COMMUNITY_Community 34|Community 34]]
- [[_COMMUNITY_Community 35|Community 35]]
- [[_COMMUNITY_Community 36|Community 36]]
- [[_COMMUNITY_Community 37|Community 37]]
- [[_COMMUNITY_Community 38|Community 38]]
- [[_COMMUNITY_Community 39|Community 39]]
- [[_COMMUNITY_Community 40|Community 40]]
- [[_COMMUNITY_Community 41|Community 41]]
- [[_COMMUNITY_Community 42|Community 42]]
- [[_COMMUNITY_Community 43|Community 43]]
- [[_COMMUNITY_Community 44|Community 44]]
- [[_COMMUNITY_Community 45|Community 45]]
- [[_COMMUNITY_Community 46|Community 46]]
- [[_COMMUNITY_Community 47|Community 47]]
- [[_COMMUNITY_Community 48|Community 48]]
- [[_COMMUNITY_Community 49|Community 49]]
- [[_COMMUNITY_Community 50|Community 50]]
- [[_COMMUNITY_Community 51|Community 51]]
- [[_COMMUNITY_Community 52|Community 52]]
- [[_COMMUNITY_Community 53|Community 53]]
- [[_COMMUNITY_Community 54|Community 54]]
- [[_COMMUNITY_Community 55|Community 55]]
- [[_COMMUNITY_Community 61|Community 61]]
- [[_COMMUNITY_Community 62|Community 62]]
- [[_COMMUNITY_Community 63|Community 63]]
- [[_COMMUNITY_Community 64|Community 64]]
- [[_COMMUNITY_Community 65|Community 65]]
- [[_COMMUNITY_Community 66|Community 66]]
- [[_COMMUNITY_Community 67|Community 67]]
- [[_COMMUNITY_Community 68|Community 68]]
- [[_COMMUNITY_Community 69|Community 69]]
- [[_COMMUNITY_Community 70|Community 70]]
- [[_COMMUNITY_Community 71|Community 71]]
- [[_COMMUNITY_Community 72|Community 72]]
- [[_COMMUNITY_Community 73|Community 73]]
- [[_COMMUNITY_Community 74|Community 74]]
- [[_COMMUNITY_Community 75|Community 75]]

## God Nodes (most connected - your core abstractions)
1. `test` - 59 edges
2. `Voucher` - 52 edges
3. `ApiProblemException` - 48 edges
4. `JdbcLedgerRepository` - 45 edges
5. `DefaultLedgerService` - 41 edges
6. `LedgerRepository` - 41 edges
7. `DefaultVoucherService` - 39 edges
8. `CurrentUserResolver` - 34 edges
9. `LedgerService` - 34 edges
10. `JdbcVoucherRepository` - 33 edges

## Surprising Connections (you probably didn't know these)
- `RequireAuth()` --calls--> `useAuth()`  [EXTRACTED]
  frontend/src/app/App.tsx → frontend/src/auth/AuthProvider.tsx
- `JdbcAgentToolAuditRepository` --implements--> `AgentToolAuditRepository`  [EXTRACTED]
  src/main/java/com/example/accounting/agent/internal/persistence/JdbcAgentToolAuditRepository.java → src/main/java/com/example/accounting/agent/internal/port/AgentToolAuditRepository.java
- `DefaultLedgerService` --implements--> `LedgerService`  [EXTRACTED]
  src/main/java/com/example/accounting/ledger/internal/application/DefaultLedgerService.java → src/main/java/com/example/accounting/ledger/LedgerService.java
- `JdbcLedgerRepository` --implements--> `LedgerRepository`  [EXTRACTED]
  src/main/java/com/example/accounting/ledger/internal/persistence/JdbcLedgerRepository.java → src/main/java/com/example/accounting/ledger/internal/port/LedgerRepository.java
- `JdbcVoucherRepository` --implements--> `VoucherRepository`  [EXTRACTED]
  src/main/java/com/example/accounting/voucher/internal/persistence/JdbcVoucherRepository.java → src/main/java/com/example/accounting/voucher/internal/port/VoucherRepository.java

## Import Cycles
- None detected.

## Communities (76 total, 16 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.15
Nodes (12): 10. 报表与查询 DSL, 12. MCP 设计, 13. 幂等、并发与错误处理, 15. 测试策略, 17. 开发执行约定, 18. 完成定义, 19. 实现参考, 1. 设计目标 (+4 more)

### Community 1 - "Community 1"
Cohesion: 0.04
Nodes (45): 10. v0.1 验收标准, 11. 交付路线, 12. 产品边界说明, 1. 产品概述, 2. 产品目标, 3.1 关系模型, 3.2 账套角色, 3. 用户、账套与权限 (+37 more)

### Community 2 - "Community 2"
Cohesion: 0.20
Nodes (10): 5.1 通用字段, 5.3 基础资料, 5.6 审计与幂等, 5. 数据模型, `account`, `accounting_period`, `audit_revision`, `dimension_type` / `dimension_value` (+2 more)

### Community 3 - "Community 3"
Cohesion: 0.10
Nodes (7): AccountingArchitectureTest, AccountingModularityTest, FinanceMcpToolsTest, auth, FrontendContractTest, test, CurrentUserResolverTest

### Community 4 - "Community 4"
Cohesion: 0.19
Nodes (4): DefaultJobService, Job, JobResponses, JobService

### Community 6 - "Community 6"
Cohesion: 0.06
Nodes (32): AuditController, DeleteMapping, DocumentController, JobController, GetMapping, HttpServletRequest, CurrentUserResolver, IdentityController (+24 more)

### Community 7 - "Community 7"
Cohesion: 0.15
Nodes (6): DefaultAuditService, AuditResponses, Entry, AuditService, JdbcAuditRepository, AuditRepository

### Community 8 - "Community 8"
Cohesion: 0.53
Nodes (4): Bean, HttpSecurity, SecurityFilterChain, SecurityConfiguration

### Community 9 - "Community 9"
Cohesion: 0.33
Nodes (5): AI 财务系统, 启动前准备, 常用命令, 当前本地环境决策, 第一版 API

### Community 10 - "Community 10"
Cohesion: 0.11
Nodes (15): DefaultReportingService, LedgerLine, Filters, FinanceQueryRequests, Query, ReportingService, FinanceQueryLine, LedgerLine (+7 more)

### Community 11 - "Community 11"
Cohesion: 0.24
Nodes (6): AfterEach, FilterChain, HttpServletResponse, OncePerRequestFilter, Stage0HttpSupportTest, TraceIdFilter

### Community 25 - "Community 25"
Cohesion: 0.09
Nodes (10): BigDecimal, InputStream, JdbcTemplate, Path, JdbcAgentToolAuditRepository, JdbcReportingRepository, ReportingRepository, RuntimeException (+2 more)

### Community 27 - "Community 27"
Cohesion: 0.12
Nodes (12): FinanceMcpTools, DefaultExtractionService, Extraction, ExtractionResponses, ExtractionService, Extraction, McpTool, Object (+4 more)

### Community 29 - "Community 29"
Cohesion: 0.09
Nodes (4): Line, LocalDate, Map, JdbcVoucherRepository

### Community 30 - "Community 30"
Cohesion: 0.14
Nodes (8): DefaultVoucherService, VoucherSnapshot, Create, Transactional, Voucher, Update, Voucher, VoucherService

### Community 31 - "Community 31"
Cohesion: 0.12
Nodes (9): LedgerRole, MembershipStatus, DefaultLedgerAccessService, AccountTemplate, FormulaTemplate, LedgerAccessService, JdbcLedgerAccessRepository, LedgerAccessRepository (+1 more)

### Community 32 - "Community 32"
Cohesion: 0.07
Nodes (27): Architecture decisions, Checkpoint: accounting core, Checkpoint: external adapters, Checkpoint: small modules, Dependency graph, Implementation Plan: Service and repository port refactor, Overview, Phase 1: Guardrails and small modules (+19 more)

### Community 33 - "Community 33"
Cohesion: 0.09
Nodes (22): ADR-001: Service contracts and database repository ports, Alternatives considered, Always, Ask first, Assumptions, Boundaries, Code style, Commands (+14 more)

### Community 34 - "Community 34"
Cohesion: 0.10
Nodes (8): VoucherLineSnapshot, Optional, JdbcExtractionRepository, ExtractionRepository, OpenPeriod, Idempotency, LedgerContext, VoucherState

### Community 35 - "Community 35"
Cohesion: 0.19
Nodes (5): DefaultDocumentService, Content, Document, DocumentResponses, DocumentService

### Community 36 - "Community 36"
Cohesion: 0.16
Nodes (5): DefaultIdentityService, IdentityService, UserResponse, JdbcIdentityRepository, IdentityRepository

### Community 37 - "Community 37"
Cohesion: 0.22
Nodes (9): 16. 具体开发计划, 阶段 0：工程基础, 阶段 1：身份、账套与隔离, 阶段 2：准则包与基础资料, 阶段 3：凭证核心, 阶段 4：账簿与报表, 阶段 5：附件与异步任务, 阶段 6：MCP (+1 more)

### Community 38 - "Community 38"
Cohesion: 0.09
Nodes (4): JdbcDocumentRepository, DocumentRepository, LedgerRepository, UUID

### Community 39 - "Community 39"
Cohesion: 0.40
Nodes (5): 5.4 凭证, `voucher`, `voucher_approval`, `voucher_line`, `voucher_line_dimension`

### Community 40 - "Community 40"
Cohesion: 0.50
Nodes (4): 11.1 上传, 11.2 PostgreSQL 任务队列, 11.3 Mock 提取, 11. 附件与异步任务

### Community 41 - "Community 41"
Cohesion: 0.50
Nodes (4): 2.1 技术栈, 2.2 为什么采用 Spring Boot, 2.3 保持简单的边界, 2. 架构决策

### Community 42 - "Community 42"
Cohesion: 0.50
Nodes (4): 5.2 身份与账套, `app_user`, `ledger`, `ledger_membership`

### Community 43 - "Community 43"
Cohesion: 0.50
Nodes (4): 5.5 文档与任务, `background_job`, `document`, `document_extraction`

### Community 44 - "Community 44"
Cohesion: 0.50
Nodes (4): 9.1 状态机, 9.2 事务, 9.3 修改与恢复, 9. 凭证状态、事务与修订

### Community 45 - "Community 45"
Cohesion: 0.67
Nodes (3): 14.1 可观测性, 14.2 保留, 14. 可观测性与保留

### Community 46 - "Community 46"
Cohesion: 0.67
Nodes (3): 7.1 认证, 7.2 授权, 7. 安全与账套隔离

### Community 47 - "Community 47"
Cohesion: 0.67
Nodes (3): 8.1 通用约定, 8.2 主要接口, 8. API 规范

### Community 48 - "Community 48"
Cohesion: 0.06
Nodes (58): ApiAuth, ApiError, apiFetch(), baseUrl, createIdempotencyKey(), jsonBody(), Account, AuditEntry (+50 more)

### Community 49 - "Community 49"
Cohesion: 0.05
Nodes (42): dependencies, @ant-design/icons, antd, dayjs, decimal.js, oidc-client-ts, react, react-dom (+34 more)

### Community 50 - "Community 50"
Cohesion: 0.11
Nodes (18): compilerOptions, allowJs, allowSyntheticDefaultImports, esModuleInterop, forceConsistentCasingInFileNames, isolatedModules, jsx, lib (+10 more)

### Community 52 - "Community 52"
Cohesion: 0.30
Nodes (4): ResolvedUser, OpeningBalanceLine, Stage2BaseDataTest, Stage2LedgerInitializationTest

### Community 53 - "Community 53"
Cohesion: 0.33
Nodes (5): components, $defs, operations, paths, webhooks

### Community 62 - "Community 62"
Cohesion: 0.18
Nodes (5): AuditContext, ExceptionHandler, ProblemDetail, ResponseEntity, ProblemDetailExceptionHandler

### Community 65 - "Community 65"
Cohesion: 0.50
Nodes (3): 前端开发说明, 本地启动, 质量门禁

### Community 68 - "Community 68"
Cohesion: 0.23
Nodes (3): OpeningBalance, LedgerService, OpeningBalance

### Community 69 - "Community 69"
Cohesion: 0.31
Nodes (3): PeriodAction, Period, Period

### Community 71 - "Community 71"
Cohesion: 0.29
Nodes (4): DimensionType, DimensionTypeCreate, DimensionType, ResultSet

### Community 72 - "Community 72"
Cohesion: 0.33
Nodes (3): DimensionValue, DimensionValueCreate, DimensionValue

### Community 74 - "Community 74"
Cohesion: 0.31
Nodes (3): Account, Account, LedgerResponses

## Knowledge Gaps
- **215 isolated node(s):** `auth`, `name`, `private`, `version`, `type` (+210 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **16 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `LedgerRole` connect `Community 31` to `Community 34`, `Community 67`, `Community 5`, `Community 38`, `Community 48`, `Community 51`, `Community 25`, `Community 30`?**
  _High betweenness centrality (0.119) - this node is a cross-community bridge._
- **Why does `test` connect `Community 3` to `Community 66`, `Community 73`, `Community 10`, `Community 75`, `Community 11`, `Community 49`, `Community 61`, `Community 52`, `Community 25`, `Community 29`, `Community 62`, `Community 63`?**
  _High betweenness centrality (0.064) - this node is a cross-community bridge._
- **Why does `scripts` connect `Community 49` to `Community 3`?**
  _High betweenness centrality (0.039) - this node is a cross-community bridge._
- **What connects `auth`, `name`, `private` to the rest of the system?**
  _215 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.043478260869565216 - nodes in this community are weakly interconnected._
- **Should `Community 3` be split into smaller, more focused modules?**
  _Cohesion score 0.10153846153846154 - nodes in this community are weakly interconnected._
- **Should `Community 6` be split into smaller, more focused modules?**
  _Cohesion score 0.05507246376811594 - nodes in this community are weakly interconnected._