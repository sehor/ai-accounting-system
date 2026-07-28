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
    void createsValidatesAndPostsABalancedVoucher() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(
                new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("voucher", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        UUID periodId = periodId(ledgerId, "2026-01");
        UUID cashId = accountId(ledgerId, "1001");
        UUID capitalId = accountId(ledgerId, "3001");

        VoucherResponses.Voucher draft = voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 15), "记", "1", "Opening",
                List.of(line(cashId, "DEBIT", "100"), line(capitalId, "CREDIT", "100"))));
        assertThat(draft.status()).isEqualTo("DRAFT");

        assertThat(voucherService.validate(userId, ledgerId, draft.id()).status()).isEqualTo("VALIDATED");
        assertThat(voucherService.post(userId, ledgerId, draft.id()).status()).isEqualTo("POSTED");
    }

    @Test
    void rejectsAnUnbalancedVoucherDuringValidation() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(
                new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("unbalanced", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        UUID periodId = periodId(ledgerId, "2026-01");
        UUID cashId = accountId(ledgerId, "1001");
        VoucherResponses.Voucher draft = voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 15), "记", "1", "Invalid",
                List.of(line(cashId, "DEBIT", "100"))));

        assertThatThrownBy(() -> voucherService.validate(userId, ledgerId, draft.id()))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("equal debit");
    }

    @Test
    void requiresApprovalBeforePostingWhenTheLedgerEnablesIt() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(
                new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("approval", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), true)).id();
        UUID periodId = periodId(ledgerId, "2026-01");
        VoucherResponses.Voucher draft = voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 15), "记", "1", "Approval",
                List.of(line(accountId(ledgerId, "1001"), "DEBIT", "100"),
                        line(accountId(ledgerId, "3001"), "CREDIT", "100"))));
        UUID voucherId = draft.id();
        voucherService.validate(userId, ledgerId, voucherId);

        assertThatThrownBy(() -> voucherService.post(userId, ledgerId, voucherId))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("APPROVED");
        assertThat(voucherService.submit(userId, ledgerId, voucherId).status()).isEqualTo("SUBMITTED");
        assertThat(voucherService.approve(userId, ledgerId, voucherId, "ok").status()).isEqualTo("APPROVED");
        assertThat(voucherService.post(userId, ledgerId, voucherId).status()).isEqualTo("POSTED");
    }

    @Test
    void canUnpostAndReverseAPostedVoucher() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(
                new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("reverse", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        UUID periodId = periodId(ledgerId, "2026-01");
        VoucherResponses.Voucher voucher = voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 15), "记", "1", "Reverse",
                List.of(line(accountId(ledgerId, "1001"), "DEBIT", "100"),
                        line(accountId(ledgerId, "3001"), "CREDIT", "100"))));
        voucherService.validate(userId, ledgerId, voucher.id());
        voucherService.post(userId, ledgerId, voucher.id());
        assertThat(voucherService.unpost(userId, ledgerId, voucher.id(), "correction").status()).isEqualTo("DRAFT");
        voucherService.validate(userId, ledgerId, voucher.id());
        voucherService.post(userId, ledgerId, voucher.id());

        VoucherResponses.Voucher reversal = voucherService.reverse(userId, ledgerId, voucher.id());
        assertThat(reversal.status()).isEqualTo("DRAFT");
        assertThat(reversal.lines().get(0).side()).isNotEqualTo(voucher.lines().get(0).side());
        assertThat(voucherService.find(userId, ledgerId, voucher.id()).status()).isEqualTo("REVERSED");
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

        voucherService.delete(userId, ledgerId, voucher.id());
        assertThatThrownBy(() -> voucherService.find(userId, ledgerId, voucher.id()))
                .isInstanceOf(ApiProblemException.class);
        assertThat(voucherService.restoreDeleted(userId, ledgerId, voucher.id()).status()).isEqualTo("DRAFT");
        assertThat(voucherService.listRevisions(userId, ledgerId, voucher.id())).isNotEmpty();
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

        assertThat(voucherService.update(userId, ledgerId, voucher.id(), new VoucherRequests.Update(0L, periodId,
                voucher.voucherDate(), voucher.voucherType(), voucher.voucherNumber(), "After", lines)).summary())
                .isEqualTo("After");
        assertThatThrownBy(() -> voucherService.update(userId, ledgerId, voucher.id(), new VoucherRequests.Update(0L,
                periodId, voucher.voucherDate(), voucher.voucherType(), voucher.voucherNumber(), "Stale", lines)))
                .isInstanceOf(ApiProblemException.class)
                .extracting(exception -> ((ApiProblemException) exception).code())
                .isEqualTo("RESOURCE_VERSION_CONFLICT");
        assertThat(voucherService.restoreRevision(userId, ledgerId, voucher.id(), 2).status()).isEqualTo("DRAFT");
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
