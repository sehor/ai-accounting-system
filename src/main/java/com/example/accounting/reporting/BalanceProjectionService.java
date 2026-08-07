package com.example.accounting.reporting;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Public reporting boundary used by posting flows to publish balance projection events. */
public interface BalanceProjectionService {

    void publishVoucher(VoucherEvent event);

    void publishOpeningBalances(OpeningBalanceEvent event);

    void requireOpenPeriod(UUID ledgerId, UUID periodId);

    /** Fails immediately unless the projection is caught up and reconciles to the facts. */
    void requireReadyForClose(UUID ledgerId, UUID periodId);

    /** Clears the finalized marker when a closed period is reopened. */
    void markReopened(UUID ledgerId, UUID periodId);

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
        POST, UNPOST
    }

    record ProjectionStatus(String status, long lastEnqueuedEventId, long lastAppliedEventId,
                            OffsetDateTime lastEnqueuedAt, OffsetDateTime projectedAt) {

        public boolean fresh(long maxLagSeconds, OffsetDateTime now) {
            if (!"READY".equals(status()) || lastEnqueuedEventId() != lastAppliedEventId()) {
                return false;
            }
            return lastEnqueuedAt() == null
                    || !lastEnqueuedAt().plusSeconds(maxLagSeconds).isBefore(now);
        }

        public boolean fresh(Duration maxLag, OffsetDateTime now) {
            if (!"READY".equals(status()) || lastEnqueuedEventId() != lastAppliedEventId()) {
                return false;
            }
            return lastEnqueuedAt() == null || !lastEnqueuedAt().plus(maxLag).isBefore(now);
        }
    }
}
