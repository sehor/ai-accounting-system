package com.example.accounting.documents.internal.port;

import com.example.accounting.documents.JobResponses;
import java.util.Optional;
import java.util.UUID;

public interface JobRepository {

    JobResponses.Job claimOne(String workerId);

    boolean complete(UUID jobId);

    void fail(UUID jobId, String status, int delayMinutes, String errorCode, String message);

    Optional<JobResponses.Job> find(UUID jobId);
}
