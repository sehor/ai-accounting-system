package com.example.accounting.security;

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
class Stage7IsolationTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private VoucherService voucherService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void rejectsCrossLedgerReadsAndReferences() {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID firstLedger = ledgerService.create(user(owner), new LedgerRequests.Create(
                "isolation-a", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        UUID secondLedger = ledgerService.create(user(other), new LedgerRequests.Create(
                "isolation-b", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        UUID periodId = id("select id from accounting_period where ledger_id = ? and period_code = '2026-01'", firstLedger);
        UUID debit = id("select id from ledger_account where ledger_id = ? and code = '1001'", firstLedger);
        UUID credit = id("select id from ledger_account where ledger_id = ? and code = '3001'", firstLedger);
        UUID voucherId = voucherService.create(owner, firstLedger, new VoucherRequests.Create(periodId,
                LocalDate.of(2026, 1, 15), "GENERAL", "1", "isolated", List.of(
                new VoucherRequests.Line(debit, "DEBIT", "CNY", BigDecimal.ONE, BigDecimal.ONE, "line"),
                new VoucherRequests.Line(credit, "CREDIT", "CNY", BigDecimal.ONE, BigDecimal.ONE, "line")))).id();

        assertThatThrownBy(() -> ledgerService.listAccounts(other, firstLedger))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> voucherService.find(other, secondLedger, voucherId))
                .isInstanceOf(ApiProblemException.class);
    }

    private CurrentUserResolver.ResolvedUser user(UUID id) {
        return new CurrentUserResolver.ResolvedUser(id, "test", id.toString());
    }

    private UUID id(String sql, UUID ledgerId) {
        return jdbcTemplate.queryForObject(sql, UUID.class, ledgerId);
    }
}
