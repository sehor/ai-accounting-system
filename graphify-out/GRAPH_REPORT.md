# Graph Report - ai-accouting-system  (2026-08-07)

## Corpus Check
- 266 files · ~139,446 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 2592 nodes · 10059 edges · 121 communities (102 shown, 19 thin omitted)
- Extraction: 92% EXTRACTED · 8% INFERRED · 0% AMBIGUOUS · INFERRED: 852 edges (avg confidence: 0.77)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `34f8b729`
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
- [[_COMMUNITY_Community 73|Community 73]]
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
- [[_COMMUNITY_Community 88|Community 88]]
- [[_COMMUNITY_Community 89|Community 89]]
- [[_COMMUNITY_Community 90|Community 90]]
- [[_COMMUNITY_Community 91|Community 91]]
- [[_COMMUNITY_Community 93|Community 93]]
- [[_COMMUNITY_Community 94|Community 94]]
- [[_COMMUNITY_Community 95|Community 95]]
- [[_COMMUNITY_Community 96|Community 96]]
- [[_COMMUNITY_Community 97|Community 97]]
- [[_COMMUNITY_Community 98|Community 98]]
- [[_COMMUNITY_Community 99|Community 99]]
- [[_COMMUNITY_Community 100|Community 100]]
- [[_COMMUNITY_Community 101|Community 101]]
- [[_COMMUNITY_Community 102|Community 102]]
- [[_COMMUNITY_Community 103|Community 103]]
- [[_COMMUNITY_Community 104|Community 104]]
- [[_COMMUNITY_Community 105|Community 105]]
- [[_COMMUNITY_Community 107|Community 107]]
- [[_COMMUNITY_Community 111|Community 111]]
- [[_COMMUNITY_Community 113|Community 113]]
- [[_COMMUNITY_Community 115|Community 115]]
- [[_COMMUNITY_Community 116|Community 116]]
- [[_COMMUNITY_Community 117|Community 117]]
- [[_COMMUNITY_Community 120|Community 120]]
- [[_COMMUNITY_Community 121|Community 121]]
- [[_COMMUNITY_Community 124|Community 124]]
- [[_COMMUNITY_Community 125|Community 125]]
- [[_COMMUNITY_Community 129|Community 129]]
- [[_COMMUNITY_Community 141|Community 141]]
- [[_COMMUNITY_Community 147|Community 147]]
- [[_COMMUNITY_Community 150|Community 150]]

## God Nodes (most connected - your core abstractions)
1. `test` - 163 edges
2. `ApiProblemException` - 90 edges
3. `FinanceMcpTools` - 85 edges
4. `DefaultLedgerService` - 59 edges
5. `CurrentUserResolver` - 55 edges
6. `LedgerService` - 54 edges
7. `DefaultFixedAssetService` - 53 edges
8. `Voucher` - 53 edges
9. `JdbcLedgerRepository` - 50 edges
10. `Sheet` - 49 edges

## Surprising Connections (you probably didn't know these)
- `main()` --calls--> `rest()`  [INFERRED]
  artifacts/chkj-import/import_fixed_assets.py → test-resources/agent_bank_statement_e2e.py
- `save_source_copy()` --calls--> `Font`  [INFERRED]
  artifacts/chkj-import/import_accounts.py → artifacts/chkj-import/pydeps/xlrd/formatting.py
- `save_workbook()` --calls--> `Font`  [INFERRED]
  artifacts/chkj-import/import_fixed_assets.py → artifacts/chkj-import/pydeps/xlrd/formatting.py
- `save_workbook()` --calls--> `Font`  [INFERRED]
  artifacts/chkj-import/import_vouchers.py → artifacts/chkj-import/pydeps/xlrd/formatting.py
- `EqNeAttrs` --uses--> `XLRDError`  [INFERRED]
  artifacts/chkj-import/pydeps/xlrd/formatting.py → artifacts/chkj-import/pydeps/xlrd/biffh.py

## Import Cycles
- 1-file cycle: `artifacts/chkj-import/pydeps/xlrd/__init__.py -> artifacts/chkj-import/pydeps/xlrd/__init__.py`

## Communities (121 total, 19 thin omitted)

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
Cohesion: 0.05
Nodes (14): AccountingArchitectureTest, AccountingModularityTest, AccountingExperienceServiceTest, FinanceMcpToolsTest, auth, ExceptionHandler, FixedAssetCalculationTest, FrontendContractTest (+6 more)

