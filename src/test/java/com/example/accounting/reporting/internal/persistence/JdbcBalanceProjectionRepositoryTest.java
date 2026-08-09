package com.example.accounting.reporting.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class JdbcBalanceProjectionRepositoryTest {

    @Test
    void summarizesCaughtUpPeriodsWithoutComparingUnrelatedGlobalEventIds() {
        OffsetDateTime firstProjected = OffsetDateTime.parse("2026-08-08T08:00:01Z");
        OffsetDateTime secondProjected = OffsetDateTime.parse("2026-08-08T08:00:02Z");

        var status = JdbcBalanceProjectionRepository.summarizeStatus(List.of(
                new JdbcBalanceProjectionRepository.ProjectionRow(
                        "READY", 100, 100, firstProjected.minusSeconds(1), firstProjected),
                new JdbcBalanceProjectionRepository.ProjectionRow(
                        "READY", 200, 200, secondProjected.minusSeconds(1), secondProjected)));

        assertThat(status.status()).isEqualTo("READY");
        assertThat(status.lastEnqueuedEventId()).isEqualTo(status.lastAppliedEventId());
        assertThat(status.projectedAt()).isEqualTo(firstProjected);
    }

    @Test
    void reportsPendingWhenAnyPeriodHasUnappliedEvents() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-08T08:00:00Z");

        var status = JdbcBalanceProjectionRepository.summarizeStatus(List.of(
                new JdbcBalanceProjectionRepository.ProjectionRow("READY", 100, 100, now, now),
                new JdbcBalanceProjectionRepository.ProjectionRow("READY", 200, 199, now, now)));

        assertThat(status.status()).isEqualTo("PENDING");
        assertThat(status.lastEnqueuedEventId()).isNotEqualTo(status.lastAppliedEventId());
    }
}
