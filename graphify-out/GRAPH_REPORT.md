# Graph Report - ai-accouting-system  (2026-07-27)

## Corpus Check
- 78 files · ~19,764 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 568 nodes · 1809 edges · 29 communities (26 shown, 3 thin omitted)
- Extraction: 93% EXTRACTED · 7% INFERRED · 0% AMBIGUOUS · INFERRED: 120 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `7ec8912e`
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

## God Nodes (most connected - your core abstractions)
1. `LedgerService` - 46 edges
2. `VoucherService` - 40 edges
3. `ApiProblemException` - 38 edges
4. `Voucher` - 33 edges
5. `CurrentUserResolver` - 31 edges
6. `ResolvedUser` - 23 edges
7. `LedgerController` - 22 edges
8. `AI 财务系统开发设计文档（TDD）v0.1` - 20 edges
9. `VoucherController` - 18 edges
10. `ReportingService` - 15 edges

## Surprising Connections (you probably didn't know these)
- None detected - all connections are within the same source files.

## Import Cycles
- None detected.

## Communities (29 total, 3 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.05
Nodes (42): 10. 报表与查询 DSL, 11.1 上传, 11.2 PostgreSQL 任务队列, 11.3 Mock 提取, 11. 附件与异步任务, 12. MCP 设计, 13. 幂等、并发与错误处理, 14.1 可观测性 (+34 more)

### Community 1 - "Community 1"
Cohesion: 0.04
Nodes (45): 10. v0.1 验收标准, 11. 交付路线, 12. 产品边界说明, 1. 产品概述, 2. 产品目标, 3.1 关系模型, 3.2 账套角色, 3. 用户、账套与权限 (+37 more)

### Community 2 - "Community 2"
Cohesion: 0.09
Nodes (23): 5.1 通用字段, 5.2 身份与账套, 5.3 基础资料, 5.4 凭证, 5.5 文档与任务, 5.6 审计与幂等, 5. 数据模型, `account` (+15 more)

### Community 3 - "Community 3"
Cohesion: 0.09
Nodes (13): AccountingModularityTest, FinanceMcpToolsTest, Stage5DocumentTest, ResolvedUser, CurrentUserResolverTest, LedgerControllerTest, DimensionTypeCreate, Stage2BaseDataTest (+5 more)

### Community 4 - "Community 4"
Cohesion: 0.35
Nodes (3): Job, JobResponses, JobService

### Community 5 - "Community 5"
Cohesion: 0.09
Nodes (21): BigDecimal, AddMember, Create, DimensionValueCreate, LedgerRequests, OpeningBalanceLine, OpeningBalances, PeriodAction (+13 more)

### Community 6 - "Community 6"
Cohesion: 0.07
Nodes (29): AuditController, DeleteMapping, DocumentController, JobController, GetMapping, HttpServletRequest, CurrentUserResolver, IdentityController (+21 more)

### Community 7 - "Community 7"
Cohesion: 0.16
Nodes (8): AccountingApplication, Create, Line, Stage4ReportingTest, String, LedgerContext, VoucherService, VoucherState

### Community 8 - "Community 8"
Cohesion: 0.53
Nodes (4): Bean, HttpSecurity, SecurityFilterChain, SecurityConfiguration

### Community 9 - "Community 9"
Cohesion: 0.33
Nodes (5): AI 财务系统, 启动前准备, 常用命令, 当前本地环境决策, 第一版 API

### Community 10 - "Community 10"
Cohesion: 0.16
Nodes (10): Filters, FinanceQueryRequests, Query, ReportingService, FinanceQueryLine, LedgerLine, ReportResponses, Statement (+2 more)

### Community 11 - "Community 11"
Cohesion: 0.11
Nodes (13): AfterEach, AuditContext, ExceptionHandler, FilterChain, HttpServletResponse, OncePerRequestFilter, Optional, Override (+5 more)

### Community 25 - "Community 25"
Cohesion: 0.05
Nodes (21): AuditResponses, Entry, AuditService, Document, DocumentResponses, DocumentService, Extraction, ExtractionResponses (+13 more)

### Community 26 - "Community 26"
Cohesion: 0.40
Nodes (3): Line, Revision, VoucherResponses

### Community 27 - "Community 27"
Cohesion: 0.34
Nodes (6): FinanceMcpTools, McpTool, Object, PreAuthorize, Supplier, T

## Knowledge Gaps
- **99 isolated node(s):** `com.example:accounting`, `AccountTemplate`, `FormulaTemplate`, `Create`, `Line` (+94 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **3 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `ApiProblemException` connect `Community 25` to `Community 3`, `Community 4`, `Community 5`, `Community 6`, `Community 7`, `Community 10`, `Community 11`, `Community 27`?**
  _High betweenness centrality (0.026) - this node is a cross-community bridge._
- **Why does `CurrentUserResolver` connect `Community 6` to `Community 3`, `Community 25`, `Community 27`?**
  _High betweenness centrality (0.020) - this node is a cross-community bridge._
- **Why does `LedgerService` connect `Community 5` to `Community 3`, `Community 25`, `Community 27`, `Community 6`?**
  _High betweenness centrality (0.015) - this node is a cross-community bridge._
- **What connects `com.example:accounting`, `AccountTemplate`, `FormulaTemplate` to the rest of the system?**
  _99 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.046511627906976744 - nodes in this community are weakly interconnected._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.043478260869565216 - nodes in this community are weakly interconnected._
- **Should `Community 2` be split into smaller, more focused modules?**
  _Cohesion score 0.08695652173913043 - nodes in this community are weakly interconnected._