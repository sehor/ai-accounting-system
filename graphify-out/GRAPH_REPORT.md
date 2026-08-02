# Graph Report - ai-accouting-system  (2026-07-31)

## Corpus Check
- 193 files · ~65,390 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1640 nodes · 5860 edges · 101 communities (82 shown, 19 thin omitted)
- Extraction: 95% EXTRACTED · 5% INFERRED · 0% AMBIGUOUS · INFERRED: 289 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `eb006538`
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
- [[_COMMUNITY_Community 17|Community 17]]
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
- [[_COMMUNITY_Community 74|Community 74]]
- [[_COMMUNITY_Community 75|Community 75]]
- [[_COMMUNITY_Community 76|Community 76]]
- [[_COMMUNITY_Community 77|Community 77]]
- [[_COMMUNITY_Community 78|Community 78]]
- [[_COMMUNITY_Community 79|Community 79]]
- [[_COMMUNITY_Community 80|Community 80]]
- [[_COMMUNITY_Community 81|Community 81]]
- [[_COMMUNITY_Community 82|Community 82]]
- [[_COMMUNITY_Community 83|Community 83]]
- [[_COMMUNITY_Community 84|Community 84]]
- [[_COMMUNITY_Community 85|Community 85]]
- [[_COMMUNITY_Community 86|Community 86]]
- [[_COMMUNITY_Community 87|Community 87]]
- [[_COMMUNITY_Community 88|Community 88]]
- [[_COMMUNITY_Community 89|Community 89]]
- [[_COMMUNITY_Community 90|Community 90]]
- [[_COMMUNITY_Community 91|Community 91]]
- [[_COMMUNITY_Community 92|Community 92]]
- [[_COMMUNITY_Community 93|Community 93]]
- [[_COMMUNITY_Community 94|Community 94]]
- [[_COMMUNITY_Community 95|Community 95]]
- [[_COMMUNITY_Community 96|Community 96]]
- [[_COMMUNITY_Community 97|Community 97]]
- [[_COMMUNITY_Community 98|Community 98]]
- [[_COMMUNITY_Community 99|Community 99]]
- [[_COMMUNITY_Community 100|Community 100]]

## God Nodes (most connected - your core abstractions)
1. `test` - 100 edges
2. `ApiProblemException` - 74 edges
3. `DefaultLedgerService` - 57 edges
4. `Voucher` - 52 edges
5. `CurrentUserResolver` - 48 edges
6. `LedgerService` - 48 edges
7. `JdbcLedgerRepository` - 48 edges
8. `DefaultVoucherService` - 44 edges
9. `LedgerRepository` - 43 edges
10. `AccountExchangeService` - 40 edges

## Surprising Connections (you probably didn't know these)
- `RequireAuth()` --calls--> `useAuth()`  [EXTRACTED]
  frontend/src/app/App.tsx → frontend/src/auth/AuthProvider.tsx
- `LoginPage()` --calls--> `useAuth()`  [EXTRACTED]
  frontend/src/pages/LoginPage.tsx → frontend/src/auth/AuthProvider.tsx
- `JdbcAgentToolAuditRepository` --implements--> `AgentToolAuditRepository`  [EXTRACTED]
  src/main/java/com/example/accounting/agent/internal/persistence/JdbcAgentToolAuditRepository.java → src/main/java/com/example/accounting/agent/internal/port/AgentToolAuditRepository.java
- `DefaultDocumentService` --implements--> `DocumentService`  [EXTRACTED]
  src/main/java/com/example/accounting/documents/internal/application/DefaultDocumentService.java → src/main/java/com/example/accounting/documents/DocumentService.java
- `DefaultIdentityService` --implements--> `IdentityService`  [EXTRACTED]
  src/main/java/com/example/accounting/identity/internal/application/DefaultIdentityService.java → src/main/java/com/example/accounting/identity/IdentityService.java

## Import Cycles
- None detected.

## Communities (101 total, 19 thin omitted)

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
Cohesion: 0.08
Nodes (10): AccountingArchitectureTest, AccountingModularityTest, FinanceMcpToolsTest, HttpDocumentExtractorTest, FrontendContractTest, test, CurrentUserResolverTest, AccountCodeRuleTest (+2 more)

