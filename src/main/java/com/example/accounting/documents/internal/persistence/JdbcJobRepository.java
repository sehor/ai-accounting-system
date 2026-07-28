package com.example.accounting.documents.internal.persistence;

import com.example.accounting.documents.JobResponses;
import com.example.accounting.documents.internal.port.JobRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcJobRepository implements JobRepository {

    private final JdbcTemplate jdbc;

    public JdbcJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public JobResponses.Job claimOne(String workerId) {
        List<JobResponses.Job> claimable = jdbc.query("""
                select id, ledger_id, job_type, aggregate_id, status, attempts, next_run_at, locked_by
                from background_job
                where status in ('QUEUED', 'RETRYING') and next_run_at <= now()
                order by next_run_at, created_at
                for update skip locked limit 1
                """, (rs, rowNum) -> map(rs));
        if (claimable.isEmpty()) {
            return null;
        }
        JobResponses.Job job = claimable.get(0);
        jdbc.update("update background_job set status = 'RUNNING', attempts = attempts + 1, "
                + "locked_at = now(), locked_by = ? where id = ?", workerId, job.id());
        return new JobResponses.Job(job.id(), job.ledgerId(), job.jobType(), job.aggregateId(), "RUNNING",
                job.attempts() + 1, job.nextRunAt(), workerId);
    }

    @Override
    public boolean complete(UUID jobId) {
        return jdbc.update("update background_job set status = 'SUCCEEDED', locked_at = null, "
                + "locked_by = null where id = ? and status = 'RUNNING'", jobId) == 1;
    }

    @Override
    public void fail(UUID jobId, String status, int delayMinutes, String errorCode, String message) {
        jdbc.update("update background_job set status = ?, next_run_at = now() + (? * interval '1 minute'), "
                + "last_error_code = ?, last_error_message = ?, locked_at = null, locked_by = null where id = ?",
                status, delayMinutes, errorCode, message, jobId);
    }

    @Override
    public Optional<JobResponses.Job> find(UUID jobId) {
        return Optional.ofNullable(jdbc.query("""
                select id, ledger_id, job_type, aggregate_id, status, attempts, next_run_at, locked_by
                from background_job where id = ?
                """, rs -> rs.next() ? map(rs) : null, jobId));
    }

    private JobResponses.Job map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new JobResponses.Job(rs.getObject("id", UUID.class), rs.getObject("ledger_id", UUID.class),
                rs.getString("job_type"), rs.getObject("aggregate_id", UUID.class), rs.getString("status"),
                rs.getInt("attempts"), rs.getObject("next_run_at", OffsetDateTime.class), rs.getString("locked_by"));
    }
}
