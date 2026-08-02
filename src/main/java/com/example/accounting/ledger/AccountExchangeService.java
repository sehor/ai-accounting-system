package com.example.accounting.ledger;

import com.example.accounting.ledger.internal.persistence.AccountManagementRepository;
import com.example.accounting.ledger.internal.persistence.AccountImportRepository;
import com.example.accounting.shared.web.ApiProblemException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountExchangeService {

    public static final long MAX_BYTES = 10L * 1024 * 1024;
    public static final int MAX_ACCOUNTS = 10_000;
    private static final List<String> STANDARD_HEADERS = List.of(
            "Code", "Name", "ParentCode", "Category", "NormalBalance", "Status",
            "CashFlowRequired", "DefaultCashFlowItemCode", "QuantityEnabled", "UnitName");
    private static final List<String> KINGDEE_HEADERS = List.of(
            "科目代码", "科目名称", "上级科目代码", "科目类别", "余额方向", "状态",
            "现金流必填", "数量核算", "单位");

    private final LedgerService ledgers;
    private final LedgerAccessService access;
    private final AccountManagementRepository accounts;
    private final AccountImportRepository imports;
    private final AccountAiMapper aiMapper;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public AccountExchangeService(
            LedgerService ledgers, LedgerAccessService access,
            AccountManagementRepository accounts, AccountImportRepository imports, AccountAiMapper aiMapper) {
        this.ledgers = ledgers;
        this.access = access;
        this.accounts = accounts;
        this.imports = imports;
        this.aiMapper = aiMapper;
    }

    @Transactional(readOnly = true)
    public byte[] template(UUID actorId, UUID ledgerId, Format format) {
        LedgerResponses.Ledger ledger = ledgers.findLedger(actorId, ledgerId);
        return workbook(ledger, accounts.codeRule(ledgerId), format, List.of(),
                ledgers.listDimensionTypes(actorId, ledgerId));
    }

    @Transactional(readOnly = true)
    public byte[] export(UUID actorId, UUID ledgerId, Format format) {
        LedgerResponses.Ledger ledger = ledgers.findLedger(actorId, ledgerId);
        return workbook(ledger, accounts.codeRule(ledgerId), format,
                ledgers.listAccounts(actorId, ledgerId), ledgers.listDimensionTypes(actorId, ledgerId));
    }

    @Transactional
    public Preview preview(UUID actorId, UUID ledgerId, Format format, String filename,
                           long declaredSize, InputStream input) {
        requireWrite(actorId, ledgerId);
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw problem(422, "ACCOUNT_IMPORT_FILE_INVALID", "Invalid account workbook",
                    "Only .xlsx workbooks are accepted");
        }
        if (declaredSize < 0 || declaredSize > MAX_BYTES) {
            throw tooLarge();
        }
        byte[] content = readBounded(input);
        if (content.length < 4 || content[0] != 'P' || content[1] != 'K'
                || content[2] != 3 || content[3] != 4) {
            throw workbookProblem("The uploaded file is not an .xlsx ZIP package");
        }
        String sha256 = sha256(content);
        UUID existing = imports.findByHash(ledgerId, sha256);
        if (existing != null) {
            return get(actorId, ledgerId, existing);
        }

        AccountCodeRule rule = accounts.codeRule(ledgerId);
        List<ParsedAccount> parsed = parse(content, format, rule);
        Map<String, LedgerResponses.Account> existingAccounts = ledgers.listAccounts(actorId, ledgerId).stream()
                .collect(Collectors.toMap(LedgerResponses.Account::code, account -> account));
        Set<String> availableCodes = new HashSet<>(existingAccounts.keySet());
        parsed.forEach(row -> availableCodes.add(row.code()));
        parsed.stream().filter(row -> rule.levelOf(row.code()) > 1).forEach(row ->
                rule.parentCode(row.code()).filter(parent -> !availableCodes.contains(parent))
                        .ifPresent(ignored -> row.issues().add("ERROR:PARENT_NOT_FOUND")));
        Map<UUID, LedgerResponses.Account> existingById = existingAccounts.values().stream()
                .collect(Collectors.toMap(LedgerResponses.Account::id, account -> account));
        List<AccountAiMapper.Source> aiSources = new ArrayList<>();
        for (int index = 0; index < parsed.size(); index++) {
            ParsedAccount row = parsed.get(index);
            // ponytail: cap optional AI work; batch/chunk only if mapping throughput becomes a measured need.
            if (aiSources.size() < 500 && !existingAccounts.containsKey(row.code())
                    && row.issues().stream().noneMatch(issue -> issue.startsWith("ERROR:"))) {
                aiSources.add(new AccountAiMapper.Source(
                        index + 1, row.code(), row.cleaned().get("name")));
            }
        }
        AccountAiMapper.Result ai = aiMapper.suggest(aiSources, existingAccounts.values().stream()
                .map(account -> new AccountAiMapper.Target(account.id(), account.code(), account.name()))
                .toList());
        Set<String> duplicateCodes = parsed.stream().collect(Collectors.groupingBy(
                        ParsedAccount::code, Collectors.counting())).entrySet().stream()
                .filter(entry -> !entry.getKey().isBlank() && entry.getValue() > 1)
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        UUID importId = UUID.randomUUID();
        long ledgerVersion = accounts.ledgerVersion(ledgerId);
        try {
            imports.create(importId, ledgerId, format.name(), ledgerVersion,
                    filename.substring(Math.max(0, filename.length() - 255)),
                    sha256, parsed.size(), ai.status(), actorId);
            int errorCount = 0;
            int rowNo = 1;
            for (ParsedAccount account : parsed) {
                List<String> issues = new ArrayList<>(account.issues());
                if (duplicateCodes.contains(account.code())) {
                    issues.add("ERROR:DUPLICATE_CODE");
                }
                LedgerResponses.Account target = existingAccounts.get(account.code());
                AccountAiMapper.Suggestion aiSuggestion = ai.suggestions().get(rowNo);
                if (target == null && aiSuggestion != null) {
                    target = existingById.get(aiSuggestion.targetAccountId());
                    issues.add("AI:SUGGESTED:" + aiSuggestion.reason());
                }
                String suggestedAction = target == null ? "CREATE" : "MAP";
                BigDecimal confidence = aiSuggestion != null
                        ? aiSuggestion.confidence()
                        : target == null ? new BigDecimal("0.9000") : BigDecimal.ONE;
                if (issues.stream().anyMatch(issue -> issue.startsWith("ERROR:"))) {
                    errorCount++;
                }
                imports.addRow(importId, rowNo++, json(account.raw()), json(account.cleaned()), account.code(),
                        target == null ? null : target.id(), target == null ? null : target.version(),
                        suggestedAction, confidence, json(issues));
            }
            imports.setErrorCount(importId, errorCount);
        } catch (DataIntegrityViolationException exception) {
            throw problem(409, "ACCOUNT_IMPORT_CONFLICT", "Account import conflict",
                    "The workbook is already being imported or contains invalid references");
        }
        return get(actorId, ledgerId, importId);
    }

    @Transactional(readOnly = true)
    public Preview get(UUID actorId, UUID ledgerId, UUID importId) {
        ledgers.findLedger(actorId, ledgerId);
        ImportHeader header = importHeader(ledgerId, importId);
        List<PreviewRow> rows = imports.previewRows(importId).stream()
                .map(row -> new PreviewRow(row.rowNo(), map(row.rawJson()), map(row.cleanedJson()),
                        row.accountCode(), row.targetId(), row.targetVersion(), row.action(),
                        row.confirmed(), row.confidence(), strings(row.issuesJson())))
                .toList();
        return new Preview(header.id(), header.ledgerId(), header.format(), header.status(),
                header.ledgerVersion(), header.filename(), header.rowCount(), header.errorCount(),
                header.aiStatus(), rows);
    }

    @Transactional
    public Preview decide(UUID actorId, UUID ledgerId, UUID importId, int rowNo, Decision decision) {
        requireWrite(actorId, ledgerId);
        ImportHeader header = importHeader(ledgerId, importId);
        if (!"PREVIEW".equals(header.status())) {
            throw problem(409, "ACCOUNT_IMPORT_STATE_INVALID", "Account import cannot be changed",
                    "Only a preview import can be edited");
        }
        String action = decision.action() == null ? "" : decision.action().toUpperCase(Locale.ROOT);
        if (!Set.of("CREATE", "UPDATE", "MAP", "SKIP").contains(action)) {
            throw problem(422, "ACCOUNT_IMPORT_DECISION_INVALID", "Invalid import decision",
                    "Action must be CREATE, UPDATE, MAP, or SKIP");
        }
        UUID target = Set.of("UPDATE", "MAP").contains(action) ? decision.targetAccountId() : null;
        Long targetVersion = null;
        if (Set.of("UPDATE", "MAP").contains(action)) {
            if (target == null) {
                throw problem(422, "ACCOUNT_IMPORT_TARGET_REQUIRED", "Import target is required",
                        "UPDATE and MAP require an account in this ledger");
            }
            LedgerResponses.Account account = ledgers.findAccount(actorId, ledgerId, target);
            if ("UPDATE".equals(action) && account.coreLocked()) {
                throw problem(409, "ACCOUNT_CORE_LOCKED", "Account core fields are locked",
                        "Map the row to this account or update only an unlocked account");
            }
            targetVersion = account.version();
        }
        int updated = imports.decide(importId, rowNo, action, target, targetVersion, decision.accountCode());
        if (updated != 1) {
            throw problem(404, "ACCOUNT_IMPORT_ROW_NOT_FOUND", "Import row not found",
                    "The import row does not exist");
        }
        return get(actorId, ledgerId, importId);
    }

    @Transactional
    public Preview commit(UUID actorId, UUID ledgerId, UUID importId) {
        requireWrite(actorId, ledgerId);
        ImportHeader header = importHeader(ledgerId, importId);
        if ("COMMITTED".equals(header.status())) {
            return get(actorId, ledgerId, importId);
        }
        if (accounts.ledgerVersion(ledgerId) != header.ledgerVersion()) {
            throw problem(409, "ACCOUNT_IMPORT_STALE", "Account import is stale",
                    "Ledger accounts changed after preview; upload the workbook again");
        }
        List<CommitRow> rows = imports.commitRows(importId).stream()
                .map(row -> new CommitRow(row.rowNo(), imported(row.cleanedJson()), row.targetId(),
                        row.targetVersion(), row.action(), row.confirmed(), strings(row.issuesJson())))
                .toList();
        for (CommitRow row : rows) {
            if (!row.confirmed() || row.action() == null
                    || row.issues().stream().anyMatch(issue -> issue.startsWith("ERROR:"))) {
                throw problem(422, "ACCOUNT_IMPORT_UNRESOLVED", "Account import has unresolved rows",
                        "Every row must be valid and explicitly confirmed before commit");
            }
        }
        Map<String, UUID> dimensionIds = ledgers.listDimensionTypes(actorId, ledgerId).stream()
                .collect(Collectors.toMap(LedgerResponses.DimensionType::code, LedgerResponses.DimensionType::id));
        for (CommitRow row : rows) {
            if (!Set.of("CREATE", "UPDATE").contains(row.action())) {
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
        AccountCodeRule rule = accounts.codeRule(ledgerId);
        List<CommitRow> orderedRows = rows.stream()
                .sorted(java.util.Comparator.comparingInt(row ->
                        Math.max(0, rule.levelOf(row.account().values().get("code")))))
                .toList();
        for (CommitRow row : orderedRows) {
            switch (row.action()) {
                case "CREATE" -> ledgers.createAccount(actorId, ledgerId,
                        row.account().createRequest(dimensionIds, cashFlowIds));
                case "UPDATE" -> {
                    LedgerResponses.Account target = ledgers.findAccount(actorId, ledgerId, row.targetAccountId());
                    if (!java.util.Objects.equals(target.version(), row.expectedVersion())) {
                        throw problem(409, "ACCOUNT_IMPORT_STALE", "Account import is stale",
                                "A target account changed after preview");
                    }
                    ledgers.updateAccount(actorId, ledgerId, target.id(),
                            row.account().patchRequest(target.version(), dimensionIds, cashFlowIds));
                }
                case "MAP", "SKIP" -> {
                    // Explicitly resolved without mutating master data.
                }
                default -> throw problem(422, "ACCOUNT_IMPORT_DECISION_INVALID",
                        "Invalid import decision", "The saved import action is invalid");
            }
        }
        imports.markCommitted(importId, ledgerId);
        return get(actorId, ledgerId, importId);
    }

    private byte[] workbook(
            LedgerResponses.Ledger ledger, AccountCodeRule rule, Format format,
            List<LedgerResponses.Account> accountRows, List<LedgerResponses.DimensionType> dimensions) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            CellStyle header = headerStyle(workbook);
            if (format == Format.KINGDEE) {
                Sheet sheet = workbook.createSheet("科目");
                writeHeaders(sheet, KINGDEE_HEADERS, header);
                Map<UUID, String> codes = accountRows.stream().collect(
                        Collectors.toMap(LedgerResponses.Account::id, LedgerResponses.Account::code));
                int rowNo = 1;
                for (LedgerResponses.Account account : accountRows) {
                    Row row = sheet.createRow(rowNo++);
                    values(row, List.of(account.code(), account.name(),
                            nullable(codes.get(account.parentId())), account.category(),
                            account.normalBalance(), account.status(),
                            Boolean.toString(account.cashFlowRequired()),
                            Boolean.toString(account.quantityEnabled()), nullable(account.unitName())));
                }
                autosize(sheet, KINGDEE_HEADERS.size());
            } else {
                metadata(workbook, ledger, rule, header);
                accountsSheet(workbook, accountRows, header);
                dimensionSheets(workbook, dimensions, accountRows, header);
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw problem(500, "ACCOUNT_EXPORT_FAILED", "Account export failed",
                    "The account workbook could not be generated");
        }
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
                        + "-" + rule.level4Width() + " " + rule.separator()));
        for (int index = 0; index < values.size(); index++) {
            values(sheet.createRow(index + 1), values.get(index));
        }
        autosize(sheet, 2);
    }

    private void accountsSheet(Workbook workbook, List<LedgerResponses.Account> accountRows, CellStyle header) {
        Sheet sheet = workbook.createSheet("Accounts");
        writeHeaders(sheet, STANDARD_HEADERS, header);
        Map<UUID, String> codes = accountRows.stream().collect(
                Collectors.toMap(LedgerResponses.Account::id, LedgerResponses.Account::code));
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
                    Boolean.toString(account.quantityEnabled()), nullable(account.unitName())));
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

    private List<ParsedAccount> parse(byte[] content, Format format, AccountCodeRule rule) {
        ZipSecureFile.setMinInflateRatio(0.01);
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheet(format == Format.STANDARD ? "Accounts" : "科目");
            if (sheet == null) {
                throw workbookProblem("Required account sheet is missing");
            }
            List<String> headers = format == Format.STANDARD ? STANDARD_HEADERS : KINGDEE_HEADERS;
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
                rows.add(parsed(format, headers, cells, rule));
            }
            if (format == Format.STANDARD) {
                standardControls(workbook, rows, rule);
            }
            return rows;
        } catch (ApiProblemException exception) {
            throw exception;
        } catch (Exception exception) {
            throw workbookProblem("The workbook is damaged or not a supported .xlsx file");
        }
    }

    private ParsedAccount parsed(Format format, List<String> headers, List<String> cells, AccountCodeRule rule) {
        Map<String, String> raw = new LinkedHashMap<>();
        for (int index = 0; index < headers.size(); index++) {
            raw.put(headers.get(index), cells.get(index));
        }
        int categoryIndex = format == Format.STANDARD ? 3 : 3;
        String code = cleanCode(cells.get(0), rule);
        String name = clean(cells.get(1));
        String parent = cleanCode(cells.get(2), rule);
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
        List<String> issues = new ArrayList<>();
        if (rule.levelOf(code) == 0) {
            issues.add("ERROR:INVALID_CODE");
        }
        if (name.isBlank()) {
            issues.add("ERROR:NAME_REQUIRED");
        }
        if (!Set.of("ASSET", "LIABILITY", "EQUITY", "COST", "REVENUE", "EXPENSE").contains(category)) {
            issues.add("ERROR:INVALID_CATEGORY");
        }
        if (!Set.of("DEBIT", "CREDIT").contains(normal)) {
            issues.add("ERROR:INVALID_NORMAL_BALANCE");
        }
        String inferred = rule.parentCode(code).orElse("");
        if (!parent.isBlank() && !parent.equals(inferred)) {
            issues.add("ERROR:PARENT_CODE_MISMATCH");
        }
        if (quantity && unit.isBlank()) {
            issues.add("ERROR:UNIT_REQUIRED");
        }
        return new ParsedAccount(code, raw, cleaned, issues);
    }

    private void standardControls(Workbook workbook, List<ParsedAccount> accounts, AccountCodeRule rule) {
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
            String accountCode = cleanCode(cleanCell(row, 0, formatter, rowNo), rule);
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

    private void values(Row row, List<String> values) {
        for (int index = 0; index < values.size(); index++) {
            row.createCell(index).setCellValue(safeCell(values.get(index)));
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

    private String cleanCode(String value, AccountCodeRule rule) {
        String cleaned = clean(value).replace(" ", "");
        if (".".equals(rule.separator())) {
            return cleaned.replace('-', '.');
        }
        return cleaned.replace('.', '-');
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
        ImportHeader header = saved == null ? null : new ImportHeader(
                saved.id(), saved.ledgerId(), Format.valueOf(saved.format()), saved.status(),
                saved.ledgerVersion(), saved.filename(), saved.rowCount(), saved.errorCount(), saved.aiStatus());
        if (header == null) {
            throw problem(404, "ACCOUNT_IMPORT_NOT_FOUND", "Account import not found",
                    "The account import is not available to this ledger");
        }
        return header;
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
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw workbookProblem("Saved import data is invalid");
        }
    }

    private List<String> strings(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw workbookProblem("Saved import issues are invalid");
        }
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

    public record Decision(String action, UUID targetAccountId, String accountCode) {
    }

    public record Preview(
            UUID id, UUID ledgerId, Format format, String status, long ledgerVersion,
            String filename, int rowCount, int errorCount, String aiStatus, List<PreviewRow> rows) {
    }

    public record PreviewRow(
            int rowNo, Map<String, String> rawData, Map<String, String> cleanedData,
            String accountCode, UUID targetAccountId, Long expectedAccountVersion,
            String action, boolean confirmed, BigDecimal confidence, List<String> issues) {
    }

    private record ParsedAccount(
            String code, Map<String, String> raw, Map<String, String> cleaned, List<String> issues) {
    }

    private record ImportHeader(
            UUID id, UUID ledgerId, Format format, String status, long ledgerVersion,
            String filename, int rowCount, int errorCount, String aiStatus) {
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

        LedgerRequests.AccountCreate createRequest(
                Map<String, UUID> dimensionIds, Map<String, UUID> cashFlowIds) {
            return new LedgerRequests.AccountCreate(
                    values.get("code"), values.get("name"), values.get("category"),
                    values.get("normalBalance"), null, Boolean.valueOf(values.get("cashFlowRequired")),
                    cashFlowIds.get(values.get("defaultCashFlowItemCode")),
                    Boolean.valueOf(values.get("quantityEnabled")), emptyToNull(values.get("unitName")),
                    requirements(dimensionIds));
        }

        LedgerRequests.AccountPatch patchRequest(
                long version, Map<String, UUID> dimensionIds, Map<String, UUID> cashFlowIds) {
            return new LedgerRequests.AccountPatch(
                    version, values.get("code"), values.get("name"), null, values.get("category"),
                    values.get("normalBalance"), values.getOrDefault("status", "ACTIVE"),
                    Boolean.valueOf(values.get("cashFlowRequired")),
                    cashFlowIds.get(values.get("defaultCashFlowItemCode")),
                    Boolean.valueOf(values.get("quantityEnabled")), emptyToNull(values.get("unitName")),
                    requirements(dimensionIds));
        }

        private List<LedgerRequests.DimensionRequirement> requirements(Map<String, UUID> dimensionIds) {
            return dimensionRequirements.stream()
                    .map(binding -> new LedgerRequests.DimensionRequirement(
                            dimensionIds.get(binding.code()), binding.required()))
                    .toList();
        }

        private static String emptyToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }
}
