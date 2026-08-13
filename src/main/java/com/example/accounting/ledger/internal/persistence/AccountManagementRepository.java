package com.example.accounting.ledger.internal.persistence;

import com.example.accounting.ledger.AccountCodeRule;
import com.example.accounting.ledger.LedgerRequests;
import com.example.accounting.ledger.LedgerResponses;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountManagementRepository {

    private static final String ACCOUNT_SELECT = """
            select a.id, a.ledger_id, a.code, a.name, a.category, a.normal_balance, a.status,
                a.parent_id, a.level, a.is_template, a.legacy_code, a.version,
                a.cash_flow_required, a.default_cash_flow_item_id, a.quantity_enabled, a.unit_name,
                a.created_at,
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

    public List<LedgerResponses.Account> listCreatedBetween(
            UUID ledgerId, OffsetDateTime startInclusive, OffsetDateTime endExclusive) {
        List<LedgerResponses.Account> accounts = jdbc.query(
                ACCOUNT_SELECT + """
                         where a.ledger_id = ?
                           and a.created_at >= ?
                           and a.created_at < ?
                         order by a.code
                        """,
                (rs, row) -> mapAccount(rs, List.of()), ledgerId, startInclusive, endExclusive);
        return attachDimensionsForAccounts(ledgerId, accounts);
    }

    public List<LedgerResponses.AccountSearchResult> search(
            UUID ledgerId, String query, LedgerRequests.AccountMatchMode matchMode, int limit) {
        List<LedgerResponses.Account> matches = switch (matchMode) {
            case EXACT -> jdbc.query(ACCOUNT_SELECT + """
                     where a.ledger_id = ?
                       and (lower(a.code) = lower(?) or lower(a.name) = lower(?))
                     order by case when lower(a.code) = lower(?) then 0 else 1 end, a.code
                     limit ?
                    """, (rs, row) -> mapAccount(rs, List.of()),
                    ledgerId, query, query, query, limit);
            case FUZZY -> jdbc.query(ACCOUNT_SELECT + """
                     where a.ledger_id = ?
                       and (position(lower(?) in lower(a.code)) > 0
                            or position(lower(?) in lower(a.name)) > 0)
                     order by case
                         when lower(a.code) = lower(?) then 0
                         when lower(a.name) = lower(?) then 1
                         when position(lower(?) in lower(a.code)) = 1 then 2
                         when position(lower(?) in lower(a.name)) = 1 then 3
                         else 4
                     end, a.code
                     limit ?
                    """, (rs, row) -> mapAccount(rs, List.of()),
                    ledgerId, query, query, query, query, query, query, limit);
        };
        if (matches.isEmpty()) {
            return List.of();
        }

        List<LedgerResponses.Account> accounts = attachDimensionsForAccounts(ledgerId, matches);
        Set<UUID> parentIds = accounts.stream()
                .map(LedgerResponses.Account::parentId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<UUID> accountIds = accounts.stream()
                .map(LedgerResponses.Account::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, LedgerResponses.AccountSummary> parents = summariesByIds(ledgerId, parentIds);
        Map<UUID, List<LedgerResponses.AccountSummary>> children = childrenByParentIds(ledgerId, accountIds);

        return accounts.stream().map(account -> new LedgerResponses.AccountSearchResult(
                account,
                account.parentId() == null ? null : parents.get(account.parentId()),
                children.getOrDefault(account.id(), List.of()))).toList();
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

    public boolean hasVoucherLines(UUID ledgerId, UUID accountId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (
                    select 1 from voucher_line
                    where ledger_id = ? and account_id = ?)
                """, Boolean.class, ledgerId, accountId));
    }

    public boolean hasOpeningBalances(UUID ledgerId, UUID accountId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (
                    select 1 from opening_balance
                    where ledger_id = ? and account_id = ?)
                """, Boolean.class, ledgerId, accountId));
    }

    public Optional<String> findConfigurationReference(UUID ledgerId, UUID accountId) {
        return Optional.ofNullable(jdbc.query("""
                select reference from (
                    select 'fixed_asset_category.asset_account_id' reference
                    from fixed_asset_category where ledger_id = ? and asset_account_id = ?
                    union all select 'fixed_asset_category.accumulated_depreciation_account_id'
                    from fixed_asset_category where ledger_id = ? and accumulated_depreciation_account_id = ?
                    union all select 'fixed_asset_category.depreciation_expense_account_id'
                    from fixed_asset_category where ledger_id = ? and depreciation_expense_account_id = ?
                    union all select 'fixed_asset_category.impairment_account_id'
                    from fixed_asset_category where ledger_id = ? and impairment_account_id = ?
                    union all select 'fixed_asset_category.clearing_account_id'
                    from fixed_asset_category where ledger_id = ? and clearing_account_id = ?
                    union all select 'fixed_asset_category.disposal_gain_account_id'
                    from fixed_asset_category where ledger_id = ? and disposal_gain_account_id = ?
                    union all select 'fixed_asset_category.disposal_loss_account_id'
                    from fixed_asset_category where ledger_id = ? and disposal_loss_account_id = ?
                    union all select 'fixed_asset.asset_account_id'
                    from fixed_asset where ledger_id = ? and asset_account_id = ?
                    union all select 'fixed_asset.accumulated_depreciation_account_id'
                    from fixed_asset where ledger_id = ? and accumulated_depreciation_account_id = ?
                    union all select 'fixed_asset.depreciation_expense_account_id'
                    from fixed_asset where ledger_id = ? and depreciation_expense_account_id = ?
                    union all select 'fixed_asset.impairment_account_id'
                    from fixed_asset where ledger_id = ? and impairment_account_id = ?
                    union all select 'fixed_asset.clearing_account_id'
                    from fixed_asset where ledger_id = ? and clearing_account_id = ?
                    union all select 'fixed_asset.disposal_gain_account_id'
                    from fixed_asset where ledger_id = ? and disposal_gain_account_id = ?
                    union all select 'fixed_asset.disposal_loss_account_id'
                    from fixed_asset where ledger_id = ? and disposal_loss_account_id = ?
                    union all select 'fixed_asset_depreciation_line.expense_account_id'
                    from fixed_asset_depreciation_line where ledger_id = ? and expense_account_id = ?
                    union all select 'fixed_asset_depreciation_line.accumulated_account_id'
                    from fixed_asset_depreciation_line where ledger_id = ? and accumulated_account_id = ?
                    union all select 'fixed_asset_disposal.receipt_account_id'
                    from fixed_asset_disposal where ledger_id = ? and receipt_account_id = ?
                    union all select 'fixed_asset_disposal.payment_account_id'
                    from fixed_asset_disposal where ledger_id = ? and payment_account_id = ?
                    union all select 'fixed_asset_disposal.output_tax_account_id'
                    from fixed_asset_disposal where ledger_id = ? and output_tax_account_id = ?
                    union all select 'fixed_asset_disposal.input_tax_account_id'
                    from fixed_asset_disposal where ledger_id = ? and input_tax_account_id = ?
                ) references_found limit 1
                """, rs -> rs.next() ? rs.getString(1) : null,
                ledgerId, accountId, ledgerId, accountId, ledgerId, accountId, ledgerId, accountId,
                ledgerId, accountId, ledgerId, accountId, ledgerId, accountId,
                ledgerId, accountId, ledgerId, accountId, ledgerId, accountId, ledgerId, accountId,
                ledgerId, accountId, ledgerId, accountId, ledgerId, accountId,
                ledgerId, accountId, ledgerId, accountId, ledgerId, accountId, ledgerId, accountId,
                ledgerId, accountId, ledgerId, accountId, ledgerId, accountId));
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
        return withDimensions(accounts, byAccount);
    }

    private List<LedgerResponses.Account> attachDimensionsForAccounts(
            UUID ledgerId, List<LedgerResponses.Account> accounts) {
        if (accounts.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<LedgerResponses.DimensionRequirement>> byAccount = new LinkedHashMap<>();
        Object[] arguments = new Object[accounts.size() + 1];
        arguments[0] = ledgerId;
        for (int index = 0; index < accounts.size(); index++) {
            arguments[index + 1] = accounts.get(index).id();
        }
        jdbc.query("""
                select ad.account_id, dt.id, dt.code, dt.name, ad.required
                from ledger_account_dimension ad
                join dimension_type dt
                  on dt.ledger_id = ad.ledger_id and dt.id = ad.dimension_type_id
                where ad.ledger_id = ? and ad.account_id in (%s)
                order by ad.account_id, dt.code
                """.formatted(placeholders(accounts.size())), rs -> {
            UUID accountId = rs.getObject("account_id", UUID.class);
            byAccount.computeIfAbsent(accountId, ignored -> new ArrayList<>()).add(mapDimension(rs));
        }, arguments);
        return withDimensions(accounts, byAccount);
    }

    private List<LedgerResponses.Account> withDimensions(
            List<LedgerResponses.Account> accounts,
            Map<UUID, List<LedgerResponses.DimensionRequirement>> byAccount) {
        return accounts.stream()
                .map(account -> copyWithDimensions(account,
                        byAccount.getOrDefault(account.id(), List.of())))
                .toList();
    }

    private Map<UUID, LedgerResponses.AccountSummary> summariesByIds(UUID ledgerId, Set<UUID> accountIds) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        Object[] arguments = arguments(ledgerId, accountIds);
        Map<UUID, LedgerResponses.AccountSummary> summaries = new LinkedHashMap<>();
        jdbc.query("""
                select id, code, name, status
                from ledger_account
                where ledger_id = ? and id in (%s)
                order by code
                """.formatted(placeholders(accountIds.size())), rs -> {
            LedgerResponses.AccountSummary summary = mapSummary(rs);
            summaries.put(summary.id(), summary);
        }, arguments);
        return summaries;
    }

    public Map<UUID, String> codesByIds(UUID ledgerId, Set<UUID> accountIds) {
        Map<UUID, String> codes = new LinkedHashMap<>();
        summariesByIds(ledgerId, accountIds).values()
                .forEach(account -> codes.put(account.id(), account.code()));
        return codes;
    }

    private Map<UUID, List<LedgerResponses.AccountSummary>> childrenByParentIds(
            UUID ledgerId, Set<UUID> parentIds) {
        if (parentIds.isEmpty()) {
            return Map.of();
        }
        Object[] arguments = arguments(ledgerId, parentIds);
        Map<UUID, List<LedgerResponses.AccountSummary>> children = new LinkedHashMap<>();
        jdbc.query("""
                select id, parent_id, code, name, status
                from ledger_account
                where ledger_id = ? and parent_id in (%s)
                order by code
                """.formatted(placeholders(parentIds.size())), rs -> {
            UUID parentId = rs.getObject("parent_id", UUID.class);
            children.computeIfAbsent(parentId, ignored -> new ArrayList<>()).add(mapSummary(rs));
        }, arguments);
        return children;
    }

    private Object[] arguments(UUID ledgerId, Set<UUID> accountIds) {
        Object[] arguments = new Object[accountIds.size() + 1];
        arguments[0] = ledgerId;
        int index = 1;
        for (UUID accountId : accountIds) {
            arguments[index++] = accountId;
        }
        return arguments;
    }

    private String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
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

    private LedgerResponses.AccountSummary mapSummary(ResultSet rs) throws SQLException {
        return new LedgerResponses.AccountSummary(
                rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("name"), rs.getString("status"));
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
                rs.getBoolean("quantity_enabled"), rs.getString("unit_name"), dimensions,
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private LedgerResponses.Account copyWithDimensions(
            LedgerResponses.Account account, List<LedgerResponses.DimensionRequirement> dimensions) {
        return new LedgerResponses.Account(
                account.id(), account.ledgerId(), account.code(), account.name(), account.category(),
                account.normalBalance(), account.status(), account.parentId(), account.level(),
                account.isLeaf(), account.isTemplate(), account.hasBusinessUsage(), account.coreLocked(),
                account.legacyCode(), account.version(), account.cashFlowRequired(),
                account.defaultCashFlowItemId(), account.quantityEnabled(), account.unitName(), dimensions,
                account.createdAt());
    }

    private void bumpLedgerVersion(UUID ledgerId) {
        jdbc.update("update ledger set version = version + 1, updated_at = now() where id = ?", ledgerId);
    }
}
