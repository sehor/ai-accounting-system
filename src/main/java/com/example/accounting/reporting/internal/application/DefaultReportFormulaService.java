package com.example.accounting.reporting.internal.application;

import com.example.accounting.ledger.LedgerAccessService;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.ledger.formula.FormulaParser;
import com.example.accounting.ledger.formula.ReportFormulaDefinition;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.AccountAmountExpression;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.AccountReference;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.CashFlowItemAmountExpression;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.DetailRule;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.FormulaGroup;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.FormulaLine;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.LineExpression;
import com.example.accounting.ledger.internal.port.ReportFormulaRepository;
import com.example.accounting.reporting.PeriodRange;
import com.example.accounting.reporting.ReportFormulaRequests;
import com.example.accounting.reporting.ReportFormulaResponses;
import com.example.accounting.reporting.ReportFormulaService;
import com.example.accounting.reporting.ReportResponses;
import com.example.accounting.reporting.StatutoryReportResponses;
import com.example.accounting.reporting.formula.CashFlowSource;
import com.example.accounting.reporting.formula.FormulaAccountAmount;
import com.example.accounting.reporting.formula.FormulaAccountResolver;
import com.example.accounting.reporting.formula.ReportFormulaEvaluator;
import com.example.accounting.reporting.formula.ReportFormulaValidator;
import com.example.accounting.reporting.internal.port.ReportingRepository;
import com.example.accounting.shared.web.ApiProblemException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Report formula lifecycle: unique draft, trial preview, atomic publish,
 * immutable version history, standard reset and rollback.
 */
@Service
public class DefaultReportFormulaService implements ReportFormulaService {

    private static final Set<LedgerRole> VIEW_ROLES = Set.of(
            LedgerRole.OWNER, LedgerRole.EDITOR, LedgerRole.REVIEWER, LedgerRole.VIEWER, LedgerRole.AGENT);
    private static final Set<LedgerRole> WRITE_ROLES = Set.of(LedgerRole.OWNER, LedgerRole.EDITOR);
    private static final Set<String> CODES = Set.of(
            ReportFormulaDefinition.REPORT_BALANCE_SHEET, ReportFormulaDefinition.REPORT_INCOME_STATEMENT,
            ReportFormulaDefinition.REPORT_CASH_FLOW);
    private static final List<String> LEGACY_CATEGORY_FIELDS = List.of(
            "debitCategories", "creditCategories", "revenueCategories", "expenseCategories");
    private static final int MAX_QUALITY_SAMPLES = 10;

    private final LedgerAccessService ledgerAccess;
    private final ReportFormulaRepository formulas;
    private final ReportingRepository reports;
    private final FormulaParser parser;
    private final ReportFormulaValidator validator;
    private final ReportFormulaEvaluator evaluator;
    private final FormulaAccountResolver accountResolver;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public DefaultReportFormulaService(LedgerAccessService ledgerAccess, ReportFormulaRepository formulas,
                                       ReportingRepository reports, FormulaParser parser,
                                       ReportFormulaValidator validator, ReportFormulaEvaluator evaluator,
                                       FormulaAccountResolver accountResolver) {
        this.ledgerAccess = ledgerAccess;
        this.formulas = formulas;
        this.reports = reports;
        this.parser = parser;
        this.validator = validator;
        this.evaluator = evaluator;
        this.accountResolver = accountResolver;
    }

    @Override
    @Transactional(readOnly = true)
    public ReportFormulaResponses.Workspace workspace(UUID actorId, UUID ledgerId, String code) {
        requireView(actorId, ledgerId);
        requireCode(code);
        ReportFormulaRepository.Snapshot snapshot = formulas.findSnapshot(ledgerId, code)
                .orElseThrow(() -> problem(404, "REPORT_FORMULA_NOT_FOUND", "Report formula not found",
                        "The ledger has no " + code + " formula"));
        ReportFormulaRepository.Revision draft = formulas.findDraft(ledgerId, code).orElse(null);
        ReportFormulaDefinition definition = parser.parse(snapshot.formulaJson());
        return new ReportFormulaResponses.Workspace(
                snapshot.code(), snapshot.name(), definition.kind(), definition.reportType(),
                definition.templateCode(), snapshot.publishedVersion(),
                toPlain(parser.readTree(snapshot.formulaJson())), draft == null ? null : draftView(draft));
    }

