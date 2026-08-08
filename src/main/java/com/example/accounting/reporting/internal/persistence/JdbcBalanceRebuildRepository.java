package com.example.accounting.reporting.internal.persistence;

import com.example.accounting.reporting.BalanceRebuildResponses;
import com.example.accounting.reporting.internal.port.BalanceRebuildRepository;
import java.time.OffsetDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcBalanceRebuildRepository implements BalanceRebuildRepository {

    private final JdbcTemplate jdbc;

    public JdbcBalanceRebuildRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
        if (job.totalPeriods() == 0) {
            int total = jdbc.queryForObject("""
                    select count(*) from accounting_period
                    where ledger_id = ? and (?::varchar is null or period_code between ? and ?)
                    """, Integer.class, job.ledgerId(), job.periodFrom(), job.periodFrom(), job.periodTo());
            jdbc.update("""
                    update balance_rebuild_job set status = 'RUNNING', started_at = coalesce(started_at, now()),
                        total_periods = ? where id = ?
                    """, total, job.id());
            job = new JobState(job.id(), job.ledgerId(), job.periodFrom(), job.periodTo(), 0, total);
        }
        if (job.processedPeriods() >= job.totalPeriods()) {
            jdbc.update("update balance_rebuild_job set status = 'SUCCEEDED', completed_at = now() where id = ?",
                    job.id());
            return true;
        }
        jdbc.update("""
                update balance_rebuild_job set status = 'RUNNING', started_at = coalesce(started_at, now())
                where id = ?
                """, job.id());
        PeriodState period = jdbc.query("""
                select id, status from accounting_period
                where ledger_id = ? and (?::varchar is null or period_code between ? and ?)
                order by period_code offset ? limit 1 for update
                """, rs -> rs.next() ? new PeriodState(rs.getObject("id", UUID.class), rs.getString("status")) : null,
                job.ledgerId(), job.periodFrom(), job.periodFrom(), job.periodTo(), job.processedPeriods());
        if (period == null) {
            jdbc.update("update balance_rebuild_job set status = 'SUCCEEDED', completed_at = now() where id = ?",
                    job.id());
            return true;
        }
        jdbc.update("""
                insert into balance_projection_state (ledger_id, period_id, status, updated_at)
                values (?, ?, 'REBUILDING', now())
                on conflict (ledger_id, period_id) do update set status = 'REBUILDING', updated_at = now()
                """, job.ledgerId(), period.id());
        jdbc.update("delete from account_period_balance where ledger_id = ? and period_id = ?",
                job.ledgerId(), period.id());
        List<BalanceAmount> amounts = jdbc.query("""
                with facts as (
                    select vl.account_id, 0::numeric opening_debit, 0::numeric opening_credit,
                        case when vl.side = 'DEBIT' then vl.base_amount else 0 end period_debit,
                        case when vl.side = 'CREDIT' then vl.base_amount else 0 end period_credit
                    from voucher_line vl join voucher v on v.ledger_id = vl.ledger_id and v.id = vl.voucher_id
                    where v.ledger_id = ? and v.period_id = ? and v.status = 'POSTED'
                    union all
                    select ob.account_id, ob.debit_base, ob.credit_base, 0::numeric, 0::numeric
                    from opening_balance ob where ob.ledger_id = ? and ob.period_id = ? and ob.confirmed
                )
                select account_id, sum(opening_debit) opening_debit, sum(opening_credit) opening_credit,
                    sum(period_debit) period_debit, sum(period_credit) period_credit
                from facts group by account_id
                """, (rs, row) -> new BalanceAmount(rs.getObject("account_id", UUID.class),
                rs.getBigDecimal("opening_debit"), rs.getBigDecimal("opening_credit"),
                rs.getBigDecimal("period_debit"), rs.getBigDecimal("period_credit")),
                job.ledgerId(), period.id(), job.ledgerId(), period.id());
        for (BalanceAmount amount : amounts) {
            jdbc.update("""
                    insert into account_period_balance (ledger_id, period_id, account_id,
                        opening_debit_base, opening_credit_base, period_debit_base, period_credit_base,
                        finalized_at, version, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, case when ? = 'CLOSED' then now() else null end, 1, now())
                    """, job.ledgerId(), period.id(), amount.accountId(), amount.openingDebit(), amount.openingCredit(),
                    amount.periodDebit(), amount.periodCredit(), period.status());
        }
        Long watermark = jdbc.queryForObject("""
                select coalesce(max(id), 0) from balance_projection_event
                where ledger_id = ? and period_id = ?
                """, Long.class, job.ledgerId(), period.id());
        jdbc.update("""
                update balance_projection_state set last_enqueued_event_id = ?, last_applied_event_id = ?,
                    projected_at = now(), status = 'READY', attempts = 0, last_error_code = null,
                    last_error_message = null, updated_at = now() where ledger_id = ? and period_id = ?
                """, watermark, watermark, job.ledgerId(), period.id());
        int processed = job.processedPeriods() + 1;
        jdbc.update("""
                update balance_rebuild_job set processed_periods = ?, status = case when ? >= total_periods
                    then 'SUCCEEDED' else 'RUNNING' end,
                    completed_at = case when ? >= total_periods then now() else null end where id = ?
                """, processed, processed, processed, job.id());
        return true;
    }

    @Override
    @Transactional
    public void failRunningJob() {
        jdbc.update("""
                update balance_rebuild_job set status = 'FAILED', completed_at = now(),
                    last_error_code = 'BALANCE_REBUILD_FAILED',
                    last_error_message = 'Balance rebuild worker failed' where status = 'RUNNING'
                """);
    }

    private record JobState(UUID id, UUID ledgerId, String periodFrom, String periodTo,
                            int processedPeriods, int totalPeriods) { }

    private record PeriodState(UUID id, String status) { }

    private record BalanceAmount(UUID accountId, BigDecimal openingDebit, BigDecimal openingCredit,
                                 BigDecimal periodDebit, BigDecimal periodCredit) { }
}
