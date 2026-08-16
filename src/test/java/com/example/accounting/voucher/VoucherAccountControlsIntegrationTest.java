package com.example.accounting.voucher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.ledger.LedgerRequests;
import com.example.accounting.ledger.LedgerResponses;
import com.example.accounting.ledger.LedgerService;
import com.example.accounting.shared.web.ApiProblemException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "accounting.balance.worker-enabled=false")
class VoucherAccountControlsIntegrationTest {

    @Autowired
    private LedgerService ledgers;

    @Autowired
    private VoucherService vouchers;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void requiresCompleteControlsBeforeSavingAndPosting() {
        UUID owner = UUID.randomUUID();
        UUID ledgerId = ledgers.create(user(owner), new LedgerRequests.Create(
                "voucher-controls", "SME", "2011-17", "CNY",
                LocalDate.of(2026, 1, 1), false)).id();
        LedgerResponses.DimensionType customer = ledgers.listDimensionTypes(owner, ledgerId).stream()
                .filter(type -> type.code().equals("CUSTOMER")).findFirst().orElseThrow();
        LedgerResponses.DimensionValue customerValue = ledgers.createDimensionValue(
                owner, ledgerId, customer.id(),
                new LedgerRequests.DimensionValueCreate("C001", "测试客户"));
        LedgerResponses.CashFlowItem cashFlow = ledgers.listCashFlowItems(owner, ledgerId).getFirst();
        LedgerResponses.Account controlled = ledgers.createAccount(owner, ledgerId,
                new LedgerRequests.AccountCreate(
                        "1410", "受控资产", "ASSET.ACCOUNTS_RECEIVABLE", "CURRENT_ASSET", "DEBIT", null,
                        true, cashFlow.id(), true, "件",
                        List.of(new LedgerRequests.DimensionRequirement(customer.id(), true))));
        LedgerResponses.Account cash = ledgers.listAccounts(owner, ledgerId).stream()
                .filter(account -> account.code().equals("1001")).findFirst().orElseThrow();
        UUID periodId = ledgers.listPeriods(owner, ledgerId).getFirst().id();

        assertThatThrownBy(() -> vouchers.create(owner, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 10), "记", UUID.randomUUID().toString().substring(0, 8),
                "受控凭证", List.of(
                line(controlled.id(), "DEBIT", "20"),
                cashLine(cash.id(), "CREDIT", "20", cashFlow.id())))))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        exception -> assertThat(exception.code()).isEqualTo("VOUCHER_CONTROL_INCOMPLETE"));

        VoucherRequests.Line controlledLine = new VoucherRequests.Line(
                controlled.id(), "DEBIT", "CNY", new BigDecimal("20"), BigDecimal.ONE,
                "受控行", null, new BigDecimal("2"), new BigDecimal("10"),
                List.of(new VoucherRequests.Dimension(customer.id(), customerValue.id())));
        VoucherResponses.Voucher posted = vouchers.create(owner, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 10), "记", UUID.randomUUID().toString().substring(0, 8),
                "完整控制项", List.of(controlledLine, cashLine(cash.id(), "CREDIT", "20", cashFlow.id()))));

        assertThat(posted.status()).isEqualTo("POSTED");
        VoucherResponses.Line saved = posted.lines().getFirst();
        assertThat(saved.cashFlowItemId()).isEqualTo(cashFlow.id());
        assertThat(saved.quantity()).isEqualByComparingTo("2");
        assertThat(saved.unitPrice()).isEqualByComparingTo("10");
        assertThat(saved.dimensions()).containsExactly(
                new VoucherResponses.Dimension(customer.id(), customerValue.id()));
        UUID combinationId = jdbc.queryForObject(
                "select dimension_combination_id from voucher_line where ledger_id = ? and id = ?",
                UUID.class, ledgerId, saved.id());
        assertThat(combinationId).isNotNull();
        assertThat(jdbc.queryForObject("""
                select count(*) from dimension_combination_member
                where ledger_id = ? and combination_id = ?
                """, Integer.class, ledgerId, combinationId)).isEqualTo(1);
    }

    @Test
    void preservesAnUnchangedInactiveDimensionCombinationWhenUpdatingAnExistingLine() {
        UUID owner = UUID.randomUUID();
        UUID ledgerId = ledgers.create(user(owner), new LedgerRequests.Create(
                "voucher-inactive-dimension-update", "SME", "2011-17", "CNY",
                LocalDate.of(2026, 1, 1), false)).id();
        LedgerResponses.DimensionType customer = ledgers.listDimensionTypes(owner, ledgerId).stream()
                .filter(type -> type.code().equals("CUSTOMER")).findFirst().orElseThrow();
        LedgerResponses.DimensionValue customerValue = ledgers.createDimensionValue(
                owner, ledgerId, customer.id(),
                new LedgerRequests.DimensionValueCreate("C001", "Historical customer"));
        LedgerResponses.CashFlowItem cashFlow = ledgers.listCashFlowItems(owner, ledgerId).getFirst();
        LedgerResponses.Account controlled = ledgers.createAccount(owner, ledgerId,
                new LedgerRequests.AccountCreate(
                        "1411", "Controlled asset", "ASSET.ACCOUNTS_RECEIVABLE", "CURRENT_ASSET", "DEBIT", null,
                        true, cashFlow.id(), false, null,
                        List.of(new LedgerRequests.DimensionRequirement(customer.id(), true))));
        LedgerResponses.Account cash = ledgers.listAccounts(owner, ledgerId).stream()
                .filter(account -> account.code().equals("1001")).findFirst().orElseThrow();
        UUID periodId = ledgers.listPeriods(owner, ledgerId).getFirst().id();
        VoucherRequests.Line controlledLine = new VoucherRequests.Line(
                controlled.id(), "DEBIT", "CNY", new BigDecimal("20"), BigDecimal.ONE,
                "controlled line", null, null, null,
                List.of(new VoucherRequests.Dimension(customer.id(), customerValue.id())));
        VoucherResponses.Voucher posted = vouchers.create(owner, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 10), "记", UUID.randomUUID().toString().substring(0, 8),
                "before", List.of(controlledLine, cashLine(cash.id(), "CREDIT", "20", cashFlow.id()))));

        ledgers.updateDimensionValue(owner, ledgerId, customer.id(), customerValue.id(),
                new LedgerRequests.DimensionValuePatch(customerValue.version(), customerValue.name(), "INACTIVE"));

        VoucherResponses.Voucher updated = vouchers.update(owner, ledgerId, posted.id(),
                new VoucherRequests.Update(posted.version(), periodId, posted.voucherDate(), posted.voucherType(),
                        posted.voucherNumber(), "after",
                        List.of(controlledLine, cashLine(cash.id(), "CREDIT", "20", cashFlow.id()))));

        assertThat(updated.version()).isEqualTo(posted.version() + 1);
        assertThat(updated.summary()).isEqualTo("after");
        assertThat(updated.lines().getFirst().dimensions()).containsExactly(
                new VoucherResponses.Dimension(customer.id(), customerValue.id()));

        LedgerResponses.DimensionValue replacement = ledgers.createDimensionValue(
                owner, ledgerId, customer.id(),
                new LedgerRequests.DimensionValueCreate("C002", "Inactive replacement"));
        ledgers.updateDimensionValue(owner, ledgerId, customer.id(), replacement.id(),
                new LedgerRequests.DimensionValuePatch(replacement.version(), replacement.name(), "INACTIVE"));
        VoucherRequests.Line replacementLine = new VoucherRequests.Line(
                controlled.id(), "DEBIT", "CNY", new BigDecimal("20"), BigDecimal.ONE,
                "controlled line", null, null, null,
                List.of(new VoucherRequests.Dimension(customer.id(), replacement.id())));

        assertThatThrownBy(() -> vouchers.update(owner, ledgerId, updated.id(),
                new VoucherRequests.Update(updated.version(), periodId, updated.voucherDate(), updated.voucherType(),
                        updated.voucherNumber(), "must roll back",
                        List.of(replacementLine, cashLine(cash.id(), "CREDIT", "20", cashFlow.id())))))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVALID_VOUCHER_DIMENSION"));
    }

    @Test
    void requiresDetailedCashFlowClassificationForExternalCashLines() {
        UUID owner = UUID.randomUUID();
        UUID ledgerId = ledgers.create(user(owner), new LedgerRequests.Create(
                "cash-flow-classification", "SME", "2011-17", "CNY",
                LocalDate.of(2026, 1, 1), false)).id();
        LedgerResponses.Account cash = ledgers.listAccounts(owner, ledgerId).stream()
                .filter(account -> account.code().equals("1001")).findFirst().orElseThrow();
        LedgerResponses.Account bank = ledgers.listAccounts(owner, ledgerId).stream()
                .filter(account -> account.code().equals("1002")).findFirst().orElseThrow();
        LedgerResponses.Account capital = ledgers.listAccounts(owner, ledgerId).stream()
                .filter(account -> account.code().equals("3001")).findFirst().orElseThrow();
        UUID periodId = ledgers.listPeriods(owner, ledgerId).getFirst().id();
        UUID salesItem = itemId(ledgerId, "SME_CF_01_SALES_RECEIPTS");
        UUID legacyCoarse = insertItem(ledgerId, "OPERATING", "经营现金流", true);
        UUID customItem = insertItem(ledgerId, "CUSTOM_CF_ITEM", "自定义项目", false);

        // 外部现金收支缺分类 → 422。
        assertThatThrownBy(() -> vouchers.create(owner, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 10), "记", "1", "missing",
                List.of(cashLine(cash.id(), "DEBIT", "100", null),
                        line(capital.id(), "CREDIT", "100")))))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("CASH_FLOW_CLASSIFICATION_REQUIRED"));
        // 旧粗分类项目 → 422。
        assertThatThrownBy(() -> vouchers.create(owner, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 10), "记", "2", "legacy",
                List.of(cashLine(cash.id(), "DEBIT", "100", legacyCoarse),
                        line(capital.id(), "CREDIT", "100")))))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("CASH_FLOW_ITEM_NOT_REPORTABLE"));
        // 公式不接收的项目 → 422。
        assertThatThrownBy(() -> vouchers.create(owner, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 10), "记", "3", "custom",
                List.of(cashLine(cash.id(), "DEBIT", "100", customItem),
                        line(capital.id(), "CREDIT", "100")))))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("CASH_FLOW_ITEM_NOT_REPORTABLE"));
        // 复合凭证（含非现金行）要求每条现金行分类：银行行缺项目 → 422。
        LedgerResponses.Account expense = ledgers.listAccounts(owner, ledgerId).stream()
                .filter(account -> account.code().equals("5603")).findFirst().orElseThrow();
        assertThatThrownBy(() -> vouchers.create(owner, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 10), "记", "4", "mixed",
                List.of(cashLine(cash.id(), "DEBIT", "50", null),
                        line(expense.id(), "DEBIT", "50"),
                        cashLine(bank.id(), "CREDIT", "100", salesItem)))))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("CASH_FLOW_CLASSIFICATION_REQUIRED"));
        // 纯现金内部划转：无项目也可过账。
        VoucherResponses.Voucher transfer = vouchers.create(owner, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 10), "记", "5", "transfer",
                List.of(line(cash.id(), "DEBIT", "50"), line(bank.id(), "CREDIT", "50"))));
        assertThat(transfer.status()).isEqualTo("POSTED");
        // 非现金凭证不受影响。
        VoucherResponses.Voucher nonCash = vouchers.create(owner, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 10), "记", "6", "non-cash",
                List.of(line(capital.id(), "DEBIT", "30"), line(capital.id(), "CREDIT", "30"))));
        assertThat(nonCash.status()).isEqualTo("POSTED");
        // 外部现金行完整分类后可过账。
        VoucherResponses.Voucher posted = vouchers.create(owner, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 10), "记", "7", "classified",
                List.of(cashLine(cash.id(), "DEBIT", "100", salesItem),
                        line(capital.id(), "CREDIT", "100"))));
        assertThat(posted.status()).isEqualTo("POSTED");
    }

    private UUID itemId(UUID ledgerId, String code) {
        return jdbc.queryForObject(
                "select id from cash_flow_item where ledger_id = ? and code = ?", UUID.class, ledgerId, code);
    }

    private UUID insertItem(UUID ledgerId, String code, String name, boolean template) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into cash_flow_item (id, ledger_id, code, name, is_template)
                values (?, ?, ?, ?, ?)
                """, id, ledgerId, code, name, template);
        return id;
    }

    private VoucherRequests.Line line(UUID accountId, String side, String amount) {
        return new VoucherRequests.Line(
                accountId, side, "CNY", new BigDecimal(amount), BigDecimal.ONE, "line");
    }

    private VoucherRequests.Line cashLine(UUID accountId, String side, String amount, UUID itemId) {
        return new VoucherRequests.Line(
                accountId, side, "CNY", new BigDecimal(amount), BigDecimal.ONE, "cash line",
                itemId, null, null, null);
    }

    private CurrentUserResolver.ResolvedUser user(UUID id) {
        return new CurrentUserResolver.ResolvedUser(id, "test", id.toString());
    }
}
