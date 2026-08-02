package com.example.accounting.ledger.internal.persistence;

import com.example.accounting.ledger.LedgerResponses;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.ledger.MembershipStatus;
import com.example.accounting.ledger.internal.port.LedgerRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLedgerRepository implements LedgerRepository {

    private final JdbcTemplate jdbc;

    public JdbcLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void createLedger(UUID ledgerId, String name, String standardCode, String standardVersion,
                             String baseCurrency, LocalDate startDate, boolean approvalEnabled, UUID actorId) {
        jdbc.update("""
                insert into ledger (id, name, accounting_standard_code, accounting_standard_version,
                    base_currency, start_date, approval_enabled, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, ledgerId, name, standardCode, standardVersion, baseCurrency, startDate,
                approvalEnabled, actorId, actorId);
    }

    @Override
    public void createOwner(UUID ledgerId, UUID actorId) {
        jdbc.update("""
                insert into ledger_membership (id, ledger_id, user_id, role, created_by, updated_by)
                values (?, ?, ?, 'OWNER', ?, ?)
                """, UUID.randomUUID(), ledgerId, actorId, actorId, actorId);
    }

    @Override
    public void createAccount(UUID ledgerId, String code, String name, String category, String normalBalance) {
        jdbc.update("""
                insert into ledger_account (id, ledger_id, code, name, category, normal_balance)
                values (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), ledgerId, code, name, category, normalBalance);
    }

    @Override
    public boolean createAccountIfAbsent(
            UUID ledgerId, String code, String name, String category, String normalBalance) {
        return jdbc.update("""
                insert into ledger_account (id, ledger_id, code, name, category, normal_balance)
                values (?, ?, ?, ?, ?, ?)
                on conflict (ledger_id, code) do nothing
                """, UUID.randomUUID(), ledgerId, code, name, category, normalBalance) == 1;
    }

    @Override
    public void createPeriod(UUID ledgerId, String periodCode, LocalDate startDate, LocalDate endDate) {
        jdbc.update("""
                insert into accounting_period (id, ledger_id, period_code, start_date, end_date)
                values (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), ledgerId, periodCode, startDate, endDate);
    }

    @Override
    public void createFormula(UUID ledgerId, String code, String name, String json) {
        jdbc.update("""
                insert into report_formula_snapshot (id, ledger_id, code, name, formula_json)
                values (?, ?, ?, ?, ?::jsonb)
                """, UUID.randomUUID(), ledgerId, code, name, json);
    }

    @Override
    public List<LedgerResponses.Ledger> list(UUID actorId) {
        return jdbc.query("""
                select l.id, l.name, l.accounting_standard_code, l.accounting_standard_version,
                    l.base_currency, l.start_date, l.approval_enabled, l.status
                from ledger l
                join ledger_membership m on m.ledger_id = l.id
                where m.user_id = ? and m.status = 'ACTIVE' and l.status = 'ACTIVE' and l.deleted_at is null
                order by l.name, l.id
                """, (rs, rowNum) -> mapLedger(rs), actorId);
    }

    @Override
    public Optional<LedgerResponses.Ledger> findLedger(UUID ledgerId) {
        return Optional.ofNullable(jdbc.query("""
                select id, name, accounting_standard_code, accounting_standard_version,
                    base_currency, start_date, approval_enabled, status
                from ledger where id = ? and deleted_at is null
                """, rs -> rs.next() ? mapLedger(rs) : null, ledgerId));
    }

    @Override
    public List<LedgerResponses.Member> listMembers(UUID ledgerId) {
        return jdbc.query("""
                select m.user_id, m.role, m.status, u.display_name, u.email
                from ledger_membership m join app_user u on u.id = m.user_id
                where m.ledger_id = ? and m.deleted_at is null order by m.user_id
                """, (rs, rowNum) -> mapMember(rs), ledgerId);
    }

    @Override
    public List<LedgerResponses.Account> listAccounts(UUID ledgerId) {
        return jdbc.query("""
                select id, ledger_id, code, name, category, normal_balance, status
                from ledger_account where ledger_id = ? order by code
                """, (rs, rowNum) -> mapAccount(rs), ledgerId);
    }

    @Override
    public Optional<LedgerResponses.Account> findAccount(UUID ledgerId, String code) {
        return Optional.ofNullable(jdbc.query("""
                select id, ledger_id, code, name, category, normal_balance, status
                from ledger_account where ledger_id = ? and code = ?
                """, rs -> rs.next() ? mapAccount(rs) : null, ledgerId, code));
    }

    @Override
    public List<LedgerResponses.Period> listPeriods(UUID ledgerId) {
        return jdbc.query("""
                select id, ledger_id, period_code, start_date, end_date, status
                from accounting_period where ledger_id = ? order by period_code
                """, (rs, rowNum) -> mapPeriod(rs), ledgerId);
    }

    @Override
    public Optional<LedgerResponses.Period> findPeriod(UUID ledgerId, UUID periodId) {
        return Optional.ofNullable(jdbc.query("""
                select id, ledger_id, period_code, start_date, end_date, status
                from accounting_period where ledger_id = ? and id = ?
                """, rs -> rs.next() ? mapPeriod(rs) : null, ledgerId, periodId));
    }

    @Override
    public void updatePeriodStatus(UUID ledgerId, UUID periodId, String status) {
        jdbc.update("update accounting_period set status = ? where ledger_id = ? and id = ?",
                status, ledgerId, periodId);
    }

    @Override
    public void recordPeriodAction(UUID ledgerId, UUID periodId, String action, String reason, UUID actorId) {
        jdbc.update("""
                insert into period_action_audit (id, ledger_id, period_id, action, reason, actor_id)
                values (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), ledgerId, periodId, action, reason, actorId);
    }

    @Override
    public List<LedgerResponses.DimensionType> listDimensionTypes(UUID ledgerId) {
        return jdbc.query("""
                select id, ledger_id, code, name, required, status
                from dimension_type where ledger_id = ? order by code
                """, (rs, rowNum) -> mapDimensionType(rs), ledgerId);
    }

    @Override
    public void createDimensionType(UUID id, UUID ledgerId, String code, String name, boolean required) {
        jdbc.update("""
                insert into dimension_type (id, ledger_id, code, name, required)
                values (?, ?, ?, ?, ?)
                """, id, ledgerId, code, name, required);
    }

    @Override
    public Optional<LedgerResponses.DimensionType> findDimensionType(UUID ledgerId, UUID typeId) {
        return Optional.ofNullable(jdbc.query("""
                select id, ledger_id, code, name, required, status
                from dimension_type where ledger_id = ? and id = ?
                """, rs -> rs.next() ? mapDimensionType(rs) : null, ledgerId, typeId));
    }

    @Override
    public boolean activeDimensionTypeExists(UUID ledgerId, UUID typeId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (
                    select 1 from dimension_type where id = ? and ledger_id = ? and status = 'ACTIVE')
                """, Boolean.class, typeId, ledgerId));
    }

    @Override
    public List<LedgerResponses.DimensionValue> listDimensionValues(UUID ledgerId, UUID typeId) {
        return jdbc.query("""
                select id, ledger_id, dimension_type_id, code, name, status
                from dimension_value where ledger_id = ? and dimension_type_id = ? order by code
                """, (rs, rowNum) -> mapDimensionValue(rs), ledgerId, typeId);
    }

    @Override
    public void createDimensionValue(UUID id, UUID ledgerId, UUID typeId, String code, String name) {
        jdbc.update("""
                insert into dimension_value (id, ledger_id, dimension_type_id, code, name)
                values (?, ?, ?, ?, ?)
                """, id, ledgerId, typeId, code, name);
    }

    @Override
    public Optional<LedgerResponses.DimensionValue> findDimensionValue(UUID ledgerId, UUID valueId) {
        return Optional.ofNullable(jdbc.query("""
                select id, ledger_id, dimension_type_id, code, name, status
                from dimension_value where ledger_id = ? and id = ?
                """, rs -> rs.next() ? mapDimensionValue(rs) : null, ledgerId, valueId));
    }

    @Override
    public List<LedgerResponses.OpeningBalance> listOpeningBalances(UUID ledgerId) {
        return jdbc.query("""
                select id, ledger_id, period_id, account_id, currency, dimension_key,
                    debit_original, credit_original, exchange_rate, debit_base, credit_base, confirmed
                from opening_balance where ledger_id = ? order by period_id, account_id, currency, dimension_key
                """, (rs, rowNum) -> mapOpeningBalance(rs), ledgerId);
    }

    @Override
    public boolean hasConfirmedOpeningBalances(UUID ledgerId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from opening_balance where ledger_id = ? and confirmed)",
                Boolean.class, ledgerId));
    }

    @Override
    public void deleteUnconfirmedOpeningBalances(UUID ledgerId) {
        jdbc.update("delete from opening_balance where ledger_id = ? and confirmed = false", ledgerId);
    }

    @Override
    public boolean upsertOpeningBalance(LedgerResponses.OpeningBalance balance) {
        return jdbc.update("""
                insert into opening_balance (id, ledger_id, period_id, account_id, currency, dimension_key,
                    debit_original, credit_original, exchange_rate, debit_base, credit_base)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (ledger_id, period_id, account_id, currency, dimension_key)
                do update set debit_original = excluded.debit_original, credit_original = excluded.credit_original,
                    exchange_rate = excluded.exchange_rate, debit_base = excluded.debit_base,
                    credit_base = excluded.credit_base, updated_at = now()
                where opening_balance.confirmed = false
                """, balance.id(), balance.ledgerId(), balance.periodId(), balance.accountId(), balance.currency(),
                balance.dimensionKey(), balance.debitOriginal(), balance.creditOriginal(), balance.exchangeRate(),
                balance.debitBase(), balance.creditBase()) == 1;
    }

    @Override
    public boolean validOpeningReference(UUID ledgerId, UUID accountId, UUID periodId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (
                    select 1 from ledger_account a join accounting_period p on p.ledger_id = a.ledger_id
                    where a.ledger_id = ? and a.id = ? and a.status = 'ACTIVE'
                      and not exists (
                          select 1 from ledger_account child
                          where child.ledger_id = a.ledger_id and child.parent_id = a.id)
                      and p.id = ? and p.status = 'OPEN')
                """, Boolean.class, ledgerId, accountId, periodId));
    }

    @Override
    public OpeningTotals openingTotals(UUID ledgerId) {
        return jdbc.queryForObject("""
                select coalesce(sum(debit_base), 0) debit, coalesce(sum(credit_base), 0) credit
                from opening_balance where ledger_id = ?
                """, (rs, rowNum) -> new OpeningTotals(rs.getBigDecimal("debit"), rs.getBigDecimal("credit")),
                ledgerId);
    }

    @Override
    public int confirmOpeningBalances(UUID ledgerId) {
        return jdbc.update("update opening_balance set confirmed = true, updated_at = now() "
                + "where ledger_id = ? and confirmed = false", ledgerId);
    }

    @Override
    public Optional<UUID> findAccountId(UUID ledgerId, String code) {
        return Optional.ofNullable(jdbc.query("select id from ledger_account where ledger_id = ? and code = ?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, ledgerId, code));
    }

    @Override
    public Optional<UUID> findPeriodId(UUID ledgerId, String periodCode) {
        return Optional.ofNullable(jdbc.query(
                "select id from accounting_period where ledger_id = ? and period_code = ?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, ledgerId, periodCode));
    }

    @Override
    public boolean userExists(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from app_user where id = ? and deleted_at is null)",
                Boolean.class, userId));
    }

    @Override
    public void upsertMember(UUID ledgerId, UUID userId, LedgerRole role, UUID actorId) {
        jdbc.update("""
                insert into ledger_membership (id, ledger_id, user_id, role, created_by, updated_by, deleted_at)
                values (?, ?, ?, ?, ?, ?, null)
                on conflict (ledger_id, user_id) do update set role = excluded.role, status = 'ACTIVE',
                    updated_at = now(), updated_by = excluded.updated_by, deleted_at = null
                """, UUID.randomUUID(), ledgerId, userId, role.name(), actorId, actorId);
    }

    @Override
    public Optional<LedgerResponses.Member> findMember(UUID ledgerId, UUID userId) {
        return Optional.ofNullable(jdbc.query("""
                select m.user_id, m.role, m.status, u.display_name, u.email
                from ledger_membership m join app_user u on u.id = m.user_id
                where m.ledger_id = ? and m.user_id = ?
                """, rs -> rs.next() ? mapMember(rs) : null, ledgerId, userId));
    }

    @Override
    public boolean updateMember(UUID ledgerId, UUID userId, LedgerRole role,
                                MembershipStatus status, UUID actorId) {
        return jdbc.update("""
                update ledger_membership set role = ?, status = ?, updated_at = now(), updated_by = ?
                where ledger_id = ? and user_id = ? and deleted_at is null
                """, role.name(), status.name(), actorId, ledgerId, userId) == 1;
    }

    @Override
    public boolean removeMember(UUID ledgerId, UUID userId, UUID actorId) {
        return jdbc.update("""
                update ledger_membership set status = 'INACTIVE', deleted_at = now(), updated_at = now(), updated_by = ?
                where ledger_id = ? and user_id = ? and deleted_at is null
                """, actorId, ledgerId, userId) == 1;
    }

    @Override
    public boolean isSoleActiveOwner(UUID ledgerId, UUID userId) {
        Integer owners = jdbc.queryForObject("""
                select count(*) from ledger_membership where ledger_id = ? and role = 'OWNER'
                    and status = 'ACTIVE' and deleted_at is null
                """, Integer.class, ledgerId);
        Integer targetOwners = jdbc.queryForObject("""
                select count(*) from ledger_membership where ledger_id = ? and user_id = ?
                    and role = 'OWNER' and status = 'ACTIVE' and deleted_at is null
                """, Integer.class, ledgerId, userId);
        return owners != null && targetOwners != null && owners == 1 && targetOwners == 1;
    }

    @Override
    public void lockLedger(UUID ledgerId) {
        jdbc.queryForObject("select id from ledger where id = ? for update", UUID.class, ledgerId);
    }

    private LedgerResponses.Ledger mapLedger(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LedgerResponses.Ledger(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("accounting_standard_code"), rs.getString("accounting_standard_version"),
                rs.getString("base_currency"), rs.getObject("start_date", LocalDate.class),
                rs.getBoolean("approval_enabled"), rs.getString("status"));
    }

    private LedgerResponses.Account mapAccount(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LedgerResponses.Account(rs.getObject("id", UUID.class),
                rs.getObject("ledger_id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("category"), rs.getString("normal_balance"), rs.getString("status"));
    }

    private LedgerResponses.Member mapMember(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LedgerResponses.Member(rs.getObject("user_id", UUID.class),
                LedgerRole.valueOf(rs.getString("role")), MembershipStatus.valueOf(rs.getString("status")),
                rs.getString("display_name"), rs.getString("email"));
    }

    private LedgerResponses.Period mapPeriod(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LedgerResponses.Period(rs.getObject("id", UUID.class),
                rs.getObject("ledger_id", UUID.class), rs.getString("period_code"),
                rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
                rs.getString("status"));
    }

    private LedgerResponses.DimensionType mapDimensionType(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LedgerResponses.DimensionType(rs.getObject("id", UUID.class),
                rs.getObject("ledger_id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getBoolean("required"), rs.getString("status"));
    }

    private LedgerResponses.DimensionValue mapDimensionValue(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LedgerResponses.DimensionValue(rs.getObject("id", UUID.class),
                rs.getObject("ledger_id", UUID.class), rs.getObject("dimension_type_id", UUID.class),
                rs.getString("code"), rs.getString("name"), rs.getString("status"));
    }

    private LedgerResponses.OpeningBalance mapOpeningBalance(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LedgerResponses.OpeningBalance(rs.getObject("id", UUID.class),
                rs.getObject("ledger_id", UUID.class), rs.getObject("period_id", UUID.class),
                rs.getObject("account_id", UUID.class), rs.getString("currency"), rs.getString("dimension_key"),
                rs.getBigDecimal("debit_original"), rs.getBigDecimal("credit_original"),
                rs.getBigDecimal("exchange_rate"), rs.getBigDecimal("debit_base"),
                rs.getBigDecimal("credit_base"), rs.getBoolean("confirmed"));
    }
}