    @Override
    @Transactional
    public ReportFormulaResponses.Draft createDraft(UUID actorId, UUID ledgerId, String code) {
        requireWrite(actorId, ledgerId);
        requireCode(code);
        ReportFormulaRepository.Snapshot snapshot = formulas.findSnapshot(ledgerId, code)
                .orElseThrow(() -> problem(404, "REPORT_FORMULA_NOT_FOUND", "Report formula not found",
                        "The ledger has no " + code + " formula"));
        if (formulas.findDraft(ledgerId, code).isPresent()) {
            throw problem(409, "REPORT_FORMULA_DRAFT_EXISTS", "Draft already exists",
                    "A draft already exists for this formula; discard or update it");
        }
        UUID draftId = formulas.createDraft(snapshot.id(), snapshot.formulaJson(),
                snapshot.publishedVersion(), actorId);
        ReportFormulaRepository.Revision draft = formulas.findDraft(ledgerId, code).orElseThrow();
        return draftView(draft);
    }

    @Override
    @Transactional
    public ReportFormulaResponses.Draft updateDraft(UUID actorId, UUID ledgerId, String code,
                                                    ReportFormulaRequests.DraftUpdate request) {
        requireWrite(actorId, ledgerId);
        requireCode(code);
        ReportFormulaRepository.Snapshot snapshot = formulas.findSnapshot(ledgerId, code)
                .orElseThrow(() -> problem(404, "REPORT_FORMULA_NOT_FOUND", "Report formula not found",
                        "The ledger has no " + code + " formula"));
        ReportFormulaRepository.Revision draft = formulas.findDraft(ledgerId, code)
                .orElseThrow(() -> problem(404, "REPORT_FORMULA_DRAFT_NOT_FOUND", "Draft not found",
                        "Create a draft before editing this formula"));
        requireDraftVersion(draft, request.expectedDraftVersion());
        ReportFormulaDefinition current = parser.parse(draft.definitionJson());
        ReportFormulaDefinition base = parser.parse(snapshot.formulaJson());
        ReportFormulaDefinition merged = mergeEdit(current, base, request);
        List<ReportFormulaValidator.FormulaIssue> issues = validator.validate(merged, base, ledgerId);
        if (!issues.isEmpty()) {
            throw problem(422, "REPORT_FORMULA_INVALID", "Report formula is invalid",
                    "The edited formula does not pass validation: " + issues);
        }
        String mergedJson = writeDefinition(merged, ledgerId, code,
                draft.definitionJson(), snapshot.formulaJson());
        if (!formulas.updateDraft(draft.id(), mergedJson, draft.draftVersion(), actorId)) {
            throw versionConflict();
        }
        formulas.recordAudit(ledgerId, snapshot.id(), "SAVE", actorId, draft.definitionJson(), mergedJson);
        return draftView(formulas.findDraft(ledgerId, code).orElseThrow());
    }

    @Override
    @Transactional
    public void deleteDraft(UUID actorId, UUID ledgerId, String code) {
        requireWrite(actorId, ledgerId);
        requireCode(code);
        ReportFormulaRepository.Snapshot snapshot = formulas.findSnapshot(ledgerId, code)
                .orElseThrow(() -> problem(404, "REPORT_FORMULA_NOT_FOUND", "Report formula not found",
                        "The ledger has no " + code + " formula"));
        ReportFormulaRepository.Revision draft = formulas.findDraft(ledgerId, code)
                .orElseThrow(() -> problem(404, "REPORT_FORMULA_DRAFT_NOT_FOUND", "Draft not found",
                        "There is no draft to discard"));
        formulas.deleteDraft(draft.id());
        formulas.recordAudit(ledgerId, snapshot.id(), "DISCARD", actorId, draft.definitionJson(), null);
    }

    @Override
    @Transactional
    public ReportFormulaResponses.Draft resetDraft(UUID actorId, UUID ledgerId, String code,
                                                   ReportFormulaRequests.DraftReset request) {
        requireWrite(actorId, ledgerId);
        requireCode(code);
        ReportFormulaRepository.Snapshot snapshot = formulas.findSnapshot(ledgerId, code)
                .orElseThrow(() -> problem(404, "REPORT_FORMULA_NOT_FOUND", "Report formula not found",
                        "The ledger has no " + code + " formula"));
        ReportFormulaRepository.Revision draft = formulas.findDraft(ledgerId, code)
                .orElseThrow(() -> problem(404, "REPORT_FORMULA_DRAFT_NOT_FOUND", "Draft not found",
                        "Create a draft before resetting it to the published definition"));
        requireDraftVersion(draft, request.expectedDraftVersion());
        if (!formulas.updateDraft(draft.id(), snapshot.formulaJson(), draft.draftVersion(), actorId)) {
            throw versionConflict();
        }
        formulas.recordAudit(ledgerId, snapshot.id(), "RESET", actorId, draft.definitionJson(),
                snapshot.formulaJson());
        return draftView(formulas.findDraft(ledgerId, code).orElseThrow());
    }