### Community 4 - "Community 4"
Cohesion: 0.06
Nodes (25): biff_dump(), hex_char_dump(), Return (unicode_strg, updated value of pos), :param f: open file object, to which the dump is written         :param header:, unpack_unicode_update_pos(), upkbits(), Determine column display width.          :param colx:           Index of the que, :class:`Cell` object in the given row and column. (+17 more)

### Community 6 - "Community 6"
Cohesion: 0.12
Nodes (16): AccountDimensionRequirement, AccountImportRow, FixedAsset, FixedAssetCategory, FixedAssetDisposal, FixedAssetPage, FixedAssetPreview, FixedAssetPreviewLine (+8 more)

### Community 7 - "Community 7"
Cohesion: 0.16
Nodes (12): ApiProblemException, Cell, ImportedLine, KingdeeExchange, MergeCategory(), MergeGroupKey, ParsedWorkbook, VoucherKey (+4 more)

### Community 8 - "Community 8"
Cohesion: 0.10
Nodes (10): Experience, ExperienceResponses, Page, DefaultAccountingExperienceService, ExperienceScope, Page, JdbcAccountingExperienceRepository, AccountingExperienceRepository (+2 more)

### Community 9 - "Community 9"
Cohesion: 0.29
Nodes (6): Agent 做账经验, AI 财务系统, 启动前准备, 常用命令, 当前本地环境决策, 第一版 API

### Community 10 - "Community 10"
Cohesion: 0.07
Nodes (22): LedgerLine, Statement, TrialBalanceLine, DefaultReportingService, AccountSearchIntegrationTest, LedgerLine, JdbcReportingRepository, ReportingRepository (+14 more)

### Community 11 - "Community 11"
Cohesion: 0.23
Nodes (6): decimalRule, OpeningFormLine, OpeningsTab(), SettingsPage(), decimalOrZero(), voucherTotals()

### Community 17 - "Community 17"
Cohesion: 0.09
Nodes (10): Book, open_workbook_xls(), Contents of a "workbook".      .. warning::        You should not instantiate th, :returns: A list of all sheets in the book.          All sheets not already load, :param sheetx: Sheet index in ``range(nsheets)``         :returns: A :class:`~xl, Makes iteration through sheets of a book a little more straightforward., :param sheet_name: Name of the sheet required.         :returns: A :class:`~xlrd, Allow indexing with sheet name or index.         :param item: Name or index of s (+2 more)

### Community 25 - "Community 25"
Cohesion: 0.05
Nodes (26): Create, ExperienceRequests, Search, Update, ExportedFile, FinanceMcpTools, FinanceMcpToolsIntegrationTest, Voucher (+18 more)

### Community 26 - "Community 26"
Cohesion: 0.20
Nodes (3): AfterEach, LedgerRenameMcpIntegrationTest, AuditContext

