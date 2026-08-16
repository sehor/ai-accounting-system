package com.example.accounting.voucher.internal.persistence;

import com.example.accounting.voucher.VoucherResponses;
import com.example.accounting.voucher.VoucherRequests;
import com.example.accounting.voucher.internal.port.VoucherRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcVoucherRepository implements VoucherRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcVoucherRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean reserveIdempotency(UUID ledgerId, UUID actorId, String key, String requestHash, UUID voucherId) {
        return jdbcTemplate.update("""
                insert into voucher_idempotency (ledger_id, actor_id, idempotency_key, request_hash, voucher_id)
                values (?, ?, ?, ?, ?) on conflict (ledger_id, actor_id, idempotency_key) do nothing
                """, ledgerId, actorId, key, requestHash, voucherId) == 1;
    }

    @Override
    public Optional<Idempotency> findIdempotency(UUID ledgerId, UUID actorId, String key) {
        return Optional.ofNullable(jdbcTemplate.query("""
                select request_hash, voucher_id from voucher_idempotency
                where ledger_id = ? and actor_id = ? and idempotency_key = ?
                """, rs -> rs.next() ? new Idempotency(rs.getString("request_hash"),
                rs.getObject("voucher_id", UUID.class)) : null, ledgerId, actorId, key));
    }

    @Override
    public String nextVoucherNumber(UUID ledgerId, UUID periodId, String voucherType) {
        jdbcTemplate.queryForObject("select pg_advisory_xact_lock(hashtext(?))", Object.class,
                ledgerId + ":" + periodId + ":" + voucherType);
        Long next = jdbcTemplate.queryForObject("""
                select coalesce(max(case when voucher_number ~ '^[0-9]+$' then voucher_number::bigint end), 0) + 1
                from voucher
                where ledger_id = ? and period_id = ? and voucher_type = ? and deleted_at is null
                """, Long.class, ledgerId, periodId, voucherType);
        return Long.toString(next);
    }

    @Override
    public Optional<LedgerContext> findLedgerContext(UUID ledgerId, UUID periodId) {
        return Optional.ofNullable(jdbcTemplate.query("""
                select p.id, l.base_currency, l.approval_enabled, p.status, p.start_date, p.end_date
                from ledger l join accounting_period p on p.ledger_id = l.id
                where l.id = ? and p.id = ? and l.deleted_at is null
                """, rs -> rs.next() ? ledgerContext(rs) : null,
                ledgerId, periodId));
    }

    @Override
    public List<LedgerContext> findLedgerContextsByDate(UUID ledgerId, LocalDate voucherDate) {
        return jdbcTemplate.query("""
                select p.id, l.base_currency, l.approval_enabled, p.status, p.start_date, p.end_date
                from ledger l join accounting_period p on p.ledger_id = l.id
                where l.id = ? and l.deleted_at is null and ? between p.start_date and p.end_date
                order by p.start_date, p.id
                """, (rs, rowNum) -> ledgerContext(rs), ledgerId, voucherDate);
    }

    private LedgerContext ledgerContext(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LedgerContext(rs.getObject("id", UUID.class), rs.getString("base_currency"),
                rs.getBoolean("approval_enabled"), rs.getString("status"),
                rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class));
    }

    @Override
    public boolean activeAccountExists(UUID ledgerId, UUID accountId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                """
                select exists (
                    select 1 from ledger_account account
                    where account.ledger_id = ? and account.id = ? and account.status = 'ACTIVE'
                      and not exists (
                          select 1 from ledger_account child
                          where child.ledger_id = account.ledger_id and child.parent_id = account.id))
                """,
                Boolean.class, ledgerId, accountId));
    }

    @Override
    public Optional<AccountControls> accountControls(UUID ledgerId, UUID accountId) {
        return Optional.ofNullable(jdbcTemplate.query("""
                select cash_flow_required, default_cash_flow_item_id, quantity_enabled, unit_name
                from ledger_account where ledger_id = ? and id = ?
                """, rs -> rs.next() ? new AccountControls(
                rs.getBoolean("cash_flow_required"),
                rs.getObject("default_cash_flow_item_id", UUID.class),
                rs.getBoolean("quantity_enabled"),
                rs.getString("unit_name"), jdbcTemplate.queryForList("""
                        select dimension_type_id from ledger_account_dimension
                        where ledger_id = ? and account_id = ?
                        """, UUID.class, ledgerId, accountId)) : null, ledgerId, accountId));
    }

    @Override
    public Map<UUID, AccountControls> accountControlsByAccounts(UUID ledgerId, List<UUID> accountIds) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(accountIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(ledgerId);
        args.addAll(accountIds);
        Map<UUID, AccountControlAccumulator> accumulators = new HashMap<>();
        jdbcTemplate.query("""
                select account.id, account.cash_flow_required, account.default_cash_flow_item_id,
                    account.quantity_enabled, account.unit_name, required.dimension_type_id
                from ledger_account account
                left join ledger_account_dimension required
                  on required.ledger_id = account.ledger_id and required.account_id = account.id
                where account.ledger_id = ? and account.id in (""" + placeholders + ")",
                (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> {
                    while (rs.next()) {
                        UUID accountId = rs.getObject("id", UUID.class);
                        boolean cashFlowRequired = rs.getBoolean("cash_flow_required");
                        UUID defaultCashFlowItemId = rs.getObject("default_cash_flow_item_id", UUID.class);
                        boolean quantityEnabled = rs.getBoolean("quantity_enabled");
                        String unitName = rs.getString("unit_name");
                        AccountControlAccumulator accumulator = accumulators.computeIfAbsent(accountId, ignored ->
                                new AccountControlAccumulator(cashFlowRequired, defaultCashFlowItemId,
                                        quantityEnabled, unitName));
                        UUID typeId = rs.getObject("dimension_type_id", UUID.class);
                        if (typeId != null) {
                            accumulator.dimensionTypeIds.add(typeId);
                        }
                    }
                    return null;
                }
        , args.toArray());
        Map<UUID, AccountControls> result = new HashMap<>();
        accumulators.forEach((id, value) -> result.put(id, value.freeze()));
        return result;
    }

    @Override
    public Set<UUID> activeAccountIds(UUID ledgerId, List<UUID> accountIds) {
        if (accountIds.isEmpty()) {
            return Set.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(accountIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(ledgerId);
        args.addAll(accountIds);
        String sql = "select account.id from ledger_account account "
                + "where account.ledger_id = ? and account.id in (" + placeholders + ") "
                + "and account.status = 'ACTIVE' "
                + "and not exists (select 1 from ledger_account child "
                + "where child.ledger_id = account.ledger_id and child.parent_id = account.id)";
        return new java.util.HashSet<>(jdbcTemplate.query(sql,
                (rs, row) -> rs.getObject(1, UUID.class), args.toArray()));
    }

    @Override
    public Set<UUID> activeCashFlowItemIds(UUID ledgerId, Set<UUID> itemIds) {
        if (itemIds.isEmpty()) {
            return Set.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(itemIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(ledgerId);
        args.addAll(itemIds);
        String sql = "select id from cash_flow_item where ledger_id = ? and status = 'ACTIVE' "
                + "and id in (" + placeholders + ")";
        return new java.util.HashSet<>(jdbcTemplate.query(sql,
                (rs, row) -> rs.getObject(1, UUID.class), args.toArray()));
    }

    @Override
    public Set<String> activeDimensionBindings(UUID ledgerId, Set<String> requestedBindings) {
        if (requestedBindings.isEmpty()) {
            return Set.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(requestedBindings.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(ledgerId);
        args.addAll(requestedBindings);
        String sql = "select type.id::text || ':' || value.id::text "
                + "from dimension_type type join dimension_value value "
                + "on value.ledger_id = type.ledger_id and value.dimension_type_id = type.id "
                + "where type.ledger_id = ? and type.status = 'ACTIVE' and value.status = 'ACTIVE' "
                + "and (type.id::text || ':' || value.id::text) in (" + placeholders + ")";
        return new java.util.HashSet<>(jdbcTemplate.query(sql,
                (rs, row) -> rs.getString(1), args.toArray()));
    }

    private static final class AccountControlAccumulator {
        private final boolean cashFlowRequired;
        private final UUID defaultCashFlowItemId;
        private final boolean quantityEnabled;
        private final String unitName;
        private final Set<UUID> dimensionTypeIds = new java.util.HashSet<>();

        private AccountControlAccumulator(boolean cashFlowRequired, UUID defaultCashFlowItemId,
                                          boolean quantityEnabled, String unitName) {
            this.cashFlowRequired = cashFlowRequired;
            this.defaultCashFlowItemId = defaultCashFlowItemId;
            this.quantityEnabled = quantityEnabled;
            this.unitName = unitName;
        }

        private AccountControls freeze() {
            return new AccountControls(cashFlowRequired, defaultCashFlowItemId, quantityEnabled,
                    unitName, List.copyOf(dimensionTypeIds));
        }
    }

    @Override
    public boolean validCashFlowItem(UUID ledgerId, UUID cashFlowItemId) {
        if (cashFlowItemId == null) {
            return true;
        }
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                select exists (
                    select 1 from cash_flow_item
                    where ledger_id = ? and id = ? and status = 'ACTIVE')
                """, Boolean.class, ledgerId, cashFlowItemId));
    }

    @Override
    public boolean validDimensionValue(UUID ledgerId, UUID dimensionTypeId, UUID dimensionValueId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                select exists (
                    select 1 from dimension_value
                    where ledger_id = ? and dimension_type_id = ? and id = ? and status = 'ACTIVE')
                """, Boolean.class, ledgerId, dimensionTypeId, dimensionValueId));
    }

    @Override
    public void createVoucher(UUID voucherId, UUID ledgerId, UUID periodId, LocalDate voucherDate,
                              String voucherType, String voucherNumber, String summary, boolean approvalRequired,
                              UUID reversalOfId, UUID actorId) {
        jdbcTemplate.update("""
                insert into voucher (id, ledger_id, period_id, voucher_date, voucher_type, voucher_number,
                    summary, approval_required, reversal_of_id, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, voucherId, ledgerId, periodId, voucherDate, voucherType, voucherNumber, summary,
                approvalRequired, reversalOfId, actorId, actorId);
    }

    @Override
    public void createGeneratedVoucher(UUID voucherId, UUID ledgerId, UUID periodId, LocalDate voucherDate,
                                       String voucherType, String voucherNumber, String summary,
                                       boolean approvalRequired, UUID reversalOfId, UUID actorId,
                                       String sourceType, UUID sourceId) {
        jdbcTemplate.update("""
                insert into voucher (id, ledger_id, period_id, voucher_date, voucher_type, voucher_number,
                    summary, approval_required, reversal_of_id, source_type, source_id, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, voucherId, ledgerId, periodId, voucherDate, voucherType, voucherNumber, summary,
                approvalRequired, reversalOfId, sourceType, sourceId, actorId, actorId);
    }

    @Override
    public boolean updateVoucher(UUID ledgerId, UUID voucherId, UUID periodId, LocalDate voucherDate,
                                 String voucherType, String voucherNumber, String summary, boolean approvalRequired,
                                 UUID actorId, long expectedVersion) {
        return jdbcTemplate.update("""
                update voucher set period_id = ?, voucher_date = ?, voucher_type = ?, voucher_number = ?,
                    summary = ?, approval_required = ?, current_revision = current_revision + 1,
                    version = version + 1, updated_at = now(), updated_by = ?
                where ledger_id = ? and id = ?
                    and status in ('DRAFT', 'VALIDATED', 'SUBMITTED', 'APPROVED', 'POSTED')
                    and version = ?
                """, periodId, voucherDate, voucherType, voucherNumber, summary, approvalRequired, actorId,
                ledgerId, voucherId, expectedVersion) == 1;
    }

    @Override
    public boolean replaceGeneratedVoucher(UUID ledgerId, UUID voucherId, UUID periodId, LocalDate voucherDate,
                                           String voucherType, String voucherNumber, String summary,
                                           boolean approvalRequired, UUID actorId, long expectedVersion,
                                           String sourceType, UUID expectedSourceId, UUID nextSourceId) {
        return jdbcTemplate.update("""
                update voucher set period_id = ?, voucher_date = ?, voucher_type = ?, voucher_number = ?,
                    summary = ?, approval_required = ?, source_type = ?, source_id = ?,
                    current_revision = current_revision + 1, version = version + 1,
                    updated_at = now(), updated_by = ?
                where ledger_id = ? and id = ? and status = 'POSTED'
                    and source_type = ? and source_id = ? and version = ?
                """, periodId, voucherDate, voucherType, voucherNumber, summary, approvalRequired,
                sourceType, nextSourceId, actorId, ledgerId, voucherId, sourceType, expectedSourceId,
                expectedVersion) == 1;
    }

    @Override
    public void deleteLines(UUID ledgerId, UUID voucherId) {
        jdbcTemplate.update("delete from voucher_line where ledger_id = ? and voucher_id = ?", ledgerId, voucherId);
    }

    @Override
    public void createLine(UUID lineId, UUID ledgerId, UUID voucherId, int lineNo, UUID accountId, String side,
                           String currency, BigDecimal originalAmount, BigDecimal exchangeRate,
                           BigDecimal baseAmount, String summary, UUID cashFlowItemId,
                           BigDecimal quantity, BigDecimal unitPrice, UUID dimensionCombinationId) {
        jdbcTemplate.update("""
                insert into voucher_line (id, ledger_id, voucher_id, line_no, account_id, side, currency,
                    original_amount, exchange_rate, base_amount, summary, cash_flow_item_id, quantity, unit_price,
                    dimension_combination_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, lineId, ledgerId, voucherId, lineNo, accountId, side, currency, originalAmount, exchangeRate,
                baseAmount, summary, cashFlowItemId, quantity, unitPrice, dimensionCombinationId);
    }

    @Override
    public void createLines(List<LineInsert> lines) {
        jdbcTemplate.batchUpdate("""
                insert into voucher_line (id, ledger_id, voucher_id, line_no, account_id, side, currency,
                    original_amount, exchange_rate, base_amount, summary, cash_flow_item_id, quantity, unit_price,
                    dimension_combination_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement ps, int index) throws java.sql.SQLException {
                LineInsert line = lines.get(index);
                ps.setObject(1, line.lineId());
                ps.setObject(2, line.ledgerId());
                ps.setObject(3, line.voucherId());
                ps.setInt(4, line.lineNo());
                ps.setObject(5, line.accountId());
                ps.setString(6, line.side());
                ps.setString(7, line.currency());
                ps.setBigDecimal(8, line.originalAmount());
                ps.setBigDecimal(9, line.exchangeRate());
                ps.setBigDecimal(10, line.baseAmount());
                ps.setString(11, line.summary());
                ps.setObject(12, line.cashFlowItemId());
                ps.setBigDecimal(13, line.quantity());
                ps.setBigDecimal(14, line.unitPrice());
                ps.setObject(15, line.dimensionCombinationId());
            }

            @Override
            public int getBatchSize() {
                return lines.size();
            }
        });
    }

    @Override
    public void createLineDimensions(
            UUID lineId, UUID ledgerId, List<com.example.accounting.voucher.VoucherRequests.Dimension> dimensions) {
        for (com.example.accounting.voucher.VoucherRequests.Dimension dimension : dimensions) {
            jdbcTemplate.update("""
                    insert into voucher_line_dimension (
                        voucher_line_id, ledger_id, dimension_type_id, dimension_value_id)
                    values (?, ?, ?, ?)
                    """, lineId, ledgerId, dimension.dimensionTypeId(), dimension.dimensionValueId());
        }
    }

    @Override
    public void createLineDimensionsBatch(List<LineDimensionInsert> dimensions) {
        if (dimensions.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
                insert into voucher_line_dimension (
                    voucher_line_id, ledger_id, dimension_type_id, dimension_value_id)
                values (?, ?, ?, ?)
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement ps, int index) throws java.sql.SQLException {
                LineDimensionInsert dimension = dimensions.get(index);
                ps.setObject(1, dimension.lineId());
                ps.setObject(2, dimension.ledgerId());
                ps.setObject(3, dimension.dimensionTypeId());
                ps.setObject(4, dimension.dimensionValueId());
            }

            @Override
            public int getBatchSize() {
                return dimensions.size();
            }
        });
    }

    @Override
    public boolean controlsComplete(UUID ledgerId, UUID voucherId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                select not exists (
                    select 1
                    from voucher_line line
                    join ledger_account account
                      on account.ledger_id = line.ledger_id and account.id = line.account_id
                    where line.ledger_id = ? and line.voucher_id = ?
                      and (
                        account.status <> 'ACTIVE'
                        or exists (
                            select 1 from ledger_account child
                            where child.ledger_id = account.ledger_id and child.parent_id = account.id)
                        or (account.cash_flow_required and line.cash_flow_item_id is null)
                        or (line.cash_flow_item_id is not null and not exists (
                            select 1 from cash_flow_item item
                            where item.ledger_id = line.ledger_id
                              and item.id = line.cash_flow_item_id and item.status = 'ACTIVE'))
                        or (account.quantity_enabled and (
                            line.quantity is null or line.unit_price is null
                            or round(line.quantity * line.unit_price, 4) <> line.original_amount))
                        or (not account.quantity_enabled
                            and (line.quantity is not null or line.unit_price is not null))
                        or exists (
                            select 1 from ledger_account_dimension required
                            where required.ledger_id = account.ledger_id
                              and required.account_id = account.id and required.required
                              and not exists (
                                  select 1 from voucher_line_dimension actual
                                  where actual.voucher_line_id = line.id
                                    and actual.dimension_type_id = required.dimension_type_id))
                        or exists (
                            select 1 from voucher_line_dimension actual
                            where actual.voucher_line_id = line.id
                              and (
                                not exists (
                                    select 1 from ledger_account_dimension allowed
                                    where allowed.ledger_id = line.ledger_id
                                      and allowed.account_id = account.id
                                      and allowed.dimension_type_id = actual.dimension_type_id)
                                or not exists (
                                    select 1 from dimension_value value
                                    where value.ledger_id = line.ledger_id
                                      and value.dimension_type_id = actual.dimension_type_id
                                      and value.id = actual.dimension_value_id
                                      and value.status = 'ACTIVE')))
                      ))
                """, Boolean.class, ledgerId, voucherId));
    }

    @Override
    public void reclassifyAccountingRole(UUID ledgerId, UUID voucherId) {
        jdbcTemplate.update("""
                with current_voucher as (
                    select id, period_id
                    from voucher
                    where ledger_id = ? and id = ? and deleted_at is null
                ), profit_account as (
                    select coalesce(
                        (select setting.profit_account_id
                         from period_closing_setting setting
                         join ledger_account account
                           on account.ledger_id = setting.ledger_id
                          and account.id = setting.profit_account_id
                         where setting.ledger_id = ? and account.status = 'ACTIVE'
                           and account.category = 'EQUITY'
                           and not exists (select 1 from ledger_account child
                                           where child.ledger_id = account.ledger_id
                                             and child.parent_id = account.id)),
                        (select account.id from ledger_account account
                         where account.ledger_id = ? and account.code = '3103'
                           and account.status = 'ACTIVE' and account.category = 'EQUITY'
                           and not exists (select 1 from ledger_account child
                                           where child.ledger_id = account.ledger_id
                                             and child.parent_id = account.id) limit 1),
                        (select account.id from ledger_account account
                         where account.ledger_id = ? and account.code = '4103'
                           and account.status = 'ACTIVE' and account.category = 'EQUITY'
                           and not exists (select 1 from ledger_account child
                                           where child.ledger_id = account.ledger_id
                                             and child.parent_id = account.id) limit 1)
                    ) account_id
                ), candidate_lines as (
                    select line.account_id, account.category,
                        sum(case when line.side = 'DEBIT' then line.base_amount else -line.base_amount end) net,
                        sum(case when line.side = 'DEBIT' then line.base_amount else 0 end) debit,
                        sum(case when line.side = 'CREDIT' then line.base_amount else 0 end) credit
                    from voucher_line line
                    join ledger_account account
                      on account.ledger_id = line.ledger_id and account.id = line.account_id
                    where line.ledger_id = ? and line.voucher_id = ?
                    group by line.account_id, account.category
                ), is_transfer as (
                    select exists (select 1 from profit_account where account_id is not null)
                       and exists (select 1 from candidate_lines line join profit_account profit
                                   on line.account_id = profit.account_id)
                       and exists (select 1 from candidate_lines
                                   where category in ('OPERATING_REVENUE', 'OTHER_INCOME',
                                       'OPERATING_COST_AND_TAX', 'OTHER_EXPENSE', 'PERIOD_EXPENSE',
                                       'INCOME_TAX', 'PRIOR_YEAR_ADJUSTMENT'))
                       and not exists (
                           select 1 from candidate_lines line left join profit_account profit on true
                           where line.account_id <> profit.account_id
                             and line.category not in ('OPERATING_REVENUE', 'OTHER_INCOME',
                                 'OPERATING_COST_AND_TAX', 'OTHER_EXPENSE', 'PERIOD_EXPENSE',
                                 'INCOME_TAX', 'PRIOR_YEAR_ADJUSTMENT'))
                       and not exists (
                           select 1
                           from candidate_lines line
                           cross join current_voucher current
                           where line.category in ('OPERATING_REVENUE', 'OTHER_INCOME',
                                       'OPERATING_COST_AND_TAX', 'OTHER_EXPENSE', 'PERIOD_EXPENSE',
                                       'INCOME_TAX', 'PRIOR_YEAR_ADJUSTMENT')
                             and line.net + coalesce((
                                 select sum(case when posted_line.side = 'DEBIT'
                                                   then posted_line.base_amount else -posted_line.base_amount end)
                                 from voucher posted
                                 join voucher_line posted_line
                                   on posted_line.ledger_id = posted.ledger_id
                                  and posted_line.voucher_id = posted.id
                                 where posted.ledger_id = ? and posted.period_id = current.period_id
                                   and posted.id <> current.id and posted.status = 'POSTED'
                                   and posted.deleted_at is null and posted_line.account_id = line.account_id
                             ), 0) <> 0)
                       and (select coalesce(sum(debit), 0) = coalesce(sum(credit), 0)
                            from candidate_lines)
                    as value
                )
                update voucher set accounting_role = case when (select value from is_transfer)
                    then 'PROFIT_LOSS_TRANSFER' else 'OPERATING' end
                where ledger_id = ? and id = ?
                """, ledgerId, voucherId, ledgerId, ledgerId, ledgerId, ledgerId, voucherId,
                ledgerId, ledgerId, voucherId);
    }

    @Override
    public List<VoucherResponses.Voucher> list(UUID ledgerId, int limit, int offset) {
        return list(ledgerId, new VoucherRequests.Search(null, null, null, null), limit, offset);
    }

    @Override
    public List<VoucherResponses.Voucher> list(UUID ledgerId, String periodCode, int limit, int offset) {
        return list(ledgerId, new VoucherRequests.Search(periodCode, null, null, null), limit, offset);
    }

    @Override
    public List<VoucherResponses.Voucher> list(UUID ledgerId, VoucherRequests.Search search, int limit, int offset) {
        return jdbcTemplate.query("""
                select v.id, v.ledger_id, v.period_id, v.voucher_date, v.voucher_type, v.voucher_number,
                    v.summary, v.status, v.approval_required, v.version, v.source_type, v.source_id
                from voucher v
                join accounting_period p on p.ledger_id = v.ledger_id and p.id = v.period_id
                where v.ledger_id = ? and v.deleted_at is null
                    and (?::varchar is null or p.period_code = ?)
                    and (?::date is null or v.voucher_date >= ?)
                    and (?::date is null or v.voucher_date <= ?)
                    and (?::varchar is null or v.voucher_type ilike '%' || ? || '%'
                        or v.voucher_number ilike '%' || ? || '%'
                        or coalesce(v.summary, '') ilike '%' || ? || '%'
                        or exists (select 1 from voucher_line line
                                   where line.ledger_id = v.ledger_id and line.voucher_id = v.id
                                     and coalesce(line.summary, '') ilike '%' || ? || '%'))
                order by v.voucher_date, v.voucher_number, v.id limit ? offset ?
                """, (rs, rowNum) -> voucher(rs.getObject("id", UUID.class),
                rs.getObject("ledger_id", UUID.class), rs.getObject("period_id", UUID.class),
                rs.getObject("voucher_date", LocalDate.class), rs.getString("voucher_type"),
                rs.getString("voucher_number"), rs.getString("summary"), rs.getString("status"),
                rs.getBoolean("approval_required"), rs.getLong("version"), List.of(),
                rs.getString("source_type"), rs.getObject("source_id", UUID.class)),
                ledgerId, search.periodCode(), search.periodCode(), search.startDate(), search.startDate(),
                search.endDate(), search.endDate(), search.keyword(), search.keyword(), search.keyword(),
                search.keyword(), search.keyword(), limit, offset);
    }

    @Override
    public long count(UUID ledgerId, String periodCode) {
        return count(ledgerId, new VoucherRequests.Search(periodCode, null, null, null));
    }

    @Override
    public long count(UUID ledgerId, VoucherRequests.Search search) {
        Long result = jdbcTemplate.queryForObject("""
                select count(*) from voucher v
                join accounting_period p on p.ledger_id = v.ledger_id and p.id = v.period_id
                where v.ledger_id = ? and v.deleted_at is null
                    and (?::varchar is null or p.period_code = ?)
                    and (?::date is null or v.voucher_date >= ?)
                    and (?::date is null or v.voucher_date <= ?)
                    and (?::varchar is null or v.voucher_type ilike '%' || ? || '%'
                        or v.voucher_number ilike '%' || ? || '%'
                        or coalesce(v.summary, '') ilike '%' || ? || '%'
                        or exists (select 1 from voucher_line line
                                   where line.ledger_id = v.ledger_id and line.voucher_id = v.id
                                     and coalesce(line.summary, '') ilike '%' || ? || '%'))
                """, Long.class, ledgerId, search.periodCode(), search.periodCode(), search.startDate(),
                search.startDate(), search.endDate(), search.endDate(), search.keyword(), search.keyword(),
                search.keyword(), search.keyword(), search.keyword());
        return result == null ? 0 : result;
    }

    @Override
    public Optional<VoucherResponses.Voucher> find(UUID ledgerId, UUID voucherId, boolean includeDeleted) {
        String sql = """
                select id, ledger_id, period_id, voucher_date, voucher_type, voucher_number, summary, status,
                    approval_required, version, source_type, source_id
                from voucher where ledger_id = ? and id = ?
                """ + (includeDeleted ? "" : " and deleted_at is null");
        return Optional.ofNullable(jdbcTemplate.query(sql, rs -> rs.next() ? voucher(
                rs.getObject("id", UUID.class), rs.getObject("ledger_id", UUID.class),
                rs.getObject("period_id", UUID.class), rs.getObject("voucher_date", LocalDate.class),
                rs.getString("voucher_type"), rs.getString("voucher_number"), rs.getString("summary"),
                rs.getString("status"), rs.getBoolean("approval_required"), rs.getLong("version"),
                lines(ledgerId, voucherId), rs.getString("source_type"),
                rs.getObject("source_id", UUID.class)) : null,
                ledgerId, voucherId));
    }

    @Override
    public List<VoucherResponses.Line> lines(UUID ledgerId, UUID voucherId) {
        return jdbcTemplate.query("""
                select id, line_no, account_id, side, currency, original_amount, exchange_rate, base_amount,
                    summary, cash_flow_item_id, quantity, unit_price
                from voucher_line where ledger_id = ? and voucher_id = ? order by line_no
                """, (rs, rowNum) -> line(rs.getObject("id", UUID.class), rs.getInt("line_no"),
                rs.getObject("account_id", UUID.class), rs.getString("side"), rs.getString("currency"),
                rs.getBigDecimal("original_amount"), rs.getBigDecimal("exchange_rate"),
                rs.getBigDecimal("base_amount"), rs.getString("summary"),
                rs.getObject("cash_flow_item_id", UUID.class), rs.getBigDecimal("quantity"),
                rs.getBigDecimal("unit_price")), ledgerId, voucherId);
    }

    @Override
    public Map<UUID, List<VoucherResponses.Line>> linesByVoucher(UUID ledgerId, List<UUID> voucherIds) {
        if (voucherIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(voucherIds.size(), "?"));
        List<Object> arguments = new ArrayList<>();
        arguments.add(ledgerId);
        arguments.addAll(voucherIds);
        return jdbcTemplate.query("""
                select voucher_id, id, line_no, account_id, side, currency, original_amount, exchange_rate,
                    base_amount, summary, cash_flow_item_id, quantity, unit_price
                from voucher_line where ledger_id = ? and voucher_id in (%s)
                order by voucher_id, line_no
                """.formatted(placeholders), rs -> {
            Map<UUID, List<VoucherResponses.Line>> result = new HashMap<>();
            while (rs.next()) {
                UUID voucherId = rs.getObject("voucher_id", UUID.class);
                result.computeIfAbsent(voucherId, ignored -> new ArrayList<>()).add(line(
                        rs.getObject("id", UUID.class), rs.getInt("line_no"),
                        rs.getObject("account_id", UUID.class), rs.getString("side"), rs.getString("currency"),
                        rs.getBigDecimal("original_amount"), rs.getBigDecimal("exchange_rate"),
                        rs.getBigDecimal("base_amount"), rs.getString("summary"),
                        rs.getObject("cash_flow_item_id", UUID.class), rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("unit_price")));
            }
            return result;
        }, arguments.toArray());
    }

    @Override
    public Optional<VoucherState> findState(UUID ledgerId, UUID voucherId, boolean deletedOnly) {
        String deletedClause = deletedOnly ? "deleted_at is not null" : "deleted_at is null";
        return Optional.ofNullable(jdbcTemplate.query("""
                select status, approval_required, version, source_type, source_id from voucher
                where ledger_id = ? and id = ? and %s
                """.formatted(deletedClause), rs -> rs.next() ? new VoucherState(rs.getString("status"),
                rs.getBoolean("approval_required"), rs.getLong("version"), rs.getString("source_type"),
                rs.getObject("source_id", UUID.class)) : null, ledgerId, voucherId));
    }

    @Override
    public int lineCount(UUID ledgerId, UUID voucherId) {
        Integer result = jdbcTemplate.queryForObject(
                "select count(*) from voucher_line where ledger_id = ? and voucher_id = ?", Integer.class,
                ledgerId, voucherId);
        return result == null ? 0 : result;
    }

    @Override
    public BigDecimal total(UUID ledgerId, UUID voucherId, String side) {
        BigDecimal result = jdbcTemplate.queryForObject(
                "select coalesce(sum(base_amount), 0) from voucher_line where ledger_id = ? and voucher_id = ? and side = ?",
                BigDecimal.class, ledgerId, voucherId, side);
        return result == null ? BigDecimal.ZERO : result;
    }

    @Override
    public boolean changeStatus(UUID ledgerId, UUID voucherId, String expected, String next, UUID actorId) {
        return jdbcTemplate.update("update voucher set status = ?, current_revision = current_revision + 1, "
                + "version = version + 1, updated_at = now(), updated_by = ? "
                + "where ledger_id = ? and id = ? and status = ?", next, actorId, ledgerId, voucherId, expected) == 1;
    }

    @Override
    public boolean post(UUID ledgerId, UUID voucherId, String expectedStatus, UUID actorId) {
        return jdbcTemplate.update("update voucher set status = 'POSTED', posted_at = now(), posted_by = ?, "
                + "current_revision = current_revision + 1, version = version + 1, updated_at = now(), "
                + "updated_by = ? where ledger_id = ? and id = ? and status = ?",
                actorId, actorId, ledgerId, voucherId, expectedStatus) == 1;
    }

    @Override
    public void recordApproval(UUID ledgerId, UUID voucherId, String action, String comment, UUID actorId) {
        jdbcTemplate.update("""
                insert into voucher_approval (id, ledger_id, voucher_id, action, comment, actor_id)
                values (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), ledgerId, voucherId, action, comment, actorId);
    }

    @Override
    public boolean deleteVoucher(UUID ledgerId, UUID voucherId, long expectedVersion) {
        return jdbcTemplate.update("""
                delete from voucher
                where ledger_id = ? and id = ? and deleted_at is null and version = ?
                """, ledgerId, voucherId, expectedVersion) == 1;
    }

    @Override
    public List<VoucherResponses.Revision> listRevisions(UUID ledgerId, UUID voucherId) {
        return jdbcTemplate.query("""
                select id, revision, action, actor_id, reason, before_data::text, after_data::text, created_at
                from audit_revision where ledger_id = ? and aggregate_type = 'VOUCHER' and aggregate_id = ?
                order by revision
                """, (rs, rowNum) -> new VoucherResponses.Revision(rs.getObject("id", UUID.class),
                rs.getInt("revision"), rs.getString("action"), rs.getObject("actor_id", UUID.class),
                rs.getString("reason"), rs.getString("before_data"), rs.getString("after_data"),
                rs.getObject("created_at", OffsetDateTime.class)), ledgerId, voucherId);
    }

    @Override
    public Optional<String> findRevisionData(UUID ledgerId, UUID voucherId, int revision) {
        return Optional.ofNullable(jdbcTemplate.query("""
                select coalesce(after_data::text, before_data::text)
                from audit_revision where ledger_id = ? and aggregate_type = 'VOUCHER'
                    and aggregate_id = ? and revision = ?
                """, rs -> rs.next() ? rs.getString(1) : null, ledgerId, voucherId, revision));
    }

    @Override
    public void restoreHeader(UUID ledgerId, UUID voucherId, UUID periodId, LocalDate voucherDate,
                              String voucherType, String voucherNumber, String summary, boolean approvalRequired,
                              UUID actorId) {
        jdbcTemplate.update("""
                update voucher set period_id = ?, voucher_date = ?, voucher_type = ?, voucher_number = ?,
                    summary = ?, approval_required = ?, current_revision = current_revision + 1,
                    version = version + 1, updated_at = now(), updated_by = ? where ledger_id = ? and id = ?
                """, periodId, voucherDate, voucherType, voucherNumber, summary, approvalRequired, actorId,
                ledgerId, voucherId);
    }

    @Override
    public int currentRevision(UUID ledgerId, UUID voucherId) {
        Integer revision = jdbcTemplate.queryForObject(
                "select current_revision from voucher where ledger_id = ? and id = ?", Integer.class,
                ledgerId, voucherId);
        return revision == null ? 0 : revision;
    }

    @Override
    public void recordRevision(UUID ledgerId, UUID voucherId, int revision, String action, UUID actorId,
                               String reason, String beforeData, String afterData) {
        jdbcTemplate.update("""
                insert into audit_revision (id, ledger_id, aggregate_type, aggregate_id, revision, action,
                    actor_id, reason, before_data, after_data)
                values (?, ?, 'VOUCHER', ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                """, UUID.randomUUID(), ledgerId, voucherId, revision, action, actorId, reason,
                beforeData, afterData);
    }

    private VoucherResponses.Voucher voucher(UUID id, UUID ledgerId, UUID periodId, LocalDate voucherDate,
                                             String voucherType, String voucherNumber, String summary, String status,
                                             boolean approvalRequired, long version, List<VoucherResponses.Line> lines,
                                             String sourceType, UUID sourceId) {
        return new VoucherResponses.Voucher(id, ledgerId, periodId, voucherDate, voucherType, voucherNumber,
                summary, status, approvalRequired, version, lines, sourceType, sourceId);
    }

    private VoucherResponses.Line line(UUID id, int lineNo, UUID accountId, String side, String currency,
                                        BigDecimal originalAmount, BigDecimal exchangeRate, BigDecimal baseAmount,
                                        String summary, UUID cashFlowItemId,
                                        BigDecimal quantity, BigDecimal unitPrice) {
        return new VoucherResponses.Line(id, lineNo, accountId, side, currency, originalAmount, exchangeRate,
                baseAmount, summary, cashFlowItemId, quantity, unitPrice, dimensions(id));
    }

    private List<VoucherResponses.Dimension> dimensions(UUID lineId) {
        // ponytail: one small lookup per line; batch it if voucher-list profiling shows this dominates.
        return jdbcTemplate.query("""
                select dimension_type_id, dimension_value_id
                from voucher_line_dimension where voucher_line_id = ? order by dimension_type_id
                """, (rs, row) -> new VoucherResponses.Dimension(
                rs.getObject("dimension_type_id", UUID.class),
                rs.getObject("dimension_value_id", UUID.class)), lineId);
    }
}
