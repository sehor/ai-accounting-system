package com.example.accounting.documents;

import com.example.accounting.shared.web.ApiProblemException;
import com.example.accounting.ledger.LedgerRole;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobService {

    private final JdbcTemplate jdbcTemplate;

    public JobService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public JobResponses.Job claimOne(String workerId) {
        List<JobResponses.Job> jobs = jdbcTemplate.query("""
                select id, ledger_id, job_type, aggregate_id, status, attempts, next_run_at, locked_by
                from background_job
                where status in ('QUEUED', 'RETRYING') and next_run_at <= now()
                order by next_run_at, created_at
                for update skip locked limit 1
                """, (rs, rowNum) -> new JobResponses.Job(rs.getObject("id", UUID.class),
                rs.getObject("ledger_id", UUID.class), rs.getString("job_type"),
                rs.getObject("aggregate_id", UUID.class), rs.getString("status"),
                rs.getInt("attempts"), rs.getObject("next_run_at", OffsetDateTime.class), rs.getString("locked_by")));
        if (jobs.isEmpty()) {
            return null;
        }
        JobResponses.Job job = jobs.get(0);
        jdbcTemplate.update("update background_job set status = 'RUNNING', attempts = attempts + 1, "
                + "locked_at = now(), locked_by = ? where id = ?", workerId, job.id());
        return new JobResponses.Job(job.id(), job.ledgerId(), job.jobType(), job.aggregateId(), "RUNNING",
                job.attempts() + 1, job.nextRunAt(), workerId);
    }

    @Transactional
    public JobResponses.Job complete(UUID jobId) {
        int updated = jdbcTemplate.update("update background_job set status = 'SUCCEEDED', locked_at = null, "
                + "locked_by = null where id = ? and status = 'RUNNING'", jobId);
        if (updated == 0) {
            throw problem(409, "JOB_STATE_INVALID", "Invalid job state", "Only running jobs can complete");
        }
        return find(jobId);
    }

    @Transactional
    public JobResponses.Job fail(UUID jobId, String errorCode, String message, boolean retryable) {
        JobResponses.Job job = find(jobId);
        int nextAttempt = job.attempts();
        String status = retryable && nextAttempt < 3 ? "RETRYING" : "FAILED";
        int minutes = nextAttempt == 1 ? 1 : nextAttempt == 2 ? 5 : 30;
        jdbcTemplate.update("update background_job set status = ?, next_run_at = now() + (? * interval '1 minute'), "
                + "last_error_code = ?, last_error_message = ?, locked_at = null, locked_by = null where id = ?",
                status, minutes, errorCode, message, jobId);
        return find(jobId);
    }

    @Transactional(readOnly = true)
    public JobResponses.Job find(UUID actorId, UUID ledgerId, UUID jobId) {
        requireRole(actorId, ledgerId);
        JobResponses.Job job = find(jobId);
        if (!ledgerId.equals(job.ledgerId())) {
            throw problem(404, "JOB_NOT_FOUND", "Job not found", "The job is not available to this ledger");
        }
        return job;
    }

    private JobResponses.Job find(UUID jobId) {
        JobResponses.Job job = jdbcTemplate.query("""
                select id, ledger_id, job_type, aggregate_id, status, attempts, next_run_at, locked_by
                from background_job where id = ?
                """, rs -> rs.next() ? new JobResponses.Job(rs.getObject("id", UUID.class),
                rs.getObject("ledger_id", UUID.class), rs.getString("job_type"),
                rs.getObject("aggregate_id", UUID.class), rs.getString("status"),
                rs.getInt("attempts"), rs.getObject("next_run_at", OffsetDateTime.class), rs.getString("locked_by")) : null,
                jobId);
        if (job == null) {
            throw problem(404, "JOB_NOT_FOUND", "Job not found", "The job does not exist");
        }
        return job;
    }

    private void requireRole(UUID actorId, UUID ledgerId) {
        String role = jdbcTemplate.query("""
                select m.role from ledger_membership m join ledger l on l.id = m.ledger_id
                where m.ledger_id = ? and m.user_id = ? and m.status = 'ACTIVE'
                    and m.deleted_at is null and l.deleted_at is null
                """, rs -> rs.next() ? rs.getString(1) : null, ledgerId, actorId);
        if (role == null) {
            throw problem(404, "LEDGER_NOT_FOUND", "Ledger not found", "The ledger is not available to this user");
        }
        if (!Set.of(LedgerRole.OWNER, LedgerRole.EDITOR, LedgerRole.REVIEWER,
                LedgerRole.VIEWER, LedgerRole.AGENT).contains(LedgerRole.valueOf(role))) {
            throw problem(403, "INSUFFICIENT_LEDGER_ROLE", "Insufficient ledger role",
                    "The current user cannot view jobs");
        }
    }

    private ApiProblemException problem(int status, String code, String title, String detail) {
        return new ApiProblemException(status, code, title, detail, false);
    }
}
