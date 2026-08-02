package com.example.accounting.reporting.internal.persistence;

import com.example.accounting.reporting.ReportResponses;
import com.example.accounting.reporting.internal.port.ReportingRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReportingRepository implements ReportingRepository {

    private final JdbcTemplate jdbc;

    public JdbcReportingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ReportResponses.TrialBalanceLine> trialBalance(UUID ledgerId, String periodCode) {
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
                    where v.ledger_id = ? and v.status in ('POSTED', 'REVERSED')
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
                        where v.ledger_id = ? and v.status in ('POSTED', 'REVERSED')
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
                where v.ledger_id = ? and v.status in ('POSTED', 'REVERSED')
                  and (?::varchar is null or p.period_code = ?)
                order by v.voucher_date, v.voucher_number, vl.line_no
                """, (rs, rowNum) -> new ReportResponses.LedgerLine(rs.getObject("voucher_id", UUID.class),
                rs.getString("voucher_number"), rs.getObject("voucher_date", LocalDate.class),
                rs.getString("account_code"), rs.getString("account_name"), rs.getString("side"),
                rs.getBigDecimal("amount"), rs.getString("dimension_key")), ledgerId, periodCode, periodCode);
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
}
