package com.example.accounting.agent;

import com.example.accounting.agent.internal.AsyncAgentToolAuditService;
import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.identity.IdentityService;
import com.example.accounting.ledger.LedgerResponses;
import com.example.accounting.ledger.LedgerRequests;
import com.example.accounting.ledger.AccountExchangeService;
import com.example.accounting.ledger.AccountingStandard;
import com.example.accounting.ledger.AccountingStandardCatalog;
import com.example.accounting.ledger.LedgerBackupService;
import com.example.accounting.ledger.LedgerService;
import com.example.accounting.exchange.KingdeeExchange;
import com.example.accounting.fixedasset.FixedAssetResponses;
import com.example.accounting.fixedasset.FixedAssetService;
import com.example.accounting.documents.DocumentResponses;
import com.example.accounting.documents.DocumentService;
import com.example.accounting.documents.ExtractionResponses;
import com.example.accounting.documents.ExtractionService;
import com.example.accounting.documents.JobResponses;
import com.example.accounting.documents.JobService;
import com.example.accounting.shared.web.ApiProblemException;
import com.example.accounting.reporting.FinanceQueryRequests;
import com.example.accounting.reporting.ReportingService;
import com.example.accounting.shared.audit.AuditContext;
import com.example.accounting.voucher.VoucherRequests;
import com.example.accounting.voucher.VoucherResponses;
import com.example.accounting.voucher.VoucherService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FinanceMcpTools {

    private final CurrentUserResolver currentUserResolver;
    private final IdentityService identityService;
    private final AccountingExperienceService experienceService;
    private final LedgerService ledgerService;
    private final FixedAssetService fixedAssetService;
    private final AccountingStandardCatalog standardCatalog;
    private final LedgerBackupService backupService;
    private final AccountExchangeService accountExchange;
    private final KingdeeExchange kingdeeExchange;
    private final ReportingService reportingService;
    private final VoucherService voucherService;
    private final DocumentService documentService;
    private final ExtractionService extractionService;
    private final JobService jobService;
    private final AsyncAgentToolAuditService audits;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public FinanceMcpTools(CurrentUserResolver currentUserResolver, IdentityService identityService,
                           AccountingExperienceService experienceService,
                           LedgerService ledgerService, FixedAssetService fixedAssetService,
                           AccountingStandardCatalog standardCatalog,
                           LedgerBackupService backupService,
                           AccountExchangeService accountExchange, KingdeeExchange kingdeeExchange,
                           ReportingService reportingService, VoucherService voucherService,
                           DocumentService documentService, ExtractionService extractionService,
                           JobService jobService, AsyncAgentToolAuditService audits) {
        this.currentUserResolver = currentUserResolver;
        this.identityService = identityService;
        this.experienceService = experienceService;
        this.ledgerService = ledgerService;
        this.fixedAssetService = fixedAssetService;
        this.standardCatalog = standardCatalog;
        this.backupService = backupService;
        this.accountExchange = accountExchange;
        this.kingdeeExchange = kingdeeExchange;
        this.reportingService = reportingService;
        this.voucherService = voucherService;
        this.documentService = documentService;
        this.extractionService = extractionService;
        this.jobService = jobService;
        this.audits = audits;
    }

    @McpTool(name = "list_ledgers", description = "List ledgers")
    @PreAuthorize("isAuthenticated()")
    public List<LedgerResponses.Ledger> listLedgers() {
        UUID actorId = actor();
        return audited(actorId, "list_ledgers", null, "", () -> ledgerService.list(actorId));
    }

    @McpTool(name = "get_operator_context", description = "List MCP tools by category")
    @PreAuthorize("isAuthenticated()")
    public AgentContextResponses.OperatorContext getOperatorContext() {
        UUID actorId = actor();
        return audited(actorId, "get_operator_context", null, "", AgentContextResponses::toolCatalog);
    }

    @McpTool(name = "create_accounting_experience", description = "Create accounting experience")
    @PreAuthorize("isAuthenticated()")
    public ExperienceResponses.Experience createAccountingExperience(
            @McpToolParam(description = "Experience payload with GENERAL or LEDGER scope")
            ExperienceRequests.Create request) {
        UUID actorId = actor();
        UUID ledgerId = request == null ? null : request.ledgerId();
        return audited(actorId, "create_accounting_experience", ledgerId, String.valueOf(request),
                () -> experienceService.create(actorId, request));
    }

    @McpTool(name = "search_accounting_experiences", description = "Search accounting experiences")
    @PreAuthorize("isAuthenticated()")
    public ExperienceResponses.Page searchAccountingExperiences(
            @McpToolParam(description = "Search payload with optional ledgerId, query, tags and pagination",
                    required = false)
            ExperienceRequests.Search request) {
        UUID actorId = actor();
        UUID ledgerId = request == null ? null : request.ledgerId();
        return audited(actorId, "search_accounting_experiences", ledgerId, String.valueOf(request),
                () -> experienceService.search(actorId, request));
    }

    @McpTool(name = "update_accounting_experience", description = "Update accounting experience")
    @PreAuthorize("isAuthenticated()")
    public ExperienceResponses.Experience updateAccountingExperience(
            @McpToolParam UUID experienceId,
            @McpToolParam(description = "Update payload with expectedVersion") ExperienceRequests.Update request) {
        UUID actorId = actor();
        return audited(actorId, "update_accounting_experience", null,
                experienceId + ":" + String.valueOf(request),
                () -> experienceService.update(actorId, experienceId, request));
    }

    @McpTool(name = "archive_accounting_experience", description = "Archive accounting experience")
    @PreAuthorize("isAuthenticated()")
    public ExperienceResponses.Experience archiveAccountingExperience(
            @McpToolParam UUID experienceId,
            @McpToolParam(description = "Expected experience version") long expectedVersion) {
        UUID actorId = actor();
        return audited(actorId, "archive_accounting_experience", null,
                experienceId + ":" + expectedVersion,
                () -> experienceService.archive(actorId, experienceId, expectedVersion));
    }

    @McpTool(name = "list_periods", description = "List accounting periods available in a ledger")
    @PreAuthorize("isAuthenticated()")
    public List<LedgerResponses.Period> listPeriods(
            @McpToolParam UUID ledgerId) {
        UUID actorId = actor();
        return audited(actorId, "list_periods", ledgerId, "",
                () -> ledgerService.listPeriods(actorId, ledgerId));
    }

    @McpTool(name = "get_current_user", description = "Get current user")
    @PreAuthorize("isAuthenticated()")
    public com.example.accounting.identity.UserResponse getCurrentUser() {
        UUID actorId = actor();
        return audited(actorId, "get_current_user", null, "",
                () -> identityService.ensureUser(currentUserResolver.resolveAuthenticatedUserDetails()));
    }

    @McpTool(name = "list_accounting_standards", description = "List installed accounting standard packages")
    @PreAuthorize("isAuthenticated()")
    public List<AccountingStandard.Package> listAccountingStandards() {
        UUID actorId = actor();
        return audited(actorId, "list_accounting_standards", null, "", standardCatalog::list);
    }

    @McpTool(name = "get_accounting_standard", description = "Get accounting standard")
    @PreAuthorize("isAuthenticated()")
    public AccountingStandard.Package getAccountingStandard(
            @McpToolParam String code,
            @McpToolParam String version) {
        UUID actorId = actor();
        return audited(actorId, "get_accounting_standard", null, code + "/" + version,
                () -> standardCatalog.find(code, version).orElseThrow(() ->
                        new ApiProblemException(404, "ACCOUNTING_STANDARD_NOT_FOUND", "Accounting standard not found",
                                "The requested accounting standard version is not installed", false)));
    }

    @McpTool(name = "get_ledger", description = "Get ledger")
    @PreAuthorize("isAuthenticated()")
    public LedgerResponses.Ledger getLedger(
            @McpToolParam UUID ledgerId) {
        UUID actorId = actor();
        return audited(actorId, "get_ledger", ledgerId, "",
                () -> ledgerService.findLedger(actorId, ledgerId));
    }

    @McpTool(name = "get_ledger_context", description = "Get ledger working context")
    @PreAuthorize("isAuthenticated()")
    public AgentContextResponses.LedgerContext getLedgerContext(
            @McpToolParam UUID ledgerId) {
        UUID actorId = actor();
        return audited(actorId, "get_ledger_context", ledgerId, "", () -> new AgentContextResponses.LedgerContext(
                ledgerService.findLedger(actorId, ledgerId),
                ledgerService.role(actorId, ledgerId),
                ledgerService.listPeriods(actorId, ledgerId),
                ledgerService.listAccounts(actorId, ledgerId),
                ledgerService.listDimensionTypes(actorId, ledgerId),
                ledgerService.listCashFlowItems(actorId, ledgerId)));
    }

    @McpTool(name = "update_ledger", description = "Update a ledger name and description")
    @PreAuthorize("isAuthenticated()")
    public LedgerResponses.Ledger updateLedger(
            @McpToolParam UUID ledgerId,
            @McpToolParam(description = "Ledger name and optional business description update") LedgerRequests.Rename request) {
        UUID actorId = actor();
        return audited(actorId, "update_ledger", ledgerId, String.valueOf(request),
                () -> ledgerService.renameLedger(actorId, ledgerId, request));
    }

    @McpTool(name = "get_ledger_role", description = "Get ledger role")
    @PreAuthorize("isAuthenticated()")
    public Map<String, String> getLedgerRole(
            @McpToolParam UUID ledgerId) {
        UUID actorId = actor();
        return audited(actorId, "get_ledger_role", ledgerId, "",
                () -> Map.of("role", ledgerService.role(actorId, ledgerId).name()));
    }

    @McpTool(name = "list_accounts", description = "List all accounts in a ledger")
    @PreAuthorize("isAuthenticated()")
    public List<LedgerResponses.Account> listAccounts(
            @McpToolParam UUID ledgerId) {
        UUID actorId = actor();
        return audited(actorId, "list_accounts", ledgerId, "",
                () -> ledgerService.listAccounts(actorId, ledgerId));
    }

    @McpTool(name = "search_accounts", description = "Search accounts by code or name")
    @PreAuthorize("isAuthenticated()")
    public List<LedgerResponses.AccountSearchResult> searchAccounts(
            @McpToolParam UUID ledgerId,
            @McpToolParam(description = "Exact code/name or fuzzy search text") String query,
            @McpToolParam(description = "EXACT or FUZZY; defaults to FUZZY", required = false)
            LedgerRequests.AccountMatchMode matchMode,
            @McpToolParam(description = "Maximum 1-100 results; defaults to 20", required = false)
            Integer limit) {
        UUID actorId = actor();
        return audited(actorId, "search_accounts", ledgerId,
                query + ":" + matchMode + ":" + limit,
                () -> ledgerService.searchAccounts(actorId, ledgerId, query, matchMode, limit));
    }

    @McpTool(name = "get_account", description = "Get one account in a ledger")
    @PreAuthorize("isAuthenticated()")
    public LedgerResponses.Account getAccount(
            @McpToolParam UUID ledgerId,
            @McpToolParam UUID accountId) {
        UUID actorId = actor();
        return audited(actorId, "get_account", ledgerId, accountId.toString(),
                () -> ledgerService.findAccount(actorId, ledgerId, accountId));
    }

    @McpTool(name = "create_account", description = "Create an accounting account in a ledger")
    @PreAuthorize("isAuthenticated()")
    public LedgerResponses.Account createAccount(
            @McpToolParam UUID ledgerId,
            @McpToolParam(description = "Account creation payload") LedgerRequests.AccountCreate request) {
        UUID actorId = actor();
        return audited(actorId, "create_account", ledgerId, request.toString(),
                () -> ledgerService.createAccount(actorId, ledgerId, request));
    }

    @McpTool(name = "update_account", description = "Update an accounting account in a ledger")
    @PreAuthorize("isAuthenticated()")
    public LedgerResponses.Account updateAccount(
            @McpToolParam UUID ledgerId,
            @McpToolParam UUID accountId,
            @McpToolParam(description = "Account patch with expectedVersion") LedgerRequests.AccountPatch request) {
        UUID actorId = actor();
        return audited(actorId, "update_account", ledgerId, accountId + ":" + request,
                () -> ledgerService.updateAccount(actorId, ledgerId, accountId, request));
    }

    @McpTool(name = "delete_account", description = "Delete an accounting account from a ledger")
    @PreAuthorize("isAuthenticated()")
    public boolean deleteAccount(
            @McpToolParam UUID ledgerId,
            @McpToolParam UUID accountId,
            @McpToolParam(description = "Expected account version") long expectedVersion) {
        UUID actorId = actor();
        return audited(actorId, "delete_account", ledgerId, accountId + ":" + expectedVersion, () -> {
            ledgerService.deleteAccount(actorId, ledgerId, accountId, expectedVersion);
            return true;
        });
    }

    @McpTool(name = "update_account_code_rule", description = "Update a ledger account code rule")
    @PreAuthorize("isAuthenticated()")
    public com.example.accounting.ledger.AccountCodeRule updateAccountCodeRule(
            @McpToolParam UUID ledgerId,
            @McpToolParam(description = "Account code rule") LedgerRequests.AccountCodeRuleUpdate request) {
        UUID actorId = actor();
        return audited(actorId, "update_account_code_rule", ledgerId, request.toString(),
                () -> ledgerService.updateAccountCodeRule(actorId, ledgerId, request));
    }

    @McpTool(name = "list_cash_flow_items", description = "List cash flow items in a ledger")
    @PreAuthorize("isAuthenticated()")
    public List<LedgerResponses.CashFlowItem> listCashFlowItems(
            @McpToolParam UUID ledgerId) {
        UUID actorId = actor();
        return audited(actorId, "list_cash_flow_items", ledgerId, "",
                () -> ledgerService.listCashFlowItems(actorId, ledgerId));
    }

    @McpTool(name = "close_period", description = "Close an accounting period")
    @PreAuthorize("isAuthenticated()")
    public LedgerResponses.Period closePeriod(
            @McpToolParam UUID ledgerId,
            @McpToolParam UUID periodId,
            @McpToolParam(description = "Close reason") LedgerRequests.PeriodAction request) {
        UUID actorId = actor();
        return audited(actorId, "close_period", ledgerId, periodId + ":" + request,
                () -> ledgerService.closePeriod(actorId, ledgerId, periodId, request));
    }

    @McpTool(name = "reopen_period", description = "Reopen an accounting period")
    @PreAuthorize("isAuthenticated()")
    public LedgerResponses.Period reopenPeriod(
            @McpToolParam UUID ledgerId,
            @McpToolParam UUID periodId,
            @McpToolParam(description = "Reopen reason") LedgerRequests.PeriodAction request) {
        UUID actorId = actor();
        return audited(actorId, "reopen_period", ledgerId, periodId + ":" + request,
                () -> ledgerService.reopenPeriod(actorId, ledgerId, periodId, request));
    }

    @McpTool(name = "list_dimension_types", description = "List dimension types in a ledger")
    @PreAuthorize("isAuthenticated()")
    public List<LedgerResponses.DimensionType> listDimensionTypes(
            @McpToolParam UUID ledgerId) {
        UUID actorId = actor();
        return audited(actorId, "list_dimension_types", ledgerId, "",
                () -> ledgerService.listDimensionTypes(actorId, ledgerId));
    }

    @McpTool(name = "create_dimension_type", description = "Create a dimension type in a ledger")
    @PreAuthorize("isAuthenticated()")
    public LedgerResponses.DimensionType createDimensionType(
            @McpToolParam UUID ledgerId,
            @McpToolParam(description = "Dimension type payload") LedgerRequests.DimensionTypeCreate request) {
        UUID actorId = actor();
        return audited(actorId, "create_dimension_type", ledgerId, request.toString(),
                () -> ledgerService.createDimensionType(actorId, ledgerId, request));
    }

    @McpTool(name = "list_dimension_values", description = "List values for a dimension type")
    @PreAuthorize("isAuthenticated()")
    public List<LedgerResponses.DimensionValue> listDimensionValues(
            @McpToolParam UUID ledgerId,
            @McpToolParam UUID typeId) {
        UUID actorId = actor();
        return audited(actorId, "list_dimension_values", ledgerId, typeId.toString(),
                () -> ledgerService.listDimensionValues(actorId, ledgerId, typeId));
    }

    @McpTool(name = "create_dimension_value", description = "Create a value for a dimension type")
    @PreAuthorize("isAuthenticated()")
    public LedgerResponses.DimensionValue createDimensionValue(
            @McpToolParam UUID ledgerId,
            @McpToolParam UUID typeId,
            @McpToolParam(description = "Dimension value payload") LedgerRequests.DimensionValueCreate request) {
        UUID actorId = actor();
        return audited(actorId, "create_dimension_value", ledgerId, typeId + ":" + request,
                () -> ledgerService.createDimensionValue(actorId, ledgerId, typeId, request));
    }

    @McpTool(name = "list_opening_balances", description = "List opening balances in a ledger")
    @PreAuthorize("isAuthenticated()")
    public List<LedgerResponses.OpeningBalance> listOpeningBalances(
            @McpToolParam UUID ledgerId) {
        UUID actorId = actor();
        return audited(actorId, "list_opening_balances", ledgerId, "",
                () -> ledgerService.listOpeningBalances(actorId, ledgerId));
    }

    @McpTool(name = "replace_opening_balances", description = "Replace opening balances in a ledger")
    @PreAuthorize("isAuthenticated()")
    public List<LedgerResponses.OpeningBalance> replaceOpeningBalances(
            @McpToolParam UUID ledgerId,
            @McpToolParam(description = "Opening balance lines") List<LedgerRequests.OpeningBalanceLine> lines) {
        UUID actorId = actor();
        return audited(actorId, "replace_opening_balances", ledgerId, lines.toString(),
                () -> ledgerService.replaceOpeningBalances(actorId, ledgerId, lines));
    }

    @McpTool(name = "import_opening_balances", description = "Import opening balances")
    @PreAuthorize("isAuthenticated()")
    public List<LedgerResponses.OpeningBalance> importOpeningBalances(
            @McpToolParam UUID ledgerId,
            @McpToolParam(description = "Base64 encoded CSV content") String base64Content) {
        UUID actorId = actor();
        byte[] content = decode(base64Content, "OPENING_BALANCE_CONTENT_INVALID");
        return audited(actorId, "import_opening_balances", ledgerId, hash(base64Content),
                () -> ledgerService.importOpeningBalances(actorId, ledgerId, new ByteArrayInputStream(content)));
    }

    @McpTool(name = "import_fixed_assets", description = "Import fixed assets")
    @PreAuthorize("isAuthenticated()")
    public FixedAssetResponses.ImportResult importFixedAssets(
            @McpToolParam UUID ledgerId,
            @McpToolParam(description = "Original .xlsx workbook file name") String fileName,
            @McpToolParam(description = "Base64 encoded .xlsx workbook content") String base64Content) {
        UUID actorId = actor();
        byte[] content = decode(base64Content, "FIXED_ASSET_IMPORT_CONTENT_INVALID");
        String normalizedFileName = fileName == null || fileName.isBlank() ? "fixed-assets.xlsx" : fileName.trim();
        return audited(actorId, "import_fixed_assets", ledgerId,
                normalizedFileName + ":" + hash(base64Content),
                () -> fixedAssetService.importAssets(actorId, ledgerId,
                        new ByteArrayMultipartFile(normalizedFileName, content)));
    }

    @McpTool(name = "confirm_opening_balances", description = "Confirm opening balances in a ledger")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Integer> confirmOpeningBalances(
            @McpToolParam UUID ledgerId) {
        UUID actorId = actor();
        return audited(actorId, "confirm_opening_balances", ledgerId, "",
                () -> Map.of("confirmedCount", ledgerService.confirmOpeningBalances(actorId, ledgerId)));
    }

    @McpTool(name = "export_accounts", description = "Export accounts")
    @PreAuthorize("isAuthenticated()")
    public ExportedFile exportAccounts(
            @McpToolParam UUID ledgerId,
            @McpToolParam(description = "Export format: STANDARD or KINGDEE") AccountExchangeService.Format format) {
        UUID actorId = actor();
        return audited(actorId, "export_accounts", ledgerId, format.name(),
                () -> file("accounts-" + format.name().toLowerCase(Locale.ROOT) + ".xlsx",
                        XLSX_CONTENT_TYPE, accountExchange.export(actorId, ledgerId, format)));
    }

    @McpTool(name = "export_account_template", description = "Export an account import template as Excel")
    @PreAuthorize("isAuthenticated()")
    public ExportedFile exportAccountTemplate(
            @McpToolParam UUID ledgerId,
            @McpToolParam(description = "Template format: STANDARD or KINGDEE") AccountExchangeService.Format format) {
        UUID actorId = actor();
        return audited(actorId, "export_account_template", ledgerId, format.name(),
                () -> file("account-import-" + format.name().toLowerCase(Locale.ROOT) + ".xlsx",
                        XLSX_CONTENT_TYPE, accountExchange.template(actorId, ledgerId, format)));
    }

    @McpTool(name = "preview_account_import", description = "Preview account import")
    @PreAuthorize("isAuthenticated()")
    public AccountExchangeService.Preview previewAccountImport(
            @McpToolParam UUID ledgerId,
            @McpToolParam(description = "Import format: STANDARD or KINGDEE") AccountExchangeService.Format format,
            @McpToolParam(description = "Original workbook file name") String fileName,
            @McpToolParam(description = "Base64 encoded workbook content") String base64Content) {
        UUID actorId = actor();
        byte[] content = decode(base64Content, "ACCOUNT_IMPORT_CONTENT_INVALID");
        return audited(actorId, "preview_account_import", ledgerId, format + ":" + fileName + ":" + hash(base64Content),
                () -> accountExchange.preview(actorId, ledgerId, format, fileName, content.length,
                        new ByteArrayInputStream(content)));
    }

    @McpTool(name = "get_account_import", description = "Get an account import preview")
    @PreAuthorize("isAuthenticated()")
    public AccountExchangeService.Preview getAccountImport(
            @McpToolParam UUID ledgerId,
            @McpToolParam UUID importId) {
        UUID actorId = actor();
        return audited(actorId, "get_account_import", ledgerId, importId.toString(),
                () -> accountExchange.get(actorId, ledgerId, importId));
    }

    /**
     * Compatibility entry point for direct application callers. Multi-row import decisions must use
     * {@link #decideAccountImportRows(UUID, UUID, List)} so the MCP surface has one atomic round trip.
     */
    @Deprecated(since = "0.1", forRemoval = false)
    @PreAuthorize("isAuthenticated()")
    public AccountExchangeService.Preview decideAccountImportRow(
            @McpToolParam UUID ledgerId,
            @McpToolParam UUID importId,
            @McpToolParam int rowNo,
            @McpToolParam(description = "Import row decision") AccountExchangeService.Decision decision) {
        UUID actorId = actor();
        return audited(actorId, "decide_account_import_row", ledgerId, importId + ":" + rowNo + ":" + decision,
                () -> accountExchange.decide(actorId, ledgerId, importId, rowNo, decision));
    }

    @McpTool(name = "decide_account_import_rows", description = "Set import decisions in batch")
    @PreAuthorize("isAuthenticated()")
    public AccountExchangeService.Preview decideAccountImportRows(
            @McpToolParam UUID ledgerId,
            @McpToolParam UUID importId,
            @McpToolParam(description = "Import row decisions") List<AccountExchangeService.RowDecision> decisions) {
        UUID actorId = actor();
        return audited(actorId, "decide_account_import_rows", ledgerId, importId + ":" + decisions,
                () -> accountExchange.decideAll(actorId, ledgerId, importId, decisions));
    }

    @McpTool(name = "commit_account_import", description = "Commit a reviewed account import")
    @PreAuthorize("isAuthenticated()")
    public AccountExchangeService.Preview commitAccountImport(
            @McpToolParam UUID ledgerId,
            @McpToolParam UUID importId) {
        UUID actorId = actor();
        return audited(actorId, "commit_account_import", ledgerId, importId.toString(),
                () -> accountExchange.commit(actorId, ledgerId, importId));
    }

    @McpTool(name = "export_kingdee_vouchers", description = "Export Kingdee vouchers")
    @PreAuthorize("isAuthenticated()")
    public ExportedFile exportKingdeeVouchers(
            @McpToolParam UUID ledgerId) {
        UUID actorId = actor();
        return audited(actorId, "export_kingdee_vouchers", ledgerId, "",
                () -> file("kingdee-vouchers.xlsx", XLSX_CONTENT_TYPE,
                        kingdeeExchange.exportKingdee(actorId, ledgerId)));
    }

    @McpTool(name = "import_kingdee_vouchers", description = "Import Kingdee vouchers")
    @PreAuthorize("isAuthenticated()")
    public KingdeeExchange.ImportResult importKingdeeVouchers(
            @McpToolParam UUID ledgerId,
            @McpToolParam(description = "Base64 encoded Kingdee workbook") String base64Content,
            @McpToolParam(description = "Unique retry key, optional", required = false) String idempotencyKey) {
        UUID actorId = actor();
        byte[] content = decode(base64Content, "KINGDEE_CONTENT_INVALID");
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        return audited(actorId, "import_kingdee_vouchers", ledgerId, hash(base64Content) + ":" + key,
                () -> kingdeeExchange.importKingdee(actorId, ledgerId, key, content.length,
                        new ByteArrayInputStream(content)));
    }

    @McpTool(name = "backup_ledger", description = "Back up ledger")
    @PreAuthorize("isAuthenticated()")
    public ExportedFile backupLedger(
            @McpToolParam UUID ledgerId) {
        UUID actorId = actor();
        return audited(actorId, "backup_ledger", ledgerId, "",
                () -> file("ledger-" + ledgerId + ".aibackup",
                        "application/vnd.ai-accounting.ledger-backup+zip",
                        backupService.backup(actorId, ledgerId)));
    }

    @McpTool(name = "restore_ledger", description = "Restore ledger")
    @PreAuthorize("isAuthenticated()")
    public LedgerResponses.Ledger restoreLedger(
            @McpToolParam(description = "Base64 encoded ledger backup archive") String base64Content,
            @McpToolParam(description = "Optional restored ledger name", required = false) String name) {
        UUID actorId = actor();
        byte[] content = decode(base64Content, "LEDGER_BACKUP_CONTENT_INVALID");
        return audited(actorId, "restore_ledger", null, hash(base64Content) + ":" + name,
                () -> backupService.restore(currentUserResolver.resolveAuthenticatedUserDetails(), name,
                        content.length, new ByteArrayInputStream(content)));
    }

    @McpTool(name = "list_documents", description = "List documents in a ledger")
    @PreAuthorize("isAuthenticated()")
    public List<DocumentResponses.Document> listDocuments(
            @McpToolParam UUID ledgerId,
            @McpToolParam(description = "Maximum number of documents", required = false) Integer limit,
            @McpToolParam(description = "Number of documents to skip", required = false) Integer offset) {
        UUID actorId = actor();
        int actualLimit = limit == null ? 50 : limit;
        int actualOffset = offset == null ? 0 : offset;
        return audited(actorId, "list_documents", ledgerId, actualLimit + ":" + actualOffset,
                () -> documentService.list(actorId, ledgerId, actualLimit, actualOffset));
    }

    @McpTool(name = "get_document", description = "Get one document in a ledger")
    @PreAuthorize("isAuthenticated()")
    public DocumentResponses.Document getDocument(
            @McpToolParam UUID ledgerId,
            @McpToolParam UUID documentId) {
        UUID actorId = actor();
        return audited(actorId, "get_document", ledgerId, documentId.toString(),
                () -> documentService.find(actorId, ledgerId, documentId));
    }

    @McpTool(name = "download_document", description = "Download document")
    @PreAuthorize("isAuthenticated()")
    public ExportedFile downloadDocument(
            @McpToolParam UUID ledgerId,
            @McpToolParam UUID documentId) {
        UUID actorId = actor();
        return audited(actorId, "download_document", ledgerId, documentId.toString(), () -> {
            DocumentResponses.Content content = documentService.content(actorId, ledgerId, documentId);
            return file(content.fileName(), content.contentType(), content.bytes());
        });
    }

    @McpTool(name = "list_document_extractions", description = "List extraction results for a document")
    @PreAuthorize("isAuthenticated()")
    public List<ExtractionResponses.Extraction> listDocumentExtractions(
            @McpToolParam UUID ledgerId,
            @McpToolParam UUID documentId) {
        UUID actorId = actor();
        return audited(actorId, "list_document_extractions", ledgerId, documentId.toString(),
                () -> extractionService.list(actorId, ledgerId, documentId));
    }

    @McpTool(name = "create_voucher_draft_from_document_standard",
            description = "Create voucher from document")
    @PreAuthorize("isAuthenticated()")
    public VoucherResponses.Voucher createVoucherDraftFromDocumentStandard(
            @McpToolParam UUID ledgerId,
            @McpToolParam UUID documentId) {
        UUID actorId = actor();
        return audited(actorId, "create_voucher_draft_from_document_standard", ledgerId, documentId.toString(),
                () -> extractionService.createVoucherDraft(actorId, ledgerId, documentId));
    }

    @McpTool(name = "ensure_account", description = "Ensure account exists")
    @PreAuthorize("isAuthenticated()")
    public LedgerResponses.Account ensureAccount(
            @McpToolParam UUID ledgerId,
            @McpToolParam(description = "Account code, name, category, and normal balance")
            LedgerRequests.AccountCreate request) {
        UUID actorId = actor();
        return audited(actorId, "ensure_account", ledgerId, request.toString(),
                () -> ledgerService.ensureAgentAccount(actorId, ledgerId, request));
    }

    @McpTool(name = "finance_query", description = "Run one whitelisted finance report query")
    @PreAuthorize("isAuthenticated()")
    public Object financeQuery(
            @McpToolParam UUID ledgerId,
            @McpToolParam(description = "One of trial_balance, balance_sheet, income_statement, general_ledger, sub_ledger") String report,
            @McpToolParam(description = "Accounting period code, or null for all periods", required = false) String periodCode) {
        UUID actorId = actor();
        return audited(actorId, "finance_query", ledgerId, report + ":" + periodCode,
                () -> queryReport(actorId, ledgerId, report, periodCode, false));
    }

    @McpTool(name = "export_report", description = "Export finance report")
    @PreAuthorize("isAuthenticated()")
    public ExportedFile exportReport(
            @McpToolParam UUID ledgerId,
            @McpToolParam(description = "One of trial_balance, balance_sheet, income_statement, general_ledger, sub_ledger")
            String report,
            @McpToolParam(description = "Accounting period code, or null for all periods", required = false)
            String periodCode,
            @McpToolParam(description = "Include parent accounts for trial_balance", required = false)
            boolean includeParents) {
        UUID actorId = actor();
        String normalizedPeriod = periodCode == null || periodCode.isBlank() ? null : periodCode.trim();
        return audited(actorId, "export_report", ledgerId, report + ":" + normalizedPeriod + ":" + includeParents,
                () -> {
                    Object data = queryReport(actorId, ledgerId, report, normalizedPeriod, includeParents);
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("ledgerId", ledgerId);
                    payload.put("report", report);
                    payload.put("periodCode", normalizedPeriod);
                    payload.put("includeParents", includeParents);
                    payload.put("data", data);
                    try {
                        return file(report.replace('_', '-') + "-"
                                        + (normalizedPeriod == null ? "all" : normalizedPeriod) + ".json",
                                "application/json; charset=UTF-8",
                                objectMapper.writeValueAsBytes(payload));
                    } catch (IOException exception) {
                        throw new ApiProblemException(500, "REPORT_EXPORT_FAILED", "Report export failed",
                                "The report file could not be generated", false);
                    }
                });
    }

    @McpTool(name = "finance_query_advanced", description = "Run advanced finance query")
    @PreAuthorize("isAuthenticated()")
    public List<com.example.accounting.reporting.ReportResponses.FinanceQueryLine> financeQueryAdvanced(
            @McpToolParam UUID ledgerId,
            @McpToolParam(description = "Metric: DEBIT, CREDIT, NET, or BALANCE") String metric,
            @McpToolParam(description = "Starting accounting period, optional", required = false) String periodFrom,
            @McpToolParam(description = "Ending accounting period, optional", required = false) String periodTo,
            @McpToolParam(description = "Group by ACCOUNT, MONTH, CURRENCY, or DIMENSION") List<String> groupBy,
            @McpToolParam(description = "Optional accountCodes and currency filters", required = false)
            FinanceQueryRequests.Filters filters) {
        UUID actorId = actor();
        FinanceQueryRequests.Query request = new FinanceQueryRequests.Query(
                metric, periodFrom, periodTo, groupBy, filters);
        return audited(actorId, "finance_query_advanced", ledgerId, request.toString(),
                () -> reportingService.financeQuery(actorId, ledgerId, request));
    }

    @McpTool(name = "get_voucher", description = "Get voucher")
    @PreAuthorize("isAuthenticated()")
    public VoucherResponses.Voucher getVoucher(
            @McpToolParam UUID ledgerId,
            @McpToolParam UUID voucherId) {
        UUID actorId = actor();
        return audited(actorId, "get_voucher", ledgerId, voucherId.toString(),
                () -> voucherService.find(actorId, ledgerId, voucherId));
    }

    @McpTool(name = "list_vouchers", description = "List vouchers")
    @PreAuthorize("isAuthenticated()")
    public List<VoucherResponses.Voucher> listVouchers(
            @McpToolParam UUID ledgerId,
            @McpToolParam(description = "Exact accounting period code in YYYY-MM format", required = false)
            String periodCode,
            @McpToolParam(description = "Inclusive voucher date lower bound in YYYY-MM-DD format", required = false)
            LocalDate startDate,
            @McpToolParam(description = "Inclusive voucher date upper bound in YYYY-MM-DD format", required = false)
            LocalDate endDate,
            @McpToolParam(description = "Keyword matched against voucher type, number, and summaries", required = false)
            String keyword,
            @McpToolParam(description = "Maximum number of vouchers", required = false) Integer limit,
            @McpToolParam(description = "Number of vouchers to skip", required = false) Integer offset) {
        UUID actorId = actor();
        int actualLimit = limit == null ? 100 : limit;
        int actualOffset = offset == null ? 0 : offset;
        VoucherRequests.Search search = new VoucherRequests.Search(periodCode, startDate, endDate, keyword);
        return audited(actorId, "list_vouchers", ledgerId,
                periodCode + ":" + startDate + ":" + endDate + ":" + keyword + ":" + actualLimit + ":" + actualOffset,
                () -> voucherService.list(actorId, ledgerId, search, actualLimit, actualOffset));
    }

    @McpTool(name = "create_voucher", description = "Save, automatically approve, and post a voucher")
    @PreAuthorize("isAuthenticated()")
    public VoucherResponses.Voucher createVoucher(
            @McpToolParam(description = "Voucher payload") VoucherRequests.Create request,
            @McpToolParam UUID ledgerId,
            @McpToolParam(description = "Unique retry key, optional", required = false) String idempotencyKey) {
        UUID actorId = actor();
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        return audited(actorId, "create_voucher", ledgerId, request + ":" + key,
                () -> voucherService.create(actorId, ledgerId, request, key));
    }

    @McpTool(name = "update_voucher", description = "Update voucher draft")
    @PreAuthorize("isAuthenticated()")
    public VoucherResponses.Voucher updateVoucher(
            @McpToolParam UUID ledgerId,
            @McpToolParam UUID voucherId,
            @McpToolParam(description = "Voucher update payload") VoucherRequests.Update request) {
        UUID actorId = actor();
        return audited(actorId, "update_voucher", ledgerId, voucherId + ":" + request,
                () -> voucherService.update(actorId, ledgerId, voucherId, request));
    }

    @McpTool(name = "validate_voucher_standard", description = "Validate voucher as owner/editor")
    @PreAuthorize("isAuthenticated()")
    public VoucherResponses.Voucher validateVoucherStandard(
            @McpToolParam UUID ledgerId,
            @McpToolParam UUID voucherId) {
        UUID actorId = actor();
        return audited(actorId, "validate_voucher_standard", ledgerId, voucherId.toString(),
                () -> voucherService.validate(actorId, ledgerId, voucherId));
    }

    @McpTool(name = "submit_voucher", description = "Submit a validated voucher for approval")
    @PreAuthorize("isAuthenticated()")
    public VoucherResponses.Voucher submitVoucher(
            @McpToolParam UUID ledgerId,
            @McpToolParam UUID voucherId) {
        UUID actorId = actor();
        return audited(actorId, "submit_voucher", ledgerId, voucherId.toString(),
                () -> voucherService.submit(actorId, ledgerId, voucherId));
    }

    @McpTool(name = "approve_voucher", description = "Approve a submitted voucher")
    @PreAuthorize("isAuthenticated()")
    public VoucherResponses.Voucher approveVoucher(
            @McpToolParam UUID ledgerId,
            @McpToolParam UUID voucherId,
            @McpToolParam(description = "Approval comment") String comment) {
        UUID actorId = actor();
        return audited(actorId, "approve_voucher", ledgerId, voucherId + ":" + comment,
                () -> voucherService.approve(actorId, ledgerId, voucherId, comment));
    }

    @McpTool(name = "reject_voucher", description = "Reject a submitted voucher")
    @PreAuthorize("isAuthenticated()")
    public VoucherResponses.Voucher rejectVoucher(
            @McpToolParam UUID ledgerId,
            @McpToolParam UUID voucherId,
            @McpToolParam(description = "Rejection comment") String comment) {
        UUID actorId = actor();
        return audited(actorId, "reject_voucher", ledgerId, voucherId + ":" + comment,
                () -> voucherService.reject(actorId, ledgerId, voucherId, comment));
    }

    @McpTool(name = "post_voucher_standard", description = "Post voucher as owner/editor")
    @PreAuthorize("isAuthenticated()")
    public VoucherResponses.Voucher postVoucherStandard(
            @McpToolParam UUID ledgerId,
            @McpToolParam UUID voucherId) {
        UUID actorId = actor();
        return audited(actorId, "post_voucher_standard", ledgerId, voucherId.toString(),
                () -> voucherService.post(actorId, ledgerId, voucherId));
    }

    @McpTool(name = "delete_voucher", description = "Delete a voucher")
    @PreAuthorize("isAuthenticated()")
    public boolean deleteVoucher(
            @McpToolParam UUID ledgerId,
            @McpToolParam UUID voucherId) {
        UUID actorId = actor();
        return audited(actorId, "delete_voucher", ledgerId, voucherId.toString(), () -> {
            voucherService.delete(actorId, ledgerId, voucherId);
            return true;
        });
    }

    @McpTool(name = "list_voucher_revisions", description = "List revisions for a voucher")
    @PreAuthorize("isAuthenticated()")
    public List<VoucherResponses.Revision> listVoucherRevisions(
            @McpToolParam UUID ledgerId,
            @McpToolParam UUID voucherId) {
        UUID actorId = actor();
        return audited(actorId, "list_voucher_revisions", ledgerId, voucherId.toString(),
                () -> voucherService.listRevisions(actorId, ledgerId, voucherId));
    }

    @McpTool(name = "restore_voucher_revision", description = "Restore a voucher revision")
    @PreAuthorize("isAuthenticated()")
    public VoucherResponses.Voucher restoreVoucherRevision(
            @McpToolParam UUID ledgerId,
            @McpToolParam UUID voucherId,
            @McpToolParam int revision) {
        UUID actorId = actor();
        return audited(actorId, "restore_voucher_revision", ledgerId, voucherId + ":" + revision,
                () -> voucherService.restoreRevision(actorId, ledgerId, voucherId, revision));
    }

    @McpTool(name = "create_voucher_draft", description = "Create voucher with agent flow")
    @PreAuthorize("isAuthenticated()")
    public VoucherResponses.Voucher createVoucherDraft(
            @McpToolParam(description = "Draft voucher payload") VoucherRequests.Create request,
            @McpToolParam UUID ledgerId,
            @McpToolParam(description = "Unique retry key for this draft creation") String idempotencyKey) {
        UUID actorId = actor();
        String key = requiredIdempotencyKey(idempotencyKey);
        return audited(actorId, "create_voucher_draft", ledgerId, request + ":" + key,
                () -> voucherService.createAgentDraft(actorId, ledgerId, request, key));
    }

    @McpTool(name = "validate_voucher", description = "Validate a voucher without posting it")
    @PreAuthorize("isAuthenticated()")
    public VoucherResponses.Voucher validateVoucher(
            @McpToolParam UUID ledgerId,
            @McpToolParam UUID voucherId) {
        UUID actorId = actor();
        return audited(actorId, "validate_voucher", ledgerId, voucherId.toString(),
                () -> voucherService.validateAgentDraft(actorId, ledgerId, voucherId));
    }

    @McpTool(name = "post_voucher", description = "Post voucher idempotently")
    @PreAuthorize("isAuthenticated()")
    public VoucherResponses.Voucher postVoucher(
            @McpToolParam UUID ledgerId,
            @McpToolParam UUID voucherId) {
        UUID actorId = actor();
        return audited(actorId, "post_voucher", ledgerId, voucherId.toString(),
                () -> voucherService.postAgentVoucher(actorId, ledgerId, voucherId));
    }

    @McpTool(name = "upload_document", description = "Upload document")
    @PreAuthorize("isAuthenticated()")
    public DocumentResponses.Document uploadDocument(
            @McpToolParam UUID ledgerId,
            @McpToolParam(description = "Original file name") String fileName,
            @McpToolParam(description = "MIME type") String contentType,
            @McpToolParam(description = "Base64 file content") String base64Content,
            @McpToolParam(description = "Unique retry key for this upload") String idempotencyKey) {
        UUID actorId = actor();
        String key = requiredIdempotencyKey(idempotencyKey);
        // ponytail: MCP JSON base64 buffers payload; REST upload remains streaming until binary MCP transport is needed.
        byte[] content;
        try {
            content = Base64.getDecoder().decode(base64Content);
        } catch (IllegalArgumentException exception) {
            throw new ApiProblemException(400, "DOCUMENT_CONTENT_INVALID", "Invalid document content",
                    "The document content must be base64", false);
        }
        return audited(actorId, "upload_document", ledgerId,
                fileName + ":" + contentType + ":" + hash(base64Content) + ":" + key,
                () -> documentService.upload(actorId, ledgerId, fileName, contentType, content.length,
                        new ByteArrayInputStream(content), key));
    }

    @McpTool(name = "extract_document", description = "Extract document data")
    @PreAuthorize("isAuthenticated()")
    public ExtractionResponses.Extraction extractDocument(
            @McpToolParam UUID ledgerId,
            @McpToolParam UUID documentId) {
        UUID actorId = actor();
        return audited(actorId, "extract_document", ledgerId, documentId.toString(),
                () -> extractionService.extract(actorId, ledgerId, documentId));
    }

    @McpTool(name = "get_job_status", description = "Get the status of a background job")
    @PreAuthorize("isAuthenticated()")
    public JobResponses.Job getJobStatus(
            @McpToolParam UUID ledgerId,
            @McpToolParam UUID jobId) {
        UUID actorId = actor();
        return audited(actorId, "get_job_status", ledgerId, jobId.toString(),
                () -> jobService.find(actorId, ledgerId, jobId));
    }

    @McpTool(name = "create_voucher_draft_from_document",
            description = "Create voucher from extraction")
    @PreAuthorize("isAuthenticated()")
    public VoucherResponses.Voucher createVoucherDraftFromDocument(
            @McpToolParam UUID ledgerId,
            @McpToolParam UUID documentId) {
        UUID actorId = actor();
        return audited(actorId, "create_voucher_draft_from_document", ledgerId, documentId.toString(),
                () -> extractionService.createAgentVoucherDraft(actorId, ledgerId, documentId));
    }

    private UUID actor() {
        return currentUserResolver.resolveAuthenticatedUser();
    }

    private Object queryReport(UUID actorId, UUID ledgerId, String report, String periodCode,
                               boolean includeParents) {
        if ("trial_balance".equals(report)) {
            return reportingService.trialBalance(actorId, ledgerId, periodCode, includeParents);
        }
        return switch (report) {
            case "balance_sheet" -> reportingService.balanceSheet(actorId, ledgerId, periodCode);
            case "income_statement" -> reportingService.incomeStatement(actorId, ledgerId, periodCode);
            case "general_ledger" -> reportingService.generalLedger(actorId, ledgerId, periodCode);
            case "sub_ledger" -> reportingService.subLedger(actorId, ledgerId, periodCode);
            default -> throw new ApiProblemException(422, "FINANCE_QUERY_INVALID", "Invalid finance query",
                    "The report is not in the whitelist", false);
        };
    }

    private ExportedFile file(String fileName, String contentType, byte[] content) {
        return new ExportedFile(fileName, contentType, Base64.getEncoder().encodeToString(content), content.length);
    }

    private byte[] decode(String content, String errorCode) {
        if (content == null) {
            throw new ApiProblemException(400, errorCode, "Invalid base64 content", "The file content is required", false);
        }
        try {
            return Base64.getDecoder().decode(content);
        } catch (IllegalArgumentException exception) {
            throw new ApiProblemException(400, errorCode, "Invalid base64 content",
                    "The file content must be base64", false);
        }
    }

    private record ByteArrayMultipartFile(String fileName, byte[] content) implements MultipartFile {

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return fileName;
        }

        @Override
        public String getContentType() {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }

        @Override
        public boolean isEmpty() {
            return content.length == 0;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public byte[] getBytes() {
            return content.clone();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public void transferTo(File destination) throws IOException {
            Files.write(destination.toPath(), content);
        }
    }

    public record ExportedFile(String fileName, String contentType, String base64Content, long byteLength) {
    }

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private String requiredIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ApiProblemException(400, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency key required",
                    "MCP write tools require a non-blank idempotency key", false);
        }
        return key.trim();
    }

    private <T> T audited(UUID actorId, String toolName, UUID ledgerId, String input, Supplier<T> action) {
        String traceId = AuditContext.traceId().orElseGet(() -> UUID.randomUUID().toString());
        String inputHash = hash(input);
        long started = System.nanoTime();
        try {
            T result = action.get();
            audits.recordSuccess(toolName, ledgerId, actorId, traceId, inputHash, elapsedMs(started));
            return result;
        } catch (RuntimeException exception) {
            String errorCode = exception instanceof ApiProblemException problem
                    ? problem.code() : "UNEXPECTED_ERROR";
            audits.recordFailure(toolName, ledgerId, actorId, traceId, inputHash, errorCode, elapsedMs(started));
            throw exception;
        }
    }

    private long elapsedMs(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash MCP audit value", exception);
        }
    }
}
