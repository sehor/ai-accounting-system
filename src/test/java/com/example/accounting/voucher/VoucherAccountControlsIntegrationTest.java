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

@SpringBootTest
class VoucherAccountControlsIntegrationTest {

    @Autowired
    private LedgerService ledgers;

    @Autowired
    private VoucherService vouchers;

    @Test
    void allowsIncompleteDraftThenRequiresCashQuantityAndDimensionsForValidation() {
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
                        "1410", "受控资产", "ASSET", "DEBIT", null,
                        true, cashFlow.id(), true, "件",
                        List.of(new LedgerRequests.DimensionRequirement(customer.id(), true))));
        LedgerResponses.Account cash = ledgers.listAccounts(owner, ledgerId).stream()
                .filter(account -> account.code().equals("1001")).findFirst().orElseThrow();
        UUID periodId = ledgers.listPeriods(owner, ledgerId).getFirst().id();

        VoucherResponses.Voucher draft = vouchers.create(owner, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 10), "记", UUID.randomUUID().toString().substring(0, 8),
                "受控草稿", List.of(
                line(controlled.id(), "DEBIT", "20"),
                line(cash.id(), "CREDIT", "20"))));
        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThatThrownBy(() -> vouchers.validate(owner, ledgerId, draft.id()))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        exception -> assertThat(exception.code()).isEqualTo("VOUCHER_CONTROL_INCOMPLETE"));

        VoucherRequests.Line controlledLine = new VoucherRequests.Line(
                controlled.id(), "DEBIT", "CNY", new BigDecimal("20"), BigDecimal.ONE,
                "受控行", null, new BigDecimal("2"), new BigDecimal("10"),
                List.of(new VoucherRequests.Dimension(customer.id(), customerValue.id())));
        VoucherResponses.Voucher updated = vouchers.update(owner, ledgerId, draft.id(),
                new VoucherRequests.Update(
                        draft.version(), periodId, LocalDate.of(2026, 1, 10), "记",
                        draft.voucherNumber(), "完整控制项",
                        List.of(controlledLine, line(cash.id(), "CREDIT", "20"))));
        VoucherResponses.Voucher validated = vouchers.validate(owner, ledgerId, updated.id());

        assertThat(validated.status()).isEqualTo("VALIDATED");
        VoucherResponses.Line saved = validated.lines().getFirst();
        assertThat(saved.cashFlowItemId()).isEqualTo(cashFlow.id());
        assertThat(saved.quantity()).isEqualByComparingTo("2");
        assertThat(saved.unitPrice()).isEqualByComparingTo("10");
        assertThat(saved.dimensions()).containsExactly(
                new VoucherResponses.Dimension(customer.id(), customerValue.id()));
    }

    private VoucherRequests.Line line(UUID accountId, String side, String amount) {
        return new VoucherRequests.Line(
                accountId, side, "CNY", new BigDecimal(amount), BigDecimal.ONE, "line");
    }

    private CurrentUserResolver.ResolvedUser user(UUID id) {
        return new CurrentUserResolver.ResolvedUser(id, "test", id.toString());
    }
}