    @Override
    @Transactional
    public ReportFormulaResponses.PreviewResult preview(UUID actorId, UUID ledgerId, String code,
                                                        ReportFormulaRequests.PreviewRequest request) {
        requireWrite(actorId, ledgerId);
        requireCode(code);
        ReportFormulaRepository.Snapshot snapshot = formulas.findSnapshot(ledgerId, code)
                .orElseThrow(() -> problem(404, "REPORT_FORMULA_NOT_FOUND", "Report formula not found",
                        "The ledger has no " + code + " formula"));
        ReportFormulaRepository.Revision draft = formulas.findDraft(ledgerId, code)
                .orElseThrow(() -> problem(404, "REPORT_FORMULA_DRAFT_NOT_FOUND", "Draft not found",
                        "Create a draft before previewing this formula"));
        requireDraftVersion(draft, request.expectedDraftVersion());
        ReportFormulaDefinition definition = parser.parse(draft.definitionJson());
        ReportFormulaDefinition base = parser.parse(snapshot.formulaJson());
        List<ReportFormulaValidator.FormulaIssue> issues = validator.validate(definition, base, ledgerId);
        if (!issues.isEmpty()) {
            return new ReportFormulaResponses.PreviewResult(draft.draftVersion(), null, false,
                    issues.stream().map(this::issue).toList(), List.of(), null);
        }
        PeriodRange range = previewRange(ledgerId, code, request);
        List<ReportFormulaResponses.Warning> warnings = new ArrayList<>();
        Object statement;
        if (ReportFormulaDefinition.KIND_ACCOUNT_DETAIL.equals(definition.kind())) {
            boolean operatingActivity = ReportFormulaDefinition.REPORT_INCOME_STATEMENT
                    .equals(definition.reportType());
            if (operatingActivity) {
                requireFormulaProjection(ledgerId, range);
            }
            List<FormulaAccountAmount> source = reports.formulaAccountAmounts(
                    ledgerId, range, operatingActivity);
            ReportResponses.Statement result = evaluator.evaluateAccountDetail(ledgerId, definition, source);
            statement = objectMapper.convertValue(result, Object.class);
        } else {
            boolean income = ReportFormulaDefinition.REPORT_INCOME_STATEMENT.equals(code);
            boolean cashFlow = ReportFormulaDefinition.REPORT_CASH_FLOW.equals(code);
            String firstPeriod = reports.firstPeriodOfYear(ledgerId, range.periodTo());
            if (firstPeriod == null) {
                throw problem(422, "REPORT_FORMULA_PERIOD_INVALID", "Period is invalid",
                        "The selected period has no year opening period");
            }
            StatutoryReportResponses.Statement result;
            if (cashFlow) {
                if (!range.periodFrom().equals(range.periodTo())) {
                    throw problem(422, "REPORT_FORMULA_PERIOD_INVALID", "Period is invalid",
                            "Cash flow previews accept a single month periodCode only");
                }
                result = cashFlowPreview(ledgerId, definition, firstPeriod, range);
            } else {
                List<FormulaAccountAmount> primary;
                List<FormulaAccountAmount> comparative;
                if (income) {
                    PeriodRange yearToDate = new PeriodRange(firstPeriod, range.periodTo());
                    requireFormulaProjection(ledgerId, yearToDate);
                    requireFormulaProjection(ledgerId, range);
                    primary = reports.formulaAccountAmounts(ledgerId, yearToDate, true);
                    comparative = reports.formulaAccountAmounts(ledgerId, range, true);
                } else {
                    primary = reports.formulaAccountAmounts(ledgerId, range, false);
                    comparative = reports.formulaAccountAmounts(ledgerId, PeriodRange.single(firstPeriod), false);
                }
                result = evaluator.evaluateFixedLines(ledgerId, definition,
                        primary, comparative, new ReportFormulaEvaluator.FixedLinesMetadata(
                                income ? "income-statement" : "balance-sheet", "SME", "2011-17",
                                range.periodTo(), income ? "本年累计金额" : "期末余额",
                                income ? "本月金额" : "年初余额"));
            }
            result.checks().stream().filter(check -> !check.passed()).forEach(check ->
                    warnings.add(new ReportFormulaResponses.Warning(
                            check.key(), check.name(), check.difference())));
            statement = objectMapper.convertValue(result, Object.class);
        }
        boolean hasWarnings = !warnings.isEmpty();
        formulas.updateDraftPreviewState(draft.id(), draft.draftVersion(), hasWarnings);
        return new ReportFormulaResponses.PreviewResult(
                draft.draftVersion(), draft.draftVersion(), hasWarnings, List.of(), warnings, statement);
    }

