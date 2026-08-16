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
                line(cash.id(), "CREDIT", "20")))))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        exception -> assertThat(exception.code()).isEqualTo("VOUCHER_CONTROL_INCOMPLETE"));

        VoucherRequests.Line controlledLine = new VoucherRequests.Line(
                controlled.id(), "DEBIT", "CNY", new BigDecimal("20"), BigDecimal.ONE,
                "受控行", null, new BigDecimal("2"), new BigDecimal("10"),
                List.of(new VoucherRequests.Dimension(customer.id(), customerValue.id())));
        VoucherResponses.Voucher posted = vouchers.create(owner, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 10), "记", UUID.randomUUID().toString().substring(0, 8),
                "完整控制项", List.of(controlledLine, line(cash.id(), "CREDIT", "20"))));

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
                "before", List.of(controlledLine, line(cash.id(), "CREDIT", "20"))));

        ledgers.updateDimensionValue(owner, ledgerId, customer.id(), customerValue.id(),
                new LedgerRequests.DimensionValuePatch(customerValue.version(), customerValue.name(), "INACTIVE"));

        VoucherResponses.Voucher updated = vouchers.update(owner, ledgerId, posted.id(),
                new VoucherRequests.Update(posted.version(), periodId, posted.voucherDate(), posted.voucherType(),
                        posted.voucherNumber(), "after",
                        List.of(controlledLine, line(cash.id(), "CREDIT", "20"))));

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
                        List.of(replacementLine, line(cash.id(), "CREDIT", "20")))))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVALID_VOUCHER_DIMENSION"));
    }

    private VoucherRequests.Line line(UUID accountId, String side, String amount) {
        return new VoucherRequests.Line(
                accountId, side, "CNY", new BigDecimal(amount), BigDecimal.ONE, "line");
    }

    private CurrentUserResolver.ResolvedUser user(UUID id) {
        return new CurrentUserResolver.ResolvedUser(id, "test", id.toString());
    }
}