### Community 4 - "Community 4"
Cohesion: 0.10
Nodes (3): JdbcDocumentRepository, DocumentIdempotency, DocumentRepository

### Community 5 - "Community 5"
Cohesion: 0.24
Nodes (5): Content, DocumentResponses, DataExchangeControllerTest, LedgerBackupControllerTest, Header

### Community 6 - "Community 6"
Cohesion: 0.22
Nodes (5): DefaultJobService, Job, JobResponses, JobService, Stage5DocumentTest

### Community 7 - "Community 7"
Cohesion: 0.14
Nodes (7): AddMember, DimensionRequirement, LedgerRequests, OpeningBalances, UpdateMember, Member, Member

### Community 8 - "Community 8"
Cohesion: 0.09
Nodes (10): Optional, JdbcExtractionRepository, JdbcLedgerAccessRepository, ExtractionRepository, OpenPeriod, LedgerAccessRepository, AccountControls, Idempotency (+2 more)

### Community 9 - "Community 9"
Cohesion: 0.33
Nodes (5): AI 财务系统, 启动前准备, 常用命令, 当前本地环境决策, 第一版 API

### Community 10 - "Community 10"
Cohesion: 0.12
Nodes (17): LedgerLine, Statement, TrialBalanceLine, DefaultReportingService, LedgerLine, Filters, FinanceQueryRequests, Query (+9 more)

### Community 11 - "Community 11"
Cohesion: 0.22
Nodes (4): VoucherLineSnapshot, Dimension, Line, Line

### Community 17 - "Community 17"
Cohesion: 0.31
Nodes (3): PeriodAction, Period, Period

### Community 25 - "Community 25"
Cohesion: 0.30
Nodes (3): DimensionValue, DimensionValueCreate, DimensionValue

### Community 26 - "Community 26"
Cohesion: 0.06
Nodes (4): LedgerRepository, VoucherRepository, UUID, Revision

### Community 29 - "Community 29"
Cohesion: 0.06
Nodes (4): Override, JdbcLedgerRepository, JdbcVoucherRepository, OpeningTotals

### Community 30 - "Community 30"
Cohesion: 0.14
Nodes (7): DefaultVoucherService, VoucherSnapshot, Transactional, Voucher, Update, Voucher, VoucherService

### Community 32 - "Community 32"
Cohesion: 0.07
Nodes (27): Architecture decisions, Checkpoint: accounting core, Checkpoint: external adapters, Checkpoint: small modules, Dependency graph, Implementation Plan: Service and repository port refactor, Overview, Phase 1: Guardrails and small modules (+19 more)

### Community 33 - "Community 33"
Cohesion: 0.09
Nodes (22): ADR-001: Service contracts and database repository ports, Alternatives considered, Always, Ask first, Assumptions, Boundaries, Code style, Commands (+14 more)

### Community 34 - "Community 34"
Cohesion: 0.19
Nodes (4): DefaultIdentityService, UserResponse, JdbcIdentityRepository, IdentityRepository

### Community 35 - "Community 35"
Cohesion: 0.18
Nodes (7): AfterEach, FilterChain, HttpServletResponse, OncePerRequestFilter, LocalUserHeaderAuthenticationFilter, Stage0HttpSupportTest, TraceIdFilter

### Community 36 - "Community 36"
Cohesion: 0.12
Nodes (13): DefaultDocumentService, Bean, Document, ExtractorTestConfiguration, HttpSecurity, HttpDocumentExtractor, Path, DocumentExtractor (+5 more)

### Community 37 - "Community 37"
Cohesion: 0.22
Nodes (9): 16. 具体开发计划, 阶段 0：工程基础, 阶段 1：身份、账套与隔离, 阶段 2：准则包与基础资料, 阶段 3：凭证核心, 阶段 4：账簿与报表, 阶段 5：附件与异步任务, 阶段 6：MCP (+1 more)

### Community 38 - "Community 38"
Cohesion: 0.20
Nodes (3): DataExchangeServiceTest, AccountExchangeIntegrationTest, Stage4ReportingTest