    @Override
    @Transactional
    public ReportFormulaResponses.PublishResult publish(UUID actorId, UUID ledgerId, String code,
                                                        ReportFormulaRequests.PublishRequest request) {
        requireWrite(actorId, ledgerId);
        requireCode(code);
        ReportFormulaRepository.Snapshot snapshot = formulas.lockSnapshot(ledgerId, code)
                .orElseThrow(() -> problem(404, "REPORT_FORMULA_NOT_FOUND", "Report formula not found",
                        "The ledger has no " + code + " formula"));
        if (snapshot.publishedVersion() != request.expectedPublishedVersion()) {
            throw versionConflict();
        }
        ReportFormulaRepository.Revision draft = formulas.lockDraft(ledgerId, code)
                .orElseThrow(() -> problem(404, "REPORT_FORMULA_DRAFT_NOT_FOUND", "Draft not found",
                        "There is no draft to publish"));
        if (!Objects.equals(draft.draftVersion(), request.expectedDraftVersion())) {
            throw versionConflict();
        }
        if (!Objects.equals(draft.lastPreviewedDraftVersion(), draft.draftVersion())) {
            throw problem(409, "REPORT_FORMULA_PREVIEW_REQUIRED", "Preview required",
                    "Preview the draft before publishing it");
        }
        if (draft.previewHasWarnings() && !request.acknowledgeWarnings()) {
            throw problem(422, "REPORT_FORMULA_WARNING_ACK_REQUIRED", "Warning acknowledgement required",
                    "The preview reported unbalanced checks; acknowledge the warnings to publish");
        }
        ReportFormulaDefinition definition = parser.parse(draft.definitionJson());
        validator.requireValid(definition, ledgerId);
        String publishedJson = writeDefinition(definition, ledgerId, code,
                draft.definitionJson(), snapshot.formulaJson());
        int newVersion = snapshot.publishedVersion() + 1;
        UUID revisionId = formulas.insertPublished(snapshot.id(), publishedJson,
                snapshot.publishedVersion(), newVersion, "USER", null, actorId);
        formulas.replaceAccountReferences(revisionId, ledgerId, concreteAccountIds(definition));
        formulas.publishSnapshot(snapshot.id(), definition.kind(), publishedJson,
                newVersion, actorId);
        formulas.deleteDraft(draft.id());
        formulas.recordAudit(ledgerId, snapshot.id(), "PUBLISH", actorId,
                snapshot.formulaJson(), publishedJson);
        return new ReportFormulaResponses.PublishResult(code, newVersion);
    }