### Community 27 - "Community 27"
Cohesion: 0.14
Nodes (30): BaseObject, Parent of almost all other classes in the package. Defines a common     :meth:`d, EqNeAttrs, Format, A collection of the border-related attributes of an ``XF`` record.     Items cor, A collection of the background-related attributes of an ``XF`` record.     Items, A collection of the alignment and similar attributes of an ``XF`` record.     It, A collection of the protection-related attributes of an ``XF`` record.     Items (+22 more)

### Community 29 - "Community 29"
Cohesion: 0.05
Nodes (7): File, Account, Override, JdbcDocumentRepository, JdbcLedgerRepository, JdbcVoucherRepository, OpeningTotals

### Community 30 - "Community 30"
Cohesion: 0.16
Nodes (6): LedgerRole, MembershipStatus, DefaultLedgerAccessService, LocalSuperAgentPolicy, JdbcLedgerAccessRepository, LedgerAccessRepository

### Community 31 - "Community 31"
Cohesion: 0.05
Nodes (7): JdbcFixedAssetRepository, AssetRecord, CategoryRecord, DisposalRecord, FixedAssetRepository, LineRecord, RunRecord

### Community 32 - "Community 32"
Cohesion: 0.07
Nodes (27): Architecture decisions, Checkpoint: accounting core, Checkpoint: external adapters, Checkpoint: small modules, Dependency graph, Implementation Plan: Service and repository port refactor, Overview, Phase 1: Guardrails and small modules (+19 more)

### Community 33 - "Community 33"
Cohesion: 0.09
Nodes (22): ADR-001: Service contracts and database repository ports, Alternatives considered, Always, Ask first, Assumptions, Boundaries, Code style, Commands (+14 more)

### Community 34 - "Community 34"
Cohesion: 0.12
Nodes (10): UserType, DefaultIdentityService, LocalSuperAgentBootstrap, LocalSuperAgentBootstrapTest, EventListener, IdentityService, UserResponse, Optional (+2 more)

### Community 35 - "Community 35"
Cohesion: 0.07
Nodes (13): Disposal, AssetPatch, CategoryCreate, CategoryPatch, DepreciationAction, Asset, Category, DepreciationPreview (+5 more)

### Community 37 - "Community 37"
Cohesion: 0.22
Nodes (9): 16. 具体开发计划, 阶段 0：工程基础, 阶段 1：身份、账套与隔离, 阶段 2：准则包与基础资料, 阶段 3：凭证核心, 阶段 4：账簿与报表, 阶段 5：附件与异步任务, 阶段 6：MCP (+1 more)

### Community 38 - "Community 38"
Cohesion: 0.05
Nodes (33): Accounting guardrails, Authorization boundaries, Experience rules, Failure handling, Operate AI Accounting System, Operating sequence, Required references, Authentication and identity (+25 more)

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
Cohesion: 0.17
Nodes (4): LinkedHashMap, Object, JdbcLedgerBackupRepository, LedgerBackupRepository

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
Cohesion: 0.18
Nodes (18): Ledger, User, AuthContext, AuthContextValue, AuthProvider(), clearSession(), createLocalSession(), finishOidcLogin() (+10 more)

### Community 49 - "Community 49"
Cohesion: 0.05
Nodes (43): dependencies, @ant-design/icons, @ant-design/v5-patch-for-react-19, antd, dayjs, decimal.js, oidc-client-ts, react (+35 more)

### Community 50 - "Community 50"
Cohesion: 0.11
Nodes (18): compilerOptions, allowJs, allowSyntheticDefaultImports, esModuleInterop, forceConsistentCasingInFileNames, isolatedModules, jsx, lib (+10 more)

### Community 51 - "Community 51"
Cohesion: 0.14
Nodes (4): DefaultJobService, Job, JdbcJobRepository, JobRepository

### Community 52 - "Community 52"
Cohesion: 0.07
Nodes (37): main(), save_source_copy(), clean_number(), main(), save_workbook(), decimal(), main(), save_workbook() (+29 more)

### Community 53 - "Community 53"
Cohesion: 0.33
Nodes (5): components, $defs, operations, paths, webhooks

### Community 61 - "Community 61"
Cohesion: 0.05
Nodes (7): AuditResponses, LedgerRepository, AccountControls, Idempotency, LedgerContext, VoucherRepository, UUID

### Community 62 - "Community 62"
Cohesion: 0.28
Nodes (4): DimensionValue, DimensionValue, DimensionValueCreate, DimensionValue

### Community 63 - "Community 63"
Cohesion: 0.16
Nodes (6): AccountManagementIntegrationTest, AccountCodeRuleUpdate, AccountPatch, LedgerRequests, OpeningBalances, Rename

### Community 65 - "Community 65"
Cohesion: 0.50
Nodes (3): 前端开发说明, 本地启动, 质量门禁

### Community 66 - "Community 66"
Cohesion: 0.15
Nodes (10): ArrayNode, JsonNode, Archive, ColumnDef, LedgerBackupService, TableDef, ObjectNode, Path (+2 more)

### Community 67 - "Community 67"
Cohesion: 0.10
Nodes (15): McpStatelessHttpIntegrationTest, HttpResponse, InputStream, AccountAiMapper, Request, Response, Result, Source (+7 more)

### Community 68 - "Community 68"
Cohesion: 0.09
Nodes (10): Asset, FixedAssetCalculation, Autowired, BigDecimal, CurrentUserResolver, JdbcTemplate, LedgerService, List (+2 more)

### Community 69 - "Community 69"
Cohesion: 0.20
Nodes (7): KingdeeImportResult, VoucherRevision, decimalRule, emptyLines, VoucherEditorPage(), VoucherForm, VoucherListPage()

### Community 70 - "Community 70"
Cohesion: 0.17
Nodes (6): VoucherLineSnapshot, Dimension, Stage2LedgerInitializationTest, Line, Dimension, Line

### Community 71 - "Community 71"
Cohesion: 0.21
Nodes (5): Member, AddMember, UpdateMember, Member, Member

### Community 73 - "Community 73"
Cohesion: 0.14
Nodes (20): unpack_string(), unpack_unicode(), upkbitsL(), check_colour_indexes_in_obj(), fill_in_standard_formats(), Font, handle_font(), handle_format() (+12 more)

### Community 75 - "Community 75"
Cohesion: 0.08
Nodes (12): DefaultDocumentService, Content, Document, DocumentResponses, DataExchangeControllerTest, DataExchangeServiceTest, ImportResult, LedgerBackupControllerTest (+4 more)

### Community 76 - "Community 76"
Cohesion: 0.16
Nodes (14): AuditEntry, DocumentRecord, App(), queryClient, RequireAuth(), useAuth(), AuditPage(), AuthCallbackPage() (+6 more)

### Community 77 - "Community 77"
Cohesion: 0.28
Nodes (3): Entry, JdbcAuditRepository, AuditRepository

### Community 78 - "Community 78"
Cohesion: 0.30
Nodes (4): Extraction, Extraction, ExtractionResponses, Extraction

### Community 79 - "Community 79"
Cohesion: 0.53
Nodes (4): Bean, HttpSecurity, SecurityFilterChain, SecurityConfiguration

### Community 80 - "Community 80"
Cohesion: 0.20
Nodes (6): Account, Account, DimensionRequirement, AccountCreate, DimensionRequirement, ResultSet

### Community 81 - "Community 81"
Cohesion: 0.13
Nodes (15): biff_count_records(), unpack_cell_range_address_list_update_pos(), unpack_string_update_pos(), colname(), display_cell_address(), Return list of strings, unpack_SST_table(), count_records() (+7 more)

### Community 82 - "Community 82"
Cohesion: 0.17
Nodes (11): 10. 验收, 1. 范围与兼容性, 2. 准则包, 3. 科目树与编码, 4. 属性、辅助核算与凭证, 5. 安全锁、状态与审计, 6. REST API, 7. Excel 合同 (+3 more)

### Community 83 - "Community 83"
Cohesion: 0.05
Nodes (27): ByteArrayMultipartFile, AuditController, CurrentUserResolver, DeleteMapping, DocumentController, JobController, DataExchangeController, FixedAssetController (+19 more)

### Community 84 - "Community 84"
Cohesion: 0.07
Nodes (38): Exception, tuple, adjust_cell_addr_biff8(), adjust_cell_addr_biff_le7(), cellname(), cellnameabs(), cellnamerel(), colname() (+30 more)

### Community 85 - "Community 85"
Cohesion: 0.50
Nodes (4): 5.2 身份与账套, `app_user`, `ledger`, `ledger_membership`

### Community 88 - "Community 88"
Cohesion: 0.20
Nodes (9): API 合同, 前端, 命令与项目位置, 实施任务, 文件格式与安全边界, 目标, 范围, 账套备份与恢复规格 (+1 more)

### Community 93 - "Community 93"
Cohesion: 0.20
Nodes (6): AccountMatchMode, AccountSearchResult, AccountSummary, CashFlowItem, DimensionRequirement, LedgerResponses

### Community 94 - "Community 94"
Cohesion: 0.21
Nodes (5): ExportLineKey, fixedSummary(), MergedLine, Map, MergeCategory

### Community 96 - "Community 96"
Cohesion: 0.28
Nodes (4): Period, PeriodAction, Period, Period

### Community 97 - "Community 97"
Cohesion: 0.17
Nodes (8): jsonBody(), AccountImportPreview, AccountingStandard, AccountForm, AccountsTab(), AccountTree, CreateLedgerForm, LedgerListPage()

### Community 98 - "Community 98"
Cohesion: 0.19
Nodes (9): Array, _build_family_tree(), CompDoc, CompDocError, DirNode, dump_list(), Interrogate the compound document's directory; return the stream as a         st, Interrogate the compound document's directory.          If the named stream is n (+1 more)

### Community 99 - "Community 99"
Cohesion: 0.29
Nodes (4): Create, Dimension, Line, VoucherRequests

### Community 100 - "Community 100"
Cohesion: 0.10
Nodes (9): AccountingExperienceIntegrationTest, FilterChain, HttpServletResponse, ResolvedUser, OpeningBalanceLine, Stage2BaseDataTest, OncePerRequestFilter, LocalUserHeaderAuthenticationFilter (+1 more)

### Community 101 - "Community 101"
Cohesion: 0.29
Nodes (4): AgentContextResponses, LedgerContext, OperatorContext, ToolGroup

### Community 102 - "Community 102"
Cohesion: 0.09
Nodes (18): AccountingApplication, AtomicLong, BooleanSupplier, Clock, HttpDocumentExtractorTest, Duration, AsyncAgentToolAuditService, AsyncAgentToolAuditServiceTest (+10 more)

### Community 103 - "Community 103"
Cohesion: 0.19
Nodes (5): AccountCodeRule, CashFlowItem, ParentResolution, CashFlowItem, AccountCodeRule

### Community 104 - "Community 104"
Cohesion: 0.06
Nodes (28): AccountCodeRule, DimensionType, CellStyle, CommitRow, DataFormatter, DimensionType, AccountExchangeIntegrationTest, AccountExchangeService (+20 more)

### Community 107 - "Community 107"
Cohesion: 0.17
Nodes (9): An exception indicating problems reading data from an Excel file., XLRDError, Name, Information relating to a named reference, formula, macro, etc.      .. note::, This is a convenience method for the frequent use case where the name         re, This is a convenience method for the use case where the name         refers to o, :param sheet_name_or_index: Name or index of sheet enquired upon         :return, :param sheet_name_or_index: Name or index of sheet to be unloaded.          .. v (+1 more)

### Community 115 - "Community 115"
Cohesion: 0.29
Nodes (3): HttpDocumentExtractor, Result, Result

### Community 116 - "Community 116"
Cohesion: 0.10
Nodes (11): DefaultLedgerServiceAccountSearchTest, BeforeEach, Account, AccountingStandard, CashFlowItem, DimensionType, Formula, Package (+3 more)

### Community 117 - "Community 117"
Cohesion: 0.28
Nodes (11): bk_header(), count_xfs(), get_row_data(), LogHandler, main(), print_labels(), show(), show_fonts() (+3 more)

### Community 121 - "Community 121"
Cohesion: 0.22
Nodes (8): ApiAuth, ApiError, apiFetch(), baseUrl, createIdempotencyKey(), ProblemDetails, backupFileError(), LedgerBackupTab()

### Community 125 - "Community 125"
Cohesion: 0.12
Nodes (11): AccountingExperienceService, DefaultAuditService, AuditService, DocumentService, ExtractionService, JobService, LedgerAccessService, PeriodCloseGuard (+3 more)

### Community 129 - "Community 129"
Cohesion: 0.13
Nodes (6): DefaultFixedAssetService, VoucherGroup, Asset, AssetCreate, DepreciationRun, Reason

### Community 150 - "Community 150"
Cohesion: 0.18
Nodes (6): ExtractorTestConfiguration, JdbcExtractionRepository, DocumentExtractor, ExtractionRepository, OpenPeriod, Primary

## Knowledge Gaps
- **290 isolated node(s):** `paths`, `webhooks`, `components`, `$defs`, `operations` (+285 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **19 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Format` connect `Community 27` to `Community 4`, `Community 104`, `Community 73`, `Community 107`, `Community 83`, `Community 25`?**
  _High betweenness centrality (0.097) - this node is a cross-community bridge._
- **Why does `Sheet` connect `Community 4` to `Community 104`, `Community 98`, `Community 27`, `Community 7`?**
  _High betweenness centrality (0.074) - this node is a cross-community bridge._
- **Why does `test` connect `Community 3` to `Community 5`, `Community 10`, `Community 147`, `Community 150`, `Community 25`, `Community 26`, `Community 34`, `Community 49`, `Community 63`, `Community 67`, `Community 68`, `Community 70`, `Community 75`, `Community 90`, `Community 93`, `Community 100`, `Community 101`, `Community 102`, `Community 103`, `Community 104`, `Community 113`, `Community 116`, `Community 120`, `Community 124`, `Community 125`?**
  _High betweenness centrality (0.071) - this node is a cross-community bridge._
- **What connects `paths`, `webhooks`, `components` to the rest of the system?**
  _366 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.043478260869565216 - nodes in this community are weakly interconnected._
- **Should `Community 3` be split into smaller, more focused modules?**
  _Cohesion score 0.05 - nodes in this community are weakly interconnected._
- **Should `Community 4` be split into smaller, more focused modules?**
  _Cohesion score 0.055191256830601096 - nodes in this community are weakly interconnected._