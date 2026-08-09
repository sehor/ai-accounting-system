package com.example.accounting.ledger;

import java.util.List;
import java.util.UUID;

/** Optional domain checks run immediately before an accounting period is closed. */
public interface PeriodCloseGuard {

    List<String> blockers(UUID actorId, UUID ledgerId, UUID periodId);
}
