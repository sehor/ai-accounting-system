package com.example.accounting.reporting.internal.application;

import com.example.accounting.ledger.LedgerAccessService;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.ledger.AccountingStandardCatalog;
import com.example.accounting.reporting.FinanceQueryRequests;
import com.example.accounting.reporting.DimensionLedgerRequests;
import com.example.accounting.reporting.ReportResponses;
import com.example.accounting.reporting.StatutoryReportResponses;
import com.example.accounting.reporting.ReportingService;
import com.example.accounting.reporting.PeriodRange;
import com.example.accounting.reporting.internal.port.ReportingRepository;
import com.example.accounting.shared.web.ApiProblemException;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class DefaultReportingService implements ReportingService {

    private static final Set<LedgerRole> VIEW_ROLES = Set.of(
            LedgerRole.OWNER, LedgerRole.EDITOR, LedgerRole.REVIEWER, LedgerRole.VIEWER, LedgerRole.AGENT);
    private static final int MAX_DIMENSION_QUERY_ROWS = 10_000;
    private static final int MAX_DIMENSION_LEDGER_PERIODS = 36;
    private static final String UNASSIGNED = "UNASSIGNED";

    private final LedgerAccessService ledgerAccess;
    private final ReportingRepository reports;
    private final AccountingStandardCatalog standards;

    @Autowired
    public DefaultReportingService(LedgerAccessService ledgerAccess, ReportingRepository reports,
                                   AccountingStandardCatalog standards) {
        this.ledgerAccess = ledgerAccess;
        this.reports = reports;
        this.standards = standards;
    }

    /** Compatibility constructor retained for focused service unit tests. */
    public DefaultReportingService(LedgerAccessService ledgerAccess, ReportingRepository reports) {
        this(ledgerAccess, reports, new AccountingStandardCatalog());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReportResponses.TrialBalanceLine> trialBalance(UUID actorId, UUID ledgerId, String periodCode) {
        return trialBalance(actorId, ledgerId, PeriodRange.single(periodCode), false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReportResponses.TrialBalanceLine> trialBalance(
            UUID actorId, UUID ledgerId, String periodCode, boolean includeParents) {
        return trialBalance(actorId, ledgerId, PeriodRange.single(periodCode), includeParents);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReportResponses.TrialBalanceLine> trialBalance(
            UUID actorId, UUID ledgerId, PeriodRange range, boolean includeParents) {
        requireView(actorId, ledgerId);
        validateRange(ledgerId, range);
        return reports.trialBalance(ledgerId, range, includeParents);
    }

    @Override
    @Transactional(readOnly = true)
    public ReportResponses.Statement balanceSheet(UUID actorId, UUID ledgerId, String periodCode) {
        return balanceSheet(actorId, ledgerId, PeriodRange.single(periodCode));
    }

    @Override
    @Transactional(readOnly = true)
    public ReportResponses.Statement balanceSheet(UUID actorId, UUID ledgerId, PeriodRange range) {
        requireView(actorId, ledgerId);
        validateRange(ledgerId, range);
        List<ReportResponses.TrialBalanceLine> lines = reports.trialBalance(ledgerId, range, false);
        Set<String> debitCategories = reports.formulaCategories(ledgerId, "BALANCE_SHEET", "debitCategories");
        Set<String> creditCategories = reports.formulaCategories(ledgerId, "BALANCE_SHEET", "creditCategories");
        List<ReportResponses.StatementLine> result = lines.stream()
                .filter(line -> debitCategories.contains(line.category()) || creditCategories.contains(line.category()))
                .map(line -> new ReportResponses.StatementLine(line.code(), line.name(),
                        debitCategories.contains(line.category()) ? line.closingDebit().subtract(line.closingCredit())
                                : line.closingCredit().subtract(line.closingDebit())))
                .toList();
        return new ReportResponses.Statement(result.size(), result);
    }

    @Override
    @Transactional(readOnly = true)
    public ReportResponses.Statement incomeStatement(UUID actorId, UUID ledgerId, String periodCode) {
        return incomeStatement(actorId, ledgerId, PeriodRange.single(periodCode));
    }

    @Override
    @Transactional(readOnly = true)
    public ReportResponses.Statement incomeStatement(UUID actorId, UUID ledgerId, PeriodRange range) {
        requireView(actorId, ledgerId);
        validateRange(ledgerId, range);
        requireIncomeProjection(ledgerId, range);
        List<ReportResponses.TrialBalanceLine> lines = reports.incomeStatementTrialBalance(ledgerId, range, false);
        Set<String> revenueCategories = reports.formulaCategories(
                ledgerId, "INCOME_STATEMENT", "revenueCategories");
        Set<String> expenseCategories = reports.formulaCategories(
                ledgerId, "INCOME_STATEMENT", "expenseCategories");
        List<ReportResponses.StatementLine> result = lines.stream()
                .filter(line -> revenueCategories.contains(line.category())
                        || expenseCategories.contains(line.category()))
                .map(line -> new ReportResponses.StatementLine(line.code(), line.name(),
                        revenueCategories.contains(line.category()) ? line.periodCredit().subtract(line.periodDebit())
                                : line.periodDebit().subtract(line.periodCredit())))
                .toList();
        return new ReportResponses.Statement(result.size(), result);
    }

    @Override
    @Transactional(readOnly = true)
    public StatutoryReportResponses.Statement statutoryStatement(
            UUID actorId, UUID ledgerId, String reportType, String periodCode) {
        requireView(actorId, ledgerId);
        if (!"balance-sheet".equals(reportType) && !"income-statement".equals(reportType)) {
            throw problem(404, "STATUTORY_REPORT_NOT_FOUND", "Statutory report not found",
                    "Only balance-sheet and income-statement are supported");
        }
        PeriodRange selected = PeriodRange.single(periodCode);
        validateRange(ledgerId, selected);
        ReportResponses.LedgerProfile profile = reports.ledgerProfile(ledgerId);
        if (!"SME".equalsIgnoreCase(profile.accountingStandardCode())) {
            throw problem(422, "STATUTORY_REPORT_UNSUPPORTED_STANDARD", "法定报表不可用",
                    "当前账套不是小企业会计准则，暂不提供法定报表");
        }
        if (!"CNY".equalsIgnoreCase(profile.baseCurrency())) {
            throw problem(422, "STATUTORY_REPORT_CURRENCY_UNSUPPORTED", "法定报表不可用",
                    "小企业会计准则法定报表首版仅支持人民币账套");
        }
        String standardVersion = "v1".equals(profile.accountingStandardVersion())
                ? "2011-17" : profile.accountingStandardVersion();
        String formulaCode = "balance-sheet".equals(reportType) ? "BALANCE_SHEET" : "INCOME_STATEMENT";
        JsonNode formula = standards.formula("SME", standardVersion, formulaCode)
                .map(com.example.accounting.ledger.AccountingStandard.Formula::definition)
                .orElseThrow(() -> problem(500, "STATUTORY_FORMULA_NOT_FOUND", "法定报表公式缺失",
                        "当前小企业会计准则标准包缺少法定报表公式"));
        String firstPeriod = reports.firstPeriodOfYear(ledgerId, periodCode);
        if (firstPeriod == null) {
            throw problem(404, "PERIOD_NOT_FOUND", "Period not found",
                    "No accounting period is available in the selected year");
        }
        List<ReportingRepository.StatutoryAccountAmount> primary;
        List<ReportingRepository.StatutoryAccountAmount> comparative;
        if ("income-statement".equals(reportType)) {
            PeriodRange yearToDate = new PeriodRange(firstPeriod, periodCode);
            requireStatutoryProjection(ledgerId, yearToDate);
            requireStatutoryProjection(ledgerId, selected);
            primary = reports.statutoryAccountAmounts(ledgerId, yearToDate, true);
            comparative = reports.statutoryAccountAmounts(ledgerId, selected, true);
            requireStatutoryMappings(primary);
            requireStatutoryMappings(comparative);
        } else {
            PeriodRange openingPeriod = PeriodRange.single(firstPeriod);
            requireStatutoryProjection(ledgerId, selected);
            requireStatutoryProjection(ledgerId, openingPeriod);
            primary = reports.statutoryAccountAmounts(ledgerId, selected, false);
            List<ReportingRepository.StatutoryAccountAmount> opening =
                    reports.statutoryAccountAmounts(ledgerId, openingPeriod, false);
            requireStatutoryMappings(primary);
            requireStatutoryMappings(opening);
            comparative = openingAmounts(opening);
        }
        return new StatutoryReportCalculator().calculate(reportType, periodCode, standardVersion, formula,
                primary, comparative);
    }

    private void requireStatutoryProjection(UUID ledgerId, PeriodRange range) {
        if (!reports.statutoryProjectionReady(ledgerId, range)) {
            throw problem(409, "STATUTORY_REPORT_PROJECTION_PENDING", "法定报表暂不可用",
                    "余额投影正在更新，请稍后刷新报表");
        }
    }

    private void requireIncomeProjection(UUID ledgerId, PeriodRange range) {
        if (!reports.statutoryProjectionReady(ledgerId, range)) {
            throw problem(409, "INCOME_STATEMENT_PROJECTION_PENDING", "利润表暂不可用",
                    "余额投影正在更新，请稍后刷新报表");
        }
    }

    private void requireStatutoryMappings(List<ReportingRepository.StatutoryAccountAmount> amounts) {
        List<ReportingRepository.StatutoryAccountAmount> unmapped = amounts.stream()
                .filter(amount -> amount.standardAccountKey() == null && hasAnyAmount(amount))
                .toList();
        if (!unmapped.isEmpty()) {
            String identifiers = unmapped.stream().limit(10)
                    .map(amount -> amount.accountId() + "/" + amount.accountCode())
                    .collect(Collectors.joining(", "));
            if (unmapped.size() > 10) {
                identifiers += ", ... (" + unmapped.size() + " accounts)";
            }
            throw problem(422, "STATUTORY_ACCOUNT_MAPPING_REQUIRED", "Statutory account mapping required",
                    "Map the following non-zero leaf accounts before generating the report: " + identifiers);
        }
    }

    private boolean hasAnyAmount(ReportingRepository.StatutoryAccountAmount amount) {
        return amount.openingDebit().signum() != 0 || amount.openingCredit().signum() != 0
                || amount.periodDebit().signum() != 0 || amount.periodCredit().signum() != 0
                || amount.closingDebit().signum() != 0 || amount.closingCredit().signum() != 0;
    }

    private List<ReportingRepository.StatutoryAccountAmount> openingAmounts(
            List<ReportingRepository.StatutoryAccountAmount> amounts) {
        return amounts.stream().map(amount -> new ReportingRepository.StatutoryAccountAmount(
                amount.accountId(), amount.accountCode(), amount.standardAccountKey(),
                amount.openingDebit(), amount.openingCredit(), BigDecimal.ZERO, BigDecimal.ZERO,
                amount.openingDebit(), amount.openingCredit())).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReportResponses.LedgerLine> generalLedger(UUID actorId, UUID ledgerId, String periodCode) {
        requireView(actorId, ledgerId);
        return reports.ledgerLines(ledgerId, periodCode);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReportResponses.LedgerLine> subLedger(UUID actorId, UUID ledgerId, String periodCode) {
        requireView(actorId, ledgerId);
        return reports.ledgerLines(ledgerId, periodCode);
    }

    @Override
    @Transactional(readOnly = true)
    public ReportResponses.GeneralLedgerPage generalLedgerBook(
            UUID actorId, UUID ledgerId, String periodCode, int page, int pageSize) {
        requireView(actorId, ledgerId);
        PeriodRange range = PeriodRange.single(periodCode);
        validateBookRequest(ledgerId, range, page, pageSize);
        return reports.generalLedgerBook(ledgerId, range, page, pageSize);
    }

    @Override
    @Transactional(readOnly = true)
    public ReportResponses.GeneralLedgerPage generalLedgerBook(
            UUID actorId, UUID ledgerId, PeriodRange range, int page, int pageSize) {
        requireView(actorId, ledgerId);
        validateBookRequest(ledgerId, range, page, pageSize);
        return reports.generalLedgerBook(ledgerId, range, page, pageSize);
    }

    @Override
    @Transactional(readOnly = true)
    public ReportResponses.SubLedgerPage subLedgerBook(
            UUID actorId, UUID ledgerId, String periodCode, UUID accountId, int page, int pageSize) {
        requireView(actorId, ledgerId);
        PeriodRange range = PeriodRange.single(periodCode);
        validateBookRequest(ledgerId, range, page, pageSize);
        if (accountId == null || !reports.accountExists(ledgerId, accountId)) {
            throw problem(404, "ACCOUNT_NOT_FOUND", "Account not found",
                    "The account is not available to this ledger");
        }
        return reports.subLedgerBook(ledgerId, range, accountId, page, pageSize);
    }

    @Override
    @Transactional(readOnly = true)
    public ReportResponses.SubLedgerPage subLedgerBook(
            UUID actorId, UUID ledgerId, PeriodRange range, UUID accountId, int page, int pageSize) {
        requireView(actorId, ledgerId);
        validateBookRequest(ledgerId, range, page, pageSize);
        if (accountId == null || !reports.accountExists(ledgerId, accountId)) {
            throw problem(404, "ACCOUNT_NOT_FOUND", "Account not found",
                    "The account is not available to this ledger");
        }
        return reports.subLedgerBook(ledgerId, range, accountId, page, pageSize);
    }

    private void validateBookRequest(UUID ledgerId, PeriodRange range, int page, int pageSize) {
        if (!reports.periodsExist(ledgerId, range)) {
            throw problem(404, "PERIOD_NOT_FOUND", "Period not found",
                    "The period is not available to this ledger");
        }
        if (page < 1 || pageSize < 1 || pageSize > 500) {
            throw problem(400, "PAGINATION_INVALID", "Invalid pagination",
                    "page must be positive and pageSize must be between 1 and 500");
        }
    }

    private void validateRange(UUID ledgerId, PeriodRange range) {
        if (!reports.periodsExist(ledgerId, range)) {
            throw problem(404, "PERIOD_NOT_FOUND", "Period not found",
                    "One or both range endpoints are not available to this ledger");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReportResponses.FinanceQueryLine> financeQuery(UUID actorId, UUID ledgerId,
                                                               FinanceQueryRequests.Query request) {
        requireView(actorId, ledgerId);
        PeriodRange range = PeriodRange.normalize(null, request.periodFrom(), request.periodTo());
        validateRange(ledgerId, range);
        String baseCurrency = reports.baseCurrency(ledgerId);
        if (requiresDimensionProjection(request)) {
            return dimensionFinanceQuery(ledgerId, range, request);
        }
        if (request.groupBy().contains("MONTH")) {
            List<ReportResponses.FinanceQueryLine> monthly = new ArrayList<>();
            YearMonth month = YearMonth.parse(range.periodFrom());
            YearMonth last = YearMonth.parse(range.periodTo());
            while (!month.isAfter(last)) {
                String period = month.toString();
                List<ReportResponses.TrialBalanceLine> monthLines = reports
                        .trialBalance(ledgerId, PeriodRange.single(period), false).stream()
                        .filter(line -> matchesAccountFilter(request, line))
                        .toList();
                if (request.groupBy().contains("ACCOUNT")) {
                    monthLines.forEach(line -> monthly.add(new ReportResponses.FinanceQueryLine(
                            groupKey(request.groupBy(), line.code(), period, baseCurrency),
                            metric(request.metric(), line))));
                } else if (!monthLines.isEmpty()) {
                    BigDecimal amount = monthLines.stream().map(line -> metric(request.metric(), line))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    monthly.add(new ReportResponses.FinanceQueryLine(
                            groupKey(request.groupBy(), null, period, baseCurrency), amount));
                }
                month = month.plusMonths(1);
            }
            return monthly;
        }
        List<ReportResponses.TrialBalanceLine> lines = reports.trialBalance(ledgerId, range, false).stream()
                .filter(line -> matchesAccountFilter(request, line))
                .toList();
        if (request.groupBy().contains("DIMENSION")) {
            throw problem(422, "FINANCE_QUERY_GROUP_UNSUPPORTED", "Unsupported finance query group",
                    "Dimension grouping requires dimension facts that are not available in v0.1");
        }
        if (lines.isEmpty()) {
            return List.of();
        }
        if (!request.groupBy().contains("ACCOUNT")) {
            BigDecimal amount = lines.stream().map(line -> metric(request.metric(), line))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return List.of(new ReportResponses.FinanceQueryLine(
                    groupKey(request.groupBy(), null, range.periodCode(), baseCurrency), amount));
        }
        return lines.stream().map(line -> new ReportResponses.FinanceQueryLine(
                groupKey(request.groupBy(), line.code(), range.periodCode(), baseCurrency), metric(request.metric(), line)))
                .toList();
    }

    private boolean requiresDimensionProjection(FinanceQueryRequests.Query request) {
        return request.groupBy().contains("DIMENSION") || request.groupBy().contains("CURRENCY")
                || (request.filters() != null && !request.filters().dimensionValues().isEmpty())
                || (request.filters() != null && request.filters().currency() != null);
    }

    private List<ReportResponses.FinanceQueryLine> dimensionFinanceQuery(
            UUID ledgerId, PeriodRange range, FinanceQueryRequests.Query request) {
        Map<UUID, ReportingRepository.DimensionTypeInfo> groupTypes = validateDimensionQuery(ledgerId, request);
        if (!reports.dimensionProjectionReady(ledgerId, range)) {
            throw problem(409, "BALANCE_PROJECTION_NOT_READY", "Balance projection not ready",
                    "The auxiliary balance projection has pending or failed events");
        }
        FinanceQueryRequests.Filters filters = request.filters();
        List<String> accountCodes = filters == null ? List.of() : filters.accountCodes();
        String currency = filters == null ? null : filters.currency();
        List<ReportingRepository.DimensionLedgerFilter> dimensionFilters = filters == null ? List.of()
                : filters.dimensionValues().stream()
                .map(value -> new ReportingRepository.DimensionLedgerFilter(
                        value.dimensionTypeId(), value.dimensionValueId()))
                .toList();
        List<ReportingRepository.DimensionBalanceRow> rows = reports.dimensionBalances(
                ledgerId, range, accountCodes, currency, dimensionFilters,
                "BALANCE".equals(request.metric()), MAX_DIMENSION_QUERY_ROWS + 1);
        if (rows.size() > MAX_DIMENSION_QUERY_ROWS) {
            throw problem(422, "FINANCE_QUERY_TOO_BROAD", "Finance query is too broad",
                    "Narrow the period, account, currency, or dimension filters");
        }
        List<ReportingRepository.DimensionBalanceRow> selected = rows.stream()
                .filter(row -> "BALANCE".equals(request.metric()) ? row.periodCode().equals(range.periodTo()) : true)
                .filter(row -> matchesAccountFilter(request, row.accountCode()))
                .filter(row -> matchesCurrencyFilter(request, row.currency()))
                .filter(row -> matchesDimensionFilters(request, row.dimensions()))
                .toList();
        if (selected.isEmpty()) {
            return List.of();
        }
        Map<String, FinanceAggregate> aggregates = new LinkedHashMap<>();
        for (ReportingRepository.DimensionBalanceRow row : selected) {
            List<ReportResponses.FinanceQueryDimension> groupedDimensions = groupedDimensions(
                    request, row.dimensions(), groupTypes);
            String groupKey = dimensionGroupKey(request.groupBy(), row, groupedDimensions);
            aggregates.computeIfAbsent(groupKey, ignored -> new FinanceAggregate(groupedDimensions))
                    .add(row, dimensionMetric(request.metric(), row));
        }
        return aggregates.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> {
            FinanceAggregate aggregate = entry.getValue();
            return new ReportResponses.FinanceQueryLine(entry.getKey(), aggregate.amount,
                    aggregate.singleOrNull(aggregate.dimensionKeys), aggregate.dimensions,
                    aggregate.singleOrNull(aggregate.currencies), aggregate.singleOrNull(aggregate.periodCodes),
                    aggregate.singleOrNull(aggregate.accountCodes));
        }).toList();
    }

    private Map<UUID, ReportingRepository.DimensionTypeInfo> validateDimensionQuery(
            UUID ledgerId, FinanceQueryRequests.Query request) {
        List<UUID> groupTypes = request.dimensionGroupTypeIds();
        if (request.groupBy().contains("DIMENSION") && (groupTypes.isEmpty() || groupTypes.size() > 4)) {
            throw problem(422, "FINANCE_QUERY_DIMENSION_GROUP_INVALID", "Invalid dimension group",
                    "DIMENSION grouping requires one to four dimension types");
        }
        if (new HashSet<>(groupTypes).size() != groupTypes.size()) {
            throw problem(422, "FINANCE_QUERY_DIMENSION_GROUP_INVALID", "Invalid dimension group",
                    "Dimension group types must not repeat");
        }
        Map<UUID, ReportingRepository.DimensionTypeInfo> types = new HashMap<>();
        for (UUID typeId : groupTypes) {
            ReportingRepository.DimensionTypeInfo type = typeId == null ? null : reports.dimensionType(ledgerId, typeId);
            if (type == null) {
                throw problem(422, "FINANCE_QUERY_DIMENSION_TYPE_INVALID", "Invalid dimension type",
                        "A dimension group type is not available to this ledger");
            }
            types.put(typeId, type);
        }
        List<FinanceQueryRequests.DimensionValue> filters = request.filters() == null
                ? List.of() : request.filters().dimensionValues();
        if (filters.size() > 16) {
            throw problem(422, "FINANCE_QUERY_DIMENSION_FILTER_INVALID", "Invalid dimension filter",
                    "At most sixteen dimension values may be filtered");
        }
        Set<UUID> filterTypes = new HashSet<>();
        for (FinanceQueryRequests.DimensionValue filter : filters) {
            if (filter == null || filter.dimensionTypeId() == null || filter.dimensionValueId() == null
                    || !filterTypes.add(filter.dimensionTypeId())
                    || !reports.dimensionValueExists(ledgerId, filter.dimensionTypeId(), filter.dimensionValueId())) {
                throw problem(422, "FINANCE_QUERY_DIMENSION_FILTER_INVALID", "Invalid dimension filter",
                        "Each dimension filter must reference one value of one ledger dimension type");
            }
        }
        return types;
    }

    private boolean matchesAccountFilter(FinanceQueryRequests.Query request, String accountCode) {
        return request.filters() == null || request.filters().accountCodes().isEmpty()
                || request.filters().accountCodes().contains(accountCode);
    }

    private boolean matchesCurrencyFilter(FinanceQueryRequests.Query request, String currency) {
        return request.filters() == null || request.filters().currency() == null
                || request.filters().currency().equals(currency);
    }

    private boolean matchesDimensionFilters(FinanceQueryRequests.Query request,
                                            List<ReportResponses.FinanceQueryDimension> dimensions) {
        if (request.filters() == null || request.filters().dimensionValues().isEmpty()) {
            return true;
        }
        Map<UUID, UUID> valuesByType = dimensions.stream().collect(Collectors.toMap(
                ReportResponses.FinanceQueryDimension::dimensionTypeId,
                ReportResponses.FinanceQueryDimension::dimensionValueId));
        return request.filters().dimensionValues().stream().allMatch(filter ->
                filter.dimensionValueId().equals(valuesByType.get(filter.dimensionTypeId())));
    }

    private List<ReportResponses.FinanceQueryDimension> groupedDimensions(
            FinanceQueryRequests.Query request, List<ReportResponses.FinanceQueryDimension> dimensions,
            Map<UUID, ReportingRepository.DimensionTypeInfo> groupTypes) {
        if (!request.groupBy().contains("DIMENSION")) {
            return List.of();
        }
        Map<UUID, ReportResponses.FinanceQueryDimension> dimensionsByType = dimensions.stream().collect(
                Collectors.toMap(ReportResponses.FinanceQueryDimension::dimensionTypeId, dimension -> dimension));
        return request.dimensionGroupTypeIds().stream().map(typeId -> dimensionsByType.getOrDefault(typeId,
                new ReportResponses.FinanceQueryDimension(typeId, null, groupTypes.get(typeId).code(),
                        groupTypes.get(typeId).name(), UNASSIGNED, "Unassigned"))).toList();
    }

    private String dimensionGroupKey(List<String> groups, ReportingRepository.DimensionBalanceRow row,
                                     List<ReportResponses.FinanceQueryDimension> dimensions) {
        return groups.stream().map(group -> switch (group) {
            case "ACCOUNT" -> row.accountCode();
            case "MONTH" -> row.periodCode();
            case "CURRENCY" -> row.currency();
            case "DIMENSION" -> dimensions.stream().map(dimension -> dimension.dimensionValueId() == null
                    ? UNASSIGNED : dimension.dimensionValueCode()).collect(Collectors.joining(","));
            default -> throw problem(422, "FINANCE_QUERY_INVALID", "Invalid finance query",
                    "The group is not in the whitelist");
        }).collect(Collectors.joining("|"));
    }

    private BigDecimal dimensionMetric(String metric, ReportingRepository.DimensionBalanceRow row) {
        return switch (metric) {
            case "DEBIT" -> row.periodDebitBase();
            case "CREDIT" -> row.periodCreditBase();
            case "NET" -> row.periodDebitBase().subtract(row.periodCreditBase());
            case "BALANCE" -> row.closingDebitBase().subtract(row.closingCreditBase());
            default -> throw problem(422, "FINANCE_QUERY_INVALID", "Invalid finance query",
                    "The metric is not in the whitelist");
        };
    }

    private boolean matchesAccountFilter(
            FinanceQueryRequests.Query request, ReportResponses.TrialBalanceLine line) {
        return request.filters() == null || request.filters().accountCodes().isEmpty()
                || request.filters().accountCodes().contains(line.code());
    }

    private BigDecimal metric(String metric, ReportResponses.TrialBalanceLine line) {
        return switch (metric) {
            case "DEBIT" -> line.debit();
            case "CREDIT" -> line.credit();
            case "NET", "BALANCE" -> line.balance();
            default -> throw problem(422, "FINANCE_QUERY_INVALID", "Invalid finance query",
                    "The metric is not in the whitelist");
        };
    }

    private String groupKey(List<String> groups, String account, String period, String currency) {
        return groups.stream().map(group -> switch (group) {
            case "ACCOUNT" -> account;
            case "MONTH" -> period;
            case "CURRENCY" -> currency;
            default -> throw problem(422, "FINANCE_QUERY_INVALID", "Invalid finance query",
                    "The group is not in the whitelist");
        }).collect(Collectors.joining("|"));
    }

    private static final class FinanceAggregate {
        private BigDecimal amount = BigDecimal.ZERO;
        private final List<ReportResponses.FinanceQueryDimension> dimensions;
        private final Set<String> dimensionKeys = new HashSet<>();
        private final Set<String> currencies = new HashSet<>();
        private final Set<String> periodCodes = new HashSet<>();
        private final Set<String> accountCodes = new HashSet<>();

        private FinanceAggregate(List<ReportResponses.FinanceQueryDimension> dimensions) {
            this.dimensions = List.copyOf(dimensions);
        }

        private void add(ReportingRepository.DimensionBalanceRow row, BigDecimal increment) {
            amount = amount.add(increment);
            dimensionKeys.add(row.dimensionKey());
            currencies.add(row.currency());
            periodCodes.add(row.periodCode());
            accountCodes.add(row.accountCode());
        }

        private String singleOrNull(Set<String> values) {
            return values.size() == 1 ? values.iterator().next() : null;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ReportResponses.DimensionLedgerPage dimensionLedger(
            UUID actorId, UUID ledgerId, DimensionLedgerRequests.Query request) {
        requireView(actorId, ledgerId);
        PeriodRange range = new PeriodRange(request.periodFrom(), request.periodTo());
        validateRange(ledgerId, range);
        validateDimensionLedgerRequest(ledgerId, request, range);
        if (!reports.accountExists(ledgerId, request.accountId())) {
            throw problem(404, "ACCOUNT_NOT_FOUND", "Account not found",
                    "The account is not available to this ledger");
        }
        if (!reports.leafAccount(ledgerId, request.accountId())) {
            throw problem(422, "DIMENSION_LEDGER_LEAF_ACCOUNT_REQUIRED", "Leaf account required",
                    "Dimension ledger balances are available only for leaf accounts");
        }
        if (!reports.dimensionProjectionReady(ledgerId, range)) {
            throw problem(409, "BALANCE_PROJECTION_NOT_READY", "Balance projection not ready",
                    "The auxiliary balance projection has pending or failed events");
        }
        List<ReportingRepository.DimensionLedgerFilter> filters = request.dimensionValues().stream()
                .map(ReportingRepository.DimensionLedgerFilter::from).toList();
        List<ReportingRepository.DimensionLedgerBalanceRow> balanceRows = reports.dimensionLedgerBalances(
                ledgerId, range, request.accountId(), request.currency(), filters);
        ReportingRepository.DimensionLedgerEntryPage entryPage = reports.dimensionLedgerEntries(
                ledgerId, range, request.accountId(), request.currency(), filters, request.page(), request.pageSize());
        Map<UUID, ReportingRepository.DimensionTypeInfo> groups = dimensionLedgerGroupTypes(
                ledgerId, request.groupDimensionTypeIds());
        List<String> warnings = balanceRows.stream().filter(row -> "LEGACY_UNMAPPED".equals(row.combinationKind()))
                .map(ignored -> "LEGACY_UNMAPPED").distinct().toList();
        List<ReportResponses.DimensionLedgerBalance> balances = balanceRows.stream().map(row ->
                new ReportResponses.DimensionLedgerBalance(row.combinationId(), row.dimensionKey(),
                        row.combinationKind(), dimensionLedgerGroupKey(request.groupDimensionTypeIds(), groups,
                        row.dimensions()), row.currency(), row.dimensions(), ledgerAmounts(row, true),
                        ledgerAmounts(row, false))).toList();
        List<ReportResponses.DimensionLedgerEntry> entries = entryPage.entries().stream().map(row -> {
            return new ReportResponses.DimensionLedgerEntry(row.voucherId(), row.voucherNumber(), row.voucherDate(),
                    row.lineNo(), row.lineId(), row.accountId(), row.accountCode(), row.accountName(),
                    row.combinationId(), row.dimensionKey(), row.combinationKind(),
                    dimensionLedgerGroupKey(request.groupDimensionTypeIds(), groups, row.dimensions()), row.dimensions(),
                    row.currency(), row.side(), row.originalDebit(), row.originalCredit(), row.baseDebit(),
                    row.baseCredit(), row.runningOriginalDebit(), row.runningOriginalCredit(),
                    row.runningBaseDebit(), row.runningBaseCredit());
        }).toList();
        return new ReportResponses.DimensionLedgerPage("READY", warnings, balances, entries,
                new ReportResponses.Pagination(request.page(), request.pageSize(), entryPage.totalItems(),
                        entryPage.totalItems() == 0 ? 0
                                : (int) ((entryPage.totalItems() + request.pageSize() - 1) / request.pageSize())));
    }

    private void validateDimensionLedgerRequest(UUID ledgerId, DimensionLedgerRequests.Query request,
                                                PeriodRange range) {
        long periods = ChronoUnit.MONTHS.between(YearMonth.parse(range.periodFrom()), YearMonth.parse(range.periodTo())) + 1;
        if (periods > MAX_DIMENSION_LEDGER_PERIODS) {
            throw problem(422, "DIMENSION_LEDGER_PERIOD_RANGE_TOO_LARGE", "Period range is too large",
                    "Dimension ledger queries may span at most thirty-six periods");
        }
        if (request.page() < 1 || request.pageSize() < 1 || request.pageSize() > 200) {
            throw problem(400, "PAGINATION_INVALID", "Invalid pagination",
                    "page must be positive and pageSize must be between 1 and 200");
        }
        if (request.dimensionValues().size() > 16) {
            throw problem(422, "DIMENSION_LEDGER_FILTER_INVALID", "Invalid dimension filter",
                    "At most sixteen dimension values may be filtered");
        }
        Set<UUID> filterTypes = new HashSet<>();
        for (DimensionLedgerRequests.DimensionValue value : request.dimensionValues()) {
            if (value == null || value.dimensionTypeId() == null || value.dimensionValueId() == null
                    || !filterTypes.add(value.dimensionTypeId())
                    || !reports.dimensionValueExists(ledgerId, value.dimensionTypeId(), value.dimensionValueId())) {
                throw problem(422, "DIMENSION_LEDGER_FILTER_INVALID", "Invalid dimension filter",
                        "Each filter must reference one value of one ledger dimension type");
            }
        }
        dimensionLedgerGroupTypes(ledgerId, request.groupDimensionTypeIds());
    }

    private Map<UUID, ReportingRepository.DimensionTypeInfo> dimensionLedgerGroupTypes(
            UUID ledgerId, List<UUID> groupTypeIds) {
        if (groupTypeIds.size() > 4 || new HashSet<>(groupTypeIds).size() != groupTypeIds.size()) {
            throw problem(422, "DIMENSION_LEDGER_GROUP_INVALID", "Invalid dimension group",
                    "At most four distinct ledger dimension types may group the response");
        }
        Map<UUID, ReportingRepository.DimensionTypeInfo> types = new LinkedHashMap<>();
        for (UUID typeId : groupTypeIds) {
            ReportingRepository.DimensionTypeInfo type = typeId == null ? null : reports.dimensionType(ledgerId, typeId);
            if (type == null) {
                throw problem(422, "DIMENSION_LEDGER_GROUP_INVALID", "Invalid dimension group",
                        "A group dimension type is not available to this ledger");
            }
            types.put(typeId, type);
        }
        return types;
    }

    private String dimensionLedgerGroupKey(List<UUID> groupTypeIds,
                                           Map<UUID, ReportingRepository.DimensionTypeInfo> types,
                                           List<ReportResponses.FinanceQueryDimension> dimensions) {
        if (groupTypeIds.isEmpty()) {
            return null;
        }
        Map<UUID, ReportResponses.FinanceQueryDimension> byType = dimensions.stream().collect(Collectors.toMap(
                ReportResponses.FinanceQueryDimension::dimensionTypeId, dimension -> dimension));
        return groupTypeIds.stream().map(typeId -> {
            ReportResponses.FinanceQueryDimension dimension = byType.get(typeId);
            return dimension == null ? UNASSIGNED : dimension.dimensionValueCode();
        }).collect(Collectors.joining("|"));
    }

    private ReportResponses.DimensionLedgerAmounts ledgerAmounts(
            ReportingRepository.DimensionLedgerBalanceRow row, boolean original) {
        return original ? new ReportResponses.DimensionLedgerAmounts(row.openingDebitOriginal(),
                row.openingCreditOriginal(), row.periodDebitOriginal(), row.periodCreditOriginal(),
                row.closingDebitOriginal(), row.closingCreditOriginal())
                : new ReportResponses.DimensionLedgerAmounts(row.openingDebitBase(), row.openingCreditBase(),
                row.periodDebitBase(), row.periodCreditBase(), row.closingDebitBase(), row.closingCreditBase());
    }

    private void requireView(UUID actorId, UUID ledgerId) {
        if (!VIEW_ROLES.contains(ledgerAccess.requireMembership(actorId, ledgerId))) {
            throw problem(403, "INSUFFICIENT_LEDGER_ROLE", "Insufficient ledger role",
                    "The current user cannot view reports");
        }
    }

    private ApiProblemException problem(int status, String code, String title, String detail) {
        return new ApiProblemException(status, code, title, detail, false);
    }
}
