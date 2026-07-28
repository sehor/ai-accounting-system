package com.example.accounting.documents;

import java.util.UUID;

public interface JobService {

    JobResponses.Job claimOne(String workerId);

    JobResponses.Job complete(UUID jobId);

    JobResponses.Job fail(UUID jobId, String errorCode, String message, boolean retryable);

    JobResponses.Job find(UUID actorId, UUID ledgerId, UUID jobId);
}
