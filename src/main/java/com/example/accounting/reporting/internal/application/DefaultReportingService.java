package com.example.accounting.reporting.internal.application;

import com.example.accounting.ledger.LedgerAccessService;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.reporting.FinanceQueryRequests;
import com.example.accounting.reporting.ReportResponses;
import com.example.accounting.reporting.ReportingService;
import com.example.accounting.reporting.internal.port.ReportingRepository;
import com.example.accounting.shared.web.ApiProblemException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
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
        return trialBalance(actorId, ledgerId, periodCode, false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReportResponses.TrialBalanceLine> trialBalance(
            UUID actorId, UUID ledgerId, String periodCode, boolean includeParents) {
        requireView(actorId, ledgerId);
        return includeParents
                ? reports.trialBalanceWithParents(ledgerId, periodCode)
                : reports.trialBalance(ledgerId, periodCode);
    }

    @Override
    @Transactional(readOnly = true)
    public ReportResponses.Statement balanceSheet(UUID actorId, UUID ledgerId, String periodCode) {
        requireView(actorId, ledgerId);
        List<ReportResponses.TrialBalanceLine> lines = reports.trialBalance(ledgerId, periodCode);
        Set<String> debitCategories = reports.formulaCategories(ledgerId, "BALANCE_SHEET", "debitCategories");
        Set<String> creditCategories = reports.formulaCategories(ledgerId, "BALANCE_SHEET", "creditCategories");
        List<ReportResponses.StatementLine> result = lines.stream()
                .filter(line -> debitCategories.contains(line.category()) || creditCategories.contains(line.category()))
                .map(line -> new ReportResponses.StatementLine(line.code(), line.name(),
                        debitCategories.contains(line.category()) ? line.debit().subtract(line.credit())
                                : line.credit().subtract(line.debit())))
                .toList();
        return new ReportResponses.Statement(result.size(), result);
    }

    @Override
    @Transactional(readOnly = true)
    public ReportResponses.Statement incomeStatement(UUID actorId, UUID ledgerId, String periodCode) {
        requireView(actorId, ledgerId);
        List<ReportResponses.TrialBalanceLine> lines = reports.trialBalance(ledgerId, periodCode);
        Set<String> revenueCategories = reports.formulaCategories(
                ledgerId, "INCOME_STATEMENT", "revenueCategories");
        Set<String> expenseCategories = reports.formulaCategories(
                ledgerId, "INCOME_STATEMENT", "expenseCategories");
        List<ReportResponses.StatementLine> result = lines.stream()
                .filter(line -> revenueCategories.contains(line.category())
                        || expenseCategories.contains(line.category()))
                .map(line -> new ReportResponses.StatementLine(line.code(), line.name(),
                        revenueCategories.contains(line.category()) ? line.credit().subtract(line.debit())
                                : line.debit().subtract(line.credit())))
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
    public List<ReportResponses.FinanceQueryLine> financeQuery(UUID actorId, UUID ledgerId,
                                                               FinanceQueryRequests.Query request) {
        requireView(actorId, ledgerId);
        if (request.periodFrom() != null && request.periodTo() != null
                && request.periodFrom().compareTo(request.periodTo()) > 0) {
            throw problem(422, "FINANCE_QUERY_INVALID", "Invalid finance query",
                    "periodFrom must not be after periodTo");
        }
        if (request.periodFrom() != null && request.periodTo() != null
                && !request.periodFrom().equals(request.periodTo())) {
            throw problem(422, "FINANCE_QUERY_RANGE_UNSUPPORTED", "Unsupported finance query range",
                    "v0.1 supports one period per finance query");
        }
        String baseCurrency = reports.baseCurrency(ledgerId);
        if (request.filters() != null && request.filters().currency() != null
                && !request.filters().currency().equals(baseCurrency)) {
            throw problem(422, "FINANCE_QUERY_CURRENCY_UNSUPPORTED", "Unsupported finance query currency",
                    "v0.1 reports are stored in the ledger base currency");
        }
        String period = Objects.equals(request.periodFrom(), request.periodTo()) ? request.periodFrom() : null;
        List<ReportResponses.TrialBalanceLine> lines = reports.trialBalance(ledgerId, period).stream()
                .filter(line -> request.filters() == null || request.filters().accountCodes() == null
                        || request.filters().accountCodes().isEmpty()
                        || request.filters().accountCodes().contains(line.code()))
                .toList();
        if (request.groupBy().contains("DIMENSION")) {
            throw problem(422, "FINANCE_QUERY_GROUP_UNSUPPORTED", "Unsupported finance query group",
                    "Dimension grouping requires dimension facts that are not available in v0.1");
        }
        if (request.groupBy().contains("MONTH") && period == null) {
            throw problem(422, "FINANCE_QUERY_GROUP_UNSUPPORTED", "Unsupported finance query group",
                    "Month grouping requires one selected period in v0.1");
        }
        if (lines.isEmpty()) {
            return List.of();
        }
        if (!request.groupBy().contains("ACCOUNT")) {
            BigDecimal amount = lines.stream().map(line -> metric(request.metric(), line))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return List.of(new ReportResponses.FinanceQueryLine(
                    groupKey(request.groupBy(), null, period, baseCurrency), amount));
        }
        return lines.stream().map(line -> new ReportResponses.FinanceQueryLine(
                groupKey(request.groupBy(), line.code(), period, baseCurrency), metric(request.metric(), line)))
                .toList();
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
