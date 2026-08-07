package com.example.accounting.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BalanceProjectionServiceTest {

    @Test
    void considersOnlyReadyAndCaughtUpStateFresh() {
        OffsetDateTime now = OffsetDateTime.now();
        BalanceProjectionService.ProjectionStatus ready = new BalanceProjectionService.ProjectionStatus(
                "READY", 8, 8, now.minusSeconds(2), now.minusSeconds(1));
        BalanceProjectionService.ProjectionStatus pending = new BalanceProjectionService.ProjectionStatus(
                "READY", 8, 7, now.minusSeconds(1), now.minusSeconds(1));
        BalanceProjectionService.ProjectionStatus failed = new BalanceProjectionService.ProjectionStatus(
                "FAILED", 8, 8, now.minusSeconds(1), now.minusSeconds(1));

        assertThat(ready.fresh(java.time.Duration.ofSeconds(5), now)).isTrue();
        assertThat(pending.fresh(java.time.Duration.ofSeconds(5), now)).isFalse();
        assertThat(failed.fresh(java.time.Duration.ofSeconds(5), now)).isFalse();
    }

    @Test
    void eventContractsCarryLedgerAndSignedDeltas() {
        UUID ledgerId = UUID.randomUUID();
        UUID periodId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        BalanceProjectionService.VoucherEvent event = new BalanceProjectionService.VoucherEvent(
                ledgerId, periodId, UUID.randomUUID(), 2, BalanceProjectionService.EventType.UNPOST,
                java.util.List.of(new BalanceProjectionService.Entry(accountId, java.math.BigDecimal.ZERO,
                        java.math.BigDecimal.ZERO, new java.math.BigDecimal("-10.00"),
                        java.math.BigDecimal.ZERO)));

        assertThat(event.entries()).singleElement().satisfies(line -> {
            assertThat(line.periodDebit()).isEqualByComparingTo("-10.00");
            assertThat(event.type()).isEqualTo(BalanceProjectionService.EventType.UNPOST);
        });
    }
}