### Community 39 - "Community 39"
Cohesion: 0.40
Nodes (5): 5.4 凭证, `voucher`, `voucher_approval`, `voucher_line`, `voucher_line_dimension`

### Community 40 - "Community 40"
Cohesion: 0.50
Nodes (4): 11.1 上传, 11.2 PostgreSQL 任务队列, 11.3 文档提取, 11. 附件与异步任务

### Community 41 - "Community 41"
Cohesion: 0.50
Nodes (4): 2.1 技术栈, 2.2 为什么采用 Spring Boot, 2.3 保持简单的边界, 2. 架构决策

### Community 42 - "Community 42"
Cohesion: 0.20
Nodes (7): Cell, ImportedLine, ImportResult, KingdeeExchange, ParsedWorkbook, VoucherKey, Row

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
Cohesion: 0.22
Nodes (15): apiFetch(), User, AuthContext, AuthContextValue, AuthProvider(), clearSession(), finishOidcLogin(), getSession() (+7 more)

### Community 49 - "Community 49"
Cohesion: 0.05
Nodes (42): dependencies, @ant-design/icons, antd, dayjs, decimal.js, oidc-client-ts, react, react-dom (+34 more)

### Community 50 - "Community 50"
Cohesion: 0.11
Nodes (18): compilerOptions, allowJs, allowSyntheticDefaultImports, esModuleInterop, forceConsistentCasingInFileNames, isolatedModules, jsx, lib (+10 more)

### Community 51 - "Community 51"
Cohesion: 0.09
Nodes (6): AuditController, DataExchangeController, CurrentUserResolver, IdentityController, LedgerBackupController, FinanceQueryController

### Community 52 - "Community 52"
Cohesion: 0.42
Nodes (6): account_specs(), http_json(), main(), mcp_message(), McpClient, rest()

### Community 53 - "Community 53"
Cohesion: 0.33
Nodes (5): components, $defs, operations, paths, webhooks

### Community 61 - "Community 61"
Cohesion: 0.14
Nodes (6): Account, ParentResolution, DimensionRequirement, AccountPatch, AccountManagementRepository, ResultSet

### Community 62 - "Community 62"
Cohesion: 0.10
Nodes (13): FinanceMcpTools, FinanceMcpToolsIntegrationTest, Extraction, DefaultExtractionService, Create, Extraction, ExtractionResponses, ExtractionService (+5 more)

### Community 63 - "Community 63"
Cohesion: 0.25
Nodes (4): Boolean, Ledger, Create, Ledger

### Community 64 - "Community 64"
Cohesion: 0.22
Nodes (4): AuditResponses, Entry, JdbcAuditRepository, AuditRepository

### Community 65 - "Community 65"
Cohesion: 0.50
Nodes (3): 前端开发说明, 本地启动, 质量门禁

### Community 66 - "Community 66"
Cohesion: 0.05
Nodes (33): AccountingApplication, ArrayNode, HttpResponse, InputStream, JsonNode, Request, Response, Suggestion (+25 more)

### Community 67 - "Community 67"
Cohesion: 0.24
Nodes (6): DeleteMapping, HttpServletRequest, Integer, LedgerController, PatchMapping, ResponseStatus

### Community 68 - "Community 68"
Cohesion: 0.31
Nodes (3): PostMapping, VoucherController, Comment

### Community 69 - "Community 69"
Cohesion: 0.06
Nodes (30): AccountCodeRule, CellStyle, CommitRow, DataFormatter, Format, AccountAiMapper, Result, Source (+22 more)

### Community 70 - "Community 70"
Cohesion: 0.09
Nodes (17): LedgerRole, MembershipStatus, DefaultAuditService, DefaultLedgerAccessService, AuditService, BigDecimal, DocumentService, IdentityService (+9 more)

### Community 75 - "Community 75"
Cohesion: 0.16
Nodes (11): ApiAuth, ApiError, baseUrl, createIdempotencyKey(), jsonBody(), AccountingStandard, Ledger, ProblemDetails (+3 more)

