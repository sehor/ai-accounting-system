package com.example.accounting.reporting.internal.persistence;

import com.example.accounting.shared.balance.BalanceProjectionService;
import com.example.accounting.reporting.ReportResponses;
import com.example.accounting.reporting.internal.port.BalanceProjectionRepository;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcBalanceProjectionRepository implements BalanceProjectionRepository {

    private final JdbcTemplate jdbc;

    public JdbcBalanceProjectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void appendAndApplyVoucherEvent(BalanceProjectionService.VoucherEvent event) {
        requireOpenPeriod(event.ledgerId(), event.periodId());
        Long eventId = append(event.ledgerId(), event.periodId(), "VOUCHER", event.voucherId(), event.version(),
                event.type().name(), event.entries());
        if (eventId != null) {
            applyThrough(event.ledgerId(), event.periodId(), eventId);
        }
    }

    @Override
    public void appendOpeningBalanceEvent(BalanceProjectionService.OpeningBalanceEvent event) {
        requireOpenPeriod(event.ledgerId(), event.periodId());
        append(event.ledgerId(), event.periodId(), "OPENING_BALANCE", event.aggregateId(), event.version(),
                "OPENING_CONFIRM", event.entries());
    }

    @Override
    public void requireOpenPeriod(UUID ledgerId, UUID periodId) {
        String status = jdbc.query("""
                select status from accounting_period
                where ledger_id = ? and id = ?
                for update
                """, rs -> rs.next() ? rs.getString("status") : null, ledgerId, periodId);
        if (!"OPEN".equals(status)) {
            throw new BalanceProjectionException("ACCOUNTING_PERIOD_CLOSED",
                    "The accounting period must be open before the balance can change");
        }
    }

    @Override
    public void requireReadyForClose(UUID ledgerId, UUID periodId) {
        String periodStatus = jdbc.query("""
                select status from accounting_period
                where ledger_id = ? and id = ? for update
                """, rs -> rs.next() ? rs.getString(1) : null, ledgerId, periodId);
        if (!"OPEN".equals(periodStatus)) {
            throw new BalanceProjectionException("PERIOD_STATE_INVALID",
                    "The accounting period must be open before it can be closed");
        }
        ProjectionRow state = jdbc.query("""
                select status, coalesce(last_enqueued_event_id, 0) enqueued,
                    coalesce(last_applied_event_id, 0) applied
                from balance_projection_state where ledger_id = ? and period_id = ?
                """, rs -> rs.next() ? new ProjectionRow(rs.getString("status"),
                rs.getLong("enqueued"), rs.getLong("applied"), null, null) : null,
                ledgerId, periodId);
        if (state == null && Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (
                    select 1 from voucher where ledger_id = ? and period_id = ? and status = 'POSTED'
                    union all
                    select 1 from opening_balance where ledger_id = ? and period_id = ? and confirmed
                )
                """, Boolean.class, ledgerId, periodId, ledgerId, periodId))) {
            throw new BalanceProjectionException("BALANCE_PROJECTION_NOT_READY",
                    "The balance projection has not been initialized for this period");
        }
        if (state != null && (!"READY".equals(state.status()) || state.enqueued() != state.applied())) {
            throw new BalanceProjectionException("BALANCE_PROJECTION_NOT_READY",
                    "The balance projection has pending or failed events");
        }
        long differences = jdbc.queryForObject("""
                with expected as (
                    select vl.account_id,
                        0::numeric opening_debit, 0::numeric opening_credit,
                        coalesce(sum(case when vl.side = 'DEBIT' then vl.base_amount else 0 end), 0) period_debit,
                        coalesce(sum(case when vl.side = 'CREDIT' then vl.base_amount else 0 end), 0) period_credit
                    from voucher_line vl
                    join voucher v on v.ledger_id = vl.ledger_id and v.id = vl.voucher_id
                    where v.ledger_id = ? and v.period_id = ? and v.status = 'POSTED'
                    group by vl.account_id
                    union all
                    select ob.account_id, sum(ob.debit_base), sum(ob.credit_base),
                        0::numeric, 0::numeric
                    from opening_balance ob
                    where ob.ledger_id = ? and ob.period_id = ? and ob.confirmed
                    group by ob.account_id
                ), grouped as (
                    select account_id, sum(opening_debit) opening_debit, sum(opening_credit) opening_credit,
                        sum(period_debit) period_debit, sum(period_credit) period_credit
                    from expected group by account_id
                ), differences as (
                    select coalesce(g.account_id, b.account_id) account_id
                    from grouped g full join account_period_balance b
                      on b.ledger_id = ? and b.period_id = ? and b.account_id = g.account_id
                    where coalesce(g.opening_debit, 0) <> coalesce(b.opening_debit_base, 0)
                       or coalesce(g.opening_credit, 0) <> coalesce(b.opening_credit_base, 0)
                       or coalesce(g.period_debit, 0) <> coalesce(b.period_debit_base, 0)
                       or coalesce(g.period_credit, 0) <> coalesce(b.period_credit_base, 0)
                ) select count(*) from differences
                """, Long.class, ledgerId, periodId, ledgerId, periodId, ledgerId, periodId);
        if (differences > 0) {
            throw new BalanceProjectionException("BALANCE_RECONCILIATION_FAILED",
                    "The balance projection does not reconcile to voucher and opening balance facts");
        }
    }

    @Override
    public void markReopened(UUID ledgerId, UUID periodId) {
        jdbc.update("""
                update account_period_balance set finalized_at = null, updated_at = now()
                where ledger_id = ? and period_id = ?
                """, ledgerId, periodId);
    }

    @Override
    public BalanceProjectionService.ProjectionStatus status(UUID ledgerId, String periodCode) {
        List<ProjectionRow> rows = jdbc.query("""
                select s.status, coalesce(s.last_enqueued_event_id, 0) enqueued,
                    coalesce(s.last_applied_event_id, 0) applied,
                    s.last_enqueued_at, s.projected_at
                from balance_projection_state s
                join accounting_period p on p.ledger_id = s.ledger_id and p.id = s.period_id
                where s.ledger_id = ? and (?::varchar is null or p.period_code = ?)
                """, (rs, row) -> new ProjectionRow(rs.getString("status"), rs.getLong("enqueued"),
                rs.getLong("applied"), rs.getObject("last_enqueued_at", OffsetDateTime.class),
                rs.getObject("projected_at", OffsetDateTime.class)), ledgerId, periodCode, periodCode);
        if (rows.isEmpty()) {
            boolean hasFacts = Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists (
                        select 1 from voucher v
                        where v.ledger_id = ? and v.status = 'POSTED'
                          and (?::varchar is null or exists (
                              select 1 from accounting_period p
                              where p.ledger_id = v.ledger_id and p.id = v.period_id and p.period_code = ?))
                        union all
                        select 1 from opening_balance ob
                        where ob.ledger_id = ? and ob.confirmed
                          and (?::varchar is null or exists (
                              select 1 from accounting_period p
                              where p.ledger_id = ob.ledger_id and p.id = ob.period_id and p.period_code = ?))
                    )
                    """, Boolean.class, ledgerId, periodCode, periodCode, ledgerId, periodCode, periodCode));
            return new BalanceProjectionService.ProjectionStatus(hasFacts ? "UNINITIALIZED" : "READY",
                    0, 0, null, null);
        }
        return summarizeStatus(rows);
    }

    static BalanceProjectionService.ProjectionStatus summarizeStatus(List<ProjectionRow> rows) {
        boolean pending = rows.stream().anyMatch(row -> row.enqueued() != row.applied());
        String status = rows.stream().anyMatch(row -> "FAILED".equals(row.status())) ? "FAILED"
                : rows.stream().anyMatch(row -> "REBUILDING".equals(row.status())) ? "REBUILDING"
                : pending ? "PENDING" : "READY";
        long enqueued = rows.stream().mapToLong(ProjectionRow::enqueued).max().orElse(0);
        long applied = pending
                ? rows.stream().mapToLong(ProjectionRow::applied).min().orElse(0)
                : enqueued;
        OffsetDateTime enqueuedAt = rows.stream().map(ProjectionRow::enqueuedAt).filter(java.util.Objects::nonNull)
                .max(OffsetDateTime::compareTo).orElse(null);
        OffsetDateTime projectedAt = rows.stream().map(ProjectionRow::projectedAt).filter(java.util.Objects::nonNull)
                .min(OffsetDateTime::compareTo).orElse(null);
        return new BalanceProjectionService.ProjectionStatus(status, enqueued, applied, enqueuedAt, projectedAt);
    }

    @Override
    public List<ReportResponses.TrialBalanceLine> trialBalance(UUID ledgerId, String periodCode) {
        return jdbc.query("""
                select a.id, a.code, a.name, a.category,
                    coalesce(sum(b.opening_debit_base + b.period_debit_base), 0) debit,
                    coalesce(sum(b.opening_credit_base + b.period_credit_base), 0) credit
                from ledger_account a
                left join account_period_balance b on b.ledger_id = a.ledger_id and b.account_id = a.id
                left join accounting_period p on p.ledger_id = b.ledger_id and p.id = b.period_id
                    and (?::varchar is null or p.period_code = ?)
                where a.ledger_id = ? and (b.account_id is null or p.id is not null)
                group by a.id, a.code, a.name, a.category
                having coalesce(sum(b.opening_debit_base + b.period_debit_base), 0) <> 0
                    or coalesce(sum(b.opening_credit_base + b.period_credit_base), 0) <> 0
                order by a.code
                """, (rs, row) -> projectionLine(rs), periodCode, periodCode, ledgerId);
    }

    @Override
    public List<ReportResponses.TrialBalanceLine> trialBalanceWithParents(UUID ledgerId, String periodCode) {
        return jdbc.query("""
                with recursive account_path as (
                    select id source_id, id account_id, parent_id
                    from ledger_account where ledger_id = ?
                    union all
                    select path.source_id, parent.id, parent.parent_id
                    from account_path path
                    join ledger_account parent on parent.id = path.parent_id
                    where parent.ledger_id = ?
                ), amounts as (
                    select b.account_id,
                        sum(b.opening_debit_base + b.period_debit_base) debit,
                        sum(b.opening_credit_base + b.period_credit_base) credit
                    from account_period_balance b
                    join accounting_period p on p.ledger_id = b.ledger_id and p.id = b.period_id
                    where b.ledger_id = ? and (?::varchar is null or p.period_code = ?)
                    group by b.account_id
                )
                select account.id, account.code, account.name, account.category,
                    coalesce(sum(amounts.debit), 0) debit, coalesce(sum(amounts.credit), 0) credit
                from ledger_account account
                join account_path path on path.account_id = account.id
                left join amounts on amounts.account_id = path.source_id
                where account.ledger_id = ?
                group by account.id, account.code, account.name, account.category
                having coalesce(sum(amounts.debit), 0) <> 0
                    or coalesce(sum(amounts.credit), 0) <> 0
                order by account.code
                """, (rs, row) -> projectionLine(rs), ledgerId, ledgerId, ledgerId,
                periodCode, periodCode, ledgerId);
    }

    @Override
    @Transactional
    public boolean applyPendingBatch(int maxEvents, int maxEventLines) {
        ProjectionBatch batch = jdbc.query("""
                select ledger_id, period_id, coalesce(last_applied_event_id, 0) applied,
                    coalesce(last_enqueued_event_id, 0) enqueued
                from balance_projection_state
                where status in ('READY', 'FAILED') and attempts < 5
                  and next_attempt_at <= now()
                  and coalesce(last_applied_event_id, 0) < coalesce(last_enqueued_event_id, 0)
                order by last_enqueued_at nulls first
                for update skip locked limit 1
                """, rs -> rs.next() ? new ProjectionBatch(rs.getObject("ledger_id", UUID.class),
                rs.getObject("period_id", UUID.class), rs.getLong("applied"), rs.getLong("enqueued")) : null);
        if (batch == null) {
            return false;
        }
        List<Long> eventIds = jdbc.queryForList("""
                select id from balance_projection_event
                where ledger_id = ? and period_id = ? and id > ? and id <= ?
                order by id limit ?
                """, Long.class, batch.ledgerId(), batch.periodId(), batch.applied(), batch.enqueued(), maxEvents);
        if (eventIds.isEmpty()) {
            jdbc.update("""
                    update balance_projection_state set last_applied_event_id = last_enqueued_event_id,
                        projected_at = now(), updated_at = now() where ledger_id = ? and period_id = ?
                    """, batch.ledgerId(), batch.periodId());
            return true;
        }
        int lineCount = 0;
        long lastApplied = batch.applied();
        for (Long eventId : eventIds) {
            List<BalanceLine> lines = jdbc.query("""
                    select account_id, opening_debit_delta, opening_credit_delta,
                        period_debit_delta, period_credit_delta
                    from balance_projection_event_line where event_id = ? order by account_id
                    """, (rs, row) -> new BalanceLine(rs.getObject("account_id", UUID.class),
                    rs.getBigDecimal("opening_debit_delta"), rs.getBigDecimal("opening_credit_delta"),
                    rs.getBigDecimal("period_debit_delta"), rs.getBigDecimal("period_credit_delta")), eventId);
            if (lineCount > 0 && lineCount + lines.size() > maxEventLines) {
                break;
            }
            for (BalanceLine line : lines) {
                applyLine(batch.ledgerId(), batch.periodId(), line);
            }
            lineCount += lines.size();
            lastApplied = eventId;
        }
        deleteZeroBalances(batch.ledgerId(), batch.periodId());
        jdbc.update("""
                update balance_projection_state set last_applied_event_id = ?, projected_at = now(),
                    status = 'READY', attempts = 0, last_error_code = null, last_error_message = null,
                    updated_at = now() where ledger_id = ? and period_id = ?
                """, lastApplied, batch.ledgerId(), batch.periodId());
        return true;
    }

    @Override
    @Transactional
    public void recordFailure() {
        jdbc.update("""
                with candidate as (
                    select ledger_id, period_id, attempts
                    from balance_projection_state
                    where status in ('READY', 'FAILED') and attempts < 5
                      and coalesce(last_applied_event_id, 0) < coalesce(last_enqueued_event_id, 0)
                    order by last_enqueued_at nulls first for update skip locked limit 1
                )
                update balance_projection_state s set status = 'FAILED', attempts = candidate.attempts + 1,
                    next_attempt_at = now() + case candidate.attempts
                        when 0 then interval '1 second' when 1 then interval '5 seconds'
                        when 2 then interval '30 seconds' when 3 then interval '2 minutes'
                        else interval '10 minutes' end,
                    last_error_code = 'BALANCE_PROJECTION_FAILED',
                    last_error_message = 'Projection worker failed; retry is scheduled', updated_at = now()
                from candidate where s.ledger_id = candidate.ledger_id and s.period_id = candidate.period_id
        """);
    }

    @Override
    @Transactional
    public int cleanupAppliedEvents(OffsetDateTime cutoff) {
        return jdbc.update("""
                delete from balance_projection_event e
                where e.created_at < ?
                  and exists (
                      select 1 from balance_projection_state s
                      where s.ledger_id = e.ledger_id and s.period_id = e.period_id
                        and s.status = 'READY' and coalesce(s.last_applied_event_id, 0) >= e.id
                  )
                """, cutoff);
    }

    private ReportResponses.TrialBalanceLine projectionLine(java.sql.ResultSet rs) throws java.sql.SQLException {
        BigDecimal debit = rs.getBigDecimal("debit");
        BigDecimal credit = rs.getBigDecimal("credit");
        return new ReportResponses.TrialBalanceLine(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("name"), rs.getString("category"), debit, credit, debit.subtract(credit));
    }

    private Long append(UUID ledgerId, UUID periodId, String aggregateType, UUID aggregateId,
                        long aggregateVersion, String eventType,
                        java.util.List<BalanceProjectionService.Entry> entries) {
        Long eventId = jdbc.query("""
                insert into balance_projection_event (
                    ledger_id, period_id, aggregate_type, aggregate_id, aggregate_version, event_type)
                values (?, ?, ?, ?, ?, ?)
                on conflict (aggregate_type, aggregate_id, aggregate_version, event_type, period_id) do nothing
                returning id
                """, rs -> rs.next() ? rs.getLong("id") : null,
                ledgerId, periodId, aggregateType, aggregateId, aggregateVersion, eventType);
        if (eventId == null) {
            return null;
        }
        Map<UUID, BalanceProjectionService.Entry> aggregated = aggregate(entries);
        for (BalanceProjectionService.Entry entry : aggregated.values()) {
            jdbc.update("""
                    insert into balance_projection_event_line (
                        event_id, ledger_id, account_id, opening_debit_delta, opening_credit_delta,
                        period_debit_delta, period_credit_delta)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, eventId, ledgerId, entry.accountId(), entry.openingDebit(), entry.openingCredit(),
                    entry.periodDebit(), entry.periodCredit());
        }
        jdbc.update("""
                insert into balance_projection_state (
                    ledger_id, period_id, last_enqueued_event_id, last_enqueued_at, updated_at)
                values (?, ?, ?, now(), now())
                on conflict (ledger_id, period_id) do update set
                    last_enqueued_event_id = excluded.last_enqueued_event_id,
                    last_enqueued_at = excluded.last_enqueued_at,
                    status = case when balance_projection_state.status = 'FAILED'
                                  then 'READY' else balance_projection_state.status end,
                    last_error_code = null,
                    last_error_message = null,
                    attempts = 0,
                    next_attempt_at = now(),
                    updated_at = now()
                """, ledgerId, periodId, eventId);
        return eventId;
    }

    private void applyThrough(UUID ledgerId, UUID periodId, long targetEventId) {
        ProjectionBatch state = jdbc.query("""
                select ledger_id, period_id, coalesce(last_applied_event_id, 0) applied,
                    coalesce(last_enqueued_event_id, 0) enqueued
                from balance_projection_state
                where ledger_id = ? and period_id = ? and status <> 'REBUILDING'
                for update
                """, rs -> rs.next() ? new ProjectionBatch(rs.getObject("ledger_id", UUID.class),
                rs.getObject("period_id", UUID.class), rs.getLong("applied"), rs.getLong("enqueued")) : null,
                ledgerId, periodId);
        if (state == null) {
            throw new BalanceProjectionException("BALANCE_PROJECTION_NOT_READY",
                    "The balance projection is rebuilding and cannot accept voucher changes");
        }
        List<Long> eventIds = jdbc.queryForList("""
                select id from balance_projection_event
                where ledger_id = ? and period_id = ? and id > ? and id <= ?
                order by id
                """, Long.class, ledgerId, periodId, state.applied(), targetEventId);
        long lastApplied = state.applied();
        for (Long eventId : eventIds) {
            List<BalanceLine> lines = jdbc.query("""
                    select account_id, opening_debit_delta, opening_credit_delta,
                        period_debit_delta, period_credit_delta
                    from balance_projection_event_line where event_id = ? order by account_id
                    """, (rs, row) -> new BalanceLine(rs.getObject("account_id", UUID.class),
                    rs.getBigDecimal("opening_debit_delta"), rs.getBigDecimal("opening_credit_delta"),
                    rs.getBigDecimal("period_debit_delta"), rs.getBigDecimal("period_credit_delta")), eventId);
            for (BalanceLine line : lines) {
                applyLine(ledgerId, periodId, line);
            }
            lastApplied = eventId;
        }
        deleteZeroBalances(ledgerId, periodId);
        jdbc.update("""
                update balance_projection_state set last_applied_event_id = ?, projected_at = now(),
                    status = 'READY', attempts = 0, last_error_code = null, last_error_message = null,
                    updated_at = now() where ledger_id = ? and period_id = ?
                """, lastApplied, ledgerId, periodId);
    }

    private void applyLine(UUID ledgerId, UUID periodId, BalanceLine line) {
        try {
            int updated = jdbc.update("""
                    update account_period_balance set
                        opening_debit_base = opening_debit_base + ?,
                        opening_credit_base = opening_credit_base + ?,
                        period_debit_base = period_debit_base + ?,
                        period_credit_base = period_credit_base + ?,
                        version = version + 1, updated_at = now()
                    where ledger_id = ? and period_id = ? and account_id = ?
                    """, line.openingDebit(), line.openingCredit(), line.periodDebit(), line.periodCredit(),
                    ledgerId, periodId, line.accountId());
            if (updated == 0) {
                if (negative(line)) {
                    throw new BalanceProjectionException("BALANCE_PROJECTION_OUT_OF_SYNC",
                            "A voucher change attempted to remove a balance that is not projected");
                }
                jdbc.update("""
                        insert into account_period_balance (
                            ledger_id, period_id, account_id, opening_debit_base, opening_credit_base,
                            period_debit_base, period_credit_base, version, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, 1, now())
                        """, ledgerId, periodId, line.accountId(), line.openingDebit(), line.openingCredit(),
                        line.periodDebit(), line.periodCredit());
            }
        } catch (DataIntegrityViolationException exception) {
            throw new BalanceProjectionException("BALANCE_PROJECTION_OUT_OF_SYNC",
                    "The voucher change would make the projected balance inconsistent");
        }
    }

    private boolean negative(BalanceLine line) {
        return line.openingDebit().signum() < 0 || line.openingCredit().signum() < 0
                || line.periodDebit().signum() < 0 || line.periodCredit().signum() < 0;
    }

    private void deleteZeroBalances(UUID ledgerId, UUID periodId) {
        jdbc.update("""
                delete from account_period_balance
                where ledger_id = ? and period_id = ?
                  and opening_debit_base = 0 and opening_credit_base = 0
                  and period_debit_base = 0 and period_credit_base = 0
                """, ledgerId, periodId);
    }

    private Map<UUID, BalanceProjectionService.Entry> aggregate(
            java.util.List<BalanceProjectionService.Entry> entries) {
        Map<UUID, BalanceProjectionService.Entry> result = new LinkedHashMap<>();
        for (BalanceProjectionService.Entry entry : entries) {
            result.merge(entry.accountId(), entry, (left, right) -> new BalanceProjectionService.Entry(
                    left.accountId(), add(left.openingDebit(), right.openingDebit()),
                    add(left.openingCredit(), right.openingCredit()), add(left.periodDebit(), right.periodDebit()),
                    add(left.periodCredit(), right.periodCredit())));
        }
        return result;
    }

    private BigDecimal add(BigDecimal left, BigDecimal right) {
        return left.add(right);
    }

    record ProjectionRow(String status, long enqueued, long applied,
                         OffsetDateTime enqueuedAt, OffsetDateTime projectedAt) {
    }

    private record ProjectionBatch(UUID ledgerId, UUID periodId, long applied, long enqueued) {
    }

    private record BalanceLine(UUID accountId, BigDecimal openingDebit, BigDecimal openingCredit,
                               BigDecimal periodDebit, BigDecimal periodCredit) {
    }
}
