package com.example.accounting.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.accounting.identity.CurrentUserResolver;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class Stage2LedgerInitializationTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsStandardAccountsAndTwelveOpenPeriodsWithANewLedger() {
        UUID userId = UUID.randomUUID();
        LedgerResponses.Ledger ledger = ledgerService.create(
                new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("初始化测试账套", "SME", "v1", "CNY",
                        LocalDate.of(2026, 1, 15), false));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from ledger_account where ledger_id = ?", Integer.class, ledger.id()))
                .isEqualTo(15);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from accounting_period where ledger_id = ?", Integer.class, ledger.id()))
                .isEqualTo(12);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from accounting_period where ledger_id = ? and status = 'OPEN'",
                Integer.class, ledger.id())).isEqualTo(12);
        assertThat(jdbcTemplate.queryForObject(
                "select name from ledger_account where ledger_id = ? and code = '1002'",
                String.class, ledger.id())).isEqualTo("银行存款");
        assertThat(jdbcTemplate.queryForObject(
                "select period_code from accounting_period where ledger_id = ? order by period_code limit 1",
                String.class, ledger.id())).isEqualTo("2026-01");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from report_formula_snapshot where ledger_id = ?", Integer.class, ledger.id()))
                .isEqualTo(2);
    }
}
