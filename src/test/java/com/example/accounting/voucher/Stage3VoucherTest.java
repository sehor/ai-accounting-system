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
    void updatesAPostedVoucherDirectlyInAnOpenPeriod() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(
                new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("reverse", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        UUID periodId = periodId(ledgerId, "2026-01");
        VoucherResponses.Voucher voucher = voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 15), "记", "1", "Reverse",
                List.of(line(accountId(ledgerId, "1001"), "DEBIT", "100"),
                        line(accountId(ledgerId, "3001"), "CREDIT", "100"))));
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
    void softDeletesRestoresAndListsVoucherRevisions() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(
                new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("delete", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        VoucherResponses.Voucher voucher = voucherService.create(userId, ledgerId,
                new VoucherRequests.Create(periodId(ledgerId, "2026-01"), LocalDate.of(2026, 1, 15),
                        "记", "1", "Delete", List.of(line(accountId(ledgerId, "1001"), "DEBIT", "1"),
                        line(accountId(ledgerId, "3001"), "CREDIT", "1"))));

        assertThatThrownBy(() -> voucherService.delete(userId, ledgerId, voucher.id()))
                .isInstanceOf(ApiProblemException.class);
        assertThat(voucherService.find(userId, ledgerId, voucher.id()).status()).isEqualTo("POSTED");
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
