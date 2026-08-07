package com.example.accounting.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.accounting.identity.CurrentUserResolver;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@org.junit.jupiter.api.Disabled("Creates ledgers; disabled until tests use an isolated database")
class Stage2LedgerInitializationTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsStandardAccountsAndOpenPeriodsThroughTheCurrentYear() {
        UUID userId = UUID.randomUUID();
        LocalDate startDate = LocalDate.of(2018, 1, 1);
        LedgerResponses.Ledger ledger = ledgerService.create(
                new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("初始化测试账套", "SME", "v1", "CNY",
                        startDate, false));

        int expectedPeriods = Math.toIntExact(ChronoUnit.MONTHS.between(
                YearMonth.from(startDate), YearMonth.of(YearMonth.now().getYear(), 12)) + 1);

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from ledger_account where ledger_id = ?", Integer.class, ledger.id()))
                .isEqualTo(15);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from accounting_period where ledger_id = ?", Integer.class, ledger.id()))
                .isEqualTo(expectedPeriods);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from accounting_period where ledger_id = ? and status = 'OPEN'",
                Integer.class, ledger.id())).isEqualTo(expectedPeriods);
        assertThat(jdbcTemplate.queryForObject(
                "select name from ledger_account where ledger_id = ? and code = '1002'",
                String.class, ledger.id())).isEqualTo("银行存款");
        assertThat(jdbcTemplate.queryForObject(
                "select period_code from accounting_period where ledger_id = ? order by period_code limit 1",
                String.class, ledger.id())).isEqualTo("2018-01");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from report_formula_snapshot where ledger_id = ?", Integer.class, ledger.id()))
                .isEqualTo(2);
    }
}
