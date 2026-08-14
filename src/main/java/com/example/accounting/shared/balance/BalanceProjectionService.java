package com.example.accounting.shared.balance;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Shared boundary used by accounting write flows and reporting projection adapters. */
public interface BalanceProjectionService {

    void publishVoucher(VoucherEvent event);

    void publishOpeningBalances(OpeningBalanceEvent event);

    void requireOpenPeriod(UUID ledgerId, UUID periodId);

    /** Fails immediately unless the projection is caught up and reconciles to the facts. */
    void requireReadyForClose(UUID ledgerId, UUID periodId);

    /** Clears the finalized marker when a closed period is reopened. */
    void markReopened(UUID ledgerId, UUID periodId);

    /** Marks both account and auxiliary snapshots finalized after the period status change succeeds. */
    void markFinalized(UUID ledgerId, UUID periodId);

    ProjectionStatus status(UUID ledgerId, String periodCode);

    record VoucherEvent(UUID ledgerId, UUID periodId, UUID voucherId, long version,
                        EventType type, List<Entry> entries) {
    }

    record OpeningBalanceEvent(UUID ledgerId, UUID periodId, UUID aggregateId, long version,
                               List<Entry> entries) {
    }

    record Entry(UUID accountId, BigDecimal openingDebit, BigDecimal openingCredit,
                 BigDecimal periodDebit, BigDecimal periodCredit) {
    }

    enum EventType {
        POST, UPDATE
    }

    record ProjectionStatus(String status, long lastEnqueuedEventId, long lastAppliedEventId,
                            OffsetDateTime lastEnqueuedAt, OffsetDateTime projectedAt) {

        public boolean fresh() {
            return "READY".equals(status()) && lastEnqueuedEventId() == lastAppliedEventId();
        }
    }
}
