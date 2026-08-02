package com.example.accounting.ledger.internal.persistence;

import com.example.accounting.ledger.AccountCodeRule;
import com.example.accounting.ledger.LedgerRequests;
import com.example.accounting.ledger.LedgerResponses;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountManagementRepository {

    private static final String ACCOUNT_SELECT = """
            select a.id, a.ledger_id, a.code, a.name, a.category, a.normal_balance, a.status,
                a.parent_id, a.level, a.is_template, a.legacy_code, a.version,
                a.cash_flow_required, a.default_cash_flow_item_id, a.quantity_enabled, a.unit_name,
                not exists (
                    select 1 from ledger_account child
                    where child.ledger_id = a.ledger_id and child.parent_id = a.id) leaf,
                (exists (
                    select 1 from voucher_line vl
                    where vl.ledger_id = a.ledger_id and vl.account_id = a.id)
                 or exists (
                    select 1 from opening_balance ob
                    where ob.ledger_id = a.ledger_id and ob.account_id = a.id)) has_business_usage,
                (exists (
                    select 1 from voucher_line vl join voucher v
                      on v.ledger_id = vl.ledger_id and v.id = vl.voucher_id
                    where vl.ledger_id = a.ledger_id and vl.account_id = a.id and v.status = 'POSTED')
                 or exists (
                    select 1 from opening_balance ob
                    where ob.ledger_id = a.ledger_id and ob.account_id = a.id and ob.confirmed)
                 or (a.legacy_code and (
                    exists (select 1 from voucher_line vl
                            where vl.ledger_id = a.ledger_id and vl.account_id = a.id)
                    or exists (select 1 from opening_balance ob
                               where ob.ledger_id = a.ledger_id and ob.account_id = a.id)
                 ))) core_locked
            from ledger_account a
            """;

    private final JdbcTemplate jdbc;

    public AccountManagementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AccountCodeRule codeRule(UUID ledgerId) {
        return jdbc.queryForObject("""
                select account_level2_width, account_level3_width, account_level4_width
                from ledger where id = ? and deleted_at is null
                """, (rs, row) -> new AccountCodeRule(
                rs.getInt(1), rs.getInt(2), rs.getInt(3)), ledgerId);
    }

    public boolean updateCodeRule(UUID ledgerId, AccountCodeRule rule) {
        return jdbc.update("""
                update ledger
                set account_level2_width = ?, account_level3_width = ?, account_level4_width = ?,
                    version = version + 1, updated_at = now()
                where id = ? and not exists (
                    select 1 from ledger_account where ledger_id = ? and level > 1)
                """, rule.level2Width(), rule.level3Width(), rule.level4Width(),
                ledgerId, ledgerId) == 1;
    }

    public void initializeCodeRule(UUID ledgerId, AccountCodeRule rule) {
        jdbc.update("""
                update ledger
                set account_level2_width = ?, account_level3_width = ?, account_level4_width = ?
                where id = ?
                """, rule.level2Width(), rule.level3Width(), rule.level4Width(), ledgerId);
    }

    public List<LedgerResponses.Account> list(UUID ledgerId) {
        List<LedgerResponses.Account> accounts = jdbc.query(
                ACCOUNT_SELECT + " where a.ledger_id = ? order by a.code",
                (rs, row) -> mapAccount(rs, List.of()), ledgerId);
        return attachDimensions(ledgerId, accounts);
    }

    public Optional<LedgerResponses.Account> find(UUID ledgerId, UUID accountId) {
        LedgerResponses.Account account = jdbc.query(
                ACCOUNT_SELECT + " where a.ledger_id = ? and a.id = ?",
                rs -> rs.next() ? mapAccount(rs, List.of()) : null, ledgerId, accountId);
        if (account == null) {
            return Optional.empty();
        }
        return Optional.of(copyWithDimensions(account, dimensions(ledgerId, accountId)));
    }

    public Optional<LedgerResponses.Account> findByCode(UUID ledgerId, String code) {
        LedgerResponses.Account account = jdbc.query(
                ACCOUNT_SELECT + " where a.ledger_id = ? and a.code = ?",
                rs -> rs.next() ? mapAccount(rs, List.of()) : null, ledgerId, code);
        if (account == null) {
            return Optional.empty();
        }
        return Optional.of(copyWithDimensions(account, dimensions(ledgerId, account.id())));
    }

    public void create(UUID id, UUID ledgerId, String code, String name, String category,
                       String normalBalance, UUID parentId, int level, boolean template,
                       boolean cashFlowRequired, UUID defaultCashFlowItemId,
                       boolean quantityEnabled, String unitName) {
        jdbc.update("""
                insert into ledger_account (
                    id, ledger_id, code, name, category, normal_balance, parent_id, level,
                    is_template, cash_flow_required, default_cash_flow_item_id,
                    quantity_enabled, unit_name)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, ledgerId, code, name, category, normalBalance, parentId, level, template,
                cashFlowRequired, defaultCashFlowItemId, quantityEnabled, unitName);
        bumpLedgerVersion(ledgerId);
    }

    public boolean createIfAbsent(UUID id, UUID ledgerId, String code, String name, String category,
                                  String normalBalance, UUID parentId, int level) {
        int inserted = jdbc.update("""
                insert into ledger_account (
                    id, ledger_id, code, name, category, normal_balance, parent_id, level)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (ledger_id, code) do nothing
                """, id, ledgerId, code, name, category, normalBalance, parentId, level);
        if (inserted == 1) {
            bumpLedgerVersion(ledgerId);
        }
        return inserted == 1;
    }

    public boolean update(UUID ledgerId, UUID accountId, long expectedVersion,
                          String code, String name, String category, String normalBalance,
                          String status, UUID parentId, int level, boolean cashFlowRequired,
                          UUID defaultCashFlowItemId, boolean quantityEnabled, String unitName) {
        int updated = jdbc.update("""
                update ledger_account
                set code = ?, name = ?, category = ?, normal_balance = ?, status = ?,
                    parent_id = ?, level = ?, cash_flow_required = ?,
                    default_cash_flow_item_id = ?, quantity_enabled = ?, unit_name = ?,
                    version = version + 1, updated_at = now()
                where ledger_id = ? and id = ? and version = ?
                """, code, name, category, normalBalance, status, parentId, level, cashFlowRequired,
                defaultCashFlowItemId, quantityEnabled, unitName, ledgerId, accountId, expectedVersion);
        if (updated == 1) {
            bumpLedgerVersion(ledgerId);
        }
        return updated == 1;
    }

    public boolean delete(UUID ledgerId, UUID accountId, long expectedVersion) {
        int deleted = jdbc.update("""
                delete from ledger_account
                where ledger_id = ? and id = ? and version = ?
                  and is_template = false
                  and not exists (
                      select 1 from ledger_account child
                      where child.ledger_id = ledger_account.ledger_id
                        and child.parent_id = ledger_account.id)
                  and not exists (
                      select 1 from voucher_line line
                      where line.ledger_id = ledger_account.ledger_id
                        and line.account_id = ledger_account.id)
                  and not exists (
                      select 1 from opening_balance balance
                      where balance.ledger_id = ledger_account.ledger_id
                        and balance.account_id = ledger_account.id)
                """, ledgerId, accountId, expectedVersion);
        if (deleted == 1) {
            bumpLedgerVersion(ledgerId);
        }
        return deleted == 1;
    }

    public boolean hasActiveDescendants(UUID ledgerId, UUID accountId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                with recursive descendants as (
                    select id, status from ledger_account
                    where ledger_id = ? and parent_id = ?
                    union all
                    select child.id, child.status
                    from ledger_account child join descendants parent on child.parent_id = parent.id
                    where child.ledger_id = ?
                )
                select exists (select 1 from descendants where status = 'ACTIVE')
                """, Boolean.class, ledgerId, accountId, ledgerId));
    }

    public boolean hasInactiveAncestors(UUID ledgerId, UUID accountId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                with recursive ancestors as (
                    select parent.id, parent.parent_id, parent.status
                    from ledger_account child join ledger_account parent on parent.id = child.parent_id
                    where child.ledger_id = ? and child.id = ? and parent.ledger_id = ?
                    union all
                    select parent.id, parent.parent_id, parent.status
                    from ledger_account parent join ancestors child on parent.id = child.parent_id
                    where parent.ledger_id = ?
                )
                select exists (select 1 from ancestors where status <> 'ACTIVE')
                """, Boolean.class, ledgerId, accountId, ledgerId, ledgerId));
    }

    public boolean validDimensionTypes(UUID ledgerId, List<LedgerRequests.DimensionRequirement> requirements) {
        if (requirements == null || requirements.isEmpty()) {
            return true;
        }
        long distinct = requirements.stream().map(LedgerRequests.DimensionRequirement::dimensionTypeId)
                .distinct().count();
        if (distinct != requirements.size()) {
            return false;
        }
        return requirements.stream().allMatch(requirement ->
                Boolean.TRUE.equals(jdbc.queryForObject("""
                        select exists (
                            select 1 from dimension_type
                            where ledger_id = ? and id = ? and status = 'ACTIVE')
                        """, Boolean.class, ledgerId, requirement.dimensionTypeId())));
    }

    public void replaceDimensions(UUID ledgerId, UUID accountId,
                                  List<LedgerRequests.DimensionRequirement> requirements) {
        jdbc.update("delete from ledger_account_dimension where ledger_id = ? and account_id = ?",
                ledgerId, accountId);
        if (requirements == null) {
            return;
        }
        for (LedgerRequests.DimensionRequirement requirement : requirements) {
            jdbc.update("""
                    insert into ledger_account_dimension (
                        account_id, ledger_id, dimension_type_id, required)
                    values (?, ?, ?, ?)
                    """, accountId, ledgerId, requirement.dimensionTypeId(), requirement.required());
        }
    }

    public void createCashFlowItem(UUID id, UUID ledgerId, String code, String name, boolean template) {
        jdbc.update("""
                insert into cash_flow_item (id, ledger_id, code, name, is_template)
                values (?, ?, ?, ?, ?)
                """, id, ledgerId, code, name, template);
    }

    public List<LedgerResponses.CashFlowItem> cashFlowItems(UUID ledgerId) {
        return jdbc.query("""
                select id, ledger_id, code, name, status, is_template
                from cash_flow_item where ledger_id = ? order by code
                """, (rs, row) -> new LedgerResponses.CashFlowItem(
                rs.getObject("id", UUID.class), rs.getObject("ledger_id", UUID.class),
                rs.getString("code"), rs.getString("name"), rs.getString("status"),
                rs.getBoolean("is_template")), ledgerId);
    }

    public boolean activeCashFlowItem(UUID ledgerId, UUID itemId) {
        if (itemId == null) {
            return true;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (
                    select 1 from cash_flow_item
                    where ledger_id = ? and id = ? and status = 'ACTIVE')
                """, Boolean.class, ledgerId, itemId));
    }

    public long ledgerVersion(UUID ledgerId) {
        Long version = jdbc.queryForObject("select version from ledger where id = ?", Long.class, ledgerId);
        return version == null ? 0 : version;
    }

    public void recordRevision(UUID ledgerId, UUID accountId, String action, UUID actorId,
                               String beforeJson, String afterJson) {
        jdbc.update("""
                insert into audit_revision (
                    id, ledger_id, aggregate_type, aggregate_id, revision, action,
                    actor_id, before_data, after_data)
                values (?, ?, 'ACCOUNT', ?, (
                    select coalesce(max(revision), 0) + 1
                    from audit_revision
                    where ledger_id = ? and aggregate_type = 'ACCOUNT' and aggregate_id = ?
                ), ?, ?, ?::jsonb, ?::jsonb)
                """, UUID.randomUUID(), ledgerId, accountId, ledgerId, accountId,
                action, actorId, beforeJson, afterJson);
    }

    private List<LedgerResponses.Account> attachDimensions(
            UUID ledgerId, List<LedgerResponses.Account> accounts) {
        Map<UUID, List<LedgerResponses.DimensionRequirement>> byAccount = new LinkedHashMap<>();
        jdbc.query("""
                select ad.account_id, dt.id, dt.code, dt.name, ad.required
                from ledger_account_dimension ad
                join dimension_type dt
                  on dt.ledger_id = ad.ledger_id and dt.id = ad.dimension_type_id
                where ad.ledger_id = ?
                order by ad.account_id, dt.code
                """, rs -> {
            UUID accountId = rs.getObject("account_id", UUID.class);
            byAccount.computeIfAbsent(accountId, ignored -> new ArrayList<>()).add(
                    mapDimension(rs));
        }, ledgerId);
        return accounts.stream()
                .map(account -> copyWithDimensions(account,
                        byAccount.getOrDefault(account.id(), List.of())))
                .toList();
    }

    private List<LedgerResponses.DimensionRequirement> dimensions(UUID ledgerId, UUID accountId) {
        return jdbc.query("""
                select dt.id, dt.code, dt.name, ad.required
                from ledger_account_dimension ad
                join dimension_type dt
                  on dt.ledger_id = ad.ledger_id and dt.id = ad.dimension_type_id
                where ad.ledger_id = ? and ad.account_id = ?
                order by dt.code
                """, (rs, row) -> mapDimension(rs), ledgerId, accountId);
    }

    private LedgerResponses.DimensionRequirement mapDimension(ResultSet rs) throws SQLException {
        return new LedgerResponses.DimensionRequirement(
                rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("name"), rs.getBoolean("required"));
    }

    private LedgerResponses.Account mapAccount(
            ResultSet rs, List<LedgerResponses.DimensionRequirement> dimensions) throws SQLException {
        return new LedgerResponses.Account(
                rs.getObject("id", UUID.class), rs.getObject("ledger_id", UUID.class),
                rs.getString("code"), rs.getString("name"), rs.getString("category"),
                rs.getString("normal_balance"), rs.getString("status"),
                rs.getObject("parent_id", UUID.class), rs.getInt("level"), rs.getBoolean("leaf"),
                rs.getBoolean("is_template"), rs.getBoolean("has_business_usage"),
                rs.getBoolean("core_locked"), rs.getBoolean("legacy_code"), rs.getLong("version"),
                rs.getBoolean("cash_flow_required"),
                rs.getObject("default_cash_flow_item_id", UUID.class),
                rs.getBoolean("quantity_enabled"), rs.getString("unit_name"), dimensions);
    }

    private LedgerResponses.Account copyWithDimensions(
            LedgerResponses.Account account, List<LedgerResponses.DimensionRequirement> dimensions) {
        return new LedgerResponses.Account(
                account.id(), account.ledgerId(), account.code(), account.name(), account.category(),
                account.normalBalance(), account.status(), account.parentId(), account.level(),
                account.isLeaf(), account.isTemplate(), account.hasBusinessUsage(), account.coreLocked(),
                account.legacyCode(), account.version(), account.cashFlowRequired(),
                account.defaultCashFlowItemId(), account.quantityEnabled(), account.unitName(), dimensions);
    }

    private void bumpLedgerVersion(UUID ledgerId) {
        jdbc.update("update ledger set version = version + 1, updated_at = now() where id = ?", ledgerId);
    }
}
