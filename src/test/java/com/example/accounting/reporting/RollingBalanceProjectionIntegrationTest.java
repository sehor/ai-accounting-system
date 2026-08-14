package com.example.accounting.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.ledger.LedgerRequests;
import com.example.accounting.ledger.LedgerResponses;
import com.example.accounting.ledger.LedgerService;
import com.example.accounting.reporting.internal.port.BalanceProjectionRepository;
import com.example.accounting.reporting.internal.port.BalanceRebuildRepository;
import com.example.accounting.voucher.VoucherRequests;
import com.example.accounting.voucher.VoucherResponses;
import com.example.accounting.voucher.VoucherService;
import com.example.accounting.shared.web.ApiProblemException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "accounting.balance.worker-enabled=false")
class RollingBalanceProjectionIntegrationTest {

    @Autowired
    private LedgerService ledgers;

    @Autowired
    private VoucherService vouchers;

    @Autowired
    private BalanceProjectionRepository projection;

    @Autowired
    private ReportingService reports;

    @Autowired
    private BalanceRebuildService rebuilds;

    @Autowired
    private BalanceRebuildRepository rebuildRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void asynchronouslyRollsLeafAndParentBalancesAcrossFuturePeriods() {
        UUID actorId = UUID.randomUUID();
        UUID ledgerId = ledgers.create(new CurrentUserResolver.ResolvedUser(
                        actorId, "projection-test", actorId.toString()),
                new LedgerRequests.Create("rolling projection", "SME", "v1", "CNY",
                        LocalDate.of(2021, 1, 1), false)).id();
        UUID january = period(ledgerId, "2021-01");
        UUID february = period(ledgerId, "2021-02");
        UUID cashParent = account(ledgerId, "1001");
        UUID cashLeaf = ledgers.createAccount(actorId, ledgerId,
                new LedgerRequests.AccountCreate("100101", "银行存款", "CURRENT_ASSET", "DEBIT")).id();
        UUID capital = account(ledgerId, "3001");

        ledgers.replaceOpeningBalances(actorId, ledgerId, List.of(
                opening(cashLeaf, january, "100", "0"),
                opening(capital, january, "0", "100")));
        ledgers.confirmOpeningBalances(actorId, ledgerId);

        assertThat(state(ledgerId, "2021-02").get("applied"))
                .isNotEqualTo(state(ledgerId, "2021-02").get("enqueued"));
        drainProjection();
        assertBalance(ledgerId, "2021-02", cashLeaf, "100.00", "0.00", "100.00", "0.00");
        assertBalance(ledgerId, "2021-02", cashParent, "100.00", "0.00", "100.00", "0.00");

        VoucherResponses.Voucher voucher = vouchers.create(actorId, ledgerId, new VoucherRequests.Create(
                february, LocalDate.of(2021, 2, 10), "GENERAL", "1", "cash withdrawal",
                List.of(line(capital, "DEBIT", "30"), line(cashLeaf, "CREDIT", "30"))));

        assertBalance(ledgerId, "2021-02", cashLeaf, "100.00", "0.00", "100.00", "0.00");
        drainProjection();
        assertBalance(ledgerId, "2021-02", cashLeaf, "100.00", "0.00", "100.00", "30.00");
        assertBalance(ledgerId, "2021-03", cashLeaf, "100.00", "30.00", "100.00", "30.00");
        assertBalance(ledgerId, "2021-03", cashParent, "100.00", "30.00", "100.00", "30.00");

        vouchers.update(actorId, ledgerId, voucher.id(), new VoucherRequests.Update(
                voucher.version(), february, voucher.voucherDate(), voucher.voucherType(), voucher.voucherNumber(),
                "cash overdraft", List.of(line(capital, "DEBIT", "120"), line(cashLeaf, "CREDIT", "120"))));
        drainProjection();
        assertBalance(ledgerId, "2021-02", cashLeaf, "100.00", "0.00", "100.00", "120.00");
        assertBalance(ledgerId, "2021-03", cashLeaf, "100.00", "120.00", "100.00", "120.00");
        assertBalance(ledgerId, "2021-03", cashParent, "100.00", "120.00", "100.00", "120.00");

        PeriodRange range = new PeriodRange("2021-02", "2021-03");
        ReportResponses.TrialBalanceLine cash = reports.trialBalance(actorId, ledgerId, range, false).stream()
                .filter(line -> line.accountId().equals(cashLeaf)).findFirst().orElseThrow();
        assertThat(cash.openingDebit()).isEqualByComparingTo("100.00");
        assertThat(cash.periodCredit()).isEqualByComparingTo("120.00");
        assertThat(cash.closingDebit()).isEqualByComparingTo("100.00");
        assertThat(cash.closingCredit()).isEqualByComparingTo("120.00");
        assertThat(cash.balance()).isEqualByComparingTo("-20.00");

        ReportResponses.SubLedgerPage detail = reports.subLedgerBook(
                actorId, ledgerId, range, cashLeaf, 1, 50);
        assertThat(detail.periodFrom()).isEqualTo("2021-02");
        assertThat(detail.periodTo()).isEqualTo("2021-03");
        assertThat(detail.periodCode()).isNull();
        assertThat(detail.openingBalance()).isEqualByComparingTo("100.00");
        assertThat(detail.data()).hasSize(1);
        assertThat(detail.endingDirection()).isEqualTo("DEBIT");
        assertThat(detail.endingBalance()).isEqualByComparingTo("-20.00");

        BalanceRebuildResponses.Job requested = rebuilds.request(actorId, ledgerId,
                new BalanceRebuildRequests.Create("2021-02", "2021-02", "verify downstream rebuild"));
        assertThat(rebuildRepository.processNextJob()).isTrue();
        BalanceRebuildResponses.Job rebuilt = rebuilds.find(actorId, ledgerId, requested.id());
        assertThat(rebuilt.status()).isEqualTo("SUCCEEDED");
        assertThat(rebuilt.periodFrom()).isEqualTo("2021-02");
        assertThat(rebuilt.periodTo()).isEqualTo(jdbc.queryForObject(
                "select max(period_code) from accounting_period where ledger_id = ?", String.class, ledgerId));
        assertThat(rebuilt.processedPeriods()).isEqualTo(rebuilt.totalPeriods());
    }