### Community 76 - "Community 76"
Cohesion: 0.20
Nodes (14): App(), queryClient, RequireAuth(), useAuth(), AppShell(), AuditPage(), AuthCallbackPage(), DocumentsPage() (+6 more)

### Community 78 - "Community 78"
Cohesion: 0.29
Nodes (3): JobController, GetMapping, ReportController

### Community 79 - "Community 79"
Cohesion: 0.13
Nodes (17): Account, AccountCodeRule, AccountDimensionRequirement, AccountImportRow, AuditEntry, DimensionValue, DocumentRecord, Period (+9 more)

### Community 80 - "Community 80"
Cohesion: 0.21
Nodes (7): Member, OpeningBalance, decimalRule, OpeningFormLine, OpeningsTab(), decimalOrZero(), voucherTotals()

### Community 81 - "Community 81"
Cohesion: 0.37
Nodes (3): ResolvedUser, OpeningBalanceLine, Stage2BaseDataTest

### Community 82 - "Community 82"
Cohesion: 0.17
Nodes (11): 10. 验收, 1. 范围与兼容性, 2. 准则包, 3. 科目树与编码, 4. 属性、辅助核算与凭证, 5. 安全锁、状态与审计, 6. REST API, 7. Excel 合同 (+3 more)

### Community 83 - "Community 83"
Cohesion: 0.19
Nodes (4): AuditContext, ExceptionHandler, ProblemDetail, ProblemDetailExceptionHandler

### Community 85 - "Community 85"
Cohesion: 0.35
Nodes (4): AccountExchangeController, Preview, PutMapping, ResponseEntity

### Community 88 - "Community 88"
Cohesion: 0.20
Nodes (9): API 合同, 前端, 命令与项目位置, 实施任务, 文件格式与安全边界, 目标, 范围, 账套备份与恢复规格 (+1 more)

### Community 89 - "Community 89"
Cohesion: 0.22
Nodes (6): AccountImportPreview, CashFlowItem, DimensionType, AccountForm, AccountsTab(), AccountTree

### Community 90 - "Community 90"
Cohesion: 0.22
Nodes (4): Account, CashFlowItem, DimensionRequirement, LedgerResponses

### Community 92 - "Community 92"
Cohesion: 0.29
Nodes (5): Create, Dimension, Line, Reason, VoucherRequests

### Community 94 - "Community 94"
Cohesion: 0.50
Nodes (4): 5.2 身份与账套, `app_user`, `ledger`, `ledger_membership`

### Community 96 - "Community 96"
Cohesion: 0.30
Nodes (3): DimensionType, DimensionTypeCreate, DimensionType

## Knowledge Gaps
- **249 isolated node(s):** `auth`, `name`, `private`, `version`, `type` (+244 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **19 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `test` connect `Community 3` to `Community 5`, `Community 6`, `Community 26`, `Community 35`, `Community 36`, `Community 38`, `Community 49`, `Community 62`, `Community 66`, `Community 69`, `Community 70`, `Community 81`, `Community 83`, `Community 84`, `Community 86`, `Community 91`, `Community 93`, `Community 95`, `Community 98`, `Community 99`, `Community 100`?**
  _High betweenness centrality (0.087) - this node is a cross-community bridge._
- **Why does `LedgerRole` connect `Community 70` to `Community 7`, `Community 8`, `Community 75`, `Community 79`, `Community 80`, `Community 26`, `Community 29`, `Community 30`, `Community 31`?**
  _High betweenness centrality (0.075) - this node is a cross-community bridge._
- **Why does `scripts` connect `Community 49` to `Community 3`?**
  _High betweenness centrality (0.030) - this node is a cross-community bridge._
- **What connects `auth`, `name`, `private` to the rest of the system?**
  _249 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.043478260869565216 - nodes in this community are weakly interconnected._
- **Should `Community 3` be split into smaller, more focused modules?**
  _Cohesion score 0.07954545454545454 - nodes in this community are weakly interconnected._
- **Should `Community 4` be split into smaller, more focused modules?**
  _Cohesion score 0.09881422924901186 - nodes in this community are weakly interconnected._