    @Override
    @Transactional(readOnly = true)
    public ReportFormulaResponses.VersionPage versions(UUID actorId, UUID ledgerId, String code,
                                                       int page, int pageSize) {
        requireView(actorId, ledgerId);
        requireCode(code);
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw problem(400, "PAGINATION_INVALID", "Invalid pagination",
                    "page must be positive and pageSize must be between 1 and 100");
        }
        if (!formulas.findSnapshot(ledgerId, code).isPresent()) {
            throw problem(404, "REPORT_FORMULA_NOT_FOUND", "Report formula not found",
                    "The ledger has no " + code + " formula");
        }
        List<ReportFormulaRepository.Revision> revisions =
                formulas.listPublishedVersions(ledgerId, code, page, pageSize);
        long total = formulas.countPublishedVersions(ledgerId, code);
        int totalPages = total == 0 ? 0 : (int) ((total + pageSize - 1) / pageSize);
        return new ReportFormulaResponses.VersionPage(page, pageSize, total, totalPages,
                revisions.stream().map(this::versionInfo).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public ReportFormulaResponses.VersionInfo version(UUID actorId, UUID ledgerId, String code, int version) {
        requireView(actorId, ledgerId);
        requireCode(code);
        return formulas.findPublishedVersion(ledgerId, code, version)
                .map(this::versionInfo)
                .orElseThrow(() -> problem(404, "REPORT_FORMULA_NOT_FOUND", "Report formula version not found",
                        "The ledger has no published version " + version + " of " + code));
    }

    @Override
    @Transactional
    public ReportFormulaResponses.RollbackResult rollback(UUID actorId, UUID ledgerId, String code, int version,
                                                          ReportFormulaRequests.RollbackRequest request) {
        requireWrite(actorId, ledgerId);
        requireCode(code);
        ReportFormulaRepository.Snapshot snapshot = formulas.lockSnapshot(ledgerId, code)
                .orElseThrow(() -> problem(404, "REPORT_FORMULA_NOT_FOUND", "Report formula not found",
                        "The ledger has no " + code + " formula"));
        if (snapshot.publishedVersion() != request.expectedPublishedVersion()) {
            throw versionConflict();
        }
        if (formulas.findDraft(ledgerId, code).isPresent()) {
            throw problem(409, "REPORT_FORMULA_DRAFT_EXISTS", "Draft already exists",
                    "Discard the existing draft before rolling back");
        }
        ReportFormulaRepository.Revision historical = formulas.findPublishedVersion(ledgerId, code, version)
                .orElseThrow(() -> problem(404, "REPORT_FORMULA_NOT_FOUND", "Report formula version not found",
                        "The ledger has no published version " + version + " of " + code));
        ReportFormulaDefinition definition = parser.parse(historical.definitionJson());
        validator.requireValid(definition, ledgerId);
        String rollbackJson = writeDefinition(definition, ledgerId, code,
                historical.definitionJson(), snapshot.formulaJson());
        int newVersion = snapshot.publishedVersion() + 1;
        UUID revisionId = formulas.insertPublished(snapshot.id(), rollbackJson,
                snapshot.publishedVersion(), newVersion, "ROLLBACK", version, actorId);
        formulas.replaceAccountReferences(revisionId, ledgerId, concreteAccountIds(definition));
        formulas.publishSnapshot(snapshot.id(), definition.kind(), rollbackJson,
                newVersion, actorId);
        formulas.recordAudit(ledgerId, snapshot.id(), "ROLLBACK", actorId,
                snapshot.formulaJson(), rollbackJson);
        return new ReportFormulaResponses.RollbackResult(code, newVersion);
    }

    /** Merges SME line edits / CAS rule edits into the draft definition. */
    private ReportFormulaDefinition mergeEdit(ReportFormulaDefinition current,
                                              ReportFormulaDefinition base,
                                              ReportFormulaRequests.DraftUpdate request) {
        boolean fixedLines = ReportFormulaDefinition.KIND_FIXED_LINES.equals(current.kind());
        if (fixedLines) {
            if (request.lines() == null || request.lines().isEmpty() || request.rules() != null) {
                throw problem(422, "REPORT_FORMULA_INVALID", "Report formula is invalid",
                        "SME edits accept line keys, names and expressions only");
            }
            if (request.lines().stream().anyMatch(Objects::isNull)) {
                throw problem(422, "REPORT_FORMULA_INVALID", "Report formula is invalid",
                        "SME line edits must not contain null entries");
            }
            Map<String, ReportFormulaRequests.LineEdit> edits = new LinkedHashMap<>();
            request.lines().forEach(edit -> edits.put(edit.lineKey(), edit));
            List<FormulaGroup> groups = new ArrayList<>();
            for (FormulaGroup group : current.groups()) {
                List<FormulaLine> lines = new ArrayList<>();
                for (FormulaLine line : group.lines()) {
                    ReportFormulaRequests.LineEdit edit = edits.remove(line.key());
                    if (edit != null) {
                        LineExpression expression = expression(edit.expression());
                        lines.add(new FormulaLine(line.key(), line.lineNo(), line.indent(),
                                line.rowType(), edit.name(), expression));
                    } else {
                        lines.add(line);
                    }
                }
                groups.add(new FormulaGroup(group.key(), group.title(), lines));
            }
            if (!edits.isEmpty()) {
                throw problem(422, "REPORT_FORMULA_INVALID", "Report formula is invalid",
                        "Unknown SME line keys: " + edits.keySet());
            }
            return new ReportFormulaDefinition(current.schemaVersion(), current.kind(),
                    current.reportType(), current.templateCode(), current.columnPolicy(),
                    groups, current.rules(), current.checks());
        }
        if (request.rules() == null || request.rules().isEmpty() || request.lines() != null) {
            throw problem(422, "REPORT_FORMULA_INVALID", "Report formula is invalid",
                    "CAS edits accept detail rules only");
        }
        if (request.rules().stream().anyMatch(Objects::isNull)) {
            throw problem(422, "REPORT_FORMULA_INVALID", "Report formula is invalid",
                    "CAS detail rules must not contain null entries");
        }
        List<DetailRule> rules = request.rules().stream()
                .map(rule -> new DetailRule(rule.key(), rule.side(),
                        rule.categories() == null ? List.of() : rule.categories(),
                        rule.accounts() == null ? List.of() : rule.accounts()))
                .toList();
        return new ReportFormulaDefinition(current.schemaVersion(), current.kind(),
                current.reportType(), current.templateCode(), current.columnPolicy(),
                List.of(), rules, current.checks());
    }

    private LineExpression expression(Object rawExpression) {
        JsonNode node = objectMapper.valueToTree(rawExpression);
        String type = node.path("type").asText();
        return switch (type) {
            case "ACCOUNT_AMOUNT" -> {
                List<AccountReference> accounts = new ArrayList<>();
                node.path("accounts").forEach(account -> accounts.add(
                        new AccountReference(account.path("type").asText(),
                                account.path("value").asText())));
                String basis = node.path("basis").asText("");
                yield new AccountAmountExpression(node.path("operation").asText(),
                        node.path("side").asText(), accounts,
                        basis.isBlank() ? null : enumValue(
                                ReportFormulaDefinition.AmountBasis.class, basis, "basis"));
            }
            case "LINEAR_COMBINATION" -> {
                List<com.example.accounting.ledger.formula.ReportFormulaDefinition.LineComponent> components =
                        new ArrayList<>();
                node.path("components").forEach(component -> components.add(
                        new com.example.accounting.ledger.formula.ReportFormulaDefinition.LineComponent(
                                component.path("lineKey").asText(), component.path("factor").asInt(1))));
                yield new com.example.accounting.ledger.formula.ReportFormulaDefinition
                        .LinearCombinationExpression(components);
            }
            case "CASH_FLOW_ITEM_AMOUNT" -> {
                List<String> itemCodes = new ArrayList<>();
                node.path("itemCodes").forEach(itemCode -> itemCodes.add(itemCode.asText()));
                List<AccountReference> cashAccounts = new ArrayList<>();
                node.path("cashAccounts").forEach(account -> cashAccounts.add(
                        new AccountReference(account.path("type").asText(),
                                account.path("value").asText())));
                yield new CashFlowItemAmountExpression(
                        enumValue(ReportFormulaDefinition.CashFlowDirection.class,
                                node.path("direction").asText(), "direction"),
                        itemCodes, cashAccounts);
            }
            default -> throw problem(422, "REPORT_FORMULA_INVALID", "Report formula is invalid",
                    "Unsupported expression type " + type);
        };
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw problem(422, "REPORT_FORMULA_INVALID", "Report formula is invalid",
                    field + " has an unsupported value: " + value);
        }
    }

    /**
     * Cash flow preview with the same calculation scope as the statutory
     * statement: year-to-date primary column, single-month comparative column,
     * and structured data completeness warnings carried inside the statement.
     */
    private StatutoryReportResponses.Statement cashFlowPreview(
            UUID ledgerId, ReportFormulaDefinition definition, String firstPeriod, PeriodRange selected) {
        PeriodRange yearToDate = new PeriodRange(firstPeriod, selected.periodTo());
        requireFormulaProjection(ledgerId, yearToDate);
        requireFormulaProjection(ledgerId, selected);
        Set<UUID> cashAccountIds = accountResolver.expandToLeafIds(ledgerId,
                cashAccountReferences(definition));
        Set<String> itemCodes = referencedItemCodes(definition);
        List<FormulaAccountAmount> primaryBalances =
                reports.formulaAccountAmounts(ledgerId, yearToDate, false);
        List<FormulaAccountAmount> monthlyBalances =
                reports.formulaAccountAmounts(ledgerId, selected, false);
        CashFlowSource primaryFlows = reports.cashFlowAmounts(
                ledgerId, yearToDate, cashAccountIds, itemCodes);
        CashFlowSource monthlyFlows = reports.cashFlowAmounts(
                ledgerId, selected, cashAccountIds, itemCodes);
        ReportingRepository.CashFlowQuality primaryQuality = reports.cashFlowQuality(
                ledgerId, yearToDate, cashAccountIds, itemCodes, MAX_QUALITY_SAMPLES);
        ReportingRepository.CashFlowQuality monthlyQuality = reports.cashFlowQuality(
                ledgerId, selected, cashAccountIds, itemCodes, MAX_QUALITY_SAMPLES);
        StatutoryReportResponses.Statement result = evaluator.evaluateFixedLines(
                ledgerId, definition, primaryBalances, monthlyBalances, primaryFlows, monthlyFlows,
                new ReportFormulaEvaluator.FixedLinesMetadata(
                        "cash-flow", "SME", "2011-17", selected.periodTo(),
                        "本年累计金额", "本月金额"));
        return withQuality(result, primaryQuality, monthlyQuality);
    }

    private StatutoryReportResponses.Statement withQuality(
            StatutoryReportResponses.Statement statement,
            ReportingRepository.CashFlowQuality primary,
            ReportingRepository.CashFlowQuality comparative) {
        boolean complete = primary.unclassifiedLineCount() == 0
                && comparative.unclassifiedLineCount() == 0;
        List<StatutoryReportResponses.QualitySample> samples = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<ReportingRepository.CashFlowQuality> sources = List.of(primary, comparative);
        for (ReportingRepository.CashFlowQuality quality : sources) {
            for (ReportingRepository.CashFlowSample sample : quality.samples()) {
                if (seen.add(sample.voucherId() + ":" + sample.lineNo())) {
                    samples.add(new StatutoryReportResponses.QualitySample(
                            sample.voucherId(), sample.voucherNumber(), sample.periodCode(),
                            sample.voucherDate(), sample.lineNo(), sample.side(),
                            sample.baseAmount(), sample.reason()));
                }
            }
        }
        samples.sort(Comparator.comparing(StatutoryReportResponses.QualitySample::periodCode)
                .thenComparing(StatutoryReportResponses.QualitySample::voucherDate)
                .thenComparing(StatutoryReportResponses.QualitySample::voucherNumber)
                .thenComparingInt(StatutoryReportResponses.QualitySample::lineNo));
        if (samples.size() > MAX_QUALITY_SAMPLES) {
            samples = new ArrayList<>(samples.subList(0, MAX_QUALITY_SAMPLES));
        }
        return new StatutoryReportResponses.Statement(
                statement.reportType(), statement.templateCode(), statement.standardCode(),
                statement.standardVersion(), statement.periodCode(), statement.primaryColumn(),
                statement.comparativeColumn(), statement.groups(), statement.checks(),
                statement.formulaCode(), statement.formulaVersion(),
                new StatutoryReportResponses.DataQuality(
                        complete ? "COMPLETE" : "INCOMPLETE",
                        primary.unclassifiedVoucherCount(), primary.unclassifiedLineCount(),
                        comparative.unclassifiedVoucherCount(), comparative.unclassifiedLineCount(),
                        samples));
    }

    private List<AccountReference> cashAccountReferences(ReportFormulaDefinition definition) {
        Set<AccountReference> references = new LinkedHashSet<>();
        for (FormulaGroup group : definition.groups()) {
            for (FormulaLine line : group.lines()) {
                if (line.expression() instanceof CashFlowItemAmountExpression cashFlow) {
                    references.addAll(cashFlow.cashAccounts());
                } else if (line.expression() instanceof AccountAmountExpression account
                        && account.accounts() != null) {
                    references.addAll(account.accounts());
                }
            }
        }
        return List.copyOf(references);
    }

    private Set<String> referencedItemCodes(ReportFormulaDefinition definition) {
        Set<String> codes = new LinkedHashSet<>();
        for (FormulaGroup group : definition.groups()) {
            for (FormulaLine line : group.lines()) {
                if (line.expression() instanceof CashFlowItemAmountExpression cashFlow) {
                    codes.addAll(cashFlow.itemCodes());
                }
            }
        }
        return codes;
    }

    private PeriodRange previewRange(UUID ledgerId, String code,
                                     ReportFormulaRequests.PreviewRequest request) {
        PeriodRange range;
        try {
            range = request.periodCode() != null
                    ? PeriodRange.single(request.periodCode())
                    : PeriodRange.normalize(null, request.periodFrom(), request.periodTo());
        } catch (IllegalArgumentException exception) {
            throw problem(422, "REPORT_FORMULA_PERIOD_INVALID", "Period is invalid",
                    "Provide a valid periodCode (SME) or periodFrom/periodTo (CAS)");
        }
        if (!reports.periodsExist(ledgerId, range)) {
            throw problem(422, "REPORT_FORMULA_PERIOD_INVALID", "Period is invalid",
                    "The selected periods do not exist in the ledger");
        }
        return range;
    }

    private void requireFormulaProjection(UUID ledgerId, PeriodRange range) {
        if (!reports.statutoryProjectionReady(ledgerId, range)) {
            throw problem(409, "REPORT_FORMULA_PROJECTION_NOT_READY", "Report projection is not ready",
                    "The selected income-statement periods are still being projected; retry shortly");
        }
    }

