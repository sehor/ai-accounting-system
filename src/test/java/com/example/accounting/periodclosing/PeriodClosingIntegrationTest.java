package com.example.accounting.periodclosing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.ledger.LedgerRequests;
import com.example.accounting.ledger.LedgerService;
import com.example.accounting.shared.web.ApiProblemException;
import com.example.accounting.voucher.VoucherRequests;
import com.example.accounting.voucher.VoucherService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class PeriodClosingIntegrationTest {
    @Autowired private LedgerService ledgers;
    @Autowired private VoucherService vouchers;
    @Autowired private PeriodClosingService closing;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void generatesExpenseAndRevenueTransfersIdempotently() {
        UUID user = UUID.randomUUID();
        UUID ledger = ledgers.create(new CurrentUserResolver.ResolvedUser(user, "test", user.toString()),
                new LedgerRequests.Create("period-closing", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        UUID period = ledgers.periodId(ledger, "2026-01");
        UUID cash = ledgers.accountId(ledger, "1001");
        UUID expense = account(ledger, "EXPENSE");
        UUID revenue = account(ledger, "REVENUE");
        vouchers.create(user, ledger, new VoucherRequests.Create(period, LocalDate.of(2026, 1, 10), "GENERAL", "1", "expense",
                List.of(line(expense, "DEBIT", "100"), line(cash, "CREDIT", "100"))));
        vouchers.create(user, ledger, new VoucherRequests.Create(period, LocalDate.of(2026, 1, 11), "GENERAL", "2", "revenue",
                List.of(line(cash, "DEBIT", "200"), line(revenue, "CREDIT", "200"))));

        PeriodClosingResponses.Step expenseStep = closing.generate(user, ledger, period, PeriodClosingStepType.EXPENSE_TRANSFER);
        PeriodClosingResponses.Step retry = closing.generate(user, ledger, period, PeriodClosingStepType.EXPENSE_TRANSFER);
        assertThat(expenseStep.status()).isEqualTo(PeriodClosingStepStatus.GENERATED);
        assertThat(retry.voucherId()).isEqualTo(expenseStep.voucherId());
        assertThat(closing.generate(user, ledger, period, PeriodClosingStepType.REVENUE_TRANSFER).status())
                .isEqualTo(PeriodClosingStepStatus.GENERATED);
        assertThat(jdbc.queryForObject("select count(*) from voucher where ledger_id = ? and source_type = 'PERIOD_CLOSING'",
                Long.class, ledger)).isEqualTo(2L);
    }

    @Test
    void rejectsYearEndStepOutsideDecember() {
        UUID user = UUID.randomUUID();
        UUID ledger = ledgers.create(new CurrentUserResolver.ResolvedUser(user, "test", user.toString()),
                new LedgerRequests.Create("period-closing-year", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        UUID period = ledgers.periodId(ledger, "2026-01");
        assertThatThrownBy(() -> closing.generate(user, ledger, period, PeriodClosingStepType.YEAR_END_PROFIT_TRANSFER))
                .isInstanceOf(ApiProblemException.class)
                .extracting(e -> ((ApiProblemException) e).code()).isEqualTo("YEAR_END_STEP_NOT_ALLOWED");
    }

    private UUID account(UUID ledger, String category) {
        return jdbc.queryForObject("select id from ledger_account where ledger_id = ? and category = ? and status = 'ACTIVE' "
                + "and not exists (select 1 from ledger_account child where child.ledger_id = ledger_account.ledger_id and child.parent_id = ledger_account.id) "
                + "order by code limit 1", UUID.class, ledger, category);
    }

    private VoucherRequests.Line line(UUID account, String side, String amount) {
        return new VoucherRequests.Line(account, side, "CNY", new BigDecimal(amount), BigDecimal.ONE, "line");
    }
}
