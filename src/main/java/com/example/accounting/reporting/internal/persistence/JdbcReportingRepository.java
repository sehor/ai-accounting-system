package com.example.accounting.reporting.internal.persistence;

import com.example.accounting.reporting.ReportResponses;
import com.example.accounting.reporting.BalanceProjectionService;
import com.example.accounting.reporting.BalanceReadMetadata;
import com.example.accounting.reporting.internal.port.BalanceProjectionRepository;
import com.example.accounting.reporting.internal.port.ReportingRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReportingRepository implements ReportingRepository {

    private final JdbcTemplate jdbc;
    private final BalanceProjectionRepository projection;
    private final String readMode;
    private final Duration maxLag;

    @Autowired
    public JdbcReportingRepository(JdbcTemplate jdbc, BalanceProjectionRepository projection,
                                   @Value("${accounting.balance.read-mode:legacy}") String readMode,
                                   @Value("${accounting.balance.max-lag:5s}") Duration maxLag) {
        this.jdbc = jdbc;
        this.projection = projection;
        this.readMode = readMode;
        this.maxLag = maxLag;
    }

    /** Compatibility constructor for repository-focused tests. */
    public JdbcReportingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.projection = null;
        this.readMode = "legacy";
        this.maxLag = Duration.ofSeconds(5);
    }

    @Override
    public List<ReportResponses.TrialBalanceLine> trialBalance(UUID ledgerId, String periodCode) {
        if (useProjection(ledgerId, periodCode)) {
            return projection.trialBalance(ledgerId, periodCode);
        }
        markFallback(ledgerId, periodCode);
        return jdbc.query("""
                select a.id, a.code, a.name, a.category,
                    coalesce(sum(x.debit), 0) debit, coalesce(sum(x.credit), 0) credit
                from ledger_account a
                left join (
                    select vl.account_id,
                        case when vl.side = 'DEBIT' then vl.base_amount else 0 end debit,
                        case when vl.side = 'CREDIT' then vl.base_amount else 0 end credit
                    from voucher_line vl
                    join voucher v on v.ledger_id = vl.ledger_id and v.id = vl.voucher_id
                    join accounting_period p on p.ledger_id = v.ledger_id and p.id = v.period_id
                    where v.ledger_id = ? and v.status = 'POSTED'
                      and (?::varchar is null or p.period_code = ?)
                    union all
                    select ob.account_id, ob.debit_base, ob.credit_base
                    from opening_balance ob
                    join accounting_period p on p.ledger_id = ob.ledger_id and p.id = ob.period_id
                    where ob.ledger_id = ? and ob.confirmed
                      and (?::varchar is null or p.period_code = ?)
                ) x on x.account_id = a.id
                where a.ledger_id = ?
                group by a.id, a.code, a.name, a.category
                having coalesce(sum(x.debit), 0) <> 0 or coalesce(sum(x.credit), 0) <> 0
                order by a.code
                """, (rs, rowNum) -> {
            BigDecimal debit = rs.getBigDecimal("debit");
            BigDecimal credit = rs.getBigDecimal("credit");
            return new ReportResponses.TrialBalanceLine(rs.getObject("id", UUID.class),
                    rs.getString("code"), rs.getString("name"), rs.getString("category"), debit, credit,
                    debit.subtract(credit));
        }, ledgerId, periodCode, periodCode, ledgerId, periodCode, periodCode, ledgerId);
    }

    @Override
    public List<ReportResponses.TrialBalanceLine> trialBalanceWithParents(UUID ledgerId, String periodCode) {
        if (useProjection(ledgerId, periodCode)) {
            return projection.trialBalanceWithParents(ledgerId, periodCode);
        }
        markFallback(ledgerId, periodCode);
        return jdbc.query("""
                with recursive account_path as (
                    select id source_id, id account_id, parent_id
                    from ledger_account where ledger_id = ?
                    union all
                    select path.source_id, parent.id, parent.parent_id
                    from account_path path
                    join ledger_account parent on parent.id = path.parent_id
                    where parent.ledger_id = ?
                ),
                amounts as (
                    select source.account_id,
                        sum(source.debit) debit, sum(source.credit) credit
                    from (
                        select vl.account_id,
                            case when vl.side = 'DEBIT' then vl.base_amount else 0 end debit,
                            case when vl.side = 'CREDIT' then vl.base_amount else 0 end credit
                        from voucher_line vl
                        join voucher v on v.ledger_id = vl.ledger_id and v.id = vl.voucher_id
                        join accounting_period p on p.ledger_id = v.ledger_id and p.id = v.period_id
                        where v.ledger_id = ? and v.status = 'POSTED'
                          and (?::varchar is null or p.period_code = ?)
                        union all
                        select ob.account_id, ob.debit_base, ob.credit_base
                        from opening_balance ob
                        join accounting_period p on p.ledger_id = ob.ledger_id and p.id = ob.period_id
                        where ob.ledger_id = ? and ob.confirmed
                          and (?::varchar is null or p.period_code = ?)
                    ) source
                    group by source.account_id
                )
                select account.id, account.code, account.name, account.category,
                    coalesce(sum(amounts.debit), 0) debit,
                    coalesce(sum(amounts.credit), 0) credit
                from ledger_account account
                join account_path path on path.account_id = account.id
                left join amounts on amounts.account_id = path.source_id
                where account.ledger_id = ?
                group by account.id, account.code, account.name, account.category
                having coalesce(sum(amounts.debit), 0) <> 0
                    or coalesce(sum(amounts.credit), 0) <> 0
                order by account.code
                """, (rs, row) -> {
            BigDecimal debit = rs.getBigDecimal("debit");
            BigDecimal credit = rs.getBigDecimal("credit");
            return new ReportResponses.TrialBalanceLine(
                    rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                    rs.getString("category"), debit, credit, debit.subtract(credit));
        }, ledgerId, ledgerId, ledgerId, periodCode, periodCode,
                ledgerId, periodCode, periodCode, ledgerId);
    }

    @Override
    public List<ReportResponses.LedgerLine> ledgerLines(UUID ledgerId, String periodCode) {
        return jdbc.query("""
                select v.id voucher_id, v.voucher_number, v.voucher_date, a.code account_code, a.name account_name,
                    vl.side, vl.base_amount amount, cast(null as varchar) dimension_key
                from voucher_line vl
                join voucher v on v.ledger_id = vl.ledger_id and v.id = vl.voucher_id
                join ledger_account a on a.ledger_id = vl.ledger_id and a.id = vl.account_id
                join accounting_period p on p.ledger_id = v.ledger_id and p.id = v.period_id
                where v.ledger_id = ? and v.status = 'POSTED'
                  and (?::varchar is null or p.period_code = ?)
                order by v.voucher_date, v.voucher_number, vl.line_no
                """, (rs, rowNum) -> new ReportResponses.LedgerLine(rs.getObject("voucher_id", UUID.class),
                rs.getString("voucher_number"), rs.getObject("voucher_date", LocalDate.class),
                rs.getString("account_code"), rs.getString("account_name"), rs.getString("side"),
                rs.getBigDecimal("amount"), rs.getString("dimension_key")), ledgerId, periodCode, periodCode);
    }

    @Override
    public boolean periodExists(UUID ledgerId, String periodCode) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from accounting_period where ledger_id = ? and period_code = ?)
                """, Boolean.class, ledgerId, periodCode));
    }

    @Override
    public boolean accountExists(UUID ledgerId, UUID accountId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from ledger_account where ledger_id = ? and id = ?)
                """, Boolean.class, ledgerId, accountId));
    }

    @Override
    public ReportResponses.GeneralLedgerPage generalLedgerBook(
            UUID ledgerId, String periodCode, int page, int pageSize) {
        long[] totalItems = {0};
        long offset = (long) (page - 1) * pageSize;
        List<ReportResponses.GeneralLedgerAccount> data = jdbc.query("""
                with selected as (
                    select period_code from accounting_period where ledger_id = ? and period_code = ?
                ),
                baseline_period as (
                    select ob.account_id, max(p.period_code) baseline_code
                    from opening_balance ob
                    join accounting_period p on p.ledger_id = ob.ledger_id and p.id = ob.period_id
                    where ob.ledger_id = ? and ob.confirmed
                      and p.period_code <= (select period_code from selected)
                    group by ob.account_id
                ),
                baseline_amount as (
                    select ob.account_id, sum(ob.debit_base - ob.credit_base) net
                    from opening_balance ob
                    join accounting_period p on p.ledger_id = ob.ledger_id and p.id = ob.period_id
                    join baseline_period bp on bp.account_id = ob.account_id and bp.baseline_code = p.period_code
                    where ob.confirmed
                    group by ob.account_id
                ),
                prior_activity as (
                    select vl.account_id,
                        sum(case when vl.side = 'DEBIT' then vl.base_amount else -vl.base_amount end) net
                    from voucher_line vl
                    join voucher v on v.ledger_id = vl.ledger_id and v.id = vl.voucher_id
                    join accounting_period p on p.ledger_id = v.ledger_id and p.id = v.period_id
                    left join baseline_period bp on bp.account_id = vl.account_id
                    where v.ledger_id = ? and v.status = 'POSTED' and v.deleted_at is null
                      and p.period_code < (select period_code from selected)
                      and (bp.baseline_code is null or p.period_code > bp.baseline_code)
                    group by vl.account_id
                ),
                period_activity as (
                    select vl.account_id,
                        sum(case when vl.side = 'DEBIT' then vl.base_amount else 0 end) debit,
                        sum(case when vl.side = 'CREDIT' then vl.base_amount else 0 end) credit
                    from voucher_line vl
                    join voucher v on v.ledger_id = vl.ledger_id and v.id = vl.voucher_id
                    join accounting_period p on p.ledger_id = v.ledger_id and p.id = v.period_id
                    where v.ledger_id = ? and v.status = 'POSTED' and v.deleted_at is null
                      and p.period_code = (select period_code from selected)
                    group by vl.account_id
                ),
                year_activity as (
                    select vl.account_id,
                        sum(case when vl.side = 'DEBIT' then vl.base_amount else 0 end) debit,
                        sum(case when vl.side = 'CREDIT' then vl.base_amount else 0 end) credit
                    from voucher_line vl
                    join voucher v on v.ledger_id = vl.ledger_id and v.id = vl.voucher_id
                    join accounting_period p on p.ledger_id = v.ledger_id and p.id = v.period_id
                    where v.ledger_id = ? and v.status = 'POSTED' and v.deleted_at is null
                      and left(p.period_code, 4) = left((select period_code from selected), 4)
                      and p.period_code <= (select period_code from selected)
                    group by vl.account_id
                ),
                account_totals as (
                    select a.id, a.code, a.name, a.normal_balance,
                        coalesce(ba.net, 0) + coalesce(pa.net, 0) opening_net,
                        coalesce(period.debit, 0) period_debit,
                        coalesce(period.credit, 0) period_credit,
                        coalesce(years.debit, 0) year_debit,
                        coalesce(years.credit, 0) year_credit
                    from ledger_account a
                    left join baseline_amount ba on ba.account_id = a.id
                    left join prior_activity pa on pa.account_id = a.id
                    left join period_activity period on period.account_id = a.id
                    left join year_activity years on years.account_id = a.id
                    where a.ledger_id = ?
                )
                select *, count(*) over() total_items
                from account_totals
                where opening_net <> 0 or period_debit <> 0 or period_credit <> 0
                    or year_debit <> 0 or year_credit <> 0
                order by code limit ? offset ?
                """, (rs, rowNum) -> {
            totalItems[0] = rs.getLong("total_items");
            BigDecimal opening = rs.getBigDecimal("opening_net");
            BigDecimal debit = rs.getBigDecimal("period_debit");
            BigDecimal credit = rs.getBigDecimal("period_credit");
            BigDecimal ending = opening.add(debit).subtract(credit);
            return new ReportResponses.GeneralLedgerAccount(
                    rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                    rs.getString("normal_balance"), direction(opening), opening.abs(), debit, credit,
                    rs.getBigDecimal("year_debit"), rs.getBigDecimal("year_credit"),
                    direction(ending), ending.abs());
        }, ledgerId, periodCode, ledgerId, ledgerId, ledgerId, ledgerId, ledgerId, pageSize, offset);
        return new ReportResponses.GeneralLedgerPage(
                periodCode, data, pagination(page, pageSize, totalItems[0]));
    }

    @Override
    public ReportResponses.SubLedgerPage subLedgerBook(
            UUID ledgerId, String periodCode, UUID accountId, int page, int pageSize) {
        String[] account = jdbc.queryForObject("""
                select code, name from ledger_account where ledger_id = ? and id = ?
                """, (rs, rowNum) -> new String[]{rs.getString("code"), rs.getString("name")},
                ledgerId, accountId);
        BigDecimal opening = openingBalance(ledgerId, periodCode, accountId);
        long[] totalItems = {0};
        long offset = (long) (page - 1) * pageSize;
        List<ReportResponses.SubLedgerEntry> data = jdbc.query("""
                with entries as (
                    select v.id voucher_id, v.voucher_number, v.voucher_date,
                        coalesce(vl.summary, v.summary, '') summary, vl.line_no, vl.id line_id,
                        case when vl.side = 'DEBIT' then vl.base_amount else 0 end debit,
                        case when vl.side = 'CREDIT' then vl.base_amount else 0 end credit,
                        case when vl.side = 'DEBIT' then vl.base_amount else -vl.base_amount end signed_amount
                    from voucher_line vl
                    join voucher v on v.ledger_id = vl.ledger_id and v.id = vl.voucher_id
                    join accounting_period p on p.ledger_id = v.ledger_id and p.id = v.period_id
                    where v.ledger_id = ? and p.period_code = ? and vl.account_id = ?
                      and v.status = 'POSTED' and v.deleted_at is null
                ),
                running as (
                    select *, count(*) over() total_items,
                        sum(signed_amount) over (
                            order by voucher_date, voucher_number, line_no, line_id) running_delta
                    from entries
                )
                select * from running
                order by voucher_date, voucher_number, line_no, line_id limit ? offset ?
                """, (rs, rowNum) -> {
            totalItems[0] = rs.getLong("total_items");
            BigDecimal balance = opening.add(rs.getBigDecimal("running_delta"));
            return new ReportResponses.SubLedgerEntry(
                    rs.getObject("voucher_id", UUID.class), rs.getString("voucher_number"),
                    rs.getObject("voucher_date", LocalDate.class), rs.getString("summary"),
                    rs.getBigDecimal("debit"), rs.getBigDecimal("credit"),
                    direction(balance), balance.abs());
        }, ledgerId, periodCode, accountId, pageSize, offset);
        BigDecimal[] totals = jdbc.queryForObject("""
                select coalesce(sum(case when vl.side = 'DEBIT' then vl.base_amount else 0 end), 0) debit,
                    coalesce(sum(case when vl.side = 'CREDIT' then vl.base_amount else 0 end), 0) credit
                from voucher_line vl
                join voucher v on v.ledger_id = vl.ledger_id and v.id = vl.voucher_id
                join accounting_period p on p.ledger_id = v.ledger_id and p.id = v.period_id
                where v.ledger_id = ? and p.period_code = ? and vl.account_id = ?
                  and v.status = 'POSTED' and v.deleted_at is null
                """, (rs, rowNum) -> new BigDecimal[]{rs.getBigDecimal("debit"), rs.getBigDecimal("credit")},
                ledgerId, periodCode, accountId);
        BigDecimal ending = opening.add(totals[0]).subtract(totals[1]);
        return new ReportResponses.SubLedgerPage(
                periodCode, accountId, account[0], account[1], direction(opening), opening.abs(), data,
                totals[0], totals[1], direction(ending), ending.abs(),
                pagination(page, pageSize, totalItems[0]));
    }

    private BigDecimal openingBalance(UUID ledgerId, String periodCode, UUID accountId) {
        BigDecimal result = jdbc.queryForObject("""
                with selected as (
                    select period_code from accounting_period where ledger_id = ? and period_code = ?
                ),
                baseline as (
                    select max(p.period_code) period_code
                    from opening_balance ob
                    join accounting_period p on p.ledger_id = ob.ledger_id and p.id = ob.period_id
                    where ob.ledger_id = ? and ob.account_id = ? and ob.confirmed
                      and p.period_code <= (select period_code from selected)
                )
                select
                    coalesce((select sum(ob.debit_base - ob.credit_base)
                        from opening_balance ob
                        join accounting_period p on p.ledger_id = ob.ledger_id and p.id = ob.period_id
                        where ob.ledger_id = ? and ob.account_id = ? and ob.confirmed
                          and p.period_code = (select period_code from baseline)), 0)
                    + coalesce((select sum(case when vl.side = 'DEBIT'
                            then vl.base_amount else -vl.base_amount end)
                        from voucher_line vl
                        join voucher v on v.ledger_id = vl.ledger_id and v.id = vl.voucher_id
                        join accounting_period p on p.ledger_id = v.ledger_id and p.id = v.period_id
                          where v.ledger_id = ? and vl.account_id = ?
                          and v.status = 'POSTED' and v.deleted_at is null
                          and p.period_code < (select period_code from selected)
                          and ((select period_code from baseline) is null
                            or p.period_code > (select period_code from baseline))), 0)
                """, BigDecimal.class, ledgerId, periodCode, ledgerId, accountId,
                ledgerId, accountId, ledgerId, accountId);
        return result == null ? BigDecimal.ZERO : result;
    }

    private ReportResponses.Pagination pagination(int page, int pageSize, long totalItems) {
        int totalPages = totalItems == 0 ? 0 : (int) ((totalItems + pageSize - 1) / pageSize);
        return new ReportResponses.Pagination(page, pageSize, totalItems, totalPages);
    }

    private String direction(BigDecimal signedBalance) {
        return signedBalance.signum() < 0 ? "CREDIT" : "DEBIT";
    }

    @Override
    public Set<String> formulaCategories(UUID ledgerId, String formulaCode, String field) {
        return Set.copyOf(jdbc.queryForList("""
                select jsonb_array_elements_text(formula_json -> cast(? as text))
                from report_formula_snapshot
                where ledger_id = ? and code = ?
                """, String.class, field, ledgerId, formulaCode));
    }

    @Override
    public String baseCurrency(UUID ledgerId) {
        return jdbc.queryForObject("select base_currency from ledger where id = ?", String.class, ledgerId);
    }

    private boolean useProjection(UUID ledgerId, String periodCode) {
        if (projection == null || "legacy".equalsIgnoreCase(readMode)) {
            return false;
        }
        BalanceProjectionService.ProjectionStatus status = projection.status(ledgerId, periodCode);
        boolean fresh = status.fresh(maxLag, OffsetDateTime.now());
        if (fresh) {
            BalanceReadMetadata.set("projection", status.projectedAt() == null
                    ? (status.lastEnqueuedAt() == null ? OffsetDateTime.now() : status.lastEnqueuedAt())
                    : status.projectedAt(), lagMs(status));
        }
        return fresh;
    }

    private void markFallback(UUID ledgerId, String periodCode) {
        OffsetDateTime now = OffsetDateTime.now();
        if (projection == null) {
            BalanceReadMetadata.set("live-fallback", now, 0);
            return;
        }
        BalanceProjectionService.ProjectionStatus status = projection.status(ledgerId, periodCode);
        BalanceReadMetadata.set("live-fallback", status.projectedAt() == null ? now : status.projectedAt(), lagMs(status));
    }

    private long lagMs(BalanceProjectionService.ProjectionStatus status) {
        return status.lastEnqueuedAt() == null ? 0
                : Math.max(0, Duration.between(status.lastEnqueuedAt(), OffsetDateTime.now()).toMillis());
    }
}