    private String writeDefinition(ReportFormulaDefinition definition, UUID ledgerId, String code,
                                   String... compatibilitySources) {
        ObjectNode target = (ObjectNode) parser.readTree(parser.write(definition));
        if (!ReportFormulaDefinition.KIND_FIXED_LINES.equals(definition.kind())) {
            return target.toString();
        }
        List<String> sources = new ArrayList<>(List.of(compatibilitySources));
        formulas.findPublishedVersion(ledgerId, code, 1)
                .map(ReportFormulaRepository.Revision::definitionJson)
                .ifPresent(sources::add);
        for (String sourceJson : sources) {
            JsonNode source = parser.readTree(sourceJson);
            for (String field : LEGACY_CATEGORY_FIELDS) {
                if (!target.has(field) && source.path(field).isArray()) {
                    target.set(field, source.path(field));
                }
            }
        }
        return target.toString();
    }

    private Set<UUID> concreteAccountIds(ReportFormulaDefinition definition) {
        Set<UUID> result = new HashSet<>();
        for (FormulaGroup group : definition.groups()) {
            for (FormulaLine line : group.lines()) {
                if (line.expression() instanceof AccountAmountExpression accountAmount) {
                    accountAmount.accounts().forEach(reference -> addConcrete(result, reference));
                } else if (line.expression() instanceof CashFlowItemAmountExpression cashFlow) {
                    cashFlow.cashAccounts().forEach(reference -> addConcrete(result, reference));
                }
            }
        }
        for (DetailRule rule : definition.rules()) {
            rule.accounts().forEach(reference -> addConcrete(result, reference));
        }
        return result;
    }

    private void addConcrete(Set<UUID> result, AccountReference reference) {
        if (reference != null && reference.value() != null
                && !ReportFormulaDefinition.REF_STANDARD_ACCOUNT_KEY.equals(reference.type())) {
            try {
                result.add(UUID.fromString(reference.value()));
            } catch (IllegalArgumentException ignored) {
                // validator reports malformed references before publish
            }
        }
    }

    private ReportFormulaResponses.Draft draftView(ReportFormulaRepository.Revision draft) {
        return new ReportFormulaResponses.Draft(
                draft.draftVersion(), draft.basePublishedVersion(),
                toPlain(parser.readTree(draft.definitionJson())),
                draft.lastPreviewedDraftVersion(), draft.previewHasWarnings(), draft.updatedAt());
    }

    private ReportFormulaResponses.VersionInfo versionInfo(ReportFormulaRepository.Revision revision) {
        return new ReportFormulaResponses.VersionInfo(
                revision.publishedVersion(), revision.source(), revision.rollbackOfVersion(),
                revision.createdBy(), revision.createdAt(), toPlain(parser.readTree(revision.definitionJson())));
    }

    /**
     * The Jackson-2 tree produced by {@link FormulaParser} would serialize as a
     * bean under the Jackson-3 MVC mapper; convert it to plain JSON values.
     */
    private Object toPlain(JsonNode node) {
        return objectMapper.convertValue(node, Object.class);
    }

    private ReportFormulaResponses.Issue issue(ReportFormulaValidator.FormulaIssue issue) {
        return new ReportFormulaResponses.Issue(issue.code(), issue.path(), issue.message());
    }

    private void requireDraftVersion(ReportFormulaRepository.Revision draft, long expected) {
        if (draft.draftVersion() != expected) {
            throw versionConflict();
        }
    }

    private ApiProblemException versionConflict() {
        return problem(409, "REPORT_FORMULA_VERSION_CONFLICT", "Version conflict",
                "The draft or published version changed; reload and retry");
    }

    private void requireCode(String code) {
        if (!CODES.contains(code)) {
            throw problem(404, "REPORT_FORMULA_NOT_FOUND", "Report formula not found",
                    "Only BALANCE_SHEET, INCOME_STATEMENT and CASH_FLOW formulas exist");
        }
    }

    private void requireView(UUID actorId, UUID ledgerId) {
        if (!VIEW_ROLES.contains(ledgerAccess.requireMembership(actorId, ledgerId))) {
            throw problem(403, "INSUFFICIENT_LEDGER_ROLE", "Insufficient ledger role",
                    "The current user cannot view report formulas");
        }
    }

    private void requireWrite(UUID actorId, UUID ledgerId) {
        if (!WRITE_ROLES.contains(ledgerAccess.requireMembership(actorId, ledgerId))) {
            throw problem(403, "INSUFFICIENT_LEDGER_ROLE", "Insufficient ledger role",
                    "Only an owner or editor can edit report formulas");
        }
    }

    private ApiProblemException problem(int status, String code, String title, String detail) {
        return new ApiProblemException(status, code, title, detail, false);
    }
}
