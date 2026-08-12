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
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
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
                select l.base_currency, l.approval_enabled, p.status, p.start_date, p.end_date
                from ledger l join accounting_period p on p.ledger_id = l.id
                where l.id = ? and p.id = ? and l.deleted_at is null
                """, rs -> rs.next() ? new LedgerContext(rs.getString("base_currency"),
                rs.getBoolean("approval_enabled"), rs.getString("status"),
                rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class)) : null,
                ledgerId, periodId));
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
                           BigDecimal quantity, BigDecimal unitPrice) {
        jdbcTemplate.update("""
                insert into voucher_line (id, ledger_id, voucher_id, line_no, account_id, side, currency,
                    original_amount, exchange_rate, base_amount, summary, cash_flow_item_id, quantity, unit_price)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, lineId, ledgerId, voucherId, lineNo, accountId, side, currency, originalAmount, exchangeRate,
                baseAmount, summary, cashFlowItemId, quantity, unitPrice);
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
        jdbcTemplate.update("""
                update fixed_asset set acquisition_voucher_id = null
                where ledger_id = ? and acquisition_voucher_id = ?
                """, ledgerId, voucherId);
        jdbcTemplate.update("""
                update fixed_asset_depreciation_line set voucher_line_id = null
                where ledger_id = ? and voucher_line_id in (
                    select id from voucher_line where ledger_id = ? and voucher_id = ?)
                """, ledgerId, ledgerId, voucherId);
        jdbcTemplate.update("""
                delete from fixed_asset_disposal
                where ledger_id = ? and (depreciation_voucher_id = ? or transfer_voucher_id = ?
                    or settlement_voucher_id = ?)
                """, ledgerId, voucherId, voucherId, voucherId);
        jdbcTemplate.update("""
                update fixed_asset_depreciation_run set superseded_by = null
                where superseded_by in (
                    select id from fixed_asset_depreciation_run where ledger_id = ? and voucher_id = ?)
                """, ledgerId, voucherId);
        jdbcTemplate.update("""
                delete from fixed_asset_depreciation_line
                where run_id in (
                    select id from fixed_asset_depreciation_run where ledger_id = ? and voucher_id = ?)
                """, ledgerId, voucherId);
        jdbcTemplate.update("delete from fixed_asset_depreciation_run where ledger_id = ? and voucher_id = ?",
                ledgerId, voucherId);
        jdbcTemplate.update("""
                delete from audit_revision
                where ledger_id = ? and aggregate_type = 'VOUCHER' and aggregate_id = ?
                """, ledgerId, voucherId);
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
