package com.example.accounting.voucher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.ledger.LedgerRequests;
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

@SpringBootTest
class Stage3VoucherTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private VoucherService voucherService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void savesABalancedVoucherAsPosted() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(
                new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("voucher", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        UUID periodId = periodId(ledgerId, "2026-01");
        UUID cashId = accountId(ledgerId, "1001");
        UUID capitalId = accountId(ledgerId, "3001");

        VoucherResponses.Voucher voucher = voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 15), "记", "1", "Opening",
                List.of(line(cashId, "DEBIT", "100"), line(capitalId, "CREDIT", "100"))));
        assertThat(voucher.status()).isEqualTo("POSTED");
        assertThat(voucher.approvalRequired()).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from voucher_approval where voucher_id = ? and action = 'APPROVE'",
                Long.class, voucher.id())).isEqualTo(1L);
    }

    @Test
    void assignsTheNextVoucherNumberWhenItIsOmitted() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(
                new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("automatic-number", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        UUID periodId = periodId(ledgerId, "2026-01");
        List<VoucherRequests.Line> lines = List.of(line(accountId(ledgerId, "1001"), "DEBIT", "1"),
                line(accountId(ledgerId, "3001"), "CREDIT", "1"),
                new VoucherRequests.Line(null, "DEBIT", "CNY", null, BigDecimal.ONE,
                        null, null, null, null, List.of()));

        VoucherResponses.Voucher numbered = voucherService.create(userId, ledgerId,
                new VoucherRequests.Create(periodId, LocalDate.of(2026, 1, 15), "记", "7", "Manual", lines));
        VoucherResponses.Voucher generated = voucherService.create(userId, ledgerId,
                new VoucherRequests.Create(null, LocalDate.of(2026, 1, 15), "记", null, "Generated", lines));
        VoucherResponses.Voucher nextGenerated = voucherService.create(userId, ledgerId,
                new VoucherRequests.Create(null, LocalDate.of(2026, 1, 15), "记", " ", "Generated", lines));

        assertThat(numbered.voucherNumber()).isEqualTo("7");
        assertThat(generated.periodId()).isEqualTo(periodId);
        assertThat(generated.voucherNumber()).isEqualTo("8");
        assertThat(nextGenerated.voucherNumber()).isEqualTo("9");
        assertThat(generated.lines()).hasSize(2);
    }

    @Test
    void rejectsClosedOrUnmappedVoucherDatesWhenPeriodIsOmitted() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(
                new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("date-derived-period", "SME", "v1", "CNY",
                        LocalDate.of(2026, 1, 1), false)).id();
        UUID periodId = periodId(ledgerId, "2026-01");
        List<VoucherRequests.Line> lines = List.of(line(accountId(ledgerId, "1001"), "DEBIT", "1"),
                line(accountId(ledgerId, "3001"), "CREDIT", "1"));
        jdbcTemplate.update("update accounting_period set status = 'CLOSED' where ledger_id = ? and id = ?",
                ledgerId, periodId);

        assertThatThrownBy(() -> voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                null, LocalDate.of(2026, 1, 15), "记", null, "Closed", lines)))
                .isInstanceOf(ApiProblemException.class)
                .satisfies(exception -> {
                    ApiProblemException problem = (ApiProblemException) exception;
                    assertThat(problem.status()).isEqualTo(409);
                    assertThat(problem.code()).isEqualTo("ACCOUNTING_PERIOD_CLOSED");
                });
        assertThatThrownBy(() -> voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                null, LocalDate.of(2030, 1, 15), "记", null, "Unmapped", lines)))
                .isInstanceOf(ApiProblemException.class)
                .satisfies(exception -> {
                    ApiProblemException problem = (ApiProblemException) exception;
                    assertThat(problem.status()).isEqualTo(422);
                    assertThat(problem.code()).isEqualTo("VOUCHER_PERIOD_NOT_FOUND");
                });
    }

    @Test
    void rejectsPeriodDateMismatchAndOverlappingPeriodConfiguration() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(
                new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("period-date-validation", "SME", "v1", "CNY",
                        LocalDate.of(2026, 1, 1), false)).id();
        List<VoucherRequests.Line> lines = List.of(line(accountId(ledgerId, "1001"), "DEBIT", "1"),
                line(accountId(ledgerId, "3001"), "CREDIT", "1"));

        assertThatThrownBy(() -> voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId(ledgerId, "2026-02"), LocalDate.of(2026, 1, 15), "记", null, "Mismatch", lines)))
                .isInstanceOf(ApiProblemException.class)
                .extracting(exception -> ((ApiProblemException) exception).code())
                .isEqualTo("VOUCHER_PERIOD_DATE_MISMATCH");

        jdbcTemplate.update("""
                insert into accounting_period (id, ledger_id, period_code, start_date, end_date, status)
                values (?, ?, '2026-13', '2026-01-10', '2026-01-20', 'OPEN')
                """, UUID.randomUUID(), ledgerId);
        assertThatThrownBy(() -> voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                null, LocalDate.of(2026, 1, 15), "记", null, "Overlap", lines)))
                .isInstanceOf(ApiProblemException.class)
                .satisfies(exception -> {
                    ApiProblemException problem = (ApiProblemException) exception;
                    assertThat(problem.status()).isEqualTo(409);
                    assertThat(problem.code()).isEqualTo("ACCOUNTING_PERIOD_DATE_OVERLAP");
                });
    }

    @Test
    void filtersVouchersByKeywordAndInclusiveDateRange() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(
                new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("voucher-search", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        UUID periodId = periodId(ledgerId, "2026-01");
        List<VoucherRequests.Line> lines = List.of(
                new VoucherRequests.Line(accountId(ledgerId, "1001"), "DEBIT", "CNY", new BigDecimal("1"),
                        BigDecimal.ONE, "研发工资"),
                new VoucherRequests.Line(accountId(ledgerId, "3001"), "CREDIT", "CNY", new BigDecimal("1"),
                        BigDecimal.ONE, "研发工资"));
        voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 5), "记", "1", "期初", lines));
        voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 15), "记", "2", "计提工资", lines));
        voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 25), "记", "3", "缴纳社保", lines));
        VoucherRequests.Search search = new VoucherRequests.Search(null, LocalDate.of(2026, 1, 10),
                LocalDate.of(2026, 1, 20), "工资");

        assertThat(voucherService.list(userId, ledgerId, search, 20, 0))
                .extracting(VoucherResponses.Voucher::summary).containsExactly("计提工资");
        assertThat(voucherService.count(userId, ledgerId, search)).isEqualTo(1L);
    }

    @Test
    void rejectsAnUnbalancedVoucherDuringValidation() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(
                new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("unbalanced", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        UUID periodId = periodId(ledgerId, "2026-01");
        UUID cashId = accountId(ledgerId, "1001");
        assertThatThrownBy(() -> voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 15), "记", "1", "Invalid",
                List.of(line(cashId, "DEBIT", "100")))))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("equal debit");
    }

    @Test
    void savesAsPostedEvenWhenTheLedgerHasLegacyApprovalEnabled() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(
                new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("approval", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), true)).id();
        UUID periodId = periodId(ledgerId, "2026-01");
        VoucherResponses.Voucher voucher = voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 15), "记", "1", "Approval",
                List.of(line(accountId(ledgerId, "1001"), "DEBIT", "100"),
                        line(accountId(ledgerId, "3001"), "CREDIT", "100"))));
        assertThat(voucher.status()).isEqualTo("POSTED");
        assertThat(voucher.approvalRequired()).isTrue();
    }

    @Test
    void updatesAGeneratedPostedVoucherDirectlyInAnOpenPeriod() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(
                new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("posted-update", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        UUID periodId = periodId(ledgerId, "2026-01");
        VoucherResponses.Voucher voucher = voucherService.createGenerated(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 15), "记", "1", "Before",
                List.of(line(accountId(ledgerId, "1001"), "DEBIT", "100"),
                        line(accountId(ledgerId, "3001"), "CREDIT", "100"))),
                "generated-voucher", "FIXED_ASSET", UUID.randomUUID());
        VoucherResponses.Voucher updated = voucherService.update(userId, ledgerId, voucher.id(),
                new VoucherRequests.Update(voucher.version(), periodId, voucher.voucherDate(), voucher.voucherType(),
                        voucher.voucherNumber(), "After", List.of(
                        line(accountId(ledgerId, "1001"), "DEBIT", "120"),
                        line(accountId(ledgerId, "3001"), "CREDIT", "120"))));
        assertThat(updated.id()).isEqualTo(voucher.id());
        assertThat(updated.status()).isEqualTo("POSTED");
        assertThat(updated.version()).isEqualTo(voucher.version() + 1);
        assertThat(updated.summary()).isEqualTo("After");
        assertThat(updated.lines()).extracting(VoucherResponses.Line::originalAmount)
                .containsOnly(new BigDecimal("120.0000"));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from balance_projection_event where ledger_id = ? and aggregate_id = ? and event_type = 'UPDATE'",
                Long.class, ledgerId, voucher.id())).isEqualTo(1L);
    }

    @Test
    void rejectsAnUnbalancedUpdateAndRollsBackThePostedVoucher() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(
                new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("unbalanced-update", "SME", "v1", "CNY",
                        LocalDate.of(2026, 1, 1), false)).id();
        UUID periodId = periodId(ledgerId, "2026-01");
        UUID debitAccountId = accountId(ledgerId, "1001");
        UUID creditAccountId = accountId(ledgerId, "3001");
        VoucherResponses.Voucher voucher = voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 15), "记", "1", "Before",
                List.of(line(debitAccountId, "DEBIT", "100"),
                        line(creditAccountId, "CREDIT", "100"))));

        assertThatThrownBy(() -> voucherService.update(userId, ledgerId, voucher.id(),
                new VoucherRequests.Update(voucher.version(), periodId, voucher.voucherDate(),
                        voucher.voucherType(), voucher.voucherNumber(), "Unbalanced", List.of(
                        line(debitAccountId, "DEBIT", "120"),
                        line(creditAccountId, "CREDIT", "100")))))
                .isInstanceOf(ApiProblemException.class)
                .extracting(exception -> ((ApiProblemException) exception).code())
                .isEqualTo("VOUCHER_NOT_BALANCED");

        VoucherResponses.Voucher unchanged = voucherService.find(userId, ledgerId, voucher.id());
        assertThat(unchanged.version()).isEqualTo(voucher.version());
        assertThat(unchanged.summary()).isEqualTo("Before");
        assertThat(unchanged.lines()).extracting(VoucherResponses.Line::originalAmount)
                .containsOnly(new BigDecimal("100.0000"));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from balance_projection_event where ledger_id = ? and aggregate_id = ? and event_type = 'UPDATE'",
                Long.class, ledgerId, voucher.id())).isZero();
    }

    @Test
    void allowsChangingTheDateButNotThePeriodOfASavedVoucher() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(
                new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("immutable-voucher-period", "SME", "v1", "CNY",
                        LocalDate.of(2026, 1, 1), false)).id();
        UUID januaryId = periodId(ledgerId, "2026-01");
        UUID februaryId = periodId(ledgerId, "2026-02");
        List<VoucherRequests.Line> lines = List.of(
                line(accountId(ledgerId, "1001"), "DEBIT", "100"),
                line(accountId(ledgerId, "3001"), "CREDIT", "100"));
        VoucherResponses.Voucher voucher = voucherService.create(userId, ledgerId,
                new VoucherRequests.Create(januaryId, LocalDate.of(2026, 1, 15),
                        "记", "1", "January", lines));

        VoucherResponses.Voucher dated = voucherService.update(userId, ledgerId, voucher.id(),
                new VoucherRequests.Update(voucher.version(), januaryId, LocalDate.of(2026, 1, 20),
                        voucher.voucherType(), voucher.voucherNumber(), voucher.summary(), lines));
        assertThat(dated.voucherDate()).isEqualTo(LocalDate.of(2026, 1, 20));

        assertThatThrownBy(() -> voucherService.update(userId, ledgerId, voucher.id(),
                new VoucherRequests.Update(dated.version(), februaryId, LocalDate.of(2026, 2, 1),
                        voucher.voucherType(), voucher.voucherNumber(), voucher.summary(), lines)))
                .isInstanceOf(ApiProblemException.class)
                .extracting(exception -> ((ApiProblemException) exception).code())
                .isEqualTo("VOUCHER_PERIOD_IMMUTABLE");

        VoucherResponses.Voucher unchanged = voucherService.find(userId, ledgerId, voucher.id());
        assertThat(unchanged.periodId()).isEqualTo(januaryId);
        assertThat(unchanged.voucherDate()).isEqualTo(LocalDate.of(2026, 1, 20));
        assertThat(unchanged.version()).isEqualTo(dated.version());
    }

    @Test
    void hardDeletesAGeneratedPostedVoucherAndRemovesItsProjectedBalance() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(
                new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("delete", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        VoucherResponses.Voucher voucher = voucherService.createGenerated(userId, ledgerId,
                new VoucherRequests.Create(periodId(ledgerId, "2026-01"), LocalDate.of(2026, 1, 15),
                        "记", "1", "Delete", List.of(line(accountId(ledgerId, "1001"), "DEBIT", "1"),
                        line(accountId(ledgerId, "3001"), "CREDIT", "1"))),
                "generated-delete", "FIXED_ASSET", UUID.randomUUID());
        UUID runId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into fixed_asset_depreciation_run (id, ledger_id, period_id, run_type, status,
                    voucher_id, input_fingerprint, total_amount, created_by)
                values (?, ?, ?, 'MONTH_END', 'POSTED', ?, 'test', 1, ?)
                """, runId, ledgerId, voucher.periodId(), voucher.id(), userId);

        voucherService.delete(userId, ledgerId, voucher.id());

        assertThatThrownBy(() -> voucherService.find(userId, ledgerId, voucher.id()))
                .isInstanceOf(ApiProblemException.class)
                .extracting(exception -> ((ApiProblemException) exception).code())
                .isEqualTo("VOUCHER_NOT_FOUND");
        assertThat(jdbcTemplate.queryForObject("select count(*) from voucher where ledger_id = ? and id = ?",
                Long.class, ledgerId, voucher.id())).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from fixed_asset_depreciation_run where id = ?",
                Long.class, runId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from audit_revision
                where ledger_id = ? and aggregate_type = 'VOUCHER' and aggregate_id = ?
                """, Long.class, ledgerId, voucher.id())).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                select line.period_debit_delta
                from balance_projection_event event
                join balance_projection_event_line line on line.event_id = event.id
                where event.ledger_id = ? and event.aggregate_id = ? and event.event_type = 'UPDATE'
                  and line.account_id = ?
                """, BigDecimal.class, ledgerId, voucher.id(), accountId(ledgerId, "1001")))
                .isEqualByComparingTo("-1.00");
    }

    @Test
    void savesNegativeVoucherAmountsOnTheirSelectedSides() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(
                new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("negative-voucher", "SME", "v1", "CNY",
                        LocalDate.of(2026, 1, 1), false)).id();
        UUID periodId = periodId(ledgerId, "2026-01");

        VoucherResponses.Voucher voucher = voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 15), "记", "1", "Negative",
                List.of(line(accountId(ledgerId, "1001"), "DEBIT", "-100"),
                        line(accountId(ledgerId, "3001"), "CREDIT", "-100"))));

        assertThat(voucher.lines()).extracting(VoucherResponses.Line::side)
                .containsExactly("DEBIT", "CREDIT");
        assertThat(voucher.lines()).extracting(VoucherResponses.Line::originalAmount)
                .containsExactly(new BigDecimal("-100.0000"), new BigDecimal("-100.0000"));
    }

    @Test
    void closedPeriodIsTheOnlyVoucherMutationLock() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(
                new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("closed-period", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        UUID periodId = periodId(ledgerId, "2026-01");
        VoucherResponses.Voucher voucher = voucherService.createGenerated(userId, ledgerId,
                new VoucherRequests.Create(periodId, LocalDate.of(2026, 1, 15), "记", "1", "Locked",
                        List.of(line(accountId(ledgerId, "1001"), "DEBIT", "1"),
                                line(accountId(ledgerId, "3001"), "CREDIT", "1"))),
                "closed-period-voucher", "FIXED_ASSET", UUID.randomUUID());
        jdbcTemplate.update("update accounting_period set status = 'CLOSED' where ledger_id = ? and id = ?",
                ledgerId, periodId);

        assertThatThrownBy(() -> voucherService.update(userId, ledgerId, voucher.id(),
                new VoucherRequests.Update(voucher.version(), periodId, voucher.voucherDate(), voucher.voucherType(),
                        voucher.voucherNumber(), "Changed", voucher.lines().stream()
                        .map(line -> new VoucherRequests.Line(line.accountId(), line.side(), line.currency(),
                                line.originalAmount(), line.exchangeRate(), line.summary()))
                        .toList())))
                .isInstanceOf(ApiProblemException.class)
                .extracting(exception -> ((ApiProblemException) exception).code())
                .isEqualTo("ACCOUNTING_PERIOD_CLOSED");
        assertThatThrownBy(() -> voucherService.delete(userId, ledgerId, voucher.id()))
                .isInstanceOf(ApiProblemException.class)
                .extracting(exception -> ((ApiProblemException) exception).code())
                .isEqualTo("ACCOUNTING_PERIOD_CLOSED");
        assertThat(jdbcTemplate.queryForObject("select count(*) from voucher where ledger_id = ? and id = ?",
                Long.class, ledgerId, voucher.id())).isEqualTo(1L);
    }

    @Test
    void reusesAnIdempotentDraftAndRejectsDifferentPayloads() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(
                new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("idempotent", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        VoucherRequests.Create request = new VoucherRequests.Create(periodId(ledgerId, "2026-01"),
                LocalDate.of(2026, 1, 15), "GENERAL", "1", "Same",
                List.of(line(accountId(ledgerId, "1001"), "DEBIT", "1"),
                        line(accountId(ledgerId, "3001"), "CREDIT", "1")));

        VoucherResponses.Voucher first = voucherService.create(userId, ledgerId, request, "request-1");
        VoucherResponses.Voucher retry = voucherService.create(userId, ledgerId, request, "request-1");

        assertThat(first.status()).isEqualTo("POSTED");
        assertThat(retry.id()).isEqualTo(first.id());
        VoucherRequests.Create changed = new VoucherRequests.Create(request.periodId(), request.voucherDate(),
                request.voucherType(), request.voucherNumber(), "Different", request.lines());
        assertThatThrownBy(() -> voucherService.create(userId, ledgerId, changed, "request-1"))
                .isInstanceOf(ApiProblemException.class)
                .extracting(exception -> ((ApiProblemException) exception).code())
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void updatesWithExpectedVersionAndRestoresARevision() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(
                new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("versioned", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        UUID periodId = periodId(ledgerId, "2026-01");
        List<VoucherRequests.Line> lines = List.of(line(accountId(ledgerId, "1001"), "DEBIT", "1"),
                line(accountId(ledgerId, "3001"), "CREDIT", "1"));
        VoucherResponses.Voucher voucher = voucherService.create(userId, ledgerId,
                new VoucherRequests.Create(periodId, LocalDate.of(2026, 1, 15), "GENERAL", "1", "Before", lines));

        VoucherResponses.Voucher after = voucherService.update(userId, ledgerId, voucher.id(),
                new VoucherRequests.Update(voucher.version(), periodId, voucher.voucherDate(),
                        voucher.voucherType(), voucher.voucherNumber(), "After", lines));
        assertThat(after.status()).isEqualTo("POSTED");
        int afterRevision = voucherService.listRevisions(userId, ledgerId, voucher.id()).stream()
                .filter(revision -> revision.action().equals("UPDATE"))
                .mapToInt(VoucherResponses.Revision::revision).max().orElseThrow();

        assertThatThrownBy(() -> voucherService.update(userId, ledgerId, voucher.id(),
                new VoucherRequests.Update(voucher.version(),
                periodId, voucher.voucherDate(), voucher.voucherType(), voucher.voucherNumber(), "Stale", lines)))
                .isInstanceOf(ApiProblemException.class)
                .extracting(exception -> ((ApiProblemException) exception).code())
                .isEqualTo("RESOURCE_VERSION_CONFLICT");
        List<VoucherRequests.Line> latestLines = List.of(line(accountId(ledgerId, "1001"), "DEBIT", "2"),
                line(accountId(ledgerId, "3001"), "CREDIT", "2"));
        assertThat(voucherService.update(userId, ledgerId, voucher.id(),
                new VoucherRequests.Update(after.version(), periodId, voucher.voucherDate(),
                        voucher.voucherType(), voucher.voucherNumber(), "Latest", latestLines)).status())
                .isEqualTo("POSTED");

        VoucherResponses.Voucher restored = voucherService.restoreRevision(
                userId, ledgerId, voucher.id(), afterRevision);
        assertThat(restored.status()).isEqualTo("POSTED");
        assertThat(restored.summary()).isEqualTo("After");
        assertThat(restored.lines()).extracting(VoucherResponses.Line::originalAmount)
                .containsOnly(new BigDecimal("1.0000"));
    }

    private VoucherRequests.Line line(UUID accountId, String side, String amount) {
        return new VoucherRequests.Line(accountId, side, "CNY", new BigDecimal(amount), BigDecimal.ONE, "line");
    }

    private UUID accountId(UUID ledgerId, String code) {
        return jdbcTemplate.queryForObject("select id from ledger_account where ledger_id = ? and code = ?",
                UUID.class, ledgerId, code);
    }

    private UUID periodId(UUID ledgerId, String code) {
        return jdbcTemplate.queryForObject("select id from accounting_period where ledger_id = ? and period_code = ?",
                UUID.class, ledgerId, code);
    }
}
