package com.example.accounting.reporting;

import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.shared.web.ApiProblemException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportingService {

    private final JdbcTemplate jdbcTemplate;

    public ReportingService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public List<ReportResponses.TrialBalanceLine> trialBalance(UUID actorId, UUID ledgerId, String periodCode) {
        requireRole(actorId, ledgerId);
        return jdbcTemplate.query("""
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
                where a.ledger_id = ? and a.status = 'ACTIVE'
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

    @Transactional(readOnly = true)
    public ReportResponses.Statement balanceSheet(UUID actorId, UUID ledgerId, String periodCode) {
        List<ReportResponses.TrialBalanceLine> lines = trialBalance(actorId, ledgerId, periodCode);
        return new ReportResponses.Statement((int) lines.stream()
                .filter(line -> Set.of("ASSET", "LIABILITY", "EQUITY").contains(line.category())).count(),
                lines.stream().filter(line -> Set.of("ASSET", "LIABILITY", "EQUITY").contains(line.category()))
                        .map(line -> new ReportResponses.StatementLine(line.code(), line.name(), line.balance())).toList());
    }

    @Transactional(readOnly = true)
    public ReportResponses.Statement incomeStatement(UUID actorId, UUID ledgerId, String periodCode) {
        List<ReportResponses.TrialBalanceLine> lines = trialBalance(actorId, ledgerId, periodCode);
        List<ReportResponses.StatementLine> result = lines.stream()
                .filter(line -> Set.of("REVENUE", "COST", "EXPENSE").contains(line.category()))
                .map(line -> new ReportResponses.StatementLine(line.code(), line.name(),
                        "REVENUE".equals(line.category()) ? line.credit().subtract(line.debit())
                                : line.debit().subtract(line.credit())))
                .toList();
        return new ReportResponses.Statement(result.size(), result);
    }

    @Transactional(readOnly = true)
    public List<ReportResponses.LedgerLine> generalLedger(UUID actorId, UUID ledgerId, String periodCode) {
        return ledgerLines(actorId, ledgerId, periodCode);
    }

    @Transactional(readOnly = true)
    public List<ReportResponses.LedgerLine> subLedger(UUID actorId, UUID ledgerId, String periodCode) {
        return ledgerLines(actorId, ledgerId, periodCode);
    }

    @Transactional(readOnly = true)
    public List<ReportResponses.FinanceQueryLine> financeQuery(UUID actorId, UUID ledgerId,
                                                                FinanceQueryRequests.Query request) {
        requireRole(actorId, ledgerId);
        if (request.periodFrom() != null && request.periodTo() != null
                && request.periodFrom().compareTo(request.periodTo()) > 0) {
            throw problem(422, "FINANCE_QUERY_INVALID", "Invalid finance query", "periodFrom must not be after periodTo");
        }
        if (request.periodFrom() != null && request.periodTo() != null
                && !request.periodFrom().equals(request.periodTo())) {
            throw problem(422, "FINANCE_QUERY_RANGE_UNSUPPORTED", "Unsupported finance query range",
                    "v0.1 supports one period per finance query");
        }
        if (request.filters() != null && request.filters().currency() != null) {
            String baseCurrency = jdbcTemplate.queryForObject("select base_currency from ledger where id = ?",
                    String.class, ledgerId);
            if (!request.filters().currency().equals(baseCurrency)) {
                throw problem(422, "FINANCE_QUERY_CURRENCY_UNSUPPORTED", "Unsupported finance query currency",
                        "v0.1 reports are stored in the ledger base currency");
            }
        }
        String period = Objects.equals(request.periodFrom(), request.periodTo()) ? request.periodFrom() : null;
        List<ReportResponses.TrialBalanceLine> lines = trialBalance(actorId, ledgerId, period).stream()
                .filter(line -> request.filters() == null || request.filters().accountCodes() == null
                        || request.filters().accountCodes().isEmpty()
                        || request.filters().accountCodes().contains(line.code()))
                .toList();
        // ponytail: v0.1 returns one aggregate for month/currency/dimension; add fact-level grouping when needed.
        boolean byAccount = request.groupBy().contains("ACCOUNT");
        return lines.stream().map(line -> new ReportResponses.FinanceQueryLine(
                byAccount ? line.code() : "ALL", switch (request.metric()) {
                    case "DEBIT" -> line.debit();
                    case "CREDIT" -> line.credit();
                    case "NET", "BALANCE" -> line.balance();
                    default -> throw problem(422, "FINANCE_QUERY_INVALID", "Invalid finance query",
                            "The metric is not in the whitelist");
                })).toList();
    }

    private List<ReportResponses.LedgerLine> ledgerLines(UUID actorId, UUID ledgerId, String periodCode) {
        requireRole(actorId, ledgerId);
        return jdbcTemplate.query("""
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
                rs.getString("voucher_number"), rs.getObject("voucher_date", java.time.LocalDate.class),
                rs.getString("account_code"), rs.getString("account_name"), rs.getString("side"),
                rs.getBigDecimal("amount"), rs.getString("dimension_key")), ledgerId, periodCode, periodCode);
    }

    private void requireRole(UUID actorId, UUID ledgerId) {
        String role = jdbcTemplate.query("""
                select m.role from ledger_membership m join ledger l on l.id = m.ledger_id
                where m.ledger_id = ? and m.user_id = ? and m.status = 'ACTIVE'
                    and m.deleted_at is null and l.deleted_at is null
                """, rs -> rs.next() ? rs.getString(1) : null, ledgerId, actorId);
        if (role == null) {
            throw new ApiProblemException(404, "LEDGER_NOT_FOUND", "Ledger not found",
                    "The ledger is not available to this user", false);
        }
        if (!Set.of(LedgerRole.OWNER, LedgerRole.EDITOR, LedgerRole.REVIEWER,
                LedgerRole.VIEWER, LedgerRole.AGENT).contains(LedgerRole.valueOf(role))) {
            throw new ApiProblemException(403, "INSUFFICIENT_LEDGER_ROLE", "Insufficient ledger role",
                    "The current user cannot view reports", false);
        }
    }

    private ApiProblemException problem(int status, String code, String title, String detail) {
        return new ApiProblemException(status, code, title, detail, false);
    }
}
