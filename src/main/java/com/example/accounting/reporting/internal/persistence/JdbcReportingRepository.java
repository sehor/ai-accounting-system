package com.example.accounting.reporting.internal.persistence;

import com.example.accounting.reporting.ReportResponses;
import com.example.accounting.reporting.PeriodRange;
import com.example.accounting.shared.balance.BalanceProjectionService;
import com.example.accounting.reporting.BalanceReadMetadata;
import com.example.accounting.reporting.internal.port.BalanceProjectionRepository;
import com.example.accounting.reporting.internal.port.ReportingRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
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

    @Autowired
    public JdbcReportingRepository(JdbcTemplate jdbc, BalanceProjectionRepository projection,
                                   @Value("${accounting.balance.read-mode:legacy}") String readMode) {
        this.jdbc = jdbc;
        this.projection = projection;
        this.readMode = readMode;
    }

    /** Compatibility constructor for repository-focused tests. */
    public JdbcReportingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.projection = null;
        this.readMode = "legacy";
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
        return readTrialBalance(ledgerId, range, includeParents);
    }

    @Override
    public List<ReportResponses.TrialBalanceLine> incomeStatementTrialBalance(
            UUID ledgerId, PeriodRange range, boolean includeParents) {
        if (projection == null) {
            throw new IllegalStateException("Balance projection is not configured");
        }
        BalanceProjectionService.ProjectionStatus status = projection.status(ledgerId, range);
        if (!status.fresh()) {
            throw new IllegalStateException("Balance projection is not ready");
        }
        BalanceReadMetadata.set("projection", status.projectedAt() == null
                ? (status.lastEnqueuedAt() == null ? OffsetDateTime.now() : status.lastEnqueuedAt())
                : status.projectedAt(), lagMs(status));
        return projection.operatingTrialBalance(ledgerId, range, includeParents);
    }

    private List<ReportResponses.TrialBalanceLine> readTrialBalance(
            UUID ledgerId, PeriodRange range, boolean includeParents) {
        if (useProjection(ledgerId, range)) {
            return projection.trialBalance(ledgerId, range, includeParents);
        }
        markFallback(ledgerId, range);
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
                opening_source as (
                    select source.account_id, sum(source.debit) debit, sum(source.credit) credit
                    from (
                        select ob.account_id, ob.debit_base debit, ob.credit_base credit
                        from opening_balance ob
                        where ob.ledger_id = ? and ob.confirmed
                        union all
                        select vl.account_id,
                            case when vl.side = 'DEBIT' then vl.base_amount else 0 end debit,
                            case when vl.side = 'CREDIT' then vl.base_amount else 0 end credit
                        from voucher_line vl
                        join voucher v on v.ledger_id = vl.ledger_id and v.id = vl.voucher_id
                        join accounting_period p on p.ledger_id = v.ledger_id and p.id = v.period_id
                        where v.ledger_id = ? and v.status = 'POSTED' and v.deleted_at is null
                          and p.period_code < ?
                    ) source group by source.account_id
                ), movement_source as (
                    select vl.account_id,
                        sum(case when vl.side = 'DEBIT' then vl.base_amount else 0 end) debit,
                        sum(case when vl.side = 'CREDIT' then vl.base_amount else 0 end) credit
                    from voucher_line vl
                    join voucher v on v.ledger_id = vl.ledger_id and v.id = vl.voucher_id
                    join accounting_period p on p.ledger_id = v.ledger_id and p.id = v.period_id
                    where v.ledger_id = ? and v.status = 'POSTED' and v.deleted_at is null
                      and p.period_code between ? and ?
                    group by vl.account_id
                ), amounts as (
                    select path.account_id,
                        sum(coalesce(opening_source.debit, 0)) opening_debit,
                        sum(coalesce(opening_source.credit, 0)) opening_credit,
                        sum(coalesce(movement_source.debit, 0)) debit,
                        sum(coalesce(movement_source.credit, 0)) credit
                    from account_path path
                    left join opening_source on opening_source.account_id = path.source_id
                    left join movement_source on movement_source.account_id = path.source_id
                    group by path.account_id
                )
                select account.id, account.code, account.name, account.category,
                    coalesce(amounts.opening_debit, 0) opening_debit,
                    coalesce(amounts.opening_credit, 0) opening_credit,
                    coalesce(amounts.debit, 0) period_debit,
                    coalesce(amounts.credit, 0) period_credit,
                    coalesce(amounts.opening_debit, 0) + coalesce(amounts.debit, 0) closing_debit,
                    coalesce(amounts.opening_credit, 0) + coalesce(amounts.credit, 0) closing_credit
                from ledger_account account
                left join amounts on amounts.account_id = account.id
                where account.ledger_id = ?
                  and (? or not exists (
                      select 1 from ledger_account child
                      where child.ledger_id = account.ledger_id and child.parent_id = account.id))
                  and (coalesce(amounts.opening_debit, 0) <> 0
                    or coalesce(amounts.opening_credit, 0) <> 0
                    or coalesce(amounts.debit, 0) <> 0 or coalesce(amounts.credit, 0) <> 0)
                order by account.code
                """, (rs, row) -> trialBalanceLine(rs), ledgerId, ledgerId,
                ledgerId, ledgerId, range.periodFrom(), ledgerId, range.periodFrom(), range.periodTo(),
                ledgerId, includeParents);
    }

    @Override
    public boolean statutoryProjectionReady(UUID ledgerId, PeriodRange range) {
        return projection != null && projection.status(ledgerId, range).fresh();
    }

    @Override
    public List<ReportResponses.TrialBalanceLine> statutoryTrialBalance(
            UUID ledgerId, PeriodRange range, boolean includeParents) {
        if (projection == null) {
            throw new IllegalStateException("Balance projection is not configured");
        }
        BalanceProjectionService.ProjectionStatus status = projection.status(ledgerId, range);
        if (!status.fresh()) {
            throw new IllegalStateException("Balance projection is not ready");
        }
        BalanceReadMetadata.set("projection", status.projectedAt() == null
                ? (status.lastEnqueuedAt() == null ? OffsetDateTime.now() : status.lastEnqueuedAt())
                : status.projectedAt(), lagMs(status));
        return projection.trialBalance(ledgerId, range, includeParents);
    }

    @Override
    public List<ReportingRepository.StatutoryAccountAmount> statutoryAccountAmounts(
            UUID ledgerId, PeriodRange range, boolean operatingActivity) {
        if (projection == null) {
            throw new IllegalStateException("Balance projection is not configured");
        }
        BalanceProjectionService.ProjectionStatus status = projection.status(ledgerId, range);
        if (!status.fresh()) {
            throw new IllegalStateException("Balance projection is not ready");
        }
        BalanceReadMetadata.set("projection", status.projectedAt() == null
                ? (status.lastEnqueuedAt() == null ? OffsetDateTime.now() : status.lastEnqueuedAt())
                : status.projectedAt(), lagMs(status));
        List<ReportResponses.TrialBalanceLine> leafAmounts = operatingActivity
                ? projection.operatingTrialBalance(ledgerId, range, false)
                : projection.trialBalance(ledgerId, range, false);
        Map<UUID, String> keys = jdbc.query("""
                select id, standard_account_key
                from ledger_account account
                where ledger_id = ? and not exists (
                    select 1 from ledger_account child
                    where child.ledger_id = account.ledger_id and child.parent_id = account.id)
                """, rs -> {
            Map<UUID, String> result = new java.util.HashMap<>();
            while (rs.next()) {
                result.put(rs.getObject("id", UUID.class), rs.getString("standard_account_key"));
            }
            return result;
        }, ledgerId);
        return leafAmounts.stream().map(line -> new ReportingRepository.StatutoryAccountAmount(
                line.accountId(), line.code(), keys.get(line.accountId()),
                line.openingDebit(), line.openingCredit(), line.periodDebit(), line.periodCredit(),
                line.closingDebit(), line.closingCredit())).toList();
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
    public boolean periodsExist(UUID ledgerId, PeriodRange range) {
        Integer count = jdbc.queryForObject("""
                select count(*) from accounting_period
                where ledger_id = ? and period_code in (?, ?)
                """, Integer.class, ledgerId, range.periodFrom(), range.periodTo());
        return count != null && count == (range.singlePeriod() ? 1 : 2);
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
        return generalLedgerBook(ledgerId, PeriodRange.single(periodCode), page, pageSize);
    }

    @Override
    public ReportResponses.GeneralLedgerPage generalLedgerBook(
            UUID ledgerId, PeriodRange range, int page, int pageSize) {
        List<ReportResponses.TrialBalanceLine> balances = trialBalance(ledgerId, range, true);
        String yearStart = jdbc.query("""
                select min(period_code) from accounting_period
                where ledger_id = ? and period_code between ? and ?
                """, rs -> rs.next() ? rs.getString(1) : null,
                ledgerId, range.periodTo().substring(0, 4) + "-01", range.periodTo());
        Map<UUID, ReportResponses.TrialBalanceLine> yearBalances = yearStart == null ? Map.of()
                : trialBalance(ledgerId, new PeriodRange(yearStart, range.periodTo()), true).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                ReportResponses.TrialBalanceLine::accountId, line -> line));
        Map<UUID, String> normalBalances = jdbc.query("""
                select id, normal_balance from ledger_account where ledger_id = ?
                """, rs -> {
            Map<UUID, String> values = new java.util.HashMap<>();
            while (rs.next()) {
                values.put(rs.getObject("id", UUID.class), rs.getString("normal_balance"));
            }
            return values;
        }, ledgerId);
        long offset = (long) (page - 1) * pageSize;
        int fromIndex = (int) Math.min(offset, balances.size());
        int toIndex = Math.min(fromIndex + pageSize, balances.size());
        List<ReportResponses.GeneralLedgerAccount> data = balances.subList(fromIndex, toIndex).stream()
                .map(line -> {
                    String normalBalance = normalBalances.get(line.accountId());
                    BalancePosition openingPosition = position(
                            normalBalance, line.openingDebit(), line.openingCredit());
                    BalancePosition closingPosition = position(
                            normalBalance, line.closingDebit(), line.closingCredit());
                    ReportResponses.TrialBalanceLine year = yearBalances.get(line.accountId());
                    return new ReportResponses.GeneralLedgerAccount(
                            line.accountId(), line.code(), line.name(), normalBalance,
                            openingPosition.direction(), openingPosition.amount(),
                            line.periodDebit(), line.periodCredit(),
                            year == null ? BigDecimal.ZERO : year.periodDebit(),
                            year == null ? BigDecimal.ZERO : year.periodCredit(), closingPosition.direction(),
                            closingPosition.amount());
                }).toList();
        return new ReportResponses.GeneralLedgerPage(
                range.periodFrom(), range.periodTo(), range.periodCode(), data,
                pagination(page, pageSize, balances.size()));
    }

    @Override
    public ReportResponses.SubLedgerPage subLedgerBook(
            UUID ledgerId, String periodCode, UUID accountId, int page, int pageSize) {
        return subLedgerBook(ledgerId, PeriodRange.single(periodCode), accountId, page, pageSize);
    }

    @Override
    public ReportResponses.SubLedgerPage subLedgerBook(
            UUID ledgerId, PeriodRange range, UUID accountId, int page, int pageSize) {
        String[] account = jdbc.queryForObject("""
                select code, name, normal_balance from ledger_account where ledger_id = ? and id = ?
                """, (rs, rowNum) -> new String[]{
                        rs.getString("code"), rs.getString("name"), rs.getString("normal_balance")},
                ledgerId, accountId);
        boolean projectionReady = useProjection(ledgerId, range);
        BalancePosition openingPosition;
        if (projectionReady) {
            openingPosition = projectedOpeningPosition(ledgerId, range.periodFrom(), accountId, account[2]);
        } else {
            markFallback(ledgerId, range);
            openingPosition = fallbackOpeningPosition(ledgerId, range.periodFrom(), accountId, account[2]);
        }
        long[] totalItems = {0};
        long offset = (long) (page - 1) * pageSize;
        List<ReportResponses.SubLedgerEntry> data = jdbc.query("""
                with recursive account_scope as (
                    select id from ledger_account where ledger_id = ? and id = ?
                    union all
                    select child.id from ledger_account child
                    join account_scope parent on parent.id = child.parent_id
                    where child.ledger_id = ?
                ), entries as (
                    select v.id voucher_id, v.voucher_number, v.voucher_date,
                        vl.account_id posting_account_id, account.code posting_account_code,
                        account.name posting_account_name,
                        coalesce(vl.summary, v.summary, '') summary, vl.line_no, vl.id line_id,
                        case when vl.side = 'DEBIT' then vl.base_amount else 0 end debit,
                        case when vl.side = 'CREDIT' then vl.base_amount else 0 end credit
                    from voucher_line vl
                    join voucher v on v.ledger_id = vl.ledger_id and v.id = vl.voucher_id
                    join accounting_period p on p.ledger_id = v.ledger_id and p.id = v.period_id
                    join ledger_account account on account.ledger_id = vl.ledger_id and account.id = vl.account_id
                    where v.ledger_id = ? and p.period_code between ? and ?
                      and vl.account_id in (select id from account_scope)
                      and v.status = 'POSTED' and v.deleted_at is null
                ), running as (
                    select *, count(*) over() total_items,
                        sum(debit) over (
                            order by voucher_date, voucher_number, line_no, line_id) running_debit,
                        sum(credit) over (
                            order by voucher_date, voucher_number, line_no, line_id) running_credit
                    from entries
                )
                select * from running
                order by voucher_date, voucher_number, line_no, line_id limit ? offset ?
                """, (rs, rowNum) -> {
            totalItems[0] = rs.getLong("total_items");
            BalancePosition balance = position(account[2],
                    openingPosition.debit().add(rs.getBigDecimal("running_debit")),
                    openingPosition.credit().add(rs.getBigDecimal("running_credit")));
            return new ReportResponses.SubLedgerEntry(
                    rs.getObject("voucher_id", UUID.class), rs.getString("voucher_number"),
                    rs.getObject("voucher_date", LocalDate.class),
                    rs.getObject("posting_account_id", UUID.class), rs.getString("posting_account_code"),
                    rs.getString("posting_account_name"), rs.getString("summary"),
                    rs.getBigDecimal("debit"), rs.getBigDecimal("credit"),
                    balance.direction(), balance.amount());
        }, ledgerId, accountId, ledgerId, ledgerId, range.periodFrom(), range.periodTo(), pageSize, offset);
        BigDecimal[] totals = jdbc.queryForObject("""
                with recursive account_scope as (
                    select id from ledger_account where ledger_id = ? and id = ?
                    union all
                    select child.id from ledger_account child
                    join account_scope parent on parent.id = child.parent_id
                    where child.ledger_id = ?
                )
                select coalesce(sum(case when vl.side = 'DEBIT' then vl.base_amount else 0 end), 0) debit,
                    coalesce(sum(case when vl.side = 'CREDIT' then vl.base_amount else 0 end), 0) credit
                from voucher_line vl
                join voucher v on v.ledger_id = vl.ledger_id and v.id = vl.voucher_id
                join accounting_period p on p.ledger_id = v.ledger_id and p.id = v.period_id
                where v.ledger_id = ? and p.period_code between ? and ?
                  and vl.account_id in (select id from account_scope)
                  and v.status = 'POSTED' and v.deleted_at is null
                """, (rs, rowNum) -> new BigDecimal[]{rs.getBigDecimal("debit"), rs.getBigDecimal("credit")},
                ledgerId, accountId, ledgerId, ledgerId, range.periodFrom(), range.periodTo());
        BalancePosition ending = position(account[2],
                openingPosition.debit().add(totals[0]), openingPosition.credit().add(totals[1]));
        return new ReportResponses.SubLedgerPage(
                range.periodFrom(), range.periodTo(), range.periodCode(),
                accountId, account[0], account[1], openingPosition.direction(), openingPosition.amount(), data,
                totals[0], totals[1], ending.direction(), ending.amount(),
                pagination(page, pageSize, totalItems[0]));
    }

    private BalancePosition projectedOpeningPosition(
            UUID ledgerId, String periodCode, UUID accountId, String normalBalance) {
        return jdbc.query("""
                select b.opening_debit_base, b.opening_credit_base
                from account_period_balance b
                join accounting_period p on p.ledger_id = b.ledger_id and p.id = b.period_id
                where b.ledger_id = ? and p.period_code = ? and b.account_id = ?
                """, rs -> rs.next()
                        ? position(normalBalance, rs.getBigDecimal("opening_debit_base"),
                                rs.getBigDecimal("opening_credit_base"))
                        : position(normalBalance, BigDecimal.ZERO, BigDecimal.ZERO),
                ledgerId, periodCode, accountId);
    }

    private BalancePosition fallbackOpeningPosition(
            UUID ledgerId, String periodCode, UUID accountId, String normalBalance) {
        return jdbc.queryForObject("""
                with recursive account_scope as (
                    select id from ledger_account where ledger_id = ? and id = ?
                    union all
                    select child.id from ledger_account child
                    join account_scope parent on parent.id = child.parent_id
                    where child.ledger_id = ?
                ), amounts as (
                    select ob.debit_base debit, ob.credit_base credit
                    from opening_balance ob
                    where ob.ledger_id = ? and ob.confirmed
                      and ob.account_id in (select id from account_scope)
                    union all
                    select case when vl.side = 'DEBIT' then vl.base_amount else 0 end debit,
                        case when vl.side = 'CREDIT' then vl.base_amount else 0 end credit
                    from voucher_line vl
                    join voucher v on v.ledger_id = vl.ledger_id and v.id = vl.voucher_id
                    join accounting_period p on p.ledger_id = v.ledger_id and p.id = v.period_id
                    where v.ledger_id = ? and v.status = 'POSTED' and v.deleted_at is null
                      and p.period_code < ? and vl.account_id in (select id from account_scope)
                )
                select coalesce(sum(debit), 0) debit, coalesce(sum(credit), 0) credit from amounts
                """, (rs, rowNum) -> position(
                        normalBalance, rs.getBigDecimal("debit"), rs.getBigDecimal("credit")),
                ledgerId, accountId, ledgerId, ledgerId, ledgerId, periodCode);
    }

    private ReportResponses.Pagination pagination(int page, int pageSize, long totalItems) {
        int totalPages = totalItems == 0 ? 0 : (int) ((totalItems + pageSize - 1) / pageSize);
        return new ReportResponses.Pagination(page, pageSize, totalItems, totalPages);
    }

    private BalancePosition position(String normalBalance, BigDecimal debit, BigDecimal credit) {
        return new BalancePosition(normalBalance, normalAmount(normalBalance, debit, credit), debit, credit);
    }

    private BigDecimal normalAmount(String normalBalance, BigDecimal debit, BigDecimal credit) {
        return "DEBIT".equals(normalBalance) ? debit.subtract(credit) : credit.subtract(debit);
    }

    private record BalancePosition(
            String direction, BigDecimal amount, BigDecimal debit, BigDecimal credit) {
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

    @Override
    public ReportResponses.LedgerProfile ledgerProfile(UUID ledgerId) {
        return jdbc.queryForObject("""
                select accounting_standard_code, accounting_standard_version, base_currency
                from ledger where id = ?
                """, (rs, rowNum) -> new ReportResponses.LedgerProfile(
                rs.getString("accounting_standard_code"),
                rs.getString("accounting_standard_version"),
                rs.getString("base_currency")), ledgerId);
    }

    @Override
    public String firstPeriodOfYear(UUID ledgerId, String periodCode) {
        String year = periodCode.substring(0, 4);
        return jdbc.query("""
                select min(period_code) from accounting_period
                where ledger_id = ? and period_code like ? and period_code <= ?
                """, rs -> rs.next() ? rs.getString(1) : null,
                ledgerId, year + "-%", periodCode);
    }

    @Override
    public boolean leafAccount(UUID ledgerId, UUID accountId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (
                    select 1 from ledger_account account
                    where account.ledger_id = ? and account.id = ?
                      and not exists (select 1 from ledger_account child
                                      where child.ledger_id = account.ledger_id and child.parent_id = account.id))
                """, Boolean.class, ledgerId, accountId));
    }

    @Override
    public boolean dimensionProjectionReady(UUID ledgerId, PeriodRange range) {
        if (projection == null) {
            return false;
        }
        BalanceProjectionService.ProjectionStatus status = projection.status(ledgerId, range);
        if (status.fresh()) {
            markProjectionRead(status);
        }
        return status.fresh();
    }

    @Override
    public boolean dimensionTypeExists(UUID ledgerId, UUID dimensionTypeId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (
                    select 1 from dimension_type where ledger_id = ? and id = ?)
                """, Boolean.class, ledgerId, dimensionTypeId));
    }

    @Override
    public DimensionTypeInfo dimensionType(UUID ledgerId, UUID dimensionTypeId) {
        return jdbc.query("""
                select id, code, name from dimension_type where ledger_id = ? and id = ?
                """, rs -> rs.next() ? new DimensionTypeInfo(rs.getObject("id", UUID.class),
                rs.getString("code"), rs.getString("name")) : null, ledgerId, dimensionTypeId);
    }

    @Override
    public boolean dimensionValueExists(UUID ledgerId, UUID dimensionTypeId, UUID dimensionValueId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (
                    select 1 from dimension_value
                    where ledger_id = ? and dimension_type_id = ? and id = ?)
                """, Boolean.class, ledgerId, dimensionTypeId, dimensionValueId));
    }

    @Override
    public List<DimensionBalanceRow> dimensionBalances(
            UUID ledgerId, PeriodRange range, List<String> accountCodes, String currency,
            List<DimensionLedgerFilter> dimensionFilters, boolean closingPeriodOnly, int limit) {
        List<Object> parameters = new ArrayList<>(List.of(ledgerId, range.periodFrom(), range.periodTo()));
        String closingPeriodClause = closingPeriodOnly ? " and period.period_code = ?" : "";
        if (closingPeriodOnly) {
            parameters.add(range.periodTo());
        }
        String accountClause = accountCodes.isEmpty() ? "" : " and account.code in ("
                + String.join(",", java.util.Collections.nCopies(accountCodes.size(), "?")) + ")";
        parameters.addAll(accountCodes);
        String currencyClause = currency == null ? "" : " and balance.currency = ?";
        if (currency != null) {
            parameters.add(currency);
        }
        String filterClause = dimensionFilterClause(
                dimensionFilters, "balance.ledger_id", "balance.dimension_combination_id", parameters);
        parameters.add(limit);
        Map<DimensionBalanceKey, MutableDimensionBalanceRow> rows = new LinkedHashMap<>();
        jdbc.query("""
                with balances as materialized (
                    select balance.*, period.period_code, account.code account_code
                    from dimension_period_balance balance
                    join accounting_period period
                      on period.ledger_id = balance.ledger_id and period.id = balance.period_id
                    join ledger_account account
                      on account.ledger_id = balance.ledger_id and account.id = balance.account_id
                    where balance.ledger_id = ? and period.period_code between ? and ?
                """ + closingPeriodClause + accountClause + currencyClause + filterClause + """
                    order by period.period_code, balance.account_id,
                        balance.dimension_combination_id, balance.currency
                    limit ?
                )
                select balance.period_code, balance.account_id, balance.account_code,
                    balance.dimension_combination_id, combination.dimension_key, balance.currency,
                    balance.period_debit_base, balance.period_credit_base,
                    balance.closing_debit_base, balance.closing_credit_base,
                    member.dimension_type_id, member.dimension_value_id,
                    member.dimension_type_code, member.dimension_type_name,
                    member.dimension_value_code, member.dimension_value_name
                from balances balance
                join dimension_combination combination
                  on combination.ledger_id = balance.ledger_id and combination.id = balance.dimension_combination_id
                left join dimension_combination_member member
                  on member.ledger_id = balance.ledger_id and member.combination_id = balance.dimension_combination_id
                order by balance.period_code, balance.account_code,
                    balance.dimension_combination_id, balance.currency,
                    member.dimension_type_id
                """, (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> {
            while (rs.next()) {
                DimensionBalanceKey key = new DimensionBalanceKey(rs.getString("period_code"),
                        rs.getObject("account_id", UUID.class), rs.getObject("dimension_combination_id", UUID.class),
                        rs.getString("currency"));
                MutableDimensionBalanceRow row = rows.get(key);
                if (row == null) {
                    row = new MutableDimensionBalanceRow(rs.getString("period_code"),
                            rs.getObject("account_id", UUID.class), rs.getString("account_code"),
                            rs.getObject("dimension_combination_id", UUID.class), rs.getString("dimension_key"),
                            rs.getString("currency"), rs.getBigDecimal("period_debit_base"),
                            rs.getBigDecimal("period_credit_base"), rs.getBigDecimal("closing_debit_base"),
                            rs.getBigDecimal("closing_credit_base"));
                    rows.put(key, row);
                }
                UUID typeId = rs.getObject("dimension_type_id", UUID.class);
                if (typeId != null) {
                    row.dimensions.add(new ReportResponses.FinanceQueryDimension(typeId,
                            rs.getObject("dimension_value_id", UUID.class), rs.getString("dimension_type_code"),
                            rs.getString("dimension_type_name"), rs.getString("dimension_value_code"),
                            rs.getString("dimension_value_name")));
                }
            }
            return null;
        }, parameters.toArray());
        return rows.values().stream().map(MutableDimensionBalanceRow::freeze).toList();
    }

    @Override
    public List<DimensionLedgerBalanceRow> dimensionLedgerBalances(
            UUID ledgerId, PeriodRange range, UUID accountId, String currency,
            List<DimensionLedgerFilter> dimensionFilters) {
        List<Object> parameters = new ArrayList<>(List.of(ledgerId, accountId, range.periodFrom(), range.periodTo()));
        String currencyClause = currency == null ? "" : " and balance.currency = ?";
        if (currency != null) {
            parameters.add(currency);
        }
        String filterClause = dimensionFilterClause(
                dimensionFilters, "balance.ledger_id", "balance.dimension_combination_id", parameters);
        for (int i = 0; i < 2; i++) {
            parameters.add(range.periodFrom());
        }
        for (int i = 0; i < 2; i++) {
            parameters.add(range.periodTo());
        }
        for (int i = 0; i < 2; i++) {
            parameters.add(range.periodFrom());
        }
        for (int i = 0; i < 2; i++) {
            parameters.add(range.periodTo());
        }
        parameters.add(ledgerId);
        Map<DimensionLedgerBalanceKey, MutableDimensionLedgerBalance> balances = new LinkedHashMap<>();
        jdbc.query("""
                with scoped as (
                    select balance.*, period.period_code
                    from dimension_period_balance balance
                    join accounting_period period
                      on period.ledger_id = balance.ledger_id and period.id = balance.period_id
                    where balance.ledger_id = ? and balance.account_id = ?
                      and period.period_code between ? and ?
                """ + currencyClause + filterClause + """
                ), aggregated as (
                    select dimension_combination_id, currency,
                        coalesce(max(opening_debit_original) filter (where period_code = ?), 0) opening_debit_original,
                        coalesce(max(opening_credit_original) filter (where period_code = ?), 0) opening_credit_original,
                        coalesce(sum(period_debit_original), 0) period_debit_original,
                        coalesce(sum(period_credit_original), 0) period_credit_original,
                        coalesce(max(closing_debit_original) filter (where period_code = ?), 0) closing_debit_original,
                        coalesce(max(closing_credit_original) filter (where period_code = ?), 0) closing_credit_original,
                        coalesce(max(opening_debit_base) filter (where period_code = ?), 0) opening_debit_base,
                        coalesce(max(opening_credit_base) filter (where period_code = ?), 0) opening_credit_base,
                        coalesce(sum(period_debit_base), 0) period_debit_base,
                        coalesce(sum(period_credit_base), 0) period_credit_base,
                        coalesce(max(closing_debit_base) filter (where period_code = ?), 0) closing_debit_base,
                        coalesce(max(closing_credit_base) filter (where period_code = ?), 0) closing_credit_base
                    from scoped
                    group by dimension_combination_id, currency
                )
                select aggregate.*, combination.dimension_key, combination.kind,
                    member.dimension_type_id, member.dimension_value_id,
                    member.dimension_type_code, member.dimension_type_name,
                    member.dimension_value_code, member.dimension_value_name
                from aggregated aggregate
                join dimension_combination combination
                  on combination.ledger_id = ? and combination.id = aggregate.dimension_combination_id
                left join dimension_combination_member member
                  on member.ledger_id = combination.ledger_id and member.combination_id = combination.id
                order by aggregate.currency, aggregate.dimension_combination_id, member.dimension_type_id
                """, (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> {
            while (rs.next()) {
                DimensionLedgerBalanceKey key = new DimensionLedgerBalanceKey(
                        rs.getObject("dimension_combination_id", UUID.class), rs.getString("currency"));
                MutableDimensionLedgerBalance balance = balances.get(key);
                if (balance == null) {
                    balance = new MutableDimensionLedgerBalance(key.combinationId(), rs.getString("dimension_key"),
                            rs.getString("kind"), key.currency(), rs.getBigDecimal("opening_debit_original"),
                            rs.getBigDecimal("opening_credit_original"), rs.getBigDecimal("period_debit_original"),
                            rs.getBigDecimal("period_credit_original"), rs.getBigDecimal("closing_debit_original"),
                            rs.getBigDecimal("closing_credit_original"), rs.getBigDecimal("opening_debit_base"),
                            rs.getBigDecimal("opening_credit_base"), rs.getBigDecimal("period_debit_base"),
                            rs.getBigDecimal("period_credit_base"), rs.getBigDecimal("closing_debit_base"),
                            rs.getBigDecimal("closing_credit_base"));
                    balances.put(key, balance);
                }
                addDimension(rs, balance.dimensions);
            }
            return null;
        }, parameters.toArray());
        return balances.values().stream().map(MutableDimensionLedgerBalance::freeze).toList();
    }

    @Override
    public DimensionLedgerEntryPage dimensionLedgerEntries(
            UUID ledgerId, PeriodRange range, UUID accountId, String currency,
            List<DimensionLedgerFilter> dimensionFilters, int page, int pageSize) {
        List<Object> parameters = new ArrayList<>(List.of(ledgerId, accountId, range.periodFrom(), range.periodTo()));
        String currencyClause = currency == null ? "" : " and line.currency = ?";
        if (currency != null) {
            parameters.add(currency);
        }
        String filterClause = dimensionFilterClause(
                dimensionFilters, "combination.ledger_id", "combination.id", parameters);
        parameters.add(ledgerId);
        parameters.add(accountId);
        parameters.add(range.periodFrom());
        parameters.add(pageSize);
        parameters.add((long) (page - 1) * pageSize);
        Map<UUID, MutableDimensionLedgerEntry> entries = new LinkedHashMap<>();
        long[] totalItems = {0};
        jdbc.query("""
                with facts as (
                    select voucher.id voucher_id, voucher.voucher_number, voucher.voucher_date,
                        line.line_no, line.id line_id, account.id account_id, account.code account_code,
                        account.name account_name, combination.id combination_id, combination.dimension_key,
                        combination.kind combination_kind, line.currency, line.side,
                        case when line.side = 'DEBIT' then line.original_amount else 0 end original_debit,
                        case when line.side = 'CREDIT' then line.original_amount else 0 end original_credit,
                        case when line.side = 'DEBIT' then line.base_amount else 0 end base_debit,
                        case when line.side = 'CREDIT' then line.base_amount else 0 end base_credit
                    from voucher_line line
                    join voucher on voucher.ledger_id = line.ledger_id and voucher.id = line.voucher_id
                    join accounting_period period on period.ledger_id = voucher.ledger_id and period.id = voucher.period_id
                    join ledger_account account on account.ledger_id = line.ledger_id and account.id = line.account_id
                    left join lateral (
                        select fallback.id
                        from dimension_combination fallback
                        where line.dimension_combination_id is null and fallback.ledger_id = line.ledger_id
                          and fallback.canonical_key = 'v1;' || coalesce((
                              select string_agg(member.dimension_type_id::text || '=' || member.dimension_value_id::text,
                                  ';' order by member.dimension_type_id::text) || ';'
                              from voucher_line_dimension member
                              where member.ledger_id = line.ledger_id and member.voucher_line_id = line.id
                          ), '')
                    ) fallback on true
                    join dimension_combination combination
                      on combination.ledger_id = line.ledger_id
                     and combination.id = coalesce(line.dimension_combination_id, fallback.id)
                    where voucher.ledger_id = ? and line.account_id = ?
                      and period.period_code between ? and ?
                      and voucher.status = 'POSTED' and voucher.deleted_at is null
                """ + currencyClause + filterClause + """
                ), openings as (
                    select balance.dimension_combination_id, balance.currency,
                        balance.opening_debit_original, balance.opening_credit_original,
                        balance.opening_debit_base, balance.opening_credit_base
                    from dimension_period_balance balance
                    join accounting_period period
                      on period.ledger_id = balance.ledger_id and period.id = balance.period_id
                    where balance.ledger_id = ? and balance.account_id = ? and period.period_code = ?
                ), counted as (
                    select count(*) total_items from facts
                ), running as (
                    select facts.*,
                        coalesce(openings.opening_debit_original, 0) + sum(original_debit)
                            over (partition by facts.combination_id, facts.currency
                            order by voucher_date, voucher_number, line_no, line_id) running_original_debit,
                        coalesce(openings.opening_credit_original, 0) + sum(original_credit)
                            over (partition by facts.combination_id, facts.currency
                            order by voucher_date, voucher_number, line_no, line_id) running_original_credit,
                        coalesce(openings.opening_debit_base, 0) + sum(base_debit)
                            over (partition by facts.combination_id, facts.currency
                            order by voucher_date, voucher_number, line_no, line_id) running_base_debit,
                        coalesce(openings.opening_credit_base, 0) + sum(base_credit)
                            over (partition by facts.combination_id, facts.currency
                            order by voucher_date, voucher_number, line_no, line_id) running_base_credit
                    from facts
                    left join openings on openings.dimension_combination_id = facts.combination_id
                        and openings.currency = facts.currency
                ), paged as (
                    select * from running
                    order by voucher_date, voucher_number, line_no, line_id
                    limit ? offset ?
                )
                select paged.*, counted.total_items,
                    member.dimension_type_id, member.dimension_value_id,
                    member.dimension_type_code, member.dimension_type_name,
                    member.dimension_value_code, member.dimension_value_name
                from counted
                left join paged on true
                left join dimension_combination_member member
                  on member.ledger_id = ? and member.combination_id = paged.combination_id
                order by voucher_date, voucher_number, line_no, line_id, member.dimension_type_id
                """, (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> {
            while (rs.next()) {
                totalItems[0] = rs.getLong("total_items");
                UUID lineId = rs.getObject("line_id", UUID.class);
                if (lineId == null) {
                    continue;
                }
                MutableDimensionLedgerEntry entry = entries.get(lineId);
                if (entry == null) {
                    entry = new MutableDimensionLedgerEntry(rs.getObject("voucher_id", UUID.class),
                            rs.getString("voucher_number"), rs.getObject("voucher_date", LocalDate.class),
                            rs.getInt("line_no"), lineId, rs.getObject("account_id", UUID.class),
                            rs.getString("account_code"), rs.getString("account_name"),
                            rs.getObject("combination_id", UUID.class), rs.getString("dimension_key"),
                            rs.getString("combination_kind"), rs.getString("currency"),
                            rs.getString("side"), rs.getBigDecimal("original_debit"),
                            rs.getBigDecimal("original_credit"), rs.getBigDecimal("base_debit"),
                            rs.getBigDecimal("base_credit"), rs.getBigDecimal("running_original_debit"),
                            rs.getBigDecimal("running_original_credit"), rs.getBigDecimal("running_base_debit"),
                            rs.getBigDecimal("running_base_credit"));
                    entries.put(lineId, entry);
                }
                addDimension(rs, entry.dimensions);
            }
            return null;
        }, append(parameters, ledgerId));
        return new DimensionLedgerEntryPage(entries.values().stream().map(MutableDimensionLedgerEntry::freeze).toList(),
                totalItems[0]);
    }

    private String dimensionFilterClause(List<DimensionLedgerFilter> filters, String ledgerIdExpression,
                                         String combinationIdExpression, List<Object> parameters) {
        StringBuilder clause = new StringBuilder();
        for (DimensionLedgerFilter filter : filters) {
            clause.append(" and exists (select 1 from dimension_combination_member filter_member")
                    .append(" where filter_member.ledger_id = ").append(ledgerIdExpression)
                    .append(" and filter_member.combination_id = ").append(combinationIdExpression)
                    .append(" and filter_member.dimension_type_id = ? and filter_member.dimension_value_id = ?)");
            parameters.add(filter.dimensionTypeId());
            parameters.add(filter.dimensionValueId());
        }
        return clause.toString();
    }

    private Object[] append(List<Object> parameters, Object value) {
        List<Object> result = new ArrayList<>(parameters);
        result.add(value);
        return result.toArray();
    }

    private void addDimension(java.sql.ResultSet rs, List<ReportResponses.FinanceQueryDimension> dimensions)
            throws java.sql.SQLException {
        UUID typeId = rs.getObject("dimension_type_id", UUID.class);
        if (typeId != null) {
            dimensions.add(new ReportResponses.FinanceQueryDimension(typeId,
                    rs.getObject("dimension_value_id", UUID.class), rs.getString("dimension_type_code"),
                    rs.getString("dimension_type_name"), rs.getString("dimension_value_code"),
                    rs.getString("dimension_value_name")));
        }
    }

    private record DimensionLedgerBalanceKey(UUID combinationId, String currency) {
    }

    private static final class MutableDimensionLedgerBalance {
        private final UUID combinationId;
        private final String dimensionKey;
        private final String kind;
        private final String currency;
        private final BigDecimal openingDebitOriginal;
        private final BigDecimal openingCreditOriginal;
        private final BigDecimal periodDebitOriginal;
        private final BigDecimal periodCreditOriginal;
        private final BigDecimal closingDebitOriginal;
        private final BigDecimal closingCreditOriginal;
        private final BigDecimal openingDebitBase;
        private final BigDecimal openingCreditBase;
        private final BigDecimal periodDebitBase;
        private final BigDecimal periodCreditBase;
        private final BigDecimal closingDebitBase;
        private final BigDecimal closingCreditBase;
        private final List<ReportResponses.FinanceQueryDimension> dimensions = new ArrayList<>();

        private MutableDimensionLedgerBalance(UUID combinationId, String dimensionKey, String kind, String currency,
                                              BigDecimal openingDebitOriginal, BigDecimal openingCreditOriginal,
                                              BigDecimal periodDebitOriginal, BigDecimal periodCreditOriginal,
                                              BigDecimal closingDebitOriginal, BigDecimal closingCreditOriginal,
                                              BigDecimal openingDebitBase, BigDecimal openingCreditBase,
                                              BigDecimal periodDebitBase, BigDecimal periodCreditBase,
                                              BigDecimal closingDebitBase, BigDecimal closingCreditBase) {
            this.combinationId = combinationId;
            this.dimensionKey = dimensionKey;
            this.kind = kind;
            this.currency = currency;
            this.openingDebitOriginal = openingDebitOriginal;
            this.openingCreditOriginal = openingCreditOriginal;
            this.periodDebitOriginal = periodDebitOriginal;
            this.periodCreditOriginal = periodCreditOriginal;
            this.closingDebitOriginal = closingDebitOriginal;
            this.closingCreditOriginal = closingCreditOriginal;
            this.openingDebitBase = openingDebitBase;
            this.openingCreditBase = openingCreditBase;
            this.periodDebitBase = periodDebitBase;
            this.periodCreditBase = periodCreditBase;
            this.closingDebitBase = closingDebitBase;
            this.closingCreditBase = closingCreditBase;
        }

        private DimensionLedgerBalanceRow freeze() {
            return new DimensionLedgerBalanceRow(combinationId, dimensionKey, kind, currency,
                    openingDebitOriginal, openingCreditOriginal, periodDebitOriginal, periodCreditOriginal,
                    closingDebitOriginal, closingCreditOriginal, openingDebitBase, openingCreditBase,
                    periodDebitBase, periodCreditBase, closingDebitBase, closingCreditBase, dimensions);
        }
    }

    private static final class MutableDimensionLedgerEntry {
        private final UUID voucherId;
        private final String voucherNumber;
        private final LocalDate voucherDate;
        private final int lineNo;
        private final UUID lineId;
        private final UUID accountId;
        private final String accountCode;
        private final String accountName;
        private final UUID combinationId;
        private final String dimensionKey;
        private final String combinationKind;
        private final String currency;
        private final String side;
        private final BigDecimal originalDebit;
        private final BigDecimal originalCredit;
        private final BigDecimal baseDebit;
        private final BigDecimal baseCredit;
        private final BigDecimal runningOriginalDebit;
        private final BigDecimal runningOriginalCredit;
        private final BigDecimal runningBaseDebit;
        private final BigDecimal runningBaseCredit;
        private final List<ReportResponses.FinanceQueryDimension> dimensions = new ArrayList<>();

        private MutableDimensionLedgerEntry(UUID voucherId, String voucherNumber, LocalDate voucherDate, int lineNo,
                                            UUID lineId, UUID accountId, String accountCode, String accountName,
                                            UUID combinationId, String dimensionKey, String combinationKind,
                                            String currency, String side, BigDecimal originalDebit,
                                            BigDecimal originalCredit, BigDecimal baseDebit, BigDecimal baseCredit,
                                            BigDecimal runningOriginalDebit, BigDecimal runningOriginalCredit,
                                            BigDecimal runningBaseDebit, BigDecimal runningBaseCredit) {
            this.voucherId = voucherId;
            this.voucherNumber = voucherNumber;
            this.voucherDate = voucherDate;
            this.lineNo = lineNo;
            this.lineId = lineId;
            this.accountId = accountId;
            this.accountCode = accountCode;
            this.accountName = accountName;
            this.combinationId = combinationId;
            this.dimensionKey = dimensionKey;
            this.combinationKind = combinationKind;
            this.currency = currency;
            this.side = side;
            this.originalDebit = originalDebit;
            this.originalCredit = originalCredit;
            this.baseDebit = baseDebit;
            this.baseCredit = baseCredit;
            this.runningOriginalDebit = runningOriginalDebit;
            this.runningOriginalCredit = runningOriginalCredit;
            this.runningBaseDebit = runningBaseDebit;
            this.runningBaseCredit = runningBaseCredit;
        }

        private DimensionLedgerEntryRow freeze() {
            return new DimensionLedgerEntryRow(voucherId, voucherNumber, voucherDate, lineNo, lineId, accountId,
                    accountCode, accountName, combinationId, dimensionKey, combinationKind, currency, side,
                    originalDebit, originalCredit,
                    baseDebit, baseCredit, runningOriginalDebit, runningOriginalCredit, runningBaseDebit,
                    runningBaseCredit, dimensions);
        }
    }

    private boolean useProjection(UUID ledgerId, String periodCode) {
        if (projection == null || "legacy".equalsIgnoreCase(readMode)) {
            return false;
        }
        BalanceProjectionService.ProjectionStatus status = projection.status(ledgerId, periodCode);
        boolean fresh = status.fresh();
        if (fresh) {
            BalanceReadMetadata.set("projection", status.projectedAt() == null
                    ? (status.lastEnqueuedAt() == null ? OffsetDateTime.now() : status.lastEnqueuedAt())
                    : status.projectedAt(), lagMs(status));
        }
        return fresh;
    }

    private boolean useProjection(UUID ledgerId, PeriodRange range) {
        if (projection == null || "legacy".equalsIgnoreCase(readMode)) {
            return false;
        }
        BalanceProjectionService.ProjectionStatus status = projection.status(ledgerId, range);
        boolean fresh = status.fresh();
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

    private void markFallback(UUID ledgerId, PeriodRange range) {
        OffsetDateTime now = OffsetDateTime.now();
        if (projection == null) {
            BalanceReadMetadata.set("live-fallback", now, 0);
            return;
        }
        BalanceProjectionService.ProjectionStatus status = projection.status(ledgerId, range);
        BalanceReadMetadata.set("live-fallback", status.projectedAt() == null ? now : status.projectedAt(),
                lagMs(status));
    }

    private void markProjectionRead(BalanceProjectionService.ProjectionStatus status) {
        BalanceReadMetadata.set("projection", status.projectedAt() == null
                ? (status.lastEnqueuedAt() == null ? OffsetDateTime.now() : status.lastEnqueuedAt())
                : status.projectedAt(), lagMs(status));
    }

    private long lagMs(BalanceProjectionService.ProjectionStatus status) {
        if (status.lastEnqueuedAt() == null) {
            return 0;
        }
        OffsetDateTime measuredAt = status.fresh() && status.projectedAt() != null
                ? status.projectedAt() : OffsetDateTime.now();
        return Math.max(0, Duration.between(status.lastEnqueuedAt(), measuredAt).toMillis());
    }

    private ReportResponses.TrialBalanceLine trialBalanceLine(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        BigDecimal openingDebit = rs.getBigDecimal("opening_debit");
        BigDecimal openingCredit = rs.getBigDecimal("opening_credit");
        BigDecimal debit = rs.getBigDecimal("period_debit");
        BigDecimal credit = rs.getBigDecimal("period_credit");
        BigDecimal closingDebit = rs.getBigDecimal("closing_debit");
        BigDecimal closingCredit = rs.getBigDecimal("closing_credit");
        return new ReportResponses.TrialBalanceLine(
                rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("category"), openingDebit, openingCredit, debit, credit,
                closingDebit, closingCredit, debit, credit, closingDebit.subtract(closingCredit));
    }

    private record DimensionBalanceKey(String periodCode, UUID accountId, UUID combinationId, String currency) {
    }

    private static final class MutableDimensionBalanceRow {
        private final String periodCode;
        private final UUID accountId;
        private final String accountCode;
        private final UUID combinationId;
        private final String dimensionKey;
        private final String currency;
        private final BigDecimal periodDebitBase;
        private final BigDecimal periodCreditBase;
        private final BigDecimal closingDebitBase;
        private final BigDecimal closingCreditBase;
        private final List<ReportResponses.FinanceQueryDimension> dimensions = new ArrayList<>();

        private MutableDimensionBalanceRow(String periodCode, UUID accountId, String accountCode,
                                           UUID combinationId, String dimensionKey, String currency,
                                           BigDecimal periodDebitBase, BigDecimal periodCreditBase,
                                           BigDecimal closingDebitBase, BigDecimal closingCreditBase) {
            this.periodCode = periodCode;
            this.accountId = accountId;
            this.accountCode = accountCode;
            this.combinationId = combinationId;
            this.dimensionKey = dimensionKey;
            this.currency = currency;
            this.periodDebitBase = periodDebitBase;
            this.periodCreditBase = periodCreditBase;
            this.closingDebitBase = closingDebitBase;
            this.closingCreditBase = closingCreditBase;
        }

        private DimensionBalanceRow freeze() {
            return new DimensionBalanceRow(periodCode, accountId, accountCode, combinationId, dimensionKey, currency,
                    periodDebitBase, periodCreditBase, closingDebitBase, closingCreditBase, dimensions);
        }
    }
}
