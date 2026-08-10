package com.example.accounting.reporting.internal.persistence;

import com.example.accounting.shared.balance.BalanceProjectionService;
import com.example.accounting.reporting.ReportResponses;
import com.example.accounting.reporting.PeriodRange;
import com.example.accounting.reporting.internal.port.BalanceProjectionRepository;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcBalanceProjectionRepository implements BalanceProjectionRepository {

    private final JdbcTemplate jdbc;
    private final BalanceSnapshotRebuilder snapshots;
    private final ThreadLocal<UUID> attemptedLedger = new ThreadLocal<>();

    public JdbcBalanceProjectionRepository(JdbcTemplate jdbc, BalanceSnapshotRebuilder snapshots) {
        this.jdbc = jdbc;
        this.snapshots = snapshots;
    }

    @Override
    public void appendVoucherEvent(BalanceProjectionService.VoucherEvent event) {
        requireOpenPeriod(event.ledgerId(), event.periodId());
        append(event.ledgerId(), event.periodId(), "VOUCHER", event.voucherId(), event.version(),
                event.type().name(), event.entries());
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
                with period_info as (
                    select p.period_code,
                        lag(p.id) over (order by p.period_code) previous_period_id,
                        row_number() over (order by p.period_code) period_number
                    from accounting_period p where p.ledger_id = ?
                ), selected as (
                    select * from period_info where period_code = (
                        select period_code from accounting_period where ledger_id = ? and id = ?)
                ), leaves as (
                    select a.id from ledger_account a
                    where a.ledger_id = ? and not exists (
                        select 1 from ledger_account child
                        where child.ledger_id = a.ledger_id and child.parent_id = a.id)
                ), facts as (
                    select vl.account_id,
                        sum(case when vl.side = 'DEBIT' then vl.base_amount else 0 end) debit,
                        sum(case when vl.side = 'CREDIT' then vl.base_amount else 0 end) credit
                    from voucher_line vl
                    join voucher v on v.ledger_id = vl.ledger_id and v.id = vl.voucher_id
                    where v.ledger_id = ? and v.period_id = ? and v.status = 'POSTED'
                    group by vl.account_id
                ), leaf_differences as (
                    select coalesce(f.account_id, b.account_id) account_id
                    from facts f full join (
                        select current.* from account_period_balance current
                        where current.ledger_id = ? and current.period_id = ?
                          and current.account_id in (select id from leaves)
                    ) b on b.account_id = f.account_id
                    where coalesce(f.debit, 0) <> coalesce(b.period_debit_base, 0)
                       or coalesce(f.credit, 0) <> coalesce(b.period_credit_base, 0)
                ), equation_differences as (
                    select b.account_id from account_period_balance b
                    where b.ledger_id = ? and b.period_id = ?
                      and b.closing_debit_base - b.closing_credit_base
                        <> b.opening_debit_base - b.opening_credit_base
                           + b.period_debit_base - b.period_credit_base
                ), parent_differences as (
                    select parent.id
                    from ledger_account parent
                    left join ledger_account child
                      on child.ledger_id = parent.ledger_id and child.parent_id = parent.id
                    left join account_period_balance pb
                      on pb.ledger_id = parent.ledger_id and pb.period_id = ? and pb.account_id = parent.id
                    left join account_period_balance cb
                      on cb.ledger_id = child.ledger_id and cb.period_id = ? and cb.account_id = child.id
                    where parent.ledger_id = ? and child.id is not null
                    group by parent.id, pb.opening_debit_base, pb.opening_credit_base,
                        pb.period_debit_base, pb.period_credit_base,
                        pb.closing_debit_base, pb.closing_credit_base
                    having coalesce(pb.opening_debit_base - pb.opening_credit_base, 0)
                            <> coalesce(sum(cb.opening_debit_base - cb.opening_credit_base), 0)
                        or coalesce(pb.period_debit_base, 0) <> coalesce(sum(cb.period_debit_base), 0)
                        or coalesce(pb.period_credit_base, 0) <> coalesce(sum(cb.period_credit_base), 0)
                        or coalesce(pb.closing_debit_base - pb.closing_credit_base, 0)
                            <> coalesce(sum(cb.closing_debit_base - cb.closing_credit_base), 0)
                ), expected_opening as (
                    select leaf.id account_id,
                        case when s.period_number = 1 then coalesce(first_opening.opening_net, 0)
                             else coalesce(previous.closing_debit_base - previous.closing_credit_base, 0) end net
                    from leaves leaf cross join selected s
                    left join account_period_balance previous
                      on previous.ledger_id = ? and previous.period_id = s.previous_period_id
                     and previous.account_id = leaf.id
                    left join (
                        select account_id, sum(debit_base - credit_base) opening_net
                        from opening_balance
                        where ledger_id = ? and confirmed group by account_id
                    ) first_opening on first_opening.account_id = leaf.id
                ), continuity_differences as (
                    select coalesce(expected.account_id, b.account_id) account_id
                    from expected_opening expected
                    full join (
                        select current.* from account_period_balance current
                        where current.ledger_id = ? and current.period_id = ?
                          and current.account_id in (select id from leaves)
                    ) b on b.account_id = expected.account_id
                    where coalesce(b.opening_debit_base - b.opening_credit_base, 0) <> coalesce(expected.net, 0)
                )
                select (select count(*) from leaf_differences)
                     + (select count(*) from equation_differences)
                     + (select count(*) from parent_differences)
                     + (select count(*) from continuity_differences)
                """, Long.class,
                ledgerId, ledgerId, periodId, ledgerId,
                ledgerId, periodId, ledgerId, periodId,
                ledgerId, periodId, periodId, periodId, ledgerId,
                ledgerId, ledgerId, ledgerId, periodId);
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
        if (periodCode == null) {
            String[] bounds = jdbc.query("""
                    select min(period_code), max(period_code) from accounting_period where ledger_id = ?
                    """, rs -> rs.next() ? new String[]{rs.getString(1), rs.getString(2)} : null, ledgerId);
            if (bounds == null || bounds[0] == null) {
                return new BalanceProjectionService.ProjectionStatus("READY", 0, 0, null, null);
            }
            return status(ledgerId, new PeriodRange(bounds[0], bounds[1]));
        }
        return status(ledgerId, PeriodRange.single(periodCode));
    }

    @Override
    public BalanceProjectionService.ProjectionStatus status(UUID ledgerId, PeriodRange range) {
        List<ProjectionRow> rows = jdbc.query("""
                select s.status, coalesce(s.last_enqueued_event_id, 0) enqueued,
                    coalesce(s.last_applied_event_id, 0) applied,
                    s.last_enqueued_at, s.projected_at
                from balance_projection_state s
                join accounting_period p on p.ledger_id = s.ledger_id and p.id = s.period_id
                where s.ledger_id = ? and p.period_code between ? and ?
                """, (rs, row) -> new ProjectionRow(rs.getString("status"), rs.getLong("enqueued"),
                rs.getLong("applied"), rs.getObject("last_enqueued_at", OffsetDateTime.class),
                rs.getObject("projected_at", OffsetDateTime.class)),
                ledgerId, range.periodFrom(), range.periodTo());
        Integer expected = jdbc.queryForObject("""
                select count(*) from accounting_period
                where ledger_id = ? and period_code between ? and ?
                """, Integer.class, ledgerId, range.periodFrom(), range.periodTo());
        if (rows.size() != expected) {
            return new BalanceProjectionService.ProjectionStatus("UNINITIALIZED",
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
        return trialBalance(ledgerId, PeriodRange.single(periodCode), false);
    }

    @Override
    public List<ReportResponses.TrialBalanceLine> trialBalanceWithParents(UUID ledgerId, String periodCode) {
        return trialBalance(ledgerId, PeriodRange.single(periodCode), true);
    }

    @Override
    public List<ReportResponses.TrialBalanceLine> trialBalance(
            UUID ledgerId, PeriodRange range, boolean includeParents) {
        return jdbc.query("""
                with opening as (
                    select b.account_id, b.opening_debit_base, b.opening_credit_base
                    from account_period_balance b
                    join accounting_period p on p.ledger_id = b.ledger_id and p.id = b.period_id
                    where b.ledger_id = ? and p.period_code = ?
                ), movement as (
                    select b.account_id, sum(b.period_debit_base) period_debit_base,
                        sum(b.period_credit_base) period_credit_base
                    from account_period_balance b
                    join accounting_period p on p.ledger_id = b.ledger_id and p.id = b.period_id
                    where b.ledger_id = ? and p.period_code between ? and ?
                    group by b.account_id
                ), closing as (
                    select b.account_id, b.closing_debit_base, b.closing_credit_base
                    from account_period_balance b
                    join accounting_period p on p.ledger_id = b.ledger_id and p.id = b.period_id
                    where b.ledger_id = ? and p.period_code = ?
                )
                select a.id, a.code, a.name, a.category,
                    coalesce(o.opening_debit_base, 0) opening_debit,
                    coalesce(o.opening_credit_base, 0) opening_credit,
                    coalesce(m.period_debit_base, 0) period_debit,
                    coalesce(m.period_credit_base, 0) period_credit,
                    coalesce(c.closing_debit_base, 0) closing_debit,
                    coalesce(c.closing_credit_base, 0) closing_credit
                from ledger_account a
                left join opening o on o.account_id = a.id
                left join movement m on m.account_id = a.id
                left join closing c on c.account_id = a.id
                where a.ledger_id = ?
                  and (? or not exists (
                      select 1 from ledger_account child
                      where child.ledger_id = a.ledger_id and child.parent_id = a.id))
                  and (coalesce(o.opening_debit_base, 0) <> 0
                    or coalesce(o.opening_credit_base, 0) <> 0
                    or coalesce(m.period_debit_base, 0) <> 0
                    or coalesce(m.period_credit_base, 0) <> 0
                    or coalesce(c.closing_debit_base, 0) <> 0
                    or coalesce(c.closing_credit_base, 0) <> 0)
                order by a.code
                """, (rs, row) -> projectionLine(rs),
                ledgerId, range.periodFrom(), ledgerId, range.periodFrom(), range.periodTo(),
                ledgerId, range.periodTo(), ledgerId, includeParents);
    }

    @Override
    public BigDecimal openingBalance(UUID ledgerId, String periodCode, UUID accountId) {
        return jdbc.queryForObject("""
                select coalesce(sum(b.opening_debit_base - b.opening_credit_base), 0)
                from account_period_balance b
                join accounting_period p on p.ledger_id = b.ledger_id and p.id = b.period_id
                where b.ledger_id = ? and p.period_code = ? and b.account_id = ?
                """, BigDecimal.class, ledgerId, periodCode, accountId);
    }

    @Override
    @Transactional
    public boolean applyPendingBatch(int maxEvents, int maxEventLines) {
        UUID ledgerId = jdbc.query("""
                select l.id
                from ledger l
                where exists (
                    select 1 from balance_projection_state s
                    where s.ledger_id = l.id and s.status in ('READY', 'FAILED')
                      and s.attempts < 5 and s.next_attempt_at <= now()
                      and coalesce(s.last_applied_event_id, 0) < coalesce(s.last_enqueued_event_id, 0))
                order by (
                    select min(s.last_enqueued_at) from balance_projection_state s
                    where s.ledger_id = l.id
                      and coalesce(s.last_applied_event_id, 0) < coalesce(s.last_enqueued_event_id, 0))
                for update skip locked limit 1
                """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null);
        if (ledgerId == null) {
            return false;
        }
        attemptedLedger.set(ledgerId);
        UUID sourcePeriodId = jdbc.query("""
                select p.id
                from balance_projection_state s
                join accounting_period p on p.ledger_id = s.ledger_id and p.id = s.period_id
                where s.ledger_id = ?
                  and coalesce(s.last_applied_event_id, 0) < coalesce(s.last_enqueued_event_id, 0)
                order by p.period_code limit 1
                """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, ledgerId);
        if (sourcePeriodId == null) {
            attemptedLedger.remove();
            return false;
        }
        snapshots.rebuildFrom(ledgerId, sourcePeriodId);
        jdbc.update("""
                update balance_projection_state set last_applied_event_id = last_enqueued_event_id,
                    projected_at = now(),
                    status = 'READY', attempts = 0, last_error_code = null, last_error_message = null,
                    next_attempt_at = now(), updated_at = now()
                where ledger_id = ?
                  and coalesce(last_applied_event_id, 0) < coalesce(last_enqueued_event_id, 0)
                """, ledgerId);
        attemptedLedger.remove();
        return true;
    }

    @Override
    @Transactional
    public void recordFailure() {
        UUID ledgerId = attemptedLedger.get();
        attemptedLedger.remove();
        if (ledgerId == null) {
            return;
        }
        jdbc.update("""
                update balance_projection_state s set status = 'FAILED', attempts = s.attempts + 1,
                    next_attempt_at = now() + case s.attempts
                        when 0 then interval '1 second' when 1 then interval '5 seconds'
                        when 2 then interval '30 seconds' when 3 then interval '2 minutes'
                        else interval '10 minutes' end,
                    last_error_code = 'BALANCE_PROJECTION_FAILED',
                    last_error_message = 'Projection worker failed; retry is scheduled', updated_at = now()
                where s.ledger_id = ?
                  and coalesce(s.last_applied_event_id, 0) < coalesce(s.last_enqueued_event_id, 0)
        """, ledgerId);
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
        BigDecimal openingDebit = rs.getBigDecimal("opening_debit");
        BigDecimal openingCredit = rs.getBigDecimal("opening_credit");
        BigDecimal debit = rs.getBigDecimal("period_debit");
        BigDecimal credit = rs.getBigDecimal("period_credit");
        BigDecimal closingDebit = rs.getBigDecimal("closing_debit");
        BigDecimal closingCredit = rs.getBigDecimal("closing_credit");
        return new ReportResponses.TrialBalanceLine(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("name"), rs.getString("category"), openingDebit, openingCredit,
                debit, credit, closingDebit, closingCredit, debit, credit,
                closingDebit.subtract(closingCredit));
    }

    private Long append(UUID ledgerId, UUID periodId, String aggregateType, UUID aggregateId,
                        long aggregateVersion, String eventType,
                        java.util.List<BalanceProjectionService.Entry> entries) {
        jdbc.queryForObject("select id from ledger where id = ? for update", UUID.class, ledgerId);
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
                select p.ledger_id, p.id, ?, now(), now()
                from accounting_period p
                join accounting_period source on source.ledger_id = p.ledger_id and source.id = ?
                where p.ledger_id = ? and p.period_code >= source.period_code
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
                """, eventId, periodId, ledgerId);
        return eventId;
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

}
