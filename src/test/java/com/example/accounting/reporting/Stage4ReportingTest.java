package com.example.accounting.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.ledger.LedgerRequests;
import com.example.accounting.ledger.LedgerResponses;
import com.example.accounting.ledger.LedgerService;
import com.example.accounting.voucher.VoucherRequests;
import com.example.accounting.voucher.VoucherResponses;
import com.example.accounting.voucher.VoucherService;
import com.example.accounting.reporting.internal.port.BalanceProjectionRepository;
import com.example.accounting.shared.web.ApiProblemException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class Stage4ReportingTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private VoucherService voucherService;

    @Autowired
    private ReportingService reportingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BalanceProjectionRepository projection;

    @Test
    void reportsOnlyPostedVoucherAmounts() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("reporting", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        UUID periodId = id("select id from accounting_period where ledger_id = ? and period_code = '2026-01'", ledgerId);
        UUID cashId = id("select id from ledger_account where ledger_id = ? and code = '1001'", ledgerId);
        UUID capitalId = id("select id from ledger_account where ledger_id = ? and code = '3001'", ledgerId);
        UUID revenueId = id("select id from ledger_account where ledger_id = ? and code = '5001'", ledgerId);
        VoucherResponses.Voucher voucher = voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 15), "记", "1", "Posted",
                List.of(line(cashId, "DEBIT", "100"), line(capitalId, "CREDIT", "50"),
                        line(revenueId, "CREDIT", "50"))));
        assertThat(voucher.status()).isEqualTo("POSTED");

        List<ReportResponses.TrialBalanceLine> lines = reportingService.trialBalance(userId, ledgerId, "2026-01");
        assertThat(lines).hasSize(3);
        assertThat(lines).extracting(ReportResponses.TrialBalanceLine::debit)
                .contains(new BigDecimal("100.00"));
        assertThat(reportingService.balanceSheet(userId, ledgerId, "2026-01").totalLines()).isGreaterThan(0);
        assertThat(reportingService.balanceSheet(userId, ledgerId, "2026-01").lines())
                .filteredOn(line -> line.code().equals("3001"))
                .singleElement()
                .extracting(ReportResponses.StatementLine::amount)
                .isEqualTo(new BigDecimal("50.00"));
        applyProjection(ledgerId);
        assertThat(reportingService.incomeStatement(userId, ledgerId, "2026-01").totalLines()).isGreaterThan(0);
        assertThat(reportingService.generalLedger(userId, ledgerId, "2026-01")).hasSize(3);
        assertThat(reportingService.subLedger(userId, ledgerId, "2026-01")).hasSize(3);
        List<ReportResponses.FinanceQueryLine> query = reportingService.financeQuery(userId, ledgerId,
                new FinanceQueryRequests.Query("NET", "2026-01", "2026-01", List.of("ACCOUNT"),
                        new FinanceQueryRequests.Filters(List.of("1001"), "CNY")));
        assertThat(query).singleElement().satisfies(line -> {
            assertThat(line.groupKey()).isEqualTo("1001");
            assertThat(line.amount()).isEqualByComparingTo("100.00");
        });
        assertThatThrownBy(() -> reportingService.financeQuery(userId, ledgerId,
                new FinanceQueryRequests.Query("SQL", null, null, List.of("ACCOUNT"), null)))
                .isInstanceOf(ApiProblemException.class);

        List<ReportResponses.FinanceQueryLine> aggregate = reportingService.financeQuery(userId, ledgerId,
                new FinanceQueryRequests.Query("DEBIT", "2026-01", "2026-01", List.of("MONTH"),
                        new FinanceQueryRequests.Filters(null, "CNY")));
        assertThat(aggregate).singleElement().satisfies(line -> {
            assertThat(line.groupKey()).isEqualTo("2026-01");
            assertThat(line.amount()).isEqualByComparingTo("100.00");
        });

        jdbcTemplate.update("""
                update report_formula_snapshot
                set formula_json = jsonb_set(formula_json, '{creditCategories}', '[]'::jsonb)
                where ledger_id = ? and code = 'BALANCE_SHEET'
                """, ledgerId);
        assertThat(reportingService.balanceSheet(userId, ledgerId, "2026-01").lines())
                .extracting(ReportResponses.StatementLine::code)
                .containsExactly("1001");

        VoucherResponses.Voucher updated = voucherService.update(userId, ledgerId, voucher.id(),
                new VoucherRequests.Update(voucher.version(), periodId, voucher.voucherDate(), voucher.voucherType(),
                        voucher.voucherNumber(), "Updated", List.of(
                        line(cashId, "DEBIT", "100"), line(capitalId, "CREDIT", "100"))));
        assertThat(updated.status()).isEqualTo("POSTED");
        assertThat(reportingService.trialBalance(userId, ledgerId, "2026-01"))
                .extracting(ReportResponses.TrialBalanceLine::balance)
                .containsExactlyInAnyOrder(new BigDecimal("100.00"), new BigDecimal("-100.00"));
    }

    @Test
    void optionallyRollsLeafAmountsIntoParentsWithoutChangingDirectTotals() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("parent reporting", "SME", "v1", "CNY",
                        LocalDate.of(2026, 1, 1), false)).id();
        UUID periodId = id("select id from accounting_period where ledger_id = ? and period_code = '2026-01'", ledgerId);
        UUID cash = ledgerService.createAccount(userId, ledgerId,
                new LedgerRequests.AccountCreate("100101", "库存现金-人民币", "CURRENT_ASSET", "DEBIT")).id();
        UUID capital = id("select id from ledger_account where ledger_id = ? and code = '3001'", ledgerId);
        VoucherResponses.Voucher voucher = voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 15), "记", "2", "Parent rollup",
                List.of(line(cash, "DEBIT", "100"), line(capital, "CREDIT", "100"))));
        assertThat(voucher.status()).isEqualTo("POSTED");

        assertThat(reportingService.trialBalance(userId, ledgerId, "2026-01"))
                .extracting(ReportResponses.TrialBalanceLine::code)
                .containsExactlyInAnyOrder("100101", "3001");
        assertThat(reportingService.trialBalance(userId, ledgerId, "2026-01", true))
                .filteredOn(line -> line.code().equals("1001"))
                .singleElement()
                .extracting(ReportResponses.TrialBalanceLine::debit)
                .isEqualTo(new BigDecimal("100.00"));
    }

    @Test
    void financeQueryGroupsAndFiltersAuxiliaryBalancesWithoutLeavingBaseCurrencyAmounts() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(new CurrentUserResolver.ResolvedUser(userId, "dimension-query", userId.toString()),
                new LedgerRequests.Create("dimension query", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        UUID january = id("select id from accounting_period where ledger_id = ? and period_code = '2026-01'", ledgerId);
        LedgerResponses.DimensionType customer = ledgerService.listDimensionTypes(userId, ledgerId).stream()
                .filter(type -> type.code().equals("CUSTOMER")).findFirst().orElseThrow();
        LedgerResponses.DimensionType project = ledgerService.listDimensionTypes(userId, ledgerId).stream()
                .filter(type -> type.code().equals("PROJECT")).findFirst().orElseThrow();
        LedgerResponses.DimensionValue customerA = ledgerService.createDimensionValue(userId, ledgerId, customer.id(),
                new LedgerRequests.DimensionValueCreate("C-A", "Customer A"));
        LedgerResponses.DimensionValue customerB = ledgerService.createDimensionValue(userId, ledgerId, customer.id(),
                new LedgerRequests.DimensionValueCreate("C-B", "Customer B"));
        LedgerResponses.DimensionValue projectValue = ledgerService.createDimensionValue(userId, ledgerId, project.id(),
                new LedgerRequests.DimensionValueCreate("P-1", "Project 1"));
        UUID receivable = ledgerService.createAccount(userId, ledgerId,
                new LedgerRequests.AccountCreate("1410", "Customer receivable", "ASSET.ACCOUNTS_RECEIVABLE",
                        "CURRENT_ASSET", "DEBIT", null,
                        false, null, false, null,
                        List.of(new LedgerRequests.DimensionRequirement(customer.id(), true),
                                new LedgerRequests.DimensionRequirement(project.id(), true)))).id();
        UUID capital = id("select id from ledger_account where ledger_id = ? and code = '3001'", ledgerId);

        voucherService.create(userId, ledgerId, new VoucherRequests.Create(january, LocalDate.of(2026, 1, 10),
                "GENERAL", "dim-cny", "customer A", List.of(
                dimensionLine(receivable, "DEBIT", "CNY", "10", "1", List.of(
                        new VoucherRequests.Dimension(customer.id(), customerA.id()),
                        new VoucherRequests.Dimension(project.id(), projectValue.id()))),
                line(capital, "CREDIT", "10"))));
        voucherService.create(userId, ledgerId, new VoucherRequests.Create(january, LocalDate.of(2026, 1, 11),
                "GENERAL", "dim-usd", "customer B", List.of(
                dimensionLine(receivable, "DEBIT", "USD", "10", "7", List.of(
                        new VoucherRequests.Dimension(customer.id(), customerB.id()),
                        new VoucherRequests.Dimension(project.id(), projectValue.id()))),
                line(capital, "CREDIT", "70"))));
        assertThatThrownBy(() -> reportingService.financeQuery(userId, ledgerId,
                new FinanceQueryRequests.Query("DEBIT", "2026-01", "2026-01", List.of("CURRENCY"), null)))
                .isInstanceOf(ApiProblemException.class)
                .satisfies(error -> assertThat(((ApiProblemException) error).code())
                        .isEqualTo("BALANCE_PROJECTION_NOT_READY"));
        applyProjection(ledgerId);

        List<ReportResponses.FinanceQueryLine> grouped = reportingService.financeQuery(userId, ledgerId,
                new FinanceQueryRequests.Query("DEBIT", "2026-01", "2026-01", List.of("DIMENSION"), null,
                        List.of(customer.id())));
        assertThat(grouped).filteredOn(line -> line.groupKey().equals("C-A")).singleElement().satisfies(line -> {
            assertThat(line.amount()).isEqualByComparingTo("10.00");
            assertThat(line.dimensions()).singleElement().satisfies(dimension -> {
                assertThat(dimension.dimensionTypeId()).isEqualTo(customer.id());
                assertThat(dimension.dimensionValueId()).isEqualTo(customerA.id());
                assertThat(dimension.dimensionValueName()).isEqualTo("Customer A");
            });
        });
        assertThat(grouped).filteredOn(line -> line.groupKey().equals("UNASSIGNED")).singleElement()
                .extracting(ReportResponses.FinanceQueryLine::amount).satisfies(amount ->
                        assertThat(amount).isEqualByComparingTo(BigDecimal.ZERO));

        List<ReportResponses.FinanceQueryLine> filtered = reportingService.financeQuery(userId, ledgerId,
                new FinanceQueryRequests.Query("DEBIT", "2026-01", "2026-01", List.of("ACCOUNT", "DIMENSION"),
                        new FinanceQueryRequests.Filters(List.of("1410"), null,
                                List.of(new FinanceQueryRequests.DimensionValue(customer.id(), customerB.id()),
                                        new FinanceQueryRequests.DimensionValue(project.id(), projectValue.id()))),
                        List.of(customer.id())));
        assertThat(filtered).singleElement().satisfies(line -> {
            assertThat(line.groupKey()).isEqualTo("1410|C-B");
            assertThat(line.amount()).isEqualByComparingTo("70.00");
        });

        List<ReportResponses.FinanceQueryLine> monthly = reportingService.financeQuery(userId, ledgerId,
                new FinanceQueryRequests.Query("NET", "2026-01", "2026-01", List.of("MONTH", "DIMENSION"),
                        new FinanceQueryRequests.Filters(List.of("1410"), null), List.of(customer.id())));
        assertThat(monthly).extracting(ReportResponses.FinanceQueryLine::groupKey)
                .containsExactly("2026-01|C-A", "2026-01|C-B");

        List<ReportResponses.FinanceQueryLine> currencies = reportingService.financeQuery(userId, ledgerId,
                new FinanceQueryRequests.Query("DEBIT", "2026-01", "2026-01", List.of("CURRENCY"),
                        new FinanceQueryRequests.Filters(List.of("1410"), null), List.of()));
        assertThat(currencies).extracting(ReportResponses.FinanceQueryLine::groupKey)
                .containsExactly("CNY", "USD");
        assertThat(currencies).extracting(ReportResponses.FinanceQueryLine::amount)
                .containsExactly(new BigDecimal("10.00"), new BigDecimal("70.00"));

        jdbcTemplate.update("""
                with generated as (
                    select series.n, 'load-test-v1;' || series.n canonical_key,
                        md5(?::text || ':load-test-v1:' || series.n) digest
                    from generate_series(1, 10001) series(n)
                )
                insert into dimension_combination (
                    id, ledger_id, kind, canonical_key, dimension_key)
                select (substr(digest, 1, 8) || '-' || substr(digest, 9, 4) || '-'
                        || substr(digest, 13, 4) || '-' || substr(digest, 17, 4) || '-'
                        || substr(digest, 21, 12))::uuid,
                    ?, 'LEGACY_UNMAPPED', canonical_key, md5(canonical_key)
                from generated
                """, ledgerId, ledgerId);
        jdbcTemplate.update("""
                insert into dimension_period_balance (
                    ledger_id, period_id, account_id, dimension_combination_id, currency,
                    period_debit_base, closing_debit_base)
                select ?, ?, ?, combination.id, 'CNY', 1, 1
                from dimension_combination combination
                where combination.ledger_id = ? and combination.canonical_key like 'load-test-v1;%'
                """, ledgerId, january, capital, ledgerId);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from dimension_period_balance
                where ledger_id = ? and period_id = ?
                """, Integer.class, ledgerId, january)).isGreaterThan(10_000);

        List<ReportResponses.FinanceQueryLine> cnyOnlyAfterLargeUnrelatedProjection =
                reportingService.financeQuery(userId, ledgerId,
                        new FinanceQueryRequests.Query("DEBIT", "2026-01", "2026-01", List.of("ACCOUNT"),
                                new FinanceQueryRequests.Filters(List.of("1410"), "CNY")));
        assertThat(cnyOnlyAfterLargeUnrelatedProjection).singleElement().satisfies(line -> {
            assertThat(line.groupKey()).isEqualTo("1410");
            assertThat(line.amount()).isEqualByComparingTo("10.00");
            assertThat(line.currency()).isEqualTo("CNY");
        });

        assertThatThrownBy(() -> reportingService.financeQuery(userId, ledgerId,
                new FinanceQueryRequests.Query("DEBIT", "2026-01", "2026-01", List.of("DIMENSION"), null)))
                .isInstanceOf(ApiProblemException.class)
                .satisfies(error -> assertThat(((ApiProblemException) error).code())
                        .isEqualTo("FINANCE_QUERY_DIMENSION_GROUP_INVALID"));
    }

    @Test
    void incomeStatementReadsOperatingProjectionAfterManualProfitLossTransfer() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("income statement closing transfer", "SME", "v1", "CNY",
                        LocalDate.of(2026, 1, 1), false)).id();
        UUID periodId = id("select id from accounting_period where ledger_id = ? and period_code = '2026-01'", ledgerId);
        UUID cashId = id("select id from ledger_account where ledger_id = ? and code = '1001'", ledgerId);
        UUID revenueId = id("select id from ledger_account where ledger_id = ? and code = '5001'", ledgerId);
        UUID profitId = id("select id from ledger_account where ledger_id = ? and code = '3103'", ledgerId);

        voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 15), "记", "1", "Revenue",
                List.of(line(cashId, "DEBIT", "100"), line(revenueId, "CREDIT", "100"))));
        VoucherResponses.Voucher transfer = voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 31), "记", "2", "Manual closing",
                List.of(line(revenueId, "DEBIT", "100"), line(profitId, "CREDIT", "100"))));
        assertThat(accountingRole(transfer.id())).isEqualTo("PROFIT_LOSS_TRANSFER");
        applyProjection(ledgerId);

        assertThat(reportingService.incomeStatement(userId, ledgerId, "2026-01").lines())
                .singleElement()
                .satisfies(line -> {
                    assertThat(line.code()).isEqualTo("5001");
                    assertThat(line.amount()).isEqualByComparingTo("100.00");
                });
    }

    @Test
    void statutoryIncomeStatementUsesOperatingProjectionAfterProfitLossTransfer() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("statutory income closing transfer", "SME", "v1", "CNY",
                        LocalDate.of(2026, 1, 1), false)).id();
        UUID periodId = id("select id from accounting_period where ledger_id = ? and period_code = '2026-01'", ledgerId);
        UUID cashId = id("select id from ledger_account where ledger_id = ? and code = '1001'", ledgerId);
        UUID revenueId = id("select id from ledger_account where ledger_id = ? and code = '5001'", ledgerId);
        UUID profitId = id("select id from ledger_account where ledger_id = ? and code = '3103'", ledgerId);

        voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 15), "记", "1", "Revenue",
                List.of(line(cashId, "DEBIT", "100"), line(revenueId, "CREDIT", "100"))));
        voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 31), "记", "2", "Manual closing",
                List.of(line(revenueId, "DEBIT", "100"), line(profitId, "CREDIT", "100"))));
        applyProjection(ledgerId);

        StatutoryReportResponses.Statement result = reportingService.statutoryStatement(
                userId, ledgerId, "income-statement", "2026-01");
        StatutoryReportResponses.Line revenue = result.groups().get(0).lines().stream()
                .filter(line -> line.lineNo() == 1).findFirst().orElseThrow();
        StatutoryReportResponses.Line netProfit = result.groups().get(0).lines().stream()
                .filter(line -> line.lineNo() == 32).findFirst().orElseThrow();
        assertThat(revenue.primaryAmount()).isEqualByComparingTo("100.00");
        assertThat(revenue.comparativeAmount()).isEqualByComparingTo("100.00");
        assertThat(netProfit.primaryAmount()).isEqualByComparingTo("100.00");
        assertThat(netProfit.comparativeAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    void mixedVoucherIsConservativelyOperatingEvenWhenItIncludesProfitAccount() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("mixed profit account voucher", "SME", "v1", "CNY",
                        LocalDate.of(2026, 1, 1), false)).id();
        UUID periodId = id("select id from accounting_period where ledger_id = ? and period_code = '2026-01'", ledgerId);
        UUID cashId = id("select id from ledger_account where ledger_id = ? and code = '1001'", ledgerId);
        UUID revenueId = id("select id from ledger_account where ledger_id = ? and code = '5001'", ledgerId);
        UUID profitId = id("select id from ledger_account where ledger_id = ? and code = '3103'", ledgerId);

        voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 15), "记", "1", "Revenue",
                List.of(line(cashId, "DEBIT", "100"), line(revenueId, "CREDIT", "100"))));
        VoucherResponses.Voucher mixed = voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 31), "记", "2", "Mixed voucher",
                List.of(line(revenueId, "DEBIT", "100"), line(profitId, "CREDIT", "50"),
                        line(cashId, "CREDIT", "50"))));

        assertThat(accountingRole(mixed.id())).isEqualTo("OPERATING");
    }

    @Test
    void configuredProfitAccountIsUsedForManualTransferClassification() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("configured profit transfer", "SME", "v1", "CNY",
                        LocalDate.of(2026, 1, 1), false)).id();
        UUID periodId = id("select id from accounting_period where ledger_id = ? and period_code = '2026-01'", ledgerId);
        UUID cashId = id("select id from ledger_account where ledger_id = ? and code = '1001'", ledgerId);
        UUID revenueId = id("select id from ledger_account where ledger_id = ? and code = '5001'", ledgerId);
        UUID configuredProfit = ledgerService.createAccount(userId, ledgerId,
                new LedgerRequests.AccountCreate("3999", "自定义本年利润",
                        "EQUITY.CURRENT_YEAR_PROFIT", "EQUITY", "CREDIT")).id();
        jdbcTemplate.update("insert into period_closing_setting (ledger_id, profit_account_id) values (?, ?)",
                ledgerId, configuredProfit);

        voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 15), "记", "1", "Revenue",
                List.of(line(cashId, "DEBIT", "100"), line(revenueId, "CREDIT", "100"))));
        VoucherResponses.Voucher transfer = voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 31), "记", "2", "Configured closing",
                List.of(line(revenueId, "DEBIT", "100"), line(configuredProfit, "CREDIT", "100"))));

        assertThat(accountingRole(transfer.id())).isEqualTo("PROFIT_LOSS_TRANSFER");
    }

    @Test
    void statutoryReportsUseStableLeafMappingsAndRejectNonZeroUnmappedAccounts() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(new CurrentUserResolver.ResolvedUser(
                        userId, "statutory-mapping", userId.toString()),
                new LedgerRequests.Create("statutory mapping", "SME", "v1", "CNY",
                        LocalDate.of(2026, 1, 1), false)).id();
        UUID periodId = id("select id from accounting_period where ledger_id = ? and period_code = '2026-01'", ledgerId);
        UUID cashParent = id("select id from ledger_account where ledger_id = ? and code = '1001'", ledgerId);
        UUID capital = id("select id from ledger_account where ledger_id = ? and code = '3001'", ledgerId);
        LedgerResponses.Account first = ledgerService.createAccount(userId, ledgerId,
                new LedgerRequests.AccountCreate("100101", "Cash branch A", "CURRENT_ASSET", "DEBIT"));
        LedgerResponses.Account second = ledgerService.createAccount(userId, ledgerId,
                new LedgerRequests.AccountCreate("100102", "Cash branch B", "CURRENT_ASSET", "DEBIT"));
        assertThat(first.parentId()).isEqualTo(cashParent);
        assertThat(first.standardAccountKey()).isEqualTo("ASSET.CASH");
        assertThat(second.standardAccountKey()).isEqualTo("ASSET.CASH");

        voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 15), "GENERAL", "stable-key", "Stable key aggregation",
                List.of(line(first.id(), "DEBIT", "40"), line(second.id(), "DEBIT", "60"),
                        line(capital, "CREDIT", "100"))));
        applyProjection(ledgerId);

        LedgerResponses.Account renamed = ledgerService.updateAccount(userId, ledgerId, first.id(),
                new LedgerRequests.AccountPatch(first.version(), null, "Renamed cash branch", null,
                        null, null, null, null, null, null, null, null));
        assertThat(renamed.standardAccountKey()).isEqualTo("ASSET.CASH");
        StatutoryReportResponses.Statement statutory = reportingService.statutoryStatement(
                userId, ledgerId, "balance-sheet", "2026-01");
        assertThat(statutory.groups().stream().flatMap(group -> group.lines().stream())
                .filter(row -> row.lineNo() == 1).findFirst().orElseThrow().primaryAmount())
                .isEqualByComparingTo("100.00");
        assertThat(statutory.checks()).allMatch(StatutoryReportResponses.Check::passed);

        List<ReportResponses.TrialBalanceLine> trial = reportingService.trialBalance(
                userId, ledgerId, "2026-01");
        assertThat(trial.stream().map(ReportResponses.TrialBalanceLine::debit)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(trial.stream().map(ReportResponses.TrialBalanceLine::credit)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));

        UUID unmapped = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into ledger_account (
                    id, ledger_id, code, name, category, normal_balance, level, standard_account_key)
                values (?, ?, '1999', 'Unmapped legacy account', 'CURRENT_ASSET', 'DEBIT', 1, null)
                """, unmapped, ledgerId);
        voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 20), "GENERAL", "unmapped", "Unmapped leaf",
                List.of(line(unmapped, "DEBIT", "10"), line(capital, "CREDIT", "10"))));
        applyProjection(ledgerId);
        assertThatThrownBy(() -> reportingService.statutoryStatement(
                userId, ledgerId, "balance-sheet", "2026-01"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("STATUTORY_ACCOUNT_MAPPING_REQUIRED"));
    }

    private void applyProjection(UUID ledgerId) {
        for (int attempt = 0; attempt < 50 && !projection.status(ledgerId, "2026-01").fresh(); attempt++) {
            if (!projection.applyPendingBatch(200, 5000)) {
                Thread.onSpinWait();
            }
        }
        assertThat(projection.status(ledgerId, "2026-01").fresh()).isTrue();
    }

    private String accountingRole(UUID voucherId) {
        return jdbcTemplate.queryForObject("select accounting_role from voucher where id = ?", String.class, voucherId);
    }

    private VoucherRequests.Line line(UUID accountId, String side, String amount) {
        return new VoucherRequests.Line(accountId, side, "CNY", new BigDecimal(amount), BigDecimal.ONE, "report");
    }

    private VoucherRequests.Line dimensionLine(UUID accountId, String side, String currency, String amount, String rate,
                                               List<VoucherRequests.Dimension> dimensions) {
        return new VoucherRequests.Line(accountId, side, currency, new BigDecimal(amount), new BigDecimal(rate),
                "dimension query", null, null, null, dimensions);
    }

    private UUID id(String sql, UUID ledgerId) {
        return jdbcTemplate.queryForObject(sql, UUID.class, ledgerId);
    }
}
