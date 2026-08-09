package com.example.accounting.reporting.internal.port;

import com.example.accounting.reporting.BalanceRebuildResponses;
import java.util.Optional;
import java.util.UUID;

public interface BalanceRebuildRepository {

    boolean hasActiveJob(UUID ledgerId, String periodFrom, String periodTo);

    void createJob(UUID jobId, UUID ledgerId, String periodFrom, String periodTo, String reason, UUID requestedBy);

    Optional<BalanceRebuildResponses.Job> findJob(UUID ledgerId, UUID jobId);

    boolean processNextJob();

    void failRunningJob();
}
