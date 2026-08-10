package com.example.accounting.reporting.internal.application;

import com.example.accounting.ledger.LedgerAccessService;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.reporting.FinanceQueryRequests;
import com.example.accounting.reporting.ReportResponses;
import com.example.accounting.reporting.ReportingService;
import com.example.accounting.reporting.PeriodRange;
import com.example.accounting.reporting.internal.port.ReportingRepository;
import com.example.accounting.shared.web.ApiProblemException;
import java.math.BigDecimal;
import java.util.List;
import java.util.ArrayList;
import java.time.YearMonth;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultReportingService implements ReportingService {

    private static final Set<LedgerRole> VIEW_ROLES = Set.of(
            LedgerRole.OWNER, LedgerRole.EDITOR, LedgerRole.REVIEWER, LedgerRole.VIEWER, LedgerRole.AGENT);

    private final LedgerAccessService ledgerAccess;
    private final ReportingRepository reports;

    public DefaultReportingService(LedgerAccessService ledgerAccess, ReportingRepository reports) {
        this.ledgerAccess = ledgerAccess;
        this.reports = reports;
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
        List<ReportResponses.TrialBalanceLine> lines = reports.trialBalance(ledgerId, range, false);
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
        if (request.filters() != null && request.filters().currency() != null
                && !request.filters().currency().equals(baseCurrency)) {
            throw problem(422, "FINANCE_QUERY_CURRENCY_UNSUPPORTED", "Unsupported finance query currency",
                    "v0.1 reports are stored in the ledger base currency");
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

    private boolean matchesAccountFilter(
            FinanceQueryRequests.Query request, ReportResponses.TrialBalanceLine line) {
        return request.filters() == null || request.filters().accountCodes() == null
                || request.filters().accountCodes().isEmpty()
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
