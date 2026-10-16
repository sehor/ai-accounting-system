package com.example.accounting.ledger;

import com.example.accounting.ledger.internal.persistence.AccountManagementRepository;
import com.example.accounting.ledger.internal.persistence.AccountImportRepository;
import com.example.accounting.shared.web.ApiProblemException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountExchangeService {

    public static final long MAX_BYTES = 10L * 1024 * 1024;
    public static final int MAX_ACCOUNTS = 10_000;
    private static final ZoneId ACCOUNTING_ZONE = ZoneId.of("Asia/Shanghai");
    private static final List<String> STANDARD_HEADERS = List.of(
            "Code", "Name", "ParentCode", "Category", "NormalBalance", "Status",
            "CashFlowRequired", "DefaultCashFlowItemCode", "QuantityEnabled", "UnitName",
            "StandardAccountKey");
    private static final List<String> KINGDEE_HEADERS = List.of(
            "科目代码", "科目名称", "上级科目代码", "科目类别", "余额方向", "状态",
            "现金流必填", "数量核算", "单位");
    private static final List<String> NATIVE_KINGDEE_HEADERS = List.of(
            "编码", "名称", "类别", "余额方向");

    private static final Map<String, String> KINGDEE_SME_KEYS_BY_NAME = Map.ofEntries(
            Map.entry("消耗性生物资产", "ASSET.CONSUMABLE_BIOLOGICAL_ASSET"),
            Map.entry("在建工程", "ASSET.CONSTRUCTION_IN_PROGRESS"),
            Map.entry("工程物资", "ASSET.CONSTRUCTION_MATERIAL"),
            Map.entry("生产性生物资产累计折旧", "ASSET.PRODUCTIVE_BIOLOGICAL_ACCUMULATED_DEPRECIATION"),
            Map.entry("长期待摊费用", "ASSET.LONG_TERM_DEFERRED_EXPENSE"),
            Map.entry("待处理财产损溢", "ASSET.PENDING_PROPERTY_LOSS_SURPLUS"),
            Map.entry("制造费用", "COST.MANUFACTURING_OVERHEAD"),
            Map.entry("工程施工", "COST.CONSTRUCTION"),
            Map.entry("机械作业", "COST.MECHANICAL_OPERATION"),
            Map.entry("劳务成本", "COST.LABOR"),
            Map.entry("其他业务成本", "EXPENSE.OTHER_BUSINESS_COST"),
            Map.entry("以前年度损益调整", "EXPENSE.PRIOR_YEAR_ADJUSTMENT"));

    private final LedgerService ledgers;
    private final LedgerAccessService access;
    private final AccountManagementRepository accounts;
    private final AccountImportRepository imports;
    private final AccountAiMapper aiMapper;
    private final AccountingStandardCatalog standards;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    public AccountExchangeService(
            LedgerService ledgers, LedgerAccessService access,
            AccountManagementRepository accounts, AccountImportRepository imports, AccountAiMapper aiMapper,
            AccountingStandardCatalog standards) {
        this.ledgers = ledgers;
        this.access = access;
        this.accounts = accounts;
        this.imports = imports;
        this.aiMapper = aiMapper;
        this.standards = standards;
    }

    public AccountExchangeService(
            LedgerService ledgers, LedgerAccessService access,
            AccountManagementRepository accounts, AccountImportRepository imports, AccountAiMapper aiMapper) {
        this(ledgers, access, accounts, imports, aiMapper, new AccountingStandardCatalog());
    }

    @Transactional(readOnly = true)
    public byte[] template(UUID actorId, UUID ledgerId, Format format) {
        LedgerResponses.Ledger ledger = ledgers.findLedger(actorId, ledgerId);
        return workbook(ledger, accounts.codeRule(ledgerId), format, List.of(),
                ledgers.listDimensionTypes(actorId, ledgerId));
    }

    @Transactional(readOnly = true)
    public byte[] export(UUID actorId, UUID ledgerId, Format format) {
        return export(actorId, ledgerId, format, null);
    }

    @Transactional(readOnly = true)
    public byte[] export(UUID actorId, UUID ledgerId, Format format, UUID createdInPeriodId) {
        LedgerResponses.Ledger ledger = ledgers.findLedger(actorId, ledgerId);
        List<LedgerResponses.Account> exportedAccounts;
        if (createdInPeriodId == null) {
            exportedAccounts = ledgers.listAccounts(actorId, ledgerId);
        } else {
            LedgerResponses.Period period = ledgers.listPeriods(actorId, ledgerId).stream()
                    .filter(candidate -> candidate.id().equals(createdInPeriodId))
                    .findFirst()
                    .orElseThrow(() -> problem(404, "PERIOD_NOT_FOUND", "Period not found",
                            "The period is not available to this ledger"));
            OffsetDateTime startInclusive = period.startDate().atStartOfDay(ACCOUNTING_ZONE).toOffsetDateTime();
            OffsetDateTime endExclusive = period.endDate().plusDays(1)
                    .atStartOfDay(ACCOUNTING_ZONE).toOffsetDateTime();
            exportedAccounts = accounts.listCreatedBetween(ledgerId, startInclusive, endExclusive);
        }
        return workbook(ledger, accounts.codeRule(ledgerId), format,
                exportedAccounts, ledgers.listDimensionTypes(actorId, ledgerId));
    }

    @Transactional(readOnly = true)
    public ReconstructionEligibilityResponse checkReconstructionEligibility(UUID actorId, UUID ledgerId) {
        ledgers.findLedger(actorId, ledgerId);
        List<ImportBlocker> blockers = accounts.checkReconstructionEligibility(ledgerId);
        return new ReconstructionEligibilityResponse(blockers.isEmpty(), blockers);
    }

    @Transactional
    public Preview preview(UUID actorId, UUID ledgerId, Format format, String filename,
                           long declaredSize, InputStream input) {
        return preview(actorId, ledgerId, format, ImportMode.APPEND, null, filename, declaredSize, input);
    }

    @Transactional
    public Preview preview(UUID actorId, UUID ledgerId, Format format, ImportMode mode,
                           AccountCodeRule customRule, String filename,
                           long declaredSize, InputStream input) {
        requireWrite(actorId, ledgerId);
        ImportMode effectiveMode = mode != null ? mode : ImportMode.APPEND;
        String lowerName = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".xlsx") && (format != Format.KINGDEE || !lowerName.endsWith(".xls"))) {
            throw problem(422, "ACCOUNT_IMPORT_FILE_INVALID", "Invalid account workbook",
                    format == Format.KINGDEE
                            ? "Only .xls or .xlsx workbooks are accepted"
                            : "Only .xlsx workbooks are accepted");
        }
        if (declaredSize < 0 || declaredSize > MAX_BYTES) {
            throw tooLarge();
        }
        byte[] content = readBounded(input);
        boolean xlsx = content.length >= 4 && content[0] == 'P' && content[1] == 'K'
                && content[2] == 3 && content[3] == 4;
        boolean xls = content.length >= 8
                && (content[0] & 0xff) == 0xd0 && (content[1] & 0xff) == 0xcf
                && (content[2] & 0xff) == 0x11 && (content[3] & 0xff) == 0xe0
                && (content[4] & 0xff) == 0xa1 && (content[5] & 0xff) == 0xb1
                && (content[6] & 0xff) == 0x1a && (content[7] & 0xff) == 0xe1;
        if (!xlsx && (format != Format.KINGDEE || !xls)) {
            throw workbookProblem("The uploaded file is not a supported Excel workbook");
        }

        AccountCodeRule currentRule = accounts.codeRule(ledgerId);
        if (effectiveMode == ImportMode.APPEND && customRule != null && !customRule.equals(currentRule)) {
            throw problem(422, "APPEND_CODE_RULE_IMMUTABLE", "Cannot alter code rule in APPEND mode",
                    "增量追加模式必须使用账套现有编码规则，不可修改编码规则");
        }

        String sha256 = sha256(content);
        // Each preview task is an immutable snapshot with a unique ID to prevent cross-tab / cross-mode corruption.
        UUID importId = UUID.randomUUID();

        // Raw parsing without rule binding
        List<ParsedAccount> parsed = parseRaw(content, format);
        if (parsed.isEmpty()) {
            throw workbookProblem("The uploaded workbook contains no account rows");
        }

        AccountCodeRule targetRule;
        Map<String, String> ruleSources;
        List<String> warnings = new ArrayList<>();
        if (effectiveMode == ImportMode.OVERWRITE) {
            RuleInference inference = inferCodeRule(parsed, currentRule, customRule, format);
            targetRule = inference.rule();
            ruleSources = inference.sources();
            warnings.addAll(inference.warnings());
        } else {
            targetRule = currentRule;
            ruleSources = Map.of("level2", "CURRENT_RULE", "level3", "CURRENT_RULE", "level4", "CURRENT_RULE");
        }

        LedgerResponses.Ledger ledger = ledgers.findLedger(actorId, ledgerId);
        List<LedgerResponses.Account> existingAccountsList = ledgers.listAccounts(actorId, ledgerId);
        Map<String, LedgerResponses.Account> existingAccounts = existingAccountsList.stream()
                .collect(Collectors.toMap(LedgerResponses.Account::code, account -> account));

        // Kingdee 4-column category/direction inheritance
        Map<String, ParsedAccount> byCode = parsed.stream()
                .collect(Collectors.toMap(ParsedAccount::code, row -> row, (a, b) -> a));
        parsed.stream().sorted(Comparator.comparingInt(row -> row.code().length())).forEach(row -> {
            String inferredParent = targetRule.parentCode(row.code()).orElse("");
            ParsedAccount parent = byCode.get(inferredParent);
            if (parent != null) {
                if (row.cleaned().get("category").isBlank() || !AccountCategory.isValid(row.cleaned().get("category"))) {
                    row.cleaned().put("category", parent.cleaned().get("category"));
                    row.issues().remove("ERROR:INVALID_CATEGORY");
                }
                if (row.cleaned().get("normalBalance").isBlank() || !Set.of("DEBIT", "CREDIT").contains(row.cleaned().get("normalBalance"))) {
                    row.cleaned().put("normalBalance", parent.cleaned().get("normalBalance"));
                    row.issues().remove("ERROR:INVALID_NORMAL_BALANCE");
                }
            }
        });

        // Duplicate code detection inside file
        Set<String> duplicateCodes = parsed.stream().collect(Collectors.groupingBy(
                        ParsedAccount::code, Collectors.counting())).entrySet().stream()
                .filter(entry -> !entry.getKey().isBlank() && entry.getValue() > 1)
                .map(Map.Entry::getKey).collect(Collectors.toSet());

        Set<String> availableParentCodes = effectiveMode == ImportMode.APPEND
                ? new HashSet<>(existingAccounts.keySet())
                : new HashSet<>();
        parsed.forEach(row -> availableParentCodes.add(row.code()));

        // Validate each row against targetRule and mode
        for (ParsedAccount account : parsed) {
            String code = account.code();
            String name = account.cleaned().get("name");
            int level = targetRule.levelOf(code);

            if (duplicateCodes.contains(code)) {
                account.issues().add("ERROR:DUPLICATE_CODE");
            }
            if (name == null || name.isBlank()) {
                account.issues().add("ERROR:NAME_REQUIRED");
            } else if (name.contains("-")) {
                account.issues().add("ERROR:INVALID_NAME");
            }
            if (!AccountCategory.isValid(account.cleaned().get("category"))) {
                account.issues().add("ERROR:INVALID_CATEGORY");
            }
            if (!Set.of("DEBIT", "CREDIT").contains(account.cleaned().get("normalBalance"))) {
                account.issues().add("ERROR:INVALID_NORMAL_BALANCE");
            }
            String rowStatus = account.cleaned().get("status");
            if (rowStatus != null && !rowStatus.isBlank() && !Set.of("ACTIVE", "INACTIVE").contains(rowStatus)) {
                account.issues().add("ERROR:INVALID_STATUS");
            }
            if (bool(account.cleaned().get("quantityEnabled"))
                    && (account.cleaned().get("unitName") == null || account.cleaned().get("unitName").isBlank())) {
                account.issues().add("ERROR:UNIT_REQUIRED");
            }

            if (level == 0) {
                account.issues().add("ERROR:INVALID_CODE");
            } else if (level > 1) {
                String inferredParent = targetRule.parentCode(code).orElse("");
                String explicitParent = account.cleaned().get("parentCode");
                if (explicitParent != null && !explicitParent.isBlank() && !explicitParent.equals(inferredParent)) {
                    account.issues().add("ERROR:PARENT_CODE_MISMATCH");
                }
                if (!availableParentCodes.contains(inferredParent)) {
                    account.issues().add("ERROR:PARENT_NOT_FOUND");
                } else if (effectiveMode == ImportMode.APPEND) {
                    LedgerResponses.Account existingParent = existingAccounts.get(inferredParent);
                    if (existingParent != null && existingParent.hasBusinessUsage()) {
                        account.issues().add("ERROR:USED_ACCOUNT_CANNOT_BECOME_PARENT");
                    }
                }

                // Check hierarchy attribute inheritance with parent
                ParsedAccount fileParent = byCode.get(inferredParent);
                String parentCategory = fileParent != null ? fileParent.cleaned().get("category")
                        : existingAccounts.containsKey(inferredParent) ? existingAccounts.get(inferredParent).category() : null;
                String parentNormalBalance = fileParent != null ? fileParent.cleaned().get("normalBalance")
                        : existingAccounts.containsKey(inferredParent) ? existingAccounts.get(inferredParent).normalBalance() : null;

                if (parentCategory != null && !account.cleaned().get("category").isBlank()
                        && !parentCategory.equals(account.cleaned().get("category"))) {
                    account.issues().add("ERROR:CATEGORY_PARENT_MISMATCH");
                }
                if (parentNormalBalance != null && !account.cleaned().get("normalBalance").isBlank()
                        && !parentNormalBalance.equals(account.cleaned().get("normalBalance"))) {
                    account.issues().add("ERROR:NORMAL_BALANCE_PARENT_MISMATCH");
                }
            }

            if (effectiveMode == ImportMode.APPEND && existingAccounts.containsKey(code)) {
                account.issues().add("ERROR:CODE_EXISTS");
            }
        }

        // Same-parent name uniqueness check
        validateNameUniqueness(parsed, targetRule, effectiveMode, existingAccounts);

        // Standard account keys mapping
        if (effectiveMode == ImportMode.OVERWRITE) {
            applyStandardMappings(format, ledger, targetRule, parsed, Map.of());
        } else {
            applyStandardMappings(format, ledger, targetRule, parsed, existingAccounts);
        }

        // Ledger-level qualification check
        List<ImportBlocker> blockers = effectiveMode == ImportMode.OVERWRITE
                ? accounts.checkReconstructionEligibility(ledgerId)
                : List.of();

        if (format == Format.KINGDEE) {
            warnings.add("金蝶格式未包含现金流和辅助核算配置，导入后将采用默认设置");
        }

        long ledgerVersion = accounts.ledgerVersion(ledgerId);
        int insertCount = parsed.size();
        int deleteCount = effectiveMode == ImportMode.OVERWRITE ? existingAccountsList.size() : 0;
        int errorCount = blockers.size() + (int) parsed.stream()
                .filter(row -> row.issues().stream().anyMatch(i -> i.startsWith("ERROR:"))).count();

        try {
            String storedFilename = filename.substring(Math.max(0, filename.length() - 255));
            String codeRuleSourcesJson = json(ruleSources);
            String blockersJson = json(blockers);

            imports.create(importId, ledgerId, format.name(), effectiveMode.name(), ledgerVersion,
                    storedFilename, sha256, parsed.size(), errorCount, insertCount, deleteCount,
                    targetRule, codeRuleSourcesJson, blockersJson, "NOT_CONFIGURED", actorId);

            int rowNo = 1;
            for (ParsedAccount account : parsed) {
                imports.addRow(importId, rowNo++, json(account.raw()), json(account.cleaned()), account.code(),
                        null, null, "CREATE", BigDecimal.ONE, json(account.issues()));
            }
        } catch (DataIntegrityViolationException exception) {
            throw problem(409, "ACCOUNT_IMPORT_CONFLICT", "Account import conflict",
                    "The workbook is already being imported or contains invalid references");
        }
        return get(actorId, ledgerId, importId);
    }

    private void validateNameUniqueness(
            List<ParsedAccount> parsed, AccountCodeRule rule, ImportMode mode,
            Map<String, LedgerResponses.Account> existingAccounts) {
        // Group existing accounts by parent code
        Map<String, Set<String>> existingNamesByParent = new HashMap<>();
        if (mode == ImportMode.APPEND) {
            for (LedgerResponses.Account account : existingAccounts.values()) {
                String parentCode = account.parentId() == null ? ""
                        : existingAccounts.values().stream()
                        .filter(candidate -> candidate.id().equals(account.parentId()))
                        .map(LedgerResponses.Account::code).findFirst().orElse("");
                existingNamesByParent.computeIfAbsent(parentCode, k -> new HashSet<>())
                        .add(clean(account.name()));
            }
        }

        // Group file accounts by parent code
        Map<String, Set<String>> fileNamesByParent = new HashMap<>();
        for (ParsedAccount account : parsed) {
            String code = account.code();
            String name = clean(account.cleaned().get("name"));
            if (name.isBlank()) continue;

            String parentCode = rule.levelOf(code) > 1 ? rule.parentCode(code).orElse("") : "";
            Set<String> existingNames = existingNamesByParent.getOrDefault(parentCode, Set.of());
            Set<String> fileNames = fileNamesByParent.computeIfAbsent(parentCode, k -> new HashSet<>());

            if (existingNames.contains(name) || !fileNames.add(name)) {
                account.issues().add("ERROR:SAME_NAME_EXISTS");
            }
        }
    }

    @Transactional(readOnly = true)
    public Preview get(UUID actorId, UUID ledgerId, UUID importId) {
        ledgers.findLedger(actorId, ledgerId);
        ImportHeader header = importHeader(ledgerId, importId);
        AccountCodeRule currentRule = accounts.codeRule(ledgerId);
        AccountCodeRule targetRule = header.targetRuleLevel2Width() != null
                ? new AccountCodeRule(header.targetRuleLevel2Width(), header.targetRuleLevel3Width(), header.targetRuleLevel4Width())
                : currentRule;
        Map<String, String> sources = map(header.codeRuleSourcesJson());
        List<ImportBlocker> blockers = typedList(header.blockersJson(), new TypeReference<>() {});

        List<PreviewRow> rows = imports.previewRows(importId).stream()
                .map(row -> new PreviewRow(row.rowNo(), map(row.rawJson()), map(row.cleanedJson()),
                        row.accountCode(), row.targetId(), row.targetVersion(), row.action(),
                        row.confirmed(), row.confidence(), strings(row.issuesJson())))
                .toList();

        List<String> warnings = new ArrayList<>();
        if (header.mode() == ImportMode.OVERWRITE) {
            if ("LEDGER_DEFAULT".equals(sources.get("level2"))) {
                warnings.add("文件未包含二级科目，第二级级长使用默认值 " + targetRule.level2Width() + "，请确认");
            }
            if ("LEDGER_DEFAULT".equals(sources.get("level3"))) {
                warnings.add("文件未包含三级科目，第三级级长使用默认值 " + targetRule.level3Width() + "，请确认");
            }
            if ("LEDGER_DEFAULT".equals(sources.get("level4"))) {
                warnings.add("文件未包含四级科目，第四级级长使用默认值 " + targetRule.level4Width() + "，请确认");
            }
        }
        if (header.format() == Format.KINGDEE) {
            warnings.add("金蝶格式未包含现金流和辅助核算配置，导入后将采用默认设置");
        }

        int insertCount = "COMMITTED".equals(header.status()) ? header.insertedCount() : header.rowCount();
        int deleteCount = header.deletedCount();
        if (!"COMMITTED".equals(header.status()) && header.mode() == ImportMode.OVERWRITE) {
            deleteCount = ledgers.listAccounts(actorId, ledgerId).size();
        }

        List<ImportError> errors = new ArrayList<>();
        for (ImportBlocker b : blockers) {
            errors.add(new ImportError(null, null, b.type(), b.message()));
        }
        for (PreviewRow r : rows) {
            for (String issue : r.issues()) {
                if (issue.startsWith("ERROR:")) {
                    errors.add(new ImportError(r.rowNo(), r.accountCode(), issue,
                            issueDescription(issue, r.accountCode(), r.cleanedData().get("name"))));
                }
            }
        }

        return new Preview(header.id(), header.ledgerId(), header.format(), header.mode(), header.status(),
                header.ledgerVersion(), header.filename(), header.contentSha256(), header.rowCount(),
                header.errorCount(), insertCount, deleteCount, currentRule, targetRule, sources,
                header.aiStatus(), blockers, warnings, rows, errors);
    }

    @Transactional
    public Preview decide(UUID actorId, UUID ledgerId, UUID importId, int rowNo, Decision decision) {
        requireWrite(actorId, ledgerId);
        ImportHeader header = importHeader(ledgerId, importId);
        requirePreview(header);
        applyDecision(actorId, ledgerId, importId, rowNo, decision);
        return get(actorId, ledgerId, importId);
    }

    @Transactional
    public Preview decideAll(
            UUID actorId, UUID ledgerId, UUID importId, List<RowDecision> decisions) {
        requireWrite(actorId, ledgerId);
        ImportHeader header = importHeader(ledgerId, importId);
        requirePreview(header);
        if (decisions == null || decisions.isEmpty()) {
            throw problem(422, "ACCOUNT_IMPORT_DECISIONS_REQUIRED", "Import decisions are required",
                    "Provide one decision for every account import row");
        }
        Set<Integer> rowNumbers = new HashSet<>();
        for (RowDecision rowDecision : decisions) {
            if (rowDecision == null || !rowNumbers.add(rowDecision.rowNo())) {
                throw problem(422, "ACCOUNT_IMPORT_DECISION_INVALID", "Invalid import decision",
                        "Each import row must have exactly one decision");
            }
        }
        Set<Integer> expectedRowNumbers = imports.previewRows(importId).stream()
                .map(AccountImportRepository.PreviewRow::rowNo)
                .collect(Collectors.toSet());
        if (!expectedRowNumbers.containsAll(rowNumbers)) {
            throw problem(404, "ACCOUNT_IMPORT_ROW_NOT_FOUND", "Import row not found",
                    "Every decision must target a row in this import");
        }
        if (rowNumbers.size() != expectedRowNumbers.size()
                || !rowNumbers.containsAll(expectedRowNumbers)) {
            throw problem(422, "ACCOUNT_IMPORT_DECISIONS_INCOMPLETE", "Import decisions are incomplete",
                    "Submit exactly one decision for every preview row in a single call");
        }
        for (RowDecision rowDecision : decisions) {
            applyDecision(actorId, ledgerId, importId, rowDecision.rowNo(), rowDecision.decision());
        }
        return get(actorId, ledgerId, importId);
    }

    private void requirePreview(ImportHeader header) {
        if (!"PREVIEW".equals(header.status())) {
            throw problem(409, "ACCOUNT_IMPORT_STATE_INVALID", "Account import cannot be changed",
                    "Only a preview import can be edited");
        }
    }

    private void applyDecision(
            UUID actorId, UUID ledgerId, UUID importId, int rowNo, Decision decision) {
        if (decision == null) {
            throw problem(422, "ACCOUNT_IMPORT_DECISION_INVALID", "Invalid import decision",
                    "Every import row decision needs an action");
        }
        String action = decision.action() == null ? "CREATE" : decision.action().toUpperCase(Locale.ROOT);
        if ("UPDATE".equals(action)) {
            throw problem(422, "ACCOUNT_IMPORT_DECISION_UNSUPPORTED", "Unsupported import decision",
                    "当前导入模式不支持 UPDATE 决策，如需更新科目请在科目管理页面修改或使用 OVERWRITE 全量重构");
        }
        UUID target = "MAP".equals(action) ? decision.targetAccountId() : null;
        Long targetVersion = null;
        if ("MAP".equals(action) && target != null) {
            LedgerResponses.Account account = ledgers.findAccount(actorId, ledgerId, target);
            targetVersion = account.version();
        }
        int updated = imports.decide(importId, rowNo, action, target, targetVersion, decision.accountCode());
        if (updated != 1) {
            throw problem(404, "ACCOUNT_IMPORT_ROW_NOT_FOUND", "Import row not found",
                    "The import row does not exist");
        }
    }

    @Transactional
    public Preview commit(UUID actorId, UUID ledgerId, UUID importId) {
        return commit(actorId, ledgerId, importId, null);
    }

    @Transactional
    public Preview commit(UUID actorId, UUID ledgerId, UUID importId, CommitRequest commitRequest) {
        requireWrite(actorId, ledgerId);
        ImportHeader header = importHeader(ledgerId, importId);
        if ("COMMITTED".equals(header.status())) {
            return get(actorId, ledgerId, importId);
        }

        // 1. Acquire ledger-level exclusive lock
        accounts.lockLedger(ledgerId);

        // Re-read header inside the lock to handle concurrent submissions idempotently
        header = importHeader(ledgerId, importId);
        if ("COMMITTED".equals(header.status())) {
            return get(actorId, ledgerId, importId);
        }

        // Validate expectedMode if client provided it
        if (commitRequest != null && commitRequest.expectedMode() != null
                && commitRequest.expectedMode() != header.mode()) {
            throw problem(409, "ACCOUNT_IMPORT_MODE_MISMATCH", "Account import mode mismatch",
                    "提交的导入模式 (" + commitRequest.expectedMode() + ") 与预检模式 (" + header.mode() + ") 不一致");
        }

        // 2. Optimistic version check against preview
        if (accounts.ledgerVersion(ledgerId) != header.ledgerVersion()) {
            throw problem(409, "ACCOUNT_IMPORT_STALE", "Account import is stale",
                    "Ledger accounts changed after preview; upload the workbook again");
        }

        // 3. Error count check
        if (header.errorCount() > 0) {
            throw problem(422, "ACCOUNT_IMPORT_BLOCKED", "Account import has unresolved errors",
                    "Resolve all blocking errors before committing the import");
        }

        List<CommitRow> rows = imports.commitRows(importId).stream()
                .map(row -> new CommitRow(row.rowNo(), imported(row.cleanedJson()), row.targetId(),
                        row.targetVersion(), row.action(), row.confirmed(), strings(row.issuesJson())))
                .toList();

        for (CommitRow row : rows) {
            if ("SKIP".equalsIgnoreCase(row.action()) || "MAP".equalsIgnoreCase(row.action())) {
                continue;
            }
            if ("UPDATE".equalsIgnoreCase(row.action())) {
                throw problem(422, "ACCOUNT_IMPORT_DECISION_UNSUPPORTED", "Unsupported import decision",
                        "当前导入模式不支持 UPDATE 决策");
            }
            if (row.issues().stream().anyMatch(issue -> issue.startsWith("ERROR:"))) {
                throw problem(422, "ACCOUNT_IMPORT_UNRESOLVED", "Account import has unresolved rows",
                        "Every row must be valid before commit");
            }
        }

        AccountCodeRule targetRule = header.targetRuleLevel2Width() != null
                ? new AccountCodeRule(header.targetRuleLevel2Width(), header.targetRuleLevel3Width(), header.targetRuleLevel4Width())
                : accounts.codeRule(ledgerId);

        Map<String, LedgerResponses.Account> existingByCode = existingAccountsByCode(actorId, ledgerId);
        Set<String> willExistCodes = header.mode() == ImportMode.APPEND
                ? new HashSet<>(existingByCode.keySet())
                : new HashSet<>();
        for (CommitRow row : rows) {
            if (!"SKIP".equalsIgnoreCase(row.action()) && !"MAP".equalsIgnoreCase(row.action())) {
                willExistCodes.add(row.account().values().get("code"));
            }
        }
        for (CommitRow row : rows) {
            if ("SKIP".equalsIgnoreCase(row.action()) || "MAP".equalsIgnoreCase(row.action())) {
                continue;
            }
            String code = row.account().values().get("code");
            int level = targetRule.levelOf(code);
            if (level > 1) {
                String parentCode = targetRule.parentCode(code).orElse("");
                if (!willExistCodes.contains(parentCode)) {
                    throw problem(422, "ACCOUNT_IMPORT_PARENT_SKIPPED", "Parent account skipped or missing",
                            "科目 " + code + " 的上级科目 " + parentCode + " 被跳过或不存在");
                }
            }
        }

        // Prepare dimension types
        Map<String, UUID> dimensionIds = ledgers.listDimensionTypes(actorId, ledgerId).stream()
                .collect(Collectors.toMap(LedgerResponses.DimensionType::code, LedgerResponses.DimensionType::id));
        for (CommitRow row : rows) {
            if ("SKIP".equalsIgnoreCase(row.action()) || "MAP".equalsIgnoreCase(row.action())) {
                continue;
            }
            for (DimensionTypeSeed seed : row.account().dimensionTypes()) {
                if (!dimensionIds.containsKey(seed.code())) {
                    LedgerResponses.DimensionType created = ledgers.createDimensionType(actorId, ledgerId,
                            new LedgerRequests.DimensionTypeCreate(seed.code(), seed.name(), false));
                    dimensionIds.put(seed.code(), created.id());
                }
            }
        }

        Map<String, UUID> cashFlowIds = accounts.cashFlowItems(ledgerId).stream()
                .collect(Collectors.toMap(LedgerResponses.CashFlowItem::code, LedgerResponses.CashFlowItem::id));

        List<AccountManagementRepository.ReconstructAccountSeed> accountSeeds = new ArrayList<>();
        List<AccountManagementRepository.AccountDimensionRequirementSeed> dimSeeds = new ArrayList<>();

        for (CommitRow row : rows) {
            if ("SKIP".equalsIgnoreCase(row.action()) || "MAP".equalsIgnoreCase(row.action())) {
                continue;
            }
            Map<String, String> v = row.account().values();
            String code = v.get("code");
            String name = v.get("name");
            String parentCode = targetRule.parentCode(code).orElse(null);
            String category = v.get("category");
            String normalBalance = v.get("normalBalance");
            String status = v.get("status");
            if (status == null || status.isBlank()) {
                status = "ACTIVE";
            }
            String standardAccountKey = emptyToNull(v.get("standardAccountKey"));
            boolean cashFlowRequired = Boolean.parseBoolean(v.get("cashFlowRequired"));
            UUID defaultCashFlowItemId = cashFlowIds.get(v.get("defaultCashFlowItemCode"));
            boolean quantityEnabled = Boolean.parseBoolean(v.get("quantityEnabled"));
            String unitName = emptyToNull(v.get("unitName"));

            accountSeeds.add(new AccountManagementRepository.ReconstructAccountSeed(
                    code, name, parentCode, category, normalBalance, status, standardAccountKey,
                    false, cashFlowRequired, defaultCashFlowItemId, quantityEnabled, unitName));

            for (DimensionRequirementSeed req : row.account().dimensionRequirements()) {
                UUID dimTypeId = dimensionIds.get(req.code());
                if (dimTypeId != null) {
                    dimSeeds.add(new AccountManagementRepository.AccountDimensionRequirementSeed(
                            code, dimTypeId, req.required()));
                }
            }
        }

        int insertedCount;
        int deletedCount;

        if (header.mode() == ImportMode.OVERWRITE) {
            List<ImportBlocker> blockers = accounts.checkReconstructionEligibility(ledgerId);
            if (!blockers.isEmpty()) {
                throw problem(409, "ACCOUNT_IMPORT_DISQUALIFIED", "Ledger is not eligible for overwrite",
                        blockers.getFirst().message());
            }
            AccountManagementRepository.ReconstructResult result = accounts.reconstructAccounts(
                    ledgerId, targetRule, accountSeeds, dimSeeds, importId);
            insertedCount = result.insertedCount();
            deletedCount = result.deletedCount();
        } else {
            for (CommitRow row : rows) {
                if ("SKIP".equalsIgnoreCase(row.action()) || "MAP".equalsIgnoreCase(row.action())) {
                    continue;
                }
                String code = row.account().values().get("code");
                if (existingByCode.containsKey(code)) {
                    throw problem(409, "ACCOUNT_IMPORT_CODE_CONFLICT", "Account code already exists",
                            "Account code " + code + " already exists in the ledger");
                }
            }
            insertedCount = accounts.appendAccounts(ledgerId, targetRule, accountSeeds, dimSeeds);
            deletedCount = 0;
        }

        imports.markCommitted(importId, ledgerId, insertedCount, deletedCount);
        return get(actorId, ledgerId, importId);
    }

    public RuleInference inferCodeRule(
            List<ParsedAccount> parsed, AccountCodeRule currentRule, AccountCodeRule customRule, Format format) {
        List<String> warnings = new ArrayList<>();
        if (customRule != null) {
            return new RuleInference(customRule,
                    Map.of("level2", "USER_SPECIFIED", "level3", "USER_SPECIFIED", "level4", "USER_SPECIFIED"),
                    warnings);
        }

        List<String> codes = parsed.stream()
                .map(ParsedAccount::code)
                .filter(c -> c != null && !c.isBlank())
                .toList();

        for (String code : codes) {
            if (!code.matches("\\d+")) {
                throw problem(422, "ACCOUNT_CODE_RULE_INVALID", "Invalid code rule",
                        "科目编码必须为纯数字，保留前导零：" + code);
            }
        }

        boolean hasExplicitParent = parsed.stream()
                .anyMatch(p -> p.cleaned().get("parentCode") != null && !p.cleaned().get("parentCode").isBlank());

        Integer w2 = null;
        Integer w3 = null;
        Integer w4 = null;

        if (hasExplicitParent) {
            Set<Integer> l2Widths = new LinkedHashSet<>();
            Set<Integer> l3Widths = new LinkedHashSet<>();
            Set<Integer> l4Widths = new LinkedHashSet<>();

            // Sort accounts by parent code length ascending so Level 2 is processed before Level 3, and Level 3 before Level 4
            List<ParsedAccount> accountsWithParent = parsed.stream()
                    .filter(p -> p.cleaned().get("parentCode") != null && !p.cleaned().get("parentCode").isBlank())
                    .sorted(Comparator.comparingInt(p -> p.cleaned().get("parentCode").length()))
                    .toList();

            for (ParsedAccount account : accountsWithParent) {
                String parent = account.cleaned().get("parentCode");
                String code = account.code();
                int width = code.length() - parent.length();
                if (width <= 0 || width > 8) {
                    throw problem(422, "ACCOUNT_CODE_RULE_INVALID", "Invalid code rule",
                            "科目 " + code + " 与其上级 " + parent + " 的级长差非法：" + width);
                }
                if (parent.length() == 4) {
                    l2Widths.add(width);
                } else if (l2Widths.size() == 1 && parent.length() == 4 + l2Widths.iterator().next()) {
                    l3Widths.add(width);
                } else if (l2Widths.size() == 1 && l3Widths.size() == 1
                        && parent.length() == 4 + l2Widths.iterator().next() + l3Widths.iterator().next()) {
                    l4Widths.add(width);
                } else {
                    throw problem(422, "ACCOUNT_CODE_RULE_INVALID", "Invalid code rule",
                            "科目 " + code + " 的上级编码长度 " + parent.length() + " 不符合层级规则");
                }
            }

            if (l2Widths.size() > 1 || l3Widths.size() > 1 || l4Widths.size() > 1) {
                throw problem(422, "ACCOUNT_CODE_RULE_INVALID", "Invalid code rule",
                        "同一层级存在混合级长，无法推断统一编码规则");
            }

            if (!l2Widths.isEmpty()) w2 = l2Widths.iterator().next();
            if (!l3Widths.isEmpty()) w3 = l3Widths.iterator().next();
            if (!l4Widths.isEmpty()) w4 = l4Widths.iterator().next();
        } else {
            int[] sortedLengths = codes.stream().mapToInt(String::length).distinct().sorted().toArray();
            if (sortedLengths.length == 0 || sortedLengths[0] != 4) {
                throw problem(422, "ACCOUNT_CODE_RULE_INVALID", "Invalid code rule",
                        "一级科目编码必须为 4 位纯数字");
            }
            if (sortedLengths.length > 4) {
                throw problem(422, "ACCOUNT_CODE_RULE_INVALID", "Invalid code rule",
                        "科目层级超过系统支持的最大 4 层限制，或同一层级级长不一致");
            }
            if (sortedLengths.length >= 2) {
                w2 = sortedLengths[1] - 4;
                if (w2 < 1 || w2 > 8) {
                    throw problem(422, "ACCOUNT_CODE_RULE_INVALID", "Invalid code rule",
                            "二级科目级长非法：" + w2);
                }
            }
            if (sortedLengths.length >= 3) {
                w3 = sortedLengths[2] - sortedLengths[1];
                if (w3 < 1 || w3 > 8) {
                    throw problem(422, "ACCOUNT_CODE_RULE_INVALID", "Invalid code rule",
                            "三级科目级长非法：" + w3);
                }
            }
            if (sortedLengths.length >= 4) {
                w4 = sortedLengths[3] - sortedLengths[2];
                if (w4 < 1 || w4 > 8) {
                    throw problem(422, "ACCOUNT_CODE_RULE_INVALID", "Invalid code rule",
                            "四级科目级长非法：" + w4);
                }
            }
        }

        Map<String, String> sources = new LinkedHashMap<>();
        int finalW2 = w2 != null ? w2 : currentRule.level2Width();
        int finalW3 = w3 != null ? w3 : currentRule.level3Width();
        int finalW4 = w4 != null ? w4 : currentRule.level4Width();

        sources.put("level2", w2 != null ? "OBSERVED" : "LEDGER_DEFAULT");
        sources.put("level3", w3 != null ? "OBSERVED" : "LEDGER_DEFAULT");
        sources.put("level4", w4 != null ? "OBSERVED" : "LEDGER_DEFAULT");

        if (w2 == null) {
            warnings.add("文件未包含二级科目，第二级级长使用默认值 " + finalW2 + "，请确认");
        }
        if (w3 == null) {
            warnings.add("文件未包含三级科目，第三级级长使用默认值 " + finalW3 + "，请确认");
        }
        if (w4 == null) {
            warnings.add("文件未包含四级科目，第四级级长使用默认值 " + finalW4 + "，请确认");
        }

        AccountCodeRule rule = new AccountCodeRule(finalW2, finalW3, finalW4);
        return new RuleInference(rule, sources, warnings);
    }

    public static String issueDescription(String issue, String accountCode, String name) {
        String code = issue.startsWith("ERROR:") ? issue.substring(6) : issue;
        return switch (code) {
            case "INVALID_CODE" -> "科目编码“" + accountCode + "”不符合编码规则或包含非数字字符";
            case "DUPLICATE_CODE" -> "科目编码“" + accountCode + "”在导入文件中重复出现";
            case "CODE_EXISTS" -> "科目编码“" + accountCode + "”已在账套中存在，增量追加模式禁止重复编码";
            case "NAME_REQUIRED" -> "科目“" + accountCode + "”名称不能为空";
            case "INVALID_NAME" -> "科目名称“" + name + "”不能包含半角连接符“-”，该字符用于分隔科目层级";
            case "INVALID_CATEGORY" -> "科目“" + accountCode + "”类别无效";
            case "INVALID_NORMAL_BALANCE" -> "科目“" + accountCode + "”余额方向无效（仅支持借/贷）";
            case "PARENT_CODE_MISMATCH" -> "科目“" + accountCode + "”指定的上级科目编码与编码规则层级推断不一致";
            case "PARENT_NOT_FOUND" -> "科目“" + accountCode + "”的上级科目不存在";
            case "CATEGORY_PARENT_MISMATCH" -> "科目“" + accountCode + "”类别必须与上级科目一致";
            case "NORMAL_BALANCE_PARENT_MISMATCH" -> "科目“" + accountCode + "”余额方向必须与上级科目一致";
            case "USED_ACCOUNT_CANNOT_BECOME_PARENT" -> "上级科目已有业务记录，不可新增子科目";
            case "SAME_NAME_EXISTS" -> "同级已存在同名科目：“" + name + "”";
            case "UNIT_REQUIRED" -> "科目“" + accountCode + "”启用了数量核算，必须填写计量单位";
            case "STANDARD_ACCOUNT_KEY_REQUIRED" -> "一级科目“" + accountCode + "”必须映射法定报表归类";
            case "STANDARD_ACCOUNT_KEY_AMBIGUOUS" -> "一级科目“" + accountCode + "”存在多个候选法定报表归类，无法唯一确定";
            case "STANDARD_ACCOUNT_KEY_INVALID" -> "科目“" + accountCode + "”的法定报表归类无效或未在当前准则中定义";
            case "STANDARD_ACCOUNT_KEY_CONFLICT" -> "科目“" + accountCode + "”的法定报表归类与现有科目冲突";
            default -> issue;
        };
    }

    private byte[] workbook(
            LedgerResponses.Ledger ledger, AccountCodeRule rule, Format format,
            List<LedgerResponses.Account> accountRows, List<LedgerResponses.DimensionType> dimensions) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            CellStyle header = headerStyle(workbook);
            if (format == Format.KINGDEE) {
                Sheet sheet = workbook.createSheet("科目列表");
                CellStyle nativeHeader = nativeKingdeeHeaderStyle(workbook);
                CellStyle nativeBody = nativeKingdeeBodyStyle(workbook);
                writeNativeKingdeeHeaders(sheet, nativeHeader);
                int rowNo = 1;
                for (LedgerResponses.Account account : accountRows) {
                    Row row = sheet.createRow(rowNo++);
                    values(row, List.of(account.code(), account.name(),
                            nativeKingdeeCategory(account), nativeKingdeeBalance(account.normalBalance())),
                            nativeBody);
                }
                sizeNativeKingdeeSheet(sheet);
            } else {
                Map<UUID, String> codes = accountCodesForExport(ledger.id(), accountRows);
                metadata(workbook, ledger, rule, header);
                accountsSheet(workbook, accountRows, codes, header);
                dimensionSheets(workbook, dimensions, accountRows, header);
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw problem(500, "ACCOUNT_EXPORT_FAILED", "Account export failed",
                    "The account workbook could not be generated");
        }
    }

    private Map<UUID, String> accountCodesForExport(
            UUID ledgerId, List<LedgerResponses.Account> accountRows) {
        Map<UUID, String> codes = new LinkedHashMap<>();
        for (LedgerResponses.Account account : accountRows) {
            codes.put(account.id(), account.code());
        }
        Set<UUID> missingParentIds = new HashSet<>();
        for (LedgerResponses.Account account : accountRows) {
            if (account.parentId() != null && !codes.containsKey(account.parentId())) {
                missingParentIds.add(account.parentId());
            }
        }
        codes.putAll(accounts.codesByIds(ledgerId, missingParentIds));
        return codes;
    }

    private void metadata(Workbook workbook, LedgerResponses.Ledger ledger,
                          AccountCodeRule rule, CellStyle header) {
        Sheet sheet = workbook.createSheet("Metadata");
        writeHeaders(sheet, List.of("Key", "Value"), header);
        List<List<String>> values = List.of(
                List.of("FormatVersion", "ACCOUNT-EXCHANGE/1"),
                List.of("AccountingStandard", ledger.accountingStandardCode()),
                List.of("AccountingStandardVersion", ledger.accountingStandardVersion()),
                List.of("AccountCodeRule", "4-" + rule.level2Width() + "-" + rule.level3Width()
                        + "-" + rule.level4Width()));
        for (int index = 0; index < values.size(); index++) {
            values(sheet.createRow(index + 1), values.get(index));
        }
        autosize(sheet, 2);
    }

    private void accountsSheet(
            Workbook workbook, List<LedgerResponses.Account> accountRows,
            Map<UUID, String> codes, CellStyle header) {
        Sheet sheet = workbook.createSheet("Accounts");
        writeHeaders(sheet, STANDARD_HEADERS, header);
        Map<UUID, String> cashCodes = accountRows.isEmpty() ? Map.of()
                : accounts.cashFlowItems(accountRows.getFirst().ledgerId()).stream()
                .collect(Collectors.toMap(LedgerResponses.CashFlowItem::id, LedgerResponses.CashFlowItem::code));
        int rowNo = 1;
        for (LedgerResponses.Account account : accountRows) {
            Row row = sheet.createRow(rowNo++);
            values(row, List.of(account.code(), account.name(), nullable(codes.get(account.parentId())),
                    account.category(), account.normalBalance(), account.status(),
                    Boolean.toString(account.cashFlowRequired()),
                    nullable(cashCodes.get(account.defaultCashFlowItemId())),
                    Boolean.toString(account.quantityEnabled()), nullable(account.unitName()),
                    nullable(account.standardAccountKey())));
        }
        autosize(sheet, STANDARD_HEADERS.size());
    }

    private void dimensionSheets(
            Workbook workbook, List<LedgerResponses.DimensionType> dimensions,
            List<LedgerResponses.Account> accountRows, CellStyle header) {
        Sheet types = workbook.createSheet("DimensionTypes");
        writeHeaders(types, List.of("Code", "Name"), header);
        for (int index = 0; index < dimensions.size(); index++) {
            values(types.createRow(index + 1), List.of(dimensions.get(index).code(), dimensions.get(index).name()));
        }
        autosize(types, 2);
        Sheet bindings = workbook.createSheet("AccountDimensions");
        writeHeaders(bindings, List.of("AccountCode", "DimensionTypeCode", "Required"), header);
        int rowNo = 1;
        for (LedgerResponses.Account account : accountRows) {
            for (LedgerResponses.DimensionRequirement binding : account.dimensionRequirements()) {
                values(bindings.createRow(rowNo++), List.of(
                        account.code(), binding.code(), Boolean.toString(binding.required())));
            }
        }
        autosize(bindings, 3);
    }

    private List<ParsedAccount> parseRaw(byte[] content, Format format) {
        ZipSecureFile.setMinInflateRatio(0.01);
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheet(format == Format.STANDARD ? "Accounts" : "科目");
            boolean nativeKingdee = false;
            if (sheet == null && format == Format.KINGDEE) {
                sheet = workbook.getSheet("科目列表");
                nativeKingdee = sheet != null;
            }
            if (sheet == null) {
                throw workbookProblem("Required account sheet is missing");
            }
            List<String> headers = format == Format.STANDARD ? STANDARD_HEADERS
                    : nativeKingdee ? NATIVE_KINGDEE_HEADERS : KINGDEE_HEADERS;
            requireHeaders(sheet, headers);
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<ParsedAccount> rows = new ArrayList<>();
            int last = sheet.getLastRowNum();
            for (int rowNo = 1; rowNo <= last; rowNo++) {
                Row row = sheet.getRow(rowNo);
                if (row == null || blank(row, headers.size(), formatter)) {
                    continue;
                }
                if (rows.size() >= MAX_ACCOUNTS) {
                    throw problem(413, "ACCOUNT_IMPORT_TOO_LARGE", "Account import is too large",
                            "A workbook may contain at most 10,000 accounts");
                }
                List<String> cells = new ArrayList<>();
                for (int column = 0; column < headers.size(); column++) {
                    Cell cell = row.getCell(column);
                    if (cell != null && cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA) {
                        throw workbookProblem("Formula cells are not accepted (row " + (rowNo + 1) + ")");
                    }
                    String value = cell == null ? "" : formatter.formatCellValue(cell);
                    if (value.length() > 2_000) {
                        throw workbookProblem("Cell text is too long (row " + (rowNo + 1) + ")");
                    }
                    cells.add(value);
                }
                rows.add(nativeKingdee ? rawNativeKingdee(headers, cells)
                        : rawParsed(format, headers, cells));
            }
            if (format == Format.STANDARD) {
                standardControls(workbook, rows);
            }
            return rows;
        } catch (ApiProblemException exception) {
            throw exception;
        } catch (Exception exception) {
            throw workbookProblem("The workbook is damaged or not a supported .xlsx file");
        }
    }

    private ParsedAccount rawNativeKingdee(List<String> headers, List<String> cells) {
        String category = switch (clean(cells.get(2))) {
            case "流动资产" -> "CURRENT_ASSET";
            case "非流动资产" -> "NON_CURRENT_ASSET";
            case "流动负债" -> "CURRENT_LIABILITY";
            case "非流动负债" -> "NON_CURRENT_LIABILITY";
            case "所有者权益" -> "EQUITY";
            case "成本" -> "COST";
            case "营业收入" -> "OPERATING_REVENUE";
            case "其他收益" -> "OTHER_INCOME";
            case "营业成本及税金" -> "OPERATING_COST_AND_TAX";
            case "其他损失" -> "OTHER_EXPENSE";
            case "期间费用" -> "PERIOD_EXPENSE";
            case "所得税" -> "INCOME_TAX";
            case "以前年度损益调整" -> "PRIOR_YEAR_ADJUSTMENT";
            default -> "";
        };
        String normal = switch (clean(cells.get(3))) {
            case "借" -> "DEBIT";
            case "贷" -> "CREDIT";
            default -> "";
        };
        String code = cleanCode(cells.get(0));
        List<String> adapted = List.of(code, clean(cells.get(1)), "",
                category, normal, "ACTIVE", "FALSE", "FALSE", "");
        ParsedAccount parsed = rawParsed(Format.KINGDEE, KINGDEE_HEADERS, adapted);
        Map<String, String> raw = new LinkedHashMap<>();
        for (int index = 0; index < headers.size(); index++) {
            raw.put(headers.get(index), cells.get(index));
        }
        return new ParsedAccount(parsed.code(), raw, parsed.cleaned(), parsed.issues());
    }

    private ParsedAccount rawParsed(Format format, List<String> headers, List<String> cells) {
        Map<String, String> raw = new LinkedHashMap<>();
        for (int index = 0; index < headers.size(); index++) {
            raw.put(headers.get(index), cells.get(index));
        }
        int categoryIndex = format == Format.STANDARD ? 3 : 3;
        String code = cleanCode(cells.get(0));
        String name = clean(cells.get(1));
        String parent = cleanCode(cells.get(2));
        String category = clean(cells.get(categoryIndex)).toUpperCase(Locale.ROOT);
        String normal = clean(cells.get(categoryIndex + 1)).toUpperCase(Locale.ROOT);
        String status = clean(cells.get(categoryIndex + 2)).toUpperCase(Locale.ROOT);
        boolean cashRequired = bool(cells.get(categoryIndex + 3));
        boolean quantity = bool(cells.get(format == Format.STANDARD ? 8 : 7));
        String unit = clean(cells.get(format == Format.STANDARD ? 9 : 8));
        Map<String, String> cleaned = new LinkedHashMap<>();
        cleaned.put("code", code);
        cleaned.put("name", name);
        cleaned.put("parentCode", parent);
        cleaned.put("category", category);
        cleaned.put("normalBalance", normal);
        cleaned.put("status", status.isBlank() ? "ACTIVE" : status);
        cleaned.put("cashFlowRequired", Boolean.toString(cashRequired));
        cleaned.put("defaultCashFlowItemCode", format == Format.STANDARD ? clean(cells.get(7)) : "");
        cleaned.put("quantityEnabled", Boolean.toString(quantity));
        cleaned.put("unitName", unit);
        cleaned.put("standardAccountKey", format == Format.STANDARD ? clean(cells.get(10)) : "");
        List<String> issues = new ArrayList<>();
        return new ParsedAccount(code, raw, cleaned, issues);
    }

    private void applyStandardMappings(
            Format format, LedgerResponses.Ledger ledger, AccountCodeRule rule,
            List<ParsedAccount> parsed, Map<String, LedgerResponses.Account> existingAccounts) {
        Map<String, String> resolvedKeys = new HashMap<>();
        for (LedgerResponses.Account existing : existingAccounts.values()) {
            if (existing.standardAccountKey() != null && !existing.standardAccountKey().isBlank()) {
                resolvedKeys.put(existing.code(), existing.standardAccountKey());
            }
        }

        List<ParsedAccount> sorted = new ArrayList<>(parsed);
        sorted.sort(Comparator.comparingInt(a -> rule.levelOf(a.code())));

        for (ParsedAccount account : sorted) {
            String code = account.code();
            int level = rule.levelOf(code);
            LedgerResponses.Account existing = existingAccounts.get(code);
            String importedKey = account.cleaned().get("standardAccountKey");

            if (existing != null) {
                if (format == Format.STANDARD && !importedKey.isBlank()
                        && !java.util.Objects.equals(importedKey, existing.standardAccountKey())) {
                    account.issues().add("ERROR:STANDARD_ACCOUNT_KEY_CONFLICT");
                } else {
                    String key = existing.standardAccountKey() == null ? "" : existing.standardAccountKey();
                    account.cleaned().put("standardAccountKey", key);
                    if (!key.isBlank()) resolvedKeys.put(code, key);
                }
                continue;
            }

            if (level > 1) {
                String parentCode = rule.parentCode(code).orElse("");
                String parentKey = resolvedKeys.get(parentCode);
                if (parentKey != null && !parentKey.isBlank()) {
                    if (format == Format.STANDARD && !importedKey.isBlank() && !importedKey.equals(parentKey)) {
                        account.issues().add("ERROR:STANDARD_ACCOUNT_KEY_CONFLICT");
                    } else {
                        account.cleaned().put("standardAccountKey", parentKey);
                        resolvedKeys.put(code, parentKey);
                    }
                    continue;
                }
            }

            if (format == Format.STANDARD) {
                if (importedKey.isBlank() || !standards.containsStandardAccountKey(
                        ledger.accountingStandardCode(), ledger.accountingStandardVersion(), importedKey)) {
                    account.issues().add("ERROR:STANDARD_ACCOUNT_KEY_INVALID");
                } else {
                    resolvedKeys.put(code, importedKey);
                }
                continue;
            }

            String namedKey = "SME".equalsIgnoreCase(ledger.accountingStandardCode())
                    && level == 1
                    ? KINGDEE_SME_KEYS_BY_NAME.get(account.cleaned().get("name")) : null;
            if (namedKey != null && standards.containsStandardAccountKey(
                    ledger.accountingStandardCode(), ledger.accountingStandardVersion(), namedKey)) {
                account.cleaned().put("standardAccountKey", namedKey);
                resolvedKeys.put(code, namedKey);
                continue;
            }
            Optional<String> packageKey = level == 1
                    ? standards.packageAccountKey(ledger.accountingStandardCode(), ledger.accountingStandardVersion(), code)
                    : Optional.empty();
            if (packageKey.isPresent()) {
                account.cleaned().put("standardAccountKey", packageKey.get());
                resolvedKeys.put(code, packageKey.get());
                continue;
            }
            List<String> matches = standards.legacyCodeMatches(
                    ledger.accountingStandardCode(), ledger.accountingStandardVersion(), account.code());
            if (matches.size() == 1) {
                account.cleaned().put("standardAccountKey", matches.getFirst());
                resolvedKeys.put(code, matches.getFirst());
            } else if (matches.size() > 1) {
                account.issues().add("ERROR:STANDARD_ACCOUNT_KEY_AMBIGUOUS");
            } else if (level == 1) {
                account.issues().add("ERROR:STANDARD_ACCOUNT_KEY_REQUIRED");
            }
        }
    }

    private void standardControls(Workbook workbook, List<ParsedAccount> accounts) {
        Sheet metadataSheet = workbook.getSheet("Metadata");
        Sheet typesSheet = workbook.getSheet("DimensionTypes");
        Sheet bindingsSheet = workbook.getSheet("AccountDimensions");
        if (metadataSheet == null || typesSheet == null || bindingsSheet == null) {
            throw workbookProblem("Metadata, DimensionTypes and AccountDimensions sheets are required");
        }
        requireHeaders(metadataSheet, List.of("Key", "Value"));
        requireHeaders(typesSheet, List.of("Code", "Name"));
        requireHeaders(bindingsSheet, List.of("AccountCode", "DimensionTypeCode", "Required"));
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        Map<String, String> metadata = new HashMap<>();
        for (int rowNo = 1; rowNo <= metadataSheet.getLastRowNum(); rowNo++) {
            Row row = metadataSheet.getRow(rowNo);
            if (row == null || blank(row, 2, formatter)) {
                continue;
            }
            String key = cleanCell(row, 0, formatter, rowNo);
            String value = cleanCell(row, 1, formatter, rowNo);
            if (key.isBlank() || metadata.putIfAbsent(key, value) != null) {
                throw workbookProblem("Invalid or duplicate metadata at row " + (rowNo + 1));
            }
        }
        if (!"ACCOUNT-EXCHANGE/1".equals(metadata.get("FormatVersion"))) {
            throw workbookProblem("Unsupported or missing account exchange format version");
        }
        List<DimensionTypeSeed> types = new ArrayList<>();
        Set<String> typeCodes = new HashSet<>();
        for (int rowNo = 1; rowNo <= typesSheet.getLastRowNum(); rowNo++) {
            Row row = typesSheet.getRow(rowNo);
            if (row == null || blank(row, 2, formatter)) {
                continue;
            }
            String code = cleanCell(row, 0, formatter, rowNo).toUpperCase(Locale.ROOT);
            String name = cleanCell(row, 1, formatter, rowNo);
            if (code.isBlank() || name.isBlank() || !typeCodes.add(code)) {
                throw workbookProblem("Invalid or duplicate dimension type at row " + (rowNo + 1));
            }
            types.add(new DimensionTypeSeed(code, name));
        }
        Map<String, List<DimensionRequirementSeed>> requirements = new HashMap<>();
        for (int rowNo = 1; rowNo <= bindingsSheet.getLastRowNum(); rowNo++) {
            Row row = bindingsSheet.getRow(rowNo);
            if (row == null || blank(row, 3, formatter)) {
                continue;
            }
            String accountCode = cleanCode(cleanCell(row, 0, formatter, rowNo));
            String typeCode = cleanCell(row, 1, formatter, rowNo).toUpperCase(Locale.ROOT);
            String required = cleanCell(row, 2, formatter, rowNo);
            if (accounts.stream().noneMatch(account -> account.code().equals(accountCode))
                    || !typeCodes.contains(typeCode)
                    || !Set.of("TRUE", "FALSE", "1", "0", "Y", "N", "YES", "NO", "是", "否", "启用", "停用")
                    .contains(required.toUpperCase(Locale.ROOT))) {
                throw workbookProblem("Invalid account dimension binding at row " + (rowNo + 1));
            }
            List<DimensionRequirementSeed> accountRequirements =
                    requirements.computeIfAbsent(accountCode, ignored -> new ArrayList<>());
            if (accountRequirements.stream().anyMatch(binding -> binding.code().equals(typeCode))) {
                throw workbookProblem("Duplicate account dimension binding at row " + (rowNo + 1));
            }
            accountRequirements.add(new DimensionRequirementSeed(typeCode, bool(required)));
        }
        String typeJson = json(types);
        accounts.forEach(account -> {
            account.cleaned().put("dimensionTypes", typeJson);
            account.cleaned().put("dimensionRequirements",
                    json(requirements.getOrDefault(account.code(), List.of())));
        });
    }

    private String cleanCell(Row row, int column, DataFormatter formatter, int rowNo) {
        Cell cell = row.getCell(column);
        if (cell != null && cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA) {
            throw workbookProblem("Formula cells are not accepted (row " + (rowNo + 1) + ")");
        }
        String value = cell == null ? "" : formatter.formatCellValue(cell);
        if (value.length() > 2_000) {
            throw workbookProblem("Cell text is too long (row " + (rowNo + 1) + ")");
        }
        return clean(value);
    }

    private void requireHeaders(Sheet sheet, List<String> expected) {
        Row row = sheet.getRow(0);
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        if (row == null) {
            throw workbookProblem("Header row is missing");
        }
        for (int column = 0; column < expected.size(); column++) {
            Cell cell = row.getCell(column);
            if (cell == null || cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA
                    || !expected.get(column).equals(formatter.formatCellValue(cell).trim())) {
                throw workbookProblem("Unexpected header at column " + (column + 1));
            }
        }
    }

    private boolean blank(Row row, int columns, DataFormatter formatter) {
        for (int index = 0; index < columns; index++) {
            Cell cell = row.getCell(index);
            if (cell != null && !formatter.formatCellValue(cell).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private void writeHeaders(Sheet sheet, List<String> headers, CellStyle style) {
        Row row = sheet.createRow(0);
        for (int index = 0; index < headers.size(); index++) {
            Cell cell = row.createCell(index);
            cell.setCellValue(headers.get(index));
            cell.setCellStyle(style);
        }
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(
                0, 0, 0, Math.max(0, headers.size() - 1)));
    }

    private CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private void writeNativeKingdeeHeaders(Sheet sheet, CellStyle style) {
        Row row = sheet.createRow(0);
        row.setHeightInPoints(22);
        for (int index = 0; index < NATIVE_KINGDEE_HEADERS.size(); index++) {
            Cell cell = row.createCell(index);
            cell.setCellValue(NATIVE_KINGDEE_HEADERS.get(index));
            cell.setCellStyle(style);
        }
        sheet.createFreezePane(0, 1);
    }

    private CellStyle nativeKingdeeHeaderStyle(Workbook workbook) {
        CellStyle style = nativeKingdeeBodyStyle(workbook);
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.BLACK.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);
        style.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
        return style;
    }

    private CellStyle nativeKingdeeBodyStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setBorderTop(org.apache.poi.ss.usermodel.BorderStyle.THIN);
        style.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
        style.setBorderLeft(org.apache.poi.ss.usermodel.BorderStyle.THIN);
        style.setBorderRight(org.apache.poi.ss.usermodel.BorderStyle.THIN);
        style.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
        return style;
    }

    private String nativeKingdeeCategory(LedgerResponses.Account account) {
        AccountCategory category = AccountCategory.fromCode(account.category());
        return category == null ? account.category() : category.label();
    }

    private String nativeKingdeeBalance(String normalBalance) {
        return switch (normalBalance) {
            case "DEBIT" -> "借";
            case "CREDIT" -> "贷";
            default -> normalBalance;
        };
    }

    private void sizeNativeKingdeeSheet(Sheet sheet) {
        sheet.setDefaultRowHeightInPoints(18);
        sheet.setColumnWidth(0, 18 * 256);
        sheet.setColumnWidth(1, 22 * 256);
        sheet.setColumnWidth(2, 18 * 256);
        sheet.setColumnWidth(3, 18 * 256);
    }

    private void values(Row row, List<String> values) {
        for (int index = 0; index < values.size(); index++) {
            row.createCell(index).setCellValue(safeCell(values.get(index)));
        }
    }

    private void values(Row row, List<String> values, CellStyle style) {
        for (int index = 0; index < values.size(); index++) {
            Cell cell = row.createCell(index);
            cell.setCellValue(safeCell(values.get(index)));
            cell.setCellStyle(style);
        }
    }

    private void autosize(Sheet sheet, int columns) {
        for (int index = 0; index < columns; index++) {
            sheet.autoSizeColumn(index);
            sheet.setColumnWidth(index, Math.min(50 * 256, Math.max(12 * 256, sheet.getColumnWidth(index) + 512)));
        }
    }

    private String safeCell(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.stripLeading();
        return !trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0)) >= 0 ? "'" + value : value;
    }

    private String clean(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).trim();
    }

    private String cleanCode(String value) {
        return clean(value).replace(" ", "").replace(".", "").replace("-", "");
    }

    private boolean bool(String value) {
        return Set.of("TRUE", "1", "Y", "YES", "是", "启用")
                .contains(clean(value).toUpperCase(Locale.ROOT));
    }

    private String nullable(String value) {
        return value == null ? "" : value;
    }

    private byte[] readBounded(InputStream input) {
        try {
            byte[] content = input.readNBytes((int) MAX_BYTES + 1);
            if (content.length > MAX_BYTES) {
                throw tooLarge();
            }
            return content;
        } catch (IOException exception) {
            throw workbookProblem("The uploaded workbook could not be read");
        }
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw problem(500, "ACCOUNT_IMPORT_HASH_FAILED", "Account import failed",
                    "The workbook fingerprint could not be calculated");
        }
    }

    private ImportHeader importHeader(UUID ledgerId, UUID importId) {
        AccountImportRepository.Header saved = imports.findHeader(ledgerId, importId);
        if (saved == null) {
            throw problem(404, "ACCOUNT_IMPORT_NOT_FOUND", "Account import not found",
                    "The account import is not available to this ledger");
        }
        return new ImportHeader(
                saved.id(), saved.ledgerId(), Format.valueOf(saved.format()),
                ImportMode.valueOf(saved.mode()), saved.status(),
                saved.ledgerVersion(), saved.filename(), saved.contentSha256(),
                saved.rowCount(), saved.errorCount(), saved.insertedCount(), saved.deletedCount(),
                saved.targetRuleLevel2Width(), saved.targetRuleLevel3Width(), saved.targetRuleLevel4Width(),
                saved.codeRuleSourcesJson(), saved.blockersJson(), saved.aiStatus());
    }

    private void requireWrite(UUID actorId, UUID ledgerId) {
        if (!Set.of(LedgerRole.OWNER, LedgerRole.EDITOR)
                .contains(access.requireMembership(actorId, ledgerId))) {
            throw problem(403, "INSUFFICIENT_LEDGER_ROLE", "Insufficient ledger role",
                    "Only ledger owners and editors can import accounts");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw problem(500, "ACCOUNT_IMPORT_JSON_FAILED", "Account import failed",
                    "Import preview data could not be serialized");
        }
    }

    private Map<String, String> map(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw workbookProblem("Saved import data is invalid");
        }
    }

    private List<String> strings(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw workbookProblem("Saved import issues are invalid");
        }
    }

    private Map<String, LedgerResponses.Account> existingAccountsByCode(UUID actorId, UUID ledgerId) {
        return ledgers.listAccounts(actorId, ledgerId).stream()
                .collect(Collectors.toMap(LedgerResponses.Account::code, account -> account));
    }

    private ImportedAccount imported(String json) {
        Map<String, String> values = map(json);
        return new ImportedAccount(values,
                typedList(values.get("dimensionTypes"), new TypeReference<>() {}),
                typedList(values.get("dimensionRequirements"), new TypeReference<>() {}));
    }

    private <T> List<T> typedList(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw workbookProblem("Saved import controls are invalid");
        }
    }

    private ApiProblemException tooLarge() {
        return problem(413, "ACCOUNT_IMPORT_TOO_LARGE", "Account import is too large",
                "The workbook must not exceed 10 MiB");
    }

    private ApiProblemException workbookProblem(String detail) {
        return problem(422, "ACCOUNT_IMPORT_INVALID", "Invalid account workbook", detail);
    }

    private ApiProblemException problem(int status, String code, String title, String detail) {
        return new ApiProblemException(status, code, title, detail, false);
    }

    public enum Format {
        STANDARD, KINGDEE
    }

    public enum ImportMode {
        APPEND, OVERWRITE
    }

    @Schema(name = "AccountImportBlocker")
    public record ImportBlocker(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "阻断类型") String type,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "阻断原因说明") String message) {
    }

    @Schema(name = "AccountImportError")
    public record ImportError(
            @Schema(description = "行号，账套级错误为 null", nullable = true) Integer rowNo,
            @Schema(description = "科目编码，账套级错误为 null", nullable = true) String accountCode,
            @Schema(description = "错误类型代码") String code,
            @Schema(description = "可操作的错误说明") String message) {
    }

    @Schema(name = "AccountImportRuleInference")
    public record RuleInference(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AccountCodeRule rule,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Map<String, String> sources,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> warnings) {
    }

    @Schema(name = "AccountReconstructionEligibilityResponse")
    public record ReconstructionEligibilityResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean eligible,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ImportBlocker> blockers) {
    }

    @Schema(name = "AccountImportCommitRequest")
    public record CommitRequest(
            @Schema(description = "预期导入模式，提供时将进行一致性校验", nullable = true) ImportMode expectedMode) {
    }

    @Schema(name = "AccountImportDecision")
    public record Decision(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String action,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID targetAccountId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String accountCode) {
    }

    public record RowDecision(int rowNo, Decision decision) {
    }

    @Schema(name = "AccountImportPreview")
    public record Preview(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID ledgerId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Format format,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ImportMode mode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long ledgerVersion,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String filename,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String contentSha256,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int rowCount,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int errorCount,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int insertCount,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int deleteCount,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AccountCodeRule currentCodeRule,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AccountCodeRule targetCodeRule,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Map<String, String> codeRuleSources,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String aiStatus,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ImportBlocker> blockers,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> warnings,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<PreviewRow> rows,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ImportError> errors) {
    }

    @Schema(name = "AccountImportPreviewRow")
    public record PreviewRow(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int rowNo,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Map<String, String> rawData,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Map<String, String> cleanedData,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String accountCode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID targetAccountId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Long expectedAccountVersion,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String action,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean confirmed,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) BigDecimal confidence,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> issues) {
    }

    private record ParsedAccount(
            String code, Map<String, String> raw, Map<String, String> cleaned, List<String> issues) {
    }

    private record ImportHeader(
            UUID id, UUID ledgerId, Format format, ImportMode mode, String status, long ledgerVersion,
            String filename, String contentSha256, int rowCount, int errorCount, int insertedCount, int deletedCount,
            Integer targetRuleLevel2Width, Integer targetRuleLevel3Width, Integer targetRuleLevel4Width,
            String codeRuleSourcesJson, String blockersJson, String aiStatus) {
    }

    private record CommitRow(
            int rowNo, ImportedAccount account, UUID targetAccountId, Long expectedVersion,
            String action, boolean confirmed, List<String> issues) {
    }

    private record DimensionTypeSeed(String code, String name) {
    }

    private record DimensionRequirementSeed(String code, boolean required) {
    }

    private record ImportedAccount(
            Map<String, String> values,
            List<DimensionTypeSeed> dimensionTypes,
            List<DimensionRequirementSeed> dimensionRequirements) {

        private static String emptyToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
