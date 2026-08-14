package com.example.accounting.reporting;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.accounting.ledger.LedgerAccessService;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.reporting.internal.application.DefaultReportingService;
import com.example.accounting.reporting.internal.port.ReportingRepository;
import com.example.accounting.shared.web.ApiProblemException;
import java.util.UUID;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultReportingServiceTest {

    private final LedgerAccessService access = org.mockito.Mockito.mock(LedgerAccessService.class);
    private final ReportingRepository repository = org.mockito.Mockito.mock(ReportingRepository.class);
    private final DefaultReportingService service = new DefaultReportingService(access, repository);

    @Test
    void rejectsUnknownPeriodsAndCrossLedgerAccountsAtTheBoundary() {
        UUID actorId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        when(access.requireMembership(actorId, ledgerId)).thenReturn(LedgerRole.VIEWER);
        when(repository.periodsExist(ledgerId, PeriodRange.single("2026-06"))).thenReturn(true);
        when(repository.accountExists(ledgerId, accountId)).thenReturn(false);

        assertThatThrownBy(() -> service.generalLedgerBook(actorId, ledgerId, "bad", 1, 50))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("PERIOD_RANGE_INVALID");
        assertThatThrownBy(() -> service.subLedgerBook(actorId, ledgerId, "2026-06", accountId, 1, 50))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @Test
    void buildsTheSmeIncomeStatementWithYearToDateAndMonthlyColumns() {
        UUID actorId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        when(access.requireMembership(actorId, ledgerId)).thenReturn(LedgerRole.VIEWER);
        when(repository.periodsExist(ledgerId, PeriodRange.single("2026-06"))).thenReturn(true);
        when(repository.ledgerProfile(ledgerId)).thenReturn(new ReportResponses.LedgerProfile("SME", "v1", "CNY"));
        when(repository.firstPeriodOfYear(ledgerId, "2026-06")).thenReturn("2026-01");
        when(repository.statutoryProjectionReady(ledgerId, new PeriodRange("2026-01", "2026-06"))).thenReturn(true);
        when(repository.statutoryProjectionReady(ledgerId, PeriodRange.single("2026-06"))).thenReturn(true);
        List<ReportResponses.TrialBalanceLine> ytd = List.of(
                line("5001", "主营业务收入", "CREDIT", "100", "100"),
                line("5401", "主营业务成本", "DEBIT", "40", "40"),
                line("5601", "销售费用", "DEBIT", "5", "5"),
                line("5801", "所得税费用", "DEBIT", "2", "2"));
        List<ReportResponses.TrialBalanceLine> month = List.of(
                line("5001", "主营业务收入", "CREDIT", "20", "20"),
                line("5401", "主营业务成本", "DEBIT", "8", "8"),
                line("5601", "销售费用", "DEBIT", "1", "1"),
                line("5801", "所得税费用", "DEBIT", "1", "1"));
        when(repository.incomeStatementTrialBalance(ledgerId, new PeriodRange("2026-01", "2026-06"), true)).thenReturn(ytd);
        when(repository.incomeStatementTrialBalance(ledgerId, PeriodRange.single("2026-06"), true)).thenReturn(month);

        StatutoryReportResponses.Statement result = service.statutoryStatement(
                actorId, ledgerId, "income-statement", "2026-06");

        assertThat(result.groups()).singleElement().satisfies(group -> {
            assertThat(group.lines()).hasSize(32);
            assertThat(group.lines().get(20).primaryAmount()).isEqualByComparingTo("55.00");
            assertThat(group.lines().get(20).comparativeAmount()).isEqualByComparingTo("11.00");
            assertThat(group.lines().get(31).primaryAmount()).isEqualByComparingTo("53.00");
            assertThat(group.lines().get(31).comparativeAmount()).isEqualByComparingTo("10.00");
        });
        assertThat(result.primaryColumn()).isEqualTo("本年累计金额");
        assertThat(result.comparativeColumn()).isEqualTo("本月金额");
    }

    @Test
    void rejectsCasAndForeignCurrencyStatutoryRequests() {
        UUID actorId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        when(access.requireMembership(actorId, ledgerId)).thenReturn(LedgerRole.VIEWER);
        when(repository.periodsExist(ledgerId, PeriodRange.single("2026-06"))).thenReturn(true);

        when(repository.ledgerProfile(ledgerId)).thenReturn(new ReportResponses.LedgerProfile("CAS", "2006", "CNY"));
        assertThatThrownBy(() -> service.statutoryStatement(actorId, ledgerId, "income-statement", "2026-06"))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("STATUTORY_REPORT_UNSUPPORTED_STANDARD");

        when(repository.ledgerProfile(ledgerId)).thenReturn(new ReportResponses.LedgerProfile("SME", "v1", "USD"));
        assertThatThrownBy(() -> service.statutoryStatement(actorId, ledgerId, "income-statement", "2026-06"))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("STATUTORY_REPORT_CURRENCY_UNSUPPORTED");
    }

    @Test
    void usesFirstPeriodOpeningBalancesForBalanceSheetYearBeginningColumn() {
        UUID actorId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        when(access.requireMembership(actorId, ledgerId)).thenReturn(LedgerRole.VIEWER);
        when(repository.periodsExist(ledgerId, PeriodRange.single("2026-06"))).thenReturn(true);
        when(repository.ledgerProfile(ledgerId)).thenReturn(new ReportResponses.LedgerProfile("SME", "v1", "CNY"));
        when(repository.firstPeriodOfYear(ledgerId, "2026-06")).thenReturn("2026-01");
        when(repository.statutoryProjectionReady(ledgerId, PeriodRange.single("2026-06"))).thenReturn(true);
        when(repository.statutoryProjectionReady(ledgerId, PeriodRange.single("2026-01"))).thenReturn(true);
        when(repository.statutoryTrialBalance(ledgerId, PeriodRange.single("2026-06"), true)).thenReturn(List.of(
                line("1001", "库存现金", "DEBIT", "100", "100"),
                line("3001", "实收资本", "CREDIT", "100", "100")));
        when(repository.statutoryTrialBalance(ledgerId, PeriodRange.single("2026-01"), true)).thenReturn(List.of(
                openingLine("1001", "库存现金", "DEBIT", "30", "70", "100"),
                openingLine("3001", "实收资本", "CREDIT", "0", "70", "70")));

        StatutoryReportResponses.Statement result = service.statutoryStatement(
                actorId, ledgerId, "balance-sheet", "2026-06");

        StatutoryReportResponses.Line assets = result.groups().get(0).lines().stream()
                .filter(line -> line.lineNo() == 30).findFirst().orElseThrow();
        StatutoryReportResponses.Line equity = result.groups().get(1).lines().stream()
                .filter(line -> line.lineNo() == 52).findFirst().orElseThrow();
        assertThat(assets.comparativeAmount()).isEqualByComparingTo("30.00");
        assertThat(equity.comparativeAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void doesNotFallBackToLiveFactsWhenStatutoryProjectionIsPending() {
        UUID actorId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        when(access.requireMembership(actorId, ledgerId)).thenReturn(LedgerRole.VIEWER);
        when(repository.periodsExist(ledgerId, PeriodRange.single("2026-06"))).thenReturn(true);
        when(repository.ledgerProfile(ledgerId)).thenReturn(new ReportResponses.LedgerProfile("SME", "v1", "CNY"));
        when(repository.firstPeriodOfYear(ledgerId, "2026-06")).thenReturn("2026-01");
        when(repository.statutoryProjectionReady(ledgerId, new PeriodRange("2026-01", "2026-06"))).thenReturn(false);

        assertThatThrownBy(() -> service.statutoryStatement(actorId, ledgerId, "income-statement", "2026-06"))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("STATUTORY_REPORT_PROJECTION_PENDING");
        verify(repository, never()).incomeStatementTrialBalance(ledgerId, new PeriodRange("2026-01", "2026-06"), true);
    }

    @Test
    void doesNotFallBackToLiveFactsWhenIncomeProjectionIsPending() {
        UUID actorId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        when(access.requireMembership(actorId, ledgerId)).thenReturn(LedgerRole.VIEWER);
        when(repository.periodsExist(ledgerId, PeriodRange.single("2026-06"))).thenReturn(true);
        when(repository.statutoryProjectionReady(ledgerId, PeriodRange.single("2026-06"))).thenReturn(false);

        assertThatThrownBy(() -> service.incomeStatement(actorId, ledgerId, "2026-06"))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("INCOME_STATEMENT_PROJECTION_PENDING");
        verify(repository, never()).incomeStatementTrialBalance(ledgerId, PeriodRange.single("2026-06"), false);
    }

    private ReportResponses.TrialBalanceLine line(String code, String name, String side,
                                                   String amount, String closing) {
        BigDecimal value = new BigDecimal(amount);
        BigDecimal ending = new BigDecimal(closing);
        return "CREDIT".equals(side)
                ? new ReportResponses.TrialBalanceLine(UUID.randomUUID(), code, name, "INCOME",
                BigDecimal.ZERO, ending, BigDecimal.ZERO, value, BigDecimal.ZERO, ending,
                BigDecimal.ZERO, value, ending.negate())
                : new ReportResponses.TrialBalanceLine(UUID.randomUUID(), code, name, "EXPENSE",
                ending, BigDecimal.ZERO, value, BigDecimal.ZERO, ending, BigDecimal.ZERO,
                value, BigDecimal.ZERO, ending);
    }

    private ReportResponses.TrialBalanceLine openingLine(String code, String name, String side,
                                                          String opening, String period, String closing) {
        BigDecimal openingAmount = new BigDecimal(opening);
        BigDecimal periodAmount = new BigDecimal(period);
        BigDecimal closingAmount = new BigDecimal(closing);
        return "CREDIT".equals(side)
                ? new ReportResponses.TrialBalanceLine(UUID.randomUUID(), code, name, "EQUITY",
                BigDecimal.ZERO, openingAmount, BigDecimal.ZERO, periodAmount,
                BigDecimal.ZERO, closingAmount, BigDecimal.ZERO, periodAmount, closingAmount.negate())
                : new ReportResponses.TrialBalanceLine(UUID.randomUUID(), code, name, "ASSET",
                openingAmount, BigDecimal.ZERO, periodAmount, BigDecimal.ZERO,
                closingAmount, BigDecimal.ZERO, periodAmount, BigDecimal.ZERO, closingAmount);
    }
}
