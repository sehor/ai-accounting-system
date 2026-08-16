package com.example.accounting.reporting.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.ledger.LedgerRequests;
import com.example.accounting.ledger.LedgerService;
import com.example.accounting.reporting.PeriodRange;
import com.example.accounting.reporting.formula.CashFlowSource;
import com.example.accounting.reporting.internal.port.ReportingRepository;
import com.example.accounting.voucher.VoucherRequests;
import com.example.accounting.voucher.VoucherService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Directed integration tests for the statutory cash flow aggregation queries:
 * posted-only scope, period bounds, internal cash-to-cash transfer exclusion,
 * per-item debit/credit totals, and classification quality counts/samples that
 * share one predicate.
 */
@SpringBootTest(properties = "accounting.balance.worker-enabled=false")
class JdbcReportingRepositoryCashFlowTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private VoucherService voucherService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReportingRepository reports;

    @Test
    void aggregatesOnlyPostedExternalCashMovementsByItem() {
        Fixture fixture = newFixture();
        UUID user = fixture.userId;

        // External inflow: investor capital into cash, classified on the cash line.
        voucherService.create(user, fixture.ledgerId, new VoucherRequests.Create(
                fixture.periodId, LocalDate.of(2026, 1, 10), "记", "1", "capital",
                List.of(line(fixture.cash, "DEBIT", "100.00", item(fixture.ledgerId, "SME_CF_15_CAPITAL_RECEIPTS")),
                        line(fixture.capital, "CREDIT", "100.00", null))));
        // External outflow: interest paid from bank, classified on the bank line.
        voucherService.create(user, fixture.ledgerId, new VoucherRequests.Create(
                fixture.periodId, LocalDate.of(2026, 1, 11), "记", "2", "interest",
                List.of(line(fixture.expense, "DEBIT", "20.00", null),
                        line(fixture.bank, "CREDIT", "20.00", item(fixture.ledgerId, "SME_CF_17_INTEREST_PAYMENTS")))));
        // Pure internal transfer between cash accounts: unclassified, must not count.
        voucherService.create(user, fixture.ledgerId, new VoucherRequests.Create(
                fixture.periodId, LocalDate.of(2026, 1, 12), "记", "3", "transfer",
                List.of(line(fixture.cash, "DEBIT", "50.00", null),
                        line(fixture.bank, "CREDIT", "50.00", null))));
        // Draft voucher: excluded regardless of classification.
        insertDraftVoucher(fixture, "4", "draft",
                line(fixture.cash, "DEBIT", "30.00", item(fixture.ledgerId, "SME_CF_01_SALES_RECEIPTS")),
                line(fixture.revenue, "CREDIT", "30.00", null));

        CashFlowSource source = reports.cashFlowAmounts(fixture.ledgerId,
                PeriodRange.single("2026-01"), Set.of(fixture.cash, fixture.bank),
                Set.of("SME_CF_15_CAPITAL_RECEIPTS", "SME_CF_17_INTEREST_PAYMENTS",
                        "SME_CF_01_SALES_RECEIPTS"));

        assertThat(source.debit().get("SME_CF_15_CAPITAL_RECEIPTS"))
                .isEqualByComparingTo("100.00");
        assertThat(source.credit().get("SME_CF_15_CAPITAL_RECEIPTS"))
                .isEqualByComparingTo("0.00");
        assertThat(source.credit().get("SME_CF_17_INTEREST_PAYMENTS"))
                .isEqualByComparingTo("20.00");
        // Draft voucher cash must not appear: the code has no posted rows at all.
        assertThat(source.debit().containsKey("SME_CF_01_SALES_RECEIPTS")).isFalse();
        // Missing codes are absent from the source (the evaluator treats them as zero).
        assertThat(source.debit().get("SME_CF_03_PURCHASE_PAYMENTS")).isNull();
    }

    @Test
    void qualityCountsAndSamplesClassifyEveryUnclassifiedReason() {
        Fixture fixture = newFixture();
        UUID user = fixture.userId;
        Set<String> reportable = Set.of("SME_CF_01_SALES_RECEIPTS", "SME_CF_02_OTHER_OPERATING_RECEIPTS",
                "SME_CF_03_PURCHASE_PAYMENTS", "SME_CF_04_EMPLOYEE_PAYMENTS", "SME_CF_05_TAX_PAYMENTS",
                "SME_CF_06_OTHER_OPERATING_PAYMENTS", "SME_CF_08_INVESTMENT_RECOVERY",
                "SME_CF_09_INVESTMENT_INCOME", "SME_CF_10_ASSET_DISPOSAL", "SME_CF_11_INVESTMENT_PAYMENTS",
                "SME_CF_12_ASSET_ACQUISITION", "SME_CF_14_BORROWING_RECEIPTS", "SME_CF_15_CAPITAL_RECEIPTS",
                "SME_CF_16_PRINCIPAL_REPAYMENTS", "SME_CF_17_INTEREST_PAYMENTS", "SME_CF_18_PROFIT_DISTRIBUTION");

        // Fully classified voucher: not part of the quality result.
        voucherService.create(user, fixture.ledgerId, new VoucherRequests.Create(
                fixture.periodId, LocalDate.of(2026, 1, 5), "记", "1", "clean",
                List.of(line(fixture.cash, "DEBIT", "10.00", item(fixture.ledgerId, "SME_CF_01_SALES_RECEIPTS")),
                        line(fixture.revenue, "CREDIT", "10.00", null))));
        // Missing item entirely: raw SQL because new vouchers require classification.
        insertPostedVoucher(fixture, "2", "missing",
                line(fixture.cash, "DEBIT", "11.00", null),
                line(fixture.revenue, "CREDIT", "11.00", null));
        // Legacy coarse item via raw SQL (SME templates no longer seed these).
        UUID legacyItem = insertItem(fixture.ledgerId, "OPERATING", "经营现金流", "ACTIVE", true);
        insertPostedVoucher(fixture, "3", "legacy",
                line(fixture.cash, "DEBIT", "12.00", legacyItem),
                line(fixture.revenue, "CREDIT", "12.00", null));
        // Inactive item: deactivate the packaged item, then reference it.
        UUID inactiveItem = item(fixture.ledgerId, "SME_CF_05_TAX_PAYMENTS");
        jdbc.update("update cash_flow_item set status = 'INACTIVE' where id = ?", inactiveItem);
        insertPostedVoucher(fixture, "4", "inactive",
                line(fixture.cash, "DEBIT", "13.00", inactiveItem),
                line(fixture.revenue, "CREDIT", "13.00", null));
        // Active custom item outside the published formula.
        UUID customItem = insertItem(fixture.ledgerId, "CUSTOM_CF_ITEM", "自定义项目", "ACTIVE", false);
        insertPostedVoucher(fixture, "5", "custom",
                line(fixture.cash, "DEBIT", "14.00", customItem),
                line(fixture.revenue, "CREDIT", "14.00", null));

        ReportingRepository.CashFlowQuality quality = reports.cashFlowQuality(
                fixture.ledgerId, PeriodRange.single("2026-01"),
                Set.of(fixture.cash, fixture.bank), reportable, 10);

        assertThat(quality.unclassifiedVoucherCount()).isEqualTo(4);
        assertThat(quality.unclassifiedLineCount()).isEqualTo(4);
        assertThat(quality.samples()).hasSize(4);
        assertThat(quality.samples()).extracting(ReportingRepository.CashFlowSample::reason)
                .containsExactly("ITEM_MISSING", "LEGACY_COARSE_ITEM", "ITEM_INACTIVE", "ITEM_NOT_IN_FORMULA");
        assertThat(quality.samples()).extracting(ReportingRepository.CashFlowSample::voucherNumber)
                .containsExactly("2", "3", "4", "5");
        assertThat(quality.samples()).extracting(ReportingRepository.CashFlowSample::baseAmount)
                .containsExactly(new BigDecimal("11.00"), new BigDecimal("12.00"),
                        new BigDecimal("13.00"), new BigDecimal("14.00"));
    }

    private Fixture newFixture() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(new CurrentUserResolver.ResolvedUser(userId, "cf-test",
                        userId.toString()),
                new LedgerRequests.Create("cf-repo", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        return new Fixture(userId, ledgerId,
                id("select id from accounting_period where ledger_id = ? and period_code = '2026-01'", ledgerId),
                id("select id from ledger_account where ledger_id = ? and code = '1001'", ledgerId),
                id("select id from ledger_account where ledger_id = ? and code = '1002'", ledgerId),
                id("select id from ledger_account where ledger_id = ? and code = '3001'", ledgerId),
                id("select id from ledger_account where ledger_id = ? and code = '5001'", ledgerId),
                id("select id from ledger_account where ledger_id = ? and code = '5603'", ledgerId));
    }

    private VoucherRequests.Line line(UUID accountId, String side, String amount, UUID itemId) {
        return new VoucherRequests.Line(accountId, side, "CNY", new BigDecimal(amount), BigDecimal.ONE,
                "cf-test", itemId, null, null, null);
    }

    private UUID item(UUID ledgerId, String code) {
        return jdbc.queryForObject(
                "select id from cash_flow_item where ledger_id = ? and code = ?", UUID.class, ledgerId, code);
    }

    private UUID insertItem(UUID ledgerId, String code, String name, String status, boolean template) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into cash_flow_item (id, ledger_id, code, name, status, is_template)
                values (?, ?, ?, ?, ?, ?)
                """, id, ledgerId, code, name, status, template);
        return id;
    }

    private void insertDraftVoucher(Fixture fixture, String number, String summary,
                                    VoucherRequests.Line debit, VoucherRequests.Line credit) {
        insertVoucherRow(fixture, number, summary, "DRAFT", debit, credit);
    }

    private void insertPostedVoucher(Fixture fixture, String number, String summary,
                                     VoucherRequests.Line debit, VoucherRequests.Line credit) {
        insertVoucherRow(fixture, number, summary, "POSTED", debit, credit);
    }

    private void insertVoucherRow(Fixture fixture, String number, String summary, String status,
                                  VoucherRequests.Line debit, VoucherRequests.Line credit) {
        UUID voucherId = UUID.randomUUID();
        jdbc.update("""
                insert into voucher (id, ledger_id, period_id, voucher_date, voucher_type, voucher_number,
                    summary, status, created_by, updated_by)
                values (?, ?, ?, ?, '记', ?, ?, ?, ?, ?)
                """, voucherId, fixture.ledgerId, fixture.periodId, LocalDate.of(2026, 1, 6), number,
                summary, status, fixture.userId, fixture.userId);
        insertLine(voucherId, fixture.ledgerId, 1, debit);
        insertLine(voucherId, fixture.ledgerId, 2, credit);
    }

    private void insertLine(UUID voucherId, UUID ledgerId, int lineNo, VoucherRequests.Line line) {
        UUID lineId = UUID.randomUUID();
        BigDecimal base = line.exchangeRate() == null || line.exchangeRate().signum() == 0
                ? line.originalAmount() : line.originalAmount().multiply(line.exchangeRate());
        jdbc.update("""
                insert into voucher_line (id, ledger_id, voucher_id, line_no, account_id, side,
                    currency, original_amount, exchange_rate, base_amount, cash_flow_item_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, lineId, ledgerId, voucherId, lineNo, line.accountId(), line.side(), line.currency(),
                line.originalAmount(), line.exchangeRate(), base, line.cashFlowItemId());
    }

    private UUID id(String sql, UUID ledgerId) {
        return jdbc.queryForObject(sql, UUID.class, ledgerId);
    }

    private record Fixture(UUID userId, UUID ledgerId, UUID periodId, UUID cash, UUID bank,
                           UUID capital, UUID revenue, UUID expense) {
    }
}