    @Test
    void preservesNegativeOpeningBalanceSidesAcrossProjectionAndReports() {
        UUID actorId = UUID.randomUUID();
        UUID ledgerId = ledgers.create(new CurrentUserResolver.ResolvedUser(
                        actorId, "negative-opening", actorId.toString()),
                new LedgerRequests.Create("negative opening", "SME", "v1", "CNY",
                        LocalDate.of(2024, 1, 1), false)).id();
        UUID january = period(ledgerId, "2024-01");
        UUID cashParent = account(ledgerId, "1001");
        UUID cashLeaf = ledgers.createAccount(actorId, ledgerId,
                new LedgerRequests.AccountCreate("100101", "negative cash", "CURRENT_ASSET", "DEBIT")).id();
        UUID capital = account(ledgerId, "3001");

        ledgers.replaceOpeningBalances(actorId, ledgerId, List.of(
                opening(cashLeaf, january, "-25", "0"),
                opening(capital, january, "0", "-25")));
        ledgers.confirmOpeningBalances(actorId, ledgerId);
        drainProjection();

        assertBalance(ledgerId, "2024-01", cashLeaf, "-25.00", "0.00", "-25.00", "0.00");
        assertBalance(ledgerId, "2024-01", capital, "0.00", "-25.00", "0.00", "-25.00");
        assertBalance(ledgerId, "2024-01", cashParent, "-25.00", "0.00", "-25.00", "0.00");
        assertBalance(ledgerId, "2024-02", cashLeaf, "-25.00", "0.00", "-25.00", "0.00");

        PeriodRange range = PeriodRange.single("2024-01");
        ReportResponses.TrialBalanceLine trial = reports.trialBalance(actorId, ledgerId, range, false).stream()
                .filter(line -> line.accountId().equals(cashLeaf)).findFirst().orElseThrow();
        assertThat(trial.openingDebit()).isEqualByComparingTo("-25.00");
        assertThat(trial.openingCredit()).isZero();

        ReportResponses.GeneralLedgerAccount general = reports.generalLedgerBook(
                        actorId, ledgerId, range, 1, 50).data().stream()
                .filter(line -> line.accountId().equals(cashLeaf)).findFirst().orElseThrow();
        assertThat(general.openingDirection()).isEqualTo("DEBIT");
        assertThat(general.openingBalance()).isEqualByComparingTo("-25.00");

        ReportResponses.SubLedgerPage detail = reports.subLedgerBook(
                actorId, ledgerId, range, cashLeaf, 1, 50);
        assertThat(detail.openingDirection()).isEqualTo("DEBIT");
        assertThat(detail.openingBalance()).isEqualByComparingTo("-25.00");
    }

