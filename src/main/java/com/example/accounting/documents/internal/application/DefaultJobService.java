package com.example.accounting.documents.internal.application;

import com.example.accounting.documents.JobResponses;
import com.example.accounting.documents.JobService;
import com.example.accounting.documents.internal.port.JobRepository;
import com.example.accounting.ledger.LedgerAccessService;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.shared.web.ApiProblemException;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultJobService implements JobService {

    private static final Set<LedgerRole> VIEW_ROLES = Set.of(
            LedgerRole.OWNER, LedgerRole.EDITOR, LedgerRole.REVIEWER, LedgerRole.VIEWER, LedgerRole.AGENT);

    private final LedgerAccessService ledgerAccess;
    private final JobRepository jobs;

    public DefaultJobService(LedgerAccessService ledgerAccess, JobRepository jobs) {
        this.ledgerAccess = ledgerAccess;
        this.jobs = jobs;
    }

    @Override
    @Transactional
    public JobResponses.Job claimOne(String workerId) {
        return jobs.claimOne(workerId);
    }

    @Override
    @Transactional
    public JobResponses.Job complete(UUID jobId) {
        if (!jobs.complete(jobId)) {
            throw problem(409, "JOB_STATE_INVALID", "Invalid job state", "Only running jobs can complete");
        }
        return requireJob(jobId);
    }

    @Override
    @Transactional
    public JobResponses.Job fail(UUID jobId, String errorCode, String message, boolean retryable) {
        JobResponses.Job job = requireJob(jobId);
        String status = retryable && job.attempts() < 3 ? "RETRYING" : "FAILED";
        int minutes = job.attempts() == 1 ? 1 : job.attempts() == 2 ? 5 : 30;
        jobs.fail(jobId, status, minutes, errorCode, message);
        return requireJob(jobId);
    }

    @Override
    @Transactional(readOnly = true)
    public JobResponses.Job find(UUID actorId, UUID ledgerId, UUID jobId) {
        if (!VIEW_ROLES.contains(ledgerAccess.requireMembership(actorId, ledgerId))) {
            throw problem(403, "INSUFFICIENT_LEDGER_ROLE", "Insufficient ledger role",
                    "The current user cannot view jobs");
        }
        JobResponses.Job job = requireJob(jobId);
        if (!ledgerId.equals(job.ledgerId())) {
            throw problem(404, "JOB_NOT_FOUND", "Job not found", "The job is not available to this ledger");
        }
        return job;
    }

    private JobResponses.Job requireJob(UUID jobId) {
        return jobs.find(jobId).orElseThrow(() ->
                problem(404, "JOB_NOT_FOUND", "Job not found", "The job does not exist"));
    }

    private ApiProblemException problem(int status, String code, String title, String detail) {
        return new ApiProblemException(status, code, title, detail, false);
    }
}
