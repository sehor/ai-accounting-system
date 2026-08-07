package com.example.accounting.reporting;

import java.util.UUID;

public interface BalanceRebuildService {

    BalanceRebuildResponses.Job request(UUID actorId, UUID ledgerId, BalanceRebuildRequests.Create request);

    BalanceRebuildResponses.Job find(UUID actorId, UUID ledgerId, UUID jobId);
}