    @Test
    void rebuildsAndRollsAuxiliaryBalancesByCombinationAndCurrency() {
        UUID actorId = UUID.randomUUID();
        UUID ledgerId = ledgers.create(new CurrentUserResolver.ResolvedUser(
                        actorId, "dimension-projection", actorId.toString()),
                new LedgerRequests.Create("dimension projection", "SME", "v1", "CNY",
                        LocalDate.of(2026, 1, 1), false)).id();
        UUID january = period(ledgerId, "2026-01");
        UUID february = period(ledgerId, "2026-02");
        LedgerResponses.DimensionType customer = customerType(actorId, ledgerId);
        LedgerResponses.DimensionType project = projectType(actorId, ledgerId);
        UUID controlled = ledgers.createAccount(actorId, ledgerId,
                new LedgerRequests.AccountCreate("1410", "客户往来", "CURRENT_ASSET", "DEBIT", null,
                        false, null, false, null, List.of(new LedgerRequests.DimensionRequirement(
                                customer.id(), true), new LedgerRequests.DimensionRequirement(project.id(), true)))).id();
        UUID capital = account(ledgerId, "3001");
        LedgerResponses.DimensionValue customerA = ledgers.createDimensionValue(actorId, ledgerId,
                customer.id(), new LedgerRequests.DimensionValueCreate("C-A", "客户 A"));
        LedgerResponses.DimensionValue customerB = ledgers.createDimensionValue(actorId, ledgerId,
                customer.id(), new LedgerRequests.DimensionValueCreate("C-B", "客户 B"));
        LedgerResponses.DimensionValue projectA = ledgers.createDimensionValue(actorId, ledgerId,
                project.id(), new LedgerRequests.DimensionValueCreate("P-A", "项目 A"));

        VoucherResponses.Voucher usdVoucher = vouchers.create(actorId, ledgerId, new VoucherRequests.Create(
                january, LocalDate.of(2026, 1, 10), "GENERAL", "dimension-usd", "customer A USD",
                List.of(dimensionLine(controlled, "DEBIT", "USD", "10", "7", customerA, projectA),
                        line(capital, "CREDIT", "70"))));
        VoucherResponses.Voucher cnyVoucher = vouchers.create(actorId, ledgerId, new VoucherRequests.Create(
                january, LocalDate.of(2026, 1, 11), "GENERAL", "dimension-cny", "customer B CNY",
                List.of(dimensionLine(controlled, "DEBIT", "CNY", "20", "1", customerB, projectA),
                        line(capital, "CREDIT", "20"))));
        UUID customerACombination = combination(ledgerId, usdVoucher.id(), controlled);
        UUID customerBCombination = combination(ledgerId, cnyVoucher.id(), controlled);

        assertThatThrownBy(() -> reports.dimensionLedger(actorId, ledgerId,
                new DimensionLedgerRequests.Query("2026-01", "2026-01", controlled, null, List.of(),
                        List.of(), 1, 50)))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("BALANCE_PROJECTION_NOT_READY");

        drainProjection();
        assertDimensionBalance(ledgerId, "2026-01", controlled, customerACombination, "USD", "10", "70");
        assertDimensionBalance(ledgerId, "2026-01", controlled, customerBCombination, "CNY", "20", "20");
        assertDimensionBalance(ledgerId, "2026-02", controlled, customerACombination, "USD", "10", "70");
        assertDimensionBalancesMatchAccount(ledgerId, "2026-01", controlled);

        ReportResponses.DimensionLedgerPage fullLedger = reports.dimensionLedger(actorId, ledgerId,
                new DimensionLedgerRequests.Query("2026-01", "2026-01", controlled, null, List.of(),
                        List.of(customer.id()), 1, 1));
        assertThat(fullLedger.projectionStatus()).isEqualTo("READY");
        assertThat(fullLedger.balances()).hasSize(2);
        assertThat(fullLedger.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.currency()).isEqualTo("USD");
            assertThat(entry.runningOriginalDebit()).isEqualByComparingTo("10");
            assertThat(entry.runningBaseDebit()).isEqualByComparingTo("70");
            assertThat(entry.groupKey()).isEqualTo("C-A");
        });
        ReportResponses.DimensionLedgerPage secondPage = reports.dimensionLedger(actorId, ledgerId,
                new DimensionLedgerRequests.Query("2026-01", "2026-01", controlled, null, List.of(),
                        List.of(customer.id()), 2, 1));
        assertThat(secondPage.pagination().totalItems()).isEqualTo(2);
        assertThat(secondPage.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.currency()).isEqualTo("CNY");
            assertThat(entry.runningOriginalDebit()).isEqualByComparingTo("20");
            assertThat(entry.runningBaseDebit()).isEqualByComparingTo("20");
        });
        ReportResponses.DimensionLedgerPage emptyPage = reports.dimensionLedger(actorId, ledgerId,
                new DimensionLedgerRequests.Query("2026-01", "2026-01", controlled, null, List.of(),
                        List.of(customer.id()), 3, 1));
        assertThat(emptyPage.entries()).isEmpty();
        assertThat(emptyPage.pagination().totalItems()).isEqualTo(2);
        assertThat(emptyPage.pagination().totalPages()).isEqualTo(2);
        ReportResponses.DimensionLedgerPage filteredLedger = reports.dimensionLedger(actorId, ledgerId,
                new DimensionLedgerRequests.Query("2026-01", "2026-01", controlled, "CNY",
                        List.of(new DimensionLedgerRequests.DimensionValue(customer.id(), customerB.id()),
                                new DimensionLedgerRequests.DimensionValue(project.id(), projectA.id())),
                        List.of(customer.id()), 1, 50));
        assertThat(filteredLedger.balances()).singleElement().satisfies(balance -> {
            assertThat(balance.currency()).isEqualTo("CNY");
            assertThat(balance.original().closingDebit()).isEqualByComparingTo("20");
        });
        jdbc.update("update dimension_combination set kind = 'LEGACY_UNMAPPED' where ledger_id = ? and id = ?",
                ledgerId, customerBCombination);
        assertThat(reports.dimensionLedger(actorId, ledgerId,
                new DimensionLedgerRequests.Query("2026-01", "2026-01", controlled, "CNY", List.of(),
                        List.of(), 1, 50)).warnings()).contains("LEGACY_UNMAPPED");

        VoucherResponses.Voucher updated = vouchers.update(actorId, ledgerId, usdVoucher.id(),
                new VoucherRequests.Update(usdVoucher.version(), january, usdVoucher.voucherDate(),
                        usdVoucher.voucherType(), usdVoucher.voucherNumber(), "move to customer B",
                        List.of(dimensionLine(controlled, "DEBIT", "USD", "10", "7", customerB, projectA),
                                line(capital, "CREDIT", "70"))));
        drainProjection();
        assertThat(dimensionBalanceCount(ledgerId, "2026-01", controlled, customerACombination)).isZero();
        assertDimensionBalance(ledgerId, "2026-01", controlled, customerBCombination, "USD", "10", "70");
        assertDimensionBalancesMatchAccount(ledgerId, "2026-01", controlled);

        vouchers.delete(actorId, ledgerId, updated.id());
        drainProjection();
        assertThat(dimensionBalanceCount(ledgerId, "2026-01", controlled, customerBCombination))
                .isEqualTo(1);
        assertDimensionBalance(ledgerId, "2026-02", controlled, customerBCombination, "CNY", "20", "20");
        assertDimensionBalancesMatchAccount(ledgerId, "2026-01", controlled);
    }

    @Test
    void reportsAnOpeningBalanceOnItsSelectedNonNormalSide() {
        UUID actorId = UUID.randomUUID();
        UUID ledgerId = ledgers.create(new CurrentUserResolver.ResolvedUser(
                        actorId, "non-normal-opening", actorId.toString()),
                new LedgerRequests.Create("non-normal opening", "SME", "v1", "CNY",
                        LocalDate.of(2025, 1, 1), false)).id();
        UUID january = period(ledgerId, "2025-01");
        UUID cash = account(ledgerId, "1001");
        UUID capital = account(ledgerId, "3001");

        ledgers.replaceOpeningBalances(actorId, ledgerId, List.of(
                opening(cash, january, "0", "10"),
                opening(capital, january, "10", "0")));
        ledgers.confirmOpeningBalances(actorId, ledgerId);
        drainProjection();

        PeriodRange range = PeriodRange.single("2025-01");
        ReportResponses.GeneralLedgerAccount general = reports.generalLedgerBook(
                        actorId, ledgerId, range, 1, 50).data().stream()
                .filter(line -> line.accountId().equals(cash)).findFirst().orElseThrow();
        assertThat(general.normalBalance()).isEqualTo("DEBIT");
        assertThat(general.openingDirection()).isEqualTo("DEBIT");
        assertThat(general.openingBalance()).isEqualByComparingTo("-10.00");

        ReportResponses.SubLedgerPage detail = reports.subLedgerBook(
                actorId, ledgerId, range, cash, 1, 50);
        assertThat(detail.openingDirection()).isEqualTo("DEBIT");
        assertThat(detail.openingBalance()).isEqualByComparingTo("-10.00");
    }

    @Test
    void aggregatesDescendantEntriesWhenReadingAPrimaryAccountSubLedger() {
        UUID actorId = UUID.randomUUID();
        UUID ledgerId = ledgers.create(new CurrentUserResolver.ResolvedUser(
                        actorId, "parent-subledger", actorId.toString()),
                new LedgerRequests.Create("parent sub-ledger", "SME", "v1", "CNY",
                        LocalDate.of(2021, 1, 1), false)).id();
        UUID january = period(ledgerId, "2021-01");
        UUID february = period(ledgerId, "2021-02");
        UUID cashParent = account(ledgerId, "1001");
        UUID firstBranch = ledgers.createAccount(actorId, ledgerId,
                new LedgerRequests.AccountCreate("100101", "cash branch", "CURRENT_ASSET", "DEBIT",
                        cashParent, false, null, false, null, List.of())).id();
        UUID thirdLevel = ledgers.createAccount(actorId, ledgerId,
                new LedgerRequests.AccountCreate("10010101", "cash third level", "CURRENT_ASSET", "DEBIT",
                        firstBranch, false, null, false, null, List.of())).id();
        UUID fourthLevelLeaf = ledgers.createAccount(actorId, ledgerId,
                new LedgerRequests.AccountCreate("1001010101", "cash fourth level", "CURRENT_ASSET", "DEBIT",
                        thirdLevel, false, null, false, null, List.of())).id();
        UUID secondChild = ledgers.createAccount(actorId, ledgerId,
                new LedgerRequests.AccountCreate("100102", "cash two", "CURRENT_ASSET", "DEBIT",
                        cashParent, false, null, false, null, List.of())).id();
        UUID capital = account(ledgerId, "3001");

        ledgers.replaceOpeningBalances(actorId, ledgerId, List.of(
                opening(fourthLevelLeaf, january, "50", "0"),
                opening(secondChild, january, "30", "0"),
                opening(capital, january, "0", "80")));
        ledgers.confirmOpeningBalances(actorId, ledgerId);
        vouchers.create(actorId, ledgerId, new VoucherRequests.Create(
                february, LocalDate.of(2021, 2, 10), "GENERAL", "1", "fourth-level child",
                List.of(line(fourthLevelLeaf, "DEBIT", "5"), line(capital, "CREDIT", "5"))));
        vouchers.create(actorId, ledgerId, new VoucherRequests.Create(
                february, LocalDate.of(2021, 2, 11), "GENERAL", "2", "second child",
                List.of(line(capital, "DEBIT", "7"), line(secondChild, "CREDIT", "7"))));
        drainProjection();

        assertThatThrownBy(() -> reports.dimensionLedger(actorId, ledgerId,
                new DimensionLedgerRequests.Query("2021-02", "2021-02", cashParent, null, List.of(),
                        List.of(), 1, 50)))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("DIMENSION_LEDGER_LEAF_ACCOUNT_REQUIRED");

        ReportResponses.SubLedgerPage detail = reports.subLedgerBook(
                actorId, ledgerId, new PeriodRange("2021-02", "2021-02"), cashParent, 1, 50);

        assertThat(detail.openingBalance()).isEqualByComparingTo("80.00");
        assertThat(detail.data()).hasSize(2);
        assertThat(detail.data()).extracting(ReportResponses.SubLedgerEntry::postingAccountId)
                .containsExactly(fourthLevelLeaf, secondChild);
        assertThat(detail.periodDebit()).isEqualByComparingTo("5.00");
        assertThat(detail.periodCredit()).isEqualByComparingTo("7.00");
        assertThat(detail.endingBalance()).isEqualByComparingTo("78.00");
    }

    @Test
    void rollsBackPostedVoucherWhenOutboxWriteFails() {
        UUID actorId = UUID.randomUUID();
        UUID ledgerId = ledgers.create(new CurrentUserResolver.ResolvedUser(
                        actorId, "projection-rollback", actorId.toString()),
                new LedgerRequests.Create("projection rollback", "SME", "v1", "CNY",
                        LocalDate.of(2022, 1, 1), false)).id();
        UUID periodId = period(ledgerId, "2022-01");
        UUID cash = account(ledgerId, "1001");
        UUID capital = account(ledgerId, "3001");
        jdbc.execute("""
                create function reject_balance_event() returns trigger language plpgsql as $$
                begin raise exception 'simulated outbox failure'; end $$
                """);
        jdbc.execute("""
                create trigger reject_balance_event before insert on balance_projection_event
                for each row execute function reject_balance_event()
                """);
        try {
            assertThatThrownBy(() -> vouchers.create(actorId, ledgerId, new VoucherRequests.Create(
                    periodId, LocalDate.of(2022, 1, 10), "GENERAL", "rollback-1", "must rollback",
                    List.of(line(cash, "DEBIT", "50"), line(capital, "CREDIT", "50")))))
                    .hasMessageContaining("simulated outbox failure");
            assertThat(jdbc.queryForObject("""
                    select count(*) from voucher where ledger_id = ? and voucher_number = 'rollback-1'
                    """, Long.class, ledgerId)).isZero();
        } finally {
            jdbc.execute("drop trigger reject_balance_event on balance_projection_event");
            jdbc.execute("drop function reject_balance_event()");
        }
    }

    @Test
    void rejectsOpeningBalancesOutsideTheFirstAccountingPeriod() {
        UUID actorId = UUID.randomUUID();
        UUID ledgerId = ledgers.create(new CurrentUserResolver.ResolvedUser(
                        actorId, "opening-period", actorId.toString()),
                new LedgerRequests.Create("opening period", "SME", "v1", "CNY",
                        LocalDate.of(2023, 1, 1), false)).id();
        UUID february = period(ledgerId, "2023-02");
        assertThatThrownBy(() -> ledgers.replaceOpeningBalances(actorId, ledgerId, List.of(
                opening(account(ledgerId, "1001"), february, "10", "0"),
                opening(account(ledgerId, "3001"), february, "0", "10"))))
                .isInstanceOf(ApiProblemException.class)
                .satisfies(error -> assertThat(((ApiProblemException) error).code())
                        .isEqualTo("OPENING_BALANCE_PERIOD_INVALID"));
    }

    private LedgerRequests.OpeningBalanceLine opening(
            UUID accountId, UUID periodId, String debit, String credit) {
        return new LedgerRequests.OpeningBalanceLine(accountId, periodId, "CNY", "",
                new BigDecimal(debit), new BigDecimal(credit), BigDecimal.ONE);
    }

    private VoucherRequests.Line line(UUID accountId, String side, String amount) {
        return new VoucherRequests.Line(accountId, side, "CNY", new BigDecimal(amount), BigDecimal.ONE, "projection");
    }

    private VoucherRequests.Line dimensionLine(UUID accountId, String side, String currency, String amount,
                                               String rate, LedgerResponses.DimensionValue... dimensions) {
        return new VoucherRequests.Line(accountId, side, currency, new BigDecimal(amount), new BigDecimal(rate),
                "dimension projection", null, null, null,
                java.util.Arrays.stream(dimensions)
                        .map(dimension -> new VoucherRequests.Dimension(dimension.dimensionTypeId(), dimension.id()))
                        .toList());
    }

    private LedgerResponses.DimensionType customerType(UUID actorId, UUID ledgerId) {
        return ledgers.listDimensionTypes(actorId, ledgerId).stream()
                .filter(type -> type.code().equals("CUSTOMER")).findFirst().orElseThrow();
    }

    private LedgerResponses.DimensionType projectType(UUID actorId, UUID ledgerId) {
        return ledgers.listDimensionTypes(actorId, ledgerId).stream()
                .filter(type -> type.code().equals("PROJECT")).findFirst().orElseThrow();
    }

    private UUID combination(UUID ledgerId, UUID voucherId, UUID accountId) {
        return jdbc.queryForObject("""
                select dimension_combination_id from voucher_line
                where ledger_id = ? and voucher_id = ? and account_id = ?
                """, UUID.class, ledgerId, voucherId, accountId);
    }

    private UUID period(UUID ledgerId, String code) {
        return jdbc.queryForObject("""
                select id from accounting_period where ledger_id = ? and period_code = ?
                """, UUID.class, ledgerId, code);
    }

    private UUID account(UUID ledgerId, String code) {
        return jdbc.queryForObject("""
                select id from ledger_account where ledger_id = ? and code = ?
                """, UUID.class, ledgerId, code);
    }

    private Map<String, Object> state(UUID ledgerId, String periodCode) {
        return jdbc.queryForMap("""
                select coalesce(last_enqueued_event_id, 0) enqueued,
                    coalesce(last_applied_event_id, 0) applied
                from balance_projection_state s
                join accounting_period p on p.ledger_id = s.ledger_id and p.id = s.period_id
                where s.ledger_id = ? and p.period_code = ?
                """, ledgerId, periodCode);
    }

    private void drainProjection() {
        while (projection.applyPendingBatch(200, 5000)) {
            // A pass catches up one ledger; shared integration contexts may contain several ledgers.
        }
    }

    private void assertBalance(UUID ledgerId, String periodCode, UUID accountId,
                               String openingDebit, String openingCredit,
                               String closingDebit, String closingCredit) {
        Map<String, Object> row = jdbc.queryForMap("""
                select opening_debit_base, opening_credit_base,
                    closing_debit_base, closing_credit_base
                from account_period_balance b
                join accounting_period p on p.ledger_id = b.ledger_id and p.id = b.period_id
                where b.ledger_id = ? and p.period_code = ? and b.account_id = ?
                """, ledgerId, periodCode, accountId);
        assertThat((BigDecimal) row.get("opening_debit_base")).isEqualByComparingTo(openingDebit);
        assertThat((BigDecimal) row.get("opening_credit_base")).isEqualByComparingTo(openingCredit);
        assertThat((BigDecimal) row.get("closing_debit_base")).isEqualByComparingTo(closingDebit);
        assertThat((BigDecimal) row.get("closing_credit_base")).isEqualByComparingTo(closingCredit);
    }

    private void assertDimensionBalance(UUID ledgerId, String periodCode, UUID accountId, UUID combinationId,
                                        String currency, String closingOriginal, String closingBase) {
        Map<String, Object> row = jdbc.queryForMap("""
                select closing_debit_original, closing_debit_base
                from dimension_period_balance b
                join accounting_period p on p.ledger_id = b.ledger_id and p.id = b.period_id
                where b.ledger_id = ? and p.period_code = ? and b.account_id = ?
                  and b.dimension_combination_id = ? and b.currency = ?
                """, ledgerId, periodCode, accountId, combinationId, currency);
        assertThat((BigDecimal) row.get("closing_debit_original")).isEqualByComparingTo(closingOriginal);
        assertThat((BigDecimal) row.get("closing_debit_base")).isEqualByComparingTo(closingBase);
    }

    private int dimensionBalanceCount(UUID ledgerId, String periodCode, UUID accountId, UUID combinationId) {
        return jdbc.queryForObject("""
                select count(*) from dimension_period_balance b
                join accounting_period p on p.ledger_id = b.ledger_id and p.id = b.period_id
                where b.ledger_id = ? and p.period_code = ? and b.account_id = ?
                  and b.dimension_combination_id = ?
                """, Integer.class, ledgerId, periodCode, accountId, combinationId);
    }

    private void assertDimensionBalancesMatchAccount(UUID ledgerId, String periodCode, UUID accountId) {
        BigDecimal dimensionClosing = jdbc.queryForObject("""
                select coalesce(sum(b.closing_debit_base - b.closing_credit_base), 0)
                from dimension_period_balance b
                join accounting_period p on p.ledger_id = b.ledger_id and p.id = b.period_id
                where b.ledger_id = ? and p.period_code = ? and b.account_id = ?
                """, BigDecimal.class, ledgerId, periodCode, accountId);
        BigDecimal accountClosing = jdbc.queryForObject("""
                select closing_debit_base - closing_credit_base from account_period_balance b
                join accounting_period p on p.ledger_id = b.ledger_id and p.id = b.period_id
                where b.ledger_id = ? and p.period_code = ? and b.account_id = ?
                """, BigDecimal.class, ledgerId, periodCode, accountId);
        assertThat(dimensionClosing).isEqualByComparingTo(accountClosing);
    }
}
