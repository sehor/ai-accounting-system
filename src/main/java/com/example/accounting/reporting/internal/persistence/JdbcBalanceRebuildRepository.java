package com.example.accounting.reporting.internal.persistence;

import com.example.accounting.reporting.BalanceRebuildResponses;
import com.example.accounting.reporting.internal.port.BalanceRebuildRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcBalanceRebuildRepository implements BalanceRebuildRepository {

    private final JdbcTemplate jdbc;
    private final BalanceSnapshotRebuilder snapshots;
    private final ThreadLocal<UUID> attemptedJob = new ThreadLocal<>();

    public JdbcBalanceRebuildRepository(JdbcTemplate jdbc, BalanceSnapshotRebuilder snapshots) {
        this.jdbc = jdbc;
        this.snapshots = snapshots;
    }

    @Override
    public boolean hasActiveJob(UUID ledgerId, String periodFrom, String periodTo) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (
                    select 1 from balance_rebuild_job j
                    where j.ledger_id = ? and j.status in ('QUEUED', 'RUNNING')
                      and (j.period_from is null or ?::varchar is null
                           or j.period_from <= ? and j.period_to >= ?)
                )
                """, Boolean.class, ledgerId, periodFrom, periodTo, periodFrom));
    }

    @Override
    public void createJob(UUID jobId, UUID ledgerId, String periodFrom, String periodTo,
                          String reason, UUID requestedBy) {
        jdbc.update("""
                insert into balance_rebuild_job (id, ledger_id, period_from, period_to, reason, requested_by)
                values (?, ?, ?, ?, ?, ?)
                """, jobId, ledgerId, periodFrom, periodTo, reason, requestedBy);
        jdbc.update("""
                insert into balance_rebuild_audit (ledger_id, job_id, actor_id, action, reason)
                values (?, ?, ?, 'REQUEST', ?)
                """, ledgerId, jobId, requestedBy, reason);
    }

    @Override
    public Optional<BalanceRebuildResponses.Job> findJob(UUID ledgerId, UUID jobId) {
        return Optional.ofNullable(jdbc.query("""
                select id, ledger_id, period_from, period_to, status, reason, requested_by,
                    processed_periods, total_periods, difference_count, created_at, started_at,
                    completed_at, last_error_code, last_error_message
                from balance_rebuild_job where ledger_id = ? and id = ?
                """, rs -> rs.next() ? new BalanceRebuildResponses.Job(
                rs.getObject("id", UUID.class), rs.getObject("ledger_id", UUID.class),
                rs.getString("period_from"), rs.getString("period_to"), rs.getString("status"),
                rs.getString("reason"), rs.getObject("requested_by", UUID.class),
                rs.getInt("processed_periods"), rs.getInt("total_periods"), rs.getInt("difference_count"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class), rs.getString("last_error_code"),
                rs.getString("last_error_message")) : null, ledgerId, jobId));
    }

    @Override
    @Transactional
    public boolean processNextJob() {
        JobState job = jdbc.query("""
                select id, ledger_id, period_from, period_to, processed_periods, total_periods
                from balance_rebuild_job where status in ('QUEUED', 'RUNNING')
                order by created_at for update skip locked limit 1
                """, rs -> rs.next() ? new JobState(rs.getObject("id", UUID.class),
                rs.getObject("ledger_id", UUID.class), rs.getString("period_from"), rs.getString("period_to"),
                rs.getInt("processed_periods"), rs.getInt("total_periods")) : null);
        if (job == null) {
            return false;
        }
        attemptedJob.set(job.id());
        jdbc.queryForObject("select id from ledger where id = ? for update", UUID.class, job.ledgerId());
        PeriodState source = jdbc.query("""
                select id, period_code from accounting_period
                where ledger_id = ? and (?::varchar is null or period_code >= ?)
                order by period_code limit 1
                """, rs -> rs.next() ? new PeriodState(
                rs.getObject("id", UUID.class), rs.getString("period_code")) : null,
                job.ledgerId(), job.periodFrom(), job.periodFrom());
        if (source == null) {
            jdbc.update("""
                    update balance_rebuild_job set status = 'SUCCEEDED', total_periods = 0,
                        processed_periods = 0, completed_at = now() where id = ?
                    """, job.id());
            attemptedJob.remove();
            return true;
        }
        int total = jdbc.queryForObject("""
                select count(*) from accounting_period where ledger_id = ? and period_code >= ?
                """, Integer.class, job.ledgerId(), source.periodCode());
        jdbc.update("""
                update balance_rebuild_job set status = 'RUNNING', started_at = coalesce(started_at, now()),
                    period_from = ?, period_to = (
                        select max(period_code) from accounting_period where ledger_id = ?),
                    total_periods = ?
                where id = ?
                """, source.periodCode(), job.ledgerId(), total, job.id());
        jdbc.update("""
                insert into balance_projection_state (ledger_id, period_id, status, updated_at)
                select p.ledger_id, p.id, 'REBUILDING', now()
                from accounting_period p where p.ledger_id = ? and p.period_code >= ?
                on conflict (ledger_id, period_id) do update set
                    status = 'REBUILDING', updated_at = now()
                """, job.ledgerId(), source.periodCode());
        snapshots.rebuildFrom(job.ledgerId(), source.id());
        Long watermark = jdbc.queryForObject("""
                select coalesce(max(id), 0) from balance_projection_event
                where ledger_id = ?
                """, Long.class, job.ledgerId());
        jdbc.update("""
                update balance_projection_state set last_enqueued_event_id = ?, last_applied_event_id = ?,
                    projected_at = now(), status = 'READY', attempts = 0, last_error_code = null,
                    last_error_message = null, next_attempt_at = now(), updated_at = now()
                where ledger_id = ? and period_id in (
                    select id from accounting_period where ledger_id = ? and period_code >= ?)
                """, watermark, watermark, job.ledgerId(), job.ledgerId(), source.periodCode());
        jdbc.update("""
                update balance_rebuild_job set processed_periods = ?, status = 'SUCCEEDED',
                    completed_at = now() where id = ?
                """, total, job.id());
        attemptedJob.remove();
        return true;
    }

    @Override
    @Transactional
    public void failRunningJob() {
        UUID jobId = attemptedJob.get();
        attemptedJob.remove();
        if (jobId == null) {
            return;
        }
        jdbc.update("""
                update balance_rebuild_job set status = 'FAILED', completed_at = now(),
                    last_error_code = 'BALANCE_REBUILD_FAILED',
                    last_error_message = 'Balance rebuild worker failed'
                where id = ? and status in ('RUNNING', 'QUEUED')
                """, jobId);
    }

    private record JobState(UUID id, UUID ledgerId, String periodFrom, String periodTo,
                            int processedPeriods, int totalPeriods) { }

    private record PeriodState(UUID id, String periodCode) { }
}
