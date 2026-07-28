package com.example.accounting.ledger;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.identity.IdentityService;
import com.example.accounting.shared.web.ApiProblemException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.example.accounting.ledger.LedgerResponses.Ledger;
import static com.example.accounting.ledger.LedgerResponses.Member;

@Service
public class LedgerService {

    private static final List<AccountTemplate> SME_ACCOUNTS = List.of(
            new AccountTemplate("1001", "库存现金", "ASSET", "DEBIT"),
            new AccountTemplate("1002", "银行存款", "ASSET", "DEBIT"),
            new AccountTemplate("1122", "应收账款", "ASSET", "DEBIT"),
            new AccountTemplate("1403", "原材料", "ASSET", "DEBIT"),
            new AccountTemplate("1601", "固定资产", "ASSET", "DEBIT"),
            new AccountTemplate("1701", "无形资产", "ASSET", "DEBIT"),
            new AccountTemplate("2001", "短期借款", "LIABILITY", "CREDIT"),
            new AccountTemplate("2202", "应付账款", "LIABILITY", "CREDIT"),
            new AccountTemplate("2241", "其他应付款", "LIABILITY", "CREDIT"),
            new AccountTemplate("3001", "实收资本", "EQUITY", "CREDIT"),
            new AccountTemplate("3103", "本年利润", "EQUITY", "CREDIT"),
            new AccountTemplate("4001", "生产成本", "COST", "DEBIT"),
            new AccountTemplate("5001", "主营业务收入", "REVENUE", "CREDIT"),
            new AccountTemplate("5401", "主营业务成本", "EXPENSE", "DEBIT"),
            new AccountTemplate("5601", "管理费用", "EXPENSE", "DEBIT"));

    private static final List<FormulaTemplate> SME_FORMULAS = List.of(
            new FormulaTemplate("BALANCE_SHEET", "Balance Sheet",
                    "{\"type\":\"balance-sheet\",\"debitCategories\":[\"ASSET\"],\"creditCategories\":[\"LIABILITY\",\"EQUITY\"]}"),
            new FormulaTemplate("INCOME_STATEMENT", "Income Statement",
                    "{\"type\":\"income-statement\",\"revenueCategories\":[\"REVENUE\"],\"expenseCategories\":[\"COST\",\"EXPENSE\"]}"));

    private final JdbcTemplate jdbcTemplate;
    private final IdentityService identityService;

    public LedgerService(JdbcTemplate jdbcTemplate, IdentityService identityService) {
        this.jdbcTemplate = jdbcTemplate;
        this.identityService = identityService;
    }

    @Transactional
    public Ledger create(CurrentUserResolver.ResolvedUser actor, LedgerRequests.Create request) {
        UUID actorId = actor.id();
        identityService.ensureUser(actor);
        UUID ledgerId = UUID.randomUUID();
        boolean approvalEnabled = Boolean.TRUE.equals(request.approvalEnabled());
        jdbcTemplate.update("""
                insert into ledger (id, name, accounting_standard_code, accounting_standard_version,
                    base_currency, start_date, approval_enabled, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, ledgerId, request.name().trim(), request.accountingStandardCode().trim(),
                request.accountingStandardVersion().trim(), request.baseCurrency(), request.startDate(),
                approvalEnabled, actorId, actorId);
        jdbcTemplate.update("""
                insert into ledger_membership (id, ledger_id, user_id, role, created_by, updated_by)
                values (?, ?, ?, 'OWNER', ?, ?)
                """, UUID.randomUUID(), ledgerId, actorId, actorId, actorId);
        initializeLedger(ledgerId, request.startDate());
        return findLedger(actorId, ledgerId);
    }

    @Transactional(readOnly = true)
    public List<Ledger> list(UUID actorId) {
        return jdbcTemplate.query("""
                select l.id, l.name, l.accounting_standard_code, l.accounting_standard_version,
                    l.base_currency, l.start_date, l.approval_enabled, l.status
                from ledger l
                join ledger_membership m on m.ledger_id = l.id
                where m.user_id = ? and m.status = 'ACTIVE' and l.status = 'ACTIVE' and l.deleted_at is null
                order by l.name, l.id
                """, this::mapLedger, actorId);
    }

    @Transactional(readOnly = true)
    public Ledger findLedger(UUID actorId, UUID ledgerId) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR, LedgerRole.REVIEWER,
                LedgerRole.VIEWER, LedgerRole.AGENT));
        return jdbcTemplate.queryForObject("""
                select id, name, accounting_standard_code, accounting_standard_version,
                    base_currency, start_date, approval_enabled, status
                from ledger where id = ? and deleted_at is null
                """, this::mapLedger, ledgerId);
    }

    @Transactional(readOnly = true)
    public List<Member> listMembers(UUID actorId, UUID ledgerId) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR, LedgerRole.REVIEWER,
                LedgerRole.VIEWER));
        return jdbcTemplate.query("""
                select user_id, role, status from ledger_membership
                where ledger_id = ? and deleted_at is null order by user_id
                """, (rs, rowNum) -> new Member(rs.getObject("user_id", UUID.class),
                LedgerRole.valueOf(rs.getString("role")), MembershipStatus.valueOf(rs.getString("status"))), ledgerId);
    }

    @Transactional(readOnly = true)
    public List<LedgerResponses.Account> listAccounts(UUID actorId, UUID ledgerId) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR, LedgerRole.REVIEWER,
                LedgerRole.VIEWER, LedgerRole.AGENT));
        return jdbcTemplate.query("""
                select id, ledger_id, code, name, category, normal_balance, status
                from ledger_account where ledger_id = ? order by code
                """, (rs, rowNum) -> new LedgerResponses.Account(rs.getObject("id", UUID.class),
                rs.getObject("ledger_id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("category"), rs.getString("normal_balance"), rs.getString("status")), ledgerId);
    }

    @Transactional(readOnly = true)
    public List<LedgerResponses.Period> listPeriods(UUID actorId, UUID ledgerId) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR, LedgerRole.REVIEWER,
                LedgerRole.VIEWER, LedgerRole.AGENT));
        return jdbcTemplate.query("""
                select id, ledger_id, period_code, start_date, end_date, status
                from accounting_period where ledger_id = ? order by period_code
                """, this::mapPeriod, ledgerId);
    }

    @Transactional
    public LedgerResponses.Period closePeriod(UUID actorId, UUID ledgerId, UUID periodId,
                                               LedgerRequests.PeriodAction request) {
        return changePeriod(actorId, ledgerId, periodId, request, "OPEN", "CLOSED", "CLOSE");
    }

    @Transactional
    public LedgerResponses.Period reopenPeriod(UUID actorId, UUID ledgerId, UUID periodId,
                                                LedgerRequests.PeriodAction request) {
        return changePeriod(actorId, ledgerId, periodId, request, "CLOSED", "OPEN", "REOPEN");
    }

    @Transactional(readOnly = true)
    public List<LedgerResponses.DimensionType> listDimensionTypes(UUID actorId, UUID ledgerId) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR, LedgerRole.REVIEWER,
                LedgerRole.VIEWER, LedgerRole.AGENT));
        return jdbcTemplate.query("""
                select id, ledger_id, code, name, required, status
                from dimension_type where ledger_id = ? order by code
                """, (rs, rowNum) -> new LedgerResponses.DimensionType(rs.getObject("id", UUID.class),
                rs.getObject("ledger_id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getBoolean("required"), rs.getString("status")), ledgerId);
    }

    @Transactional
    public LedgerResponses.DimensionType createDimensionType(UUID actorId, UUID ledgerId,
                                                               LedgerRequests.DimensionTypeCreate request) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR));
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into dimension_type (id, ledger_id, code, name, required)
                values (?, ?, ?, ?, ?)
                """, id, ledgerId, request.code().trim().toUpperCase(Locale.ROOT), request.name().trim(),
                Boolean.TRUE.equals(request.required()));
        return jdbcTemplate.queryForObject("""
                select id, ledger_id, code, name, required, status from dimension_type where id = ?
                """, (rs, rowNum) -> new LedgerResponses.DimensionType(rs.getObject("id", UUID.class),
                rs.getObject("ledger_id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getBoolean("required"), rs.getString("status")), id);
    }

    @Transactional(readOnly = true)
    public List<LedgerResponses.DimensionValue> listDimensionValues(UUID actorId, UUID ledgerId, UUID typeId) {
        requireDimensionType(actorId, ledgerId, typeId, false);
        return jdbcTemplate.query("""
                select id, ledger_id, dimension_type_id, code, name, status
                from dimension_value where ledger_id = ? and dimension_type_id = ? order by code
                """, (rs, rowNum) -> new LedgerResponses.DimensionValue(rs.getObject("id", UUID.class),
                rs.getObject("ledger_id", UUID.class), rs.getObject("dimension_type_id", UUID.class),
                rs.getString("code"), rs.getString("name"), rs.getString("status")), ledgerId, typeId);
    }

    @Transactional
    public LedgerResponses.DimensionValue createDimensionValue(UUID actorId, UUID ledgerId, UUID typeId,
                                                                 LedgerRequests.DimensionValueCreate request) {
        requireDimensionType(actorId, ledgerId, typeId, true);
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into dimension_value (id, ledger_id, dimension_type_id, code, name)
                values (?, ?, ?, ?, ?)
                """, id, ledgerId, typeId, request.code().trim(), request.name().trim());
        return jdbcTemplate.queryForObject("""
                select id, ledger_id, dimension_type_id, code, name, status from dimension_value where id = ?
                """, (rs, rowNum) -> new LedgerResponses.DimensionValue(rs.getObject("id", UUID.class),
                rs.getObject("ledger_id", UUID.class), rs.getObject("dimension_type_id", UUID.class),
                rs.getString("code"), rs.getString("name"), rs.getString("status")), id);
    }

    @Transactional(readOnly = true)
    public List<LedgerResponses.OpeningBalance> listOpeningBalances(UUID actorId, UUID ledgerId) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR, LedgerRole.REVIEWER,
                LedgerRole.VIEWER, LedgerRole.AGENT));
        return jdbcTemplate.query("""
                select id, ledger_id, period_id, account_id, currency, dimension_key,
                    debit_original, credit_original, exchange_rate, debit_base, credit_base, confirmed
                from opening_balance where ledger_id = ? order by period_id, account_id, currency, dimension_key
                """, (rs, rowNum) -> mapOpeningBalance(rs, rowNum), ledgerId);
    }

    @Transactional
    public List<LedgerResponses.OpeningBalance> replaceOpeningBalances(UUID actorId, UUID ledgerId,
                                                                         List<LedgerRequests.OpeningBalanceLine> lines) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR));
        for (LedgerRequests.OpeningBalanceLine line : lines) {
            validateOpeningBalanceLine(ledgerId, line);
            BigDecimal debit = money(line.debitOriginal());
            BigDecimal credit = money(line.creditOriginal());
            BigDecimal rate = line.exchangeRate().setScale(8, RoundingMode.HALF_UP);
            BigDecimal debitBase = debit.multiply(rate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal creditBase = credit.multiply(rate).setScale(2, RoundingMode.HALF_UP);
            String dimensionKey = line.dimensionKey() == null ? "" : line.dimensionKey().trim();
            int updated = jdbcTemplate.update("""
                    insert into opening_balance (id, ledger_id, period_id, account_id, currency, dimension_key,
                        debit_original, credit_original, exchange_rate, debit_base, credit_base)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (ledger_id, period_id, account_id, currency, dimension_key)
                    do update set debit_original = excluded.debit_original, credit_original = excluded.credit_original,
                        exchange_rate = excluded.exchange_rate, debit_base = excluded.debit_base,
                        credit_base = excluded.credit_base, updated_at = now()
                    where opening_balance.confirmed = false
                    """, UUID.randomUUID(), ledgerId, line.periodId(), line.accountId(), line.currency(), dimensionKey,
                    debit, credit, rate, debitBase, creditBase);
            if (updated == 0) {
                throw problem(409, "OPENING_BALANCE_CONFIRMED", "Opening balance is confirmed",
                        "Confirmed opening balances cannot be changed");
            }
        }
        return listOpeningBalances(actorId, ledgerId);
    }

    @Transactional
    public int confirmOpeningBalances(UUID actorId, UUID ledgerId) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR));
        BigDecimal debit = jdbcTemplate.queryForObject(
                "select coalesce(sum(debit_base), 0) from opening_balance where ledger_id = ?", BigDecimal.class,
                ledgerId);
        BigDecimal credit = jdbcTemplate.queryForObject(
                "select coalesce(sum(credit_base), 0) from opening_balance where ledger_id = ?", BigDecimal.class,
                ledgerId);
        if (debit.compareTo(credit) != 0) {
            throw problem(422, "OPENING_BALANCE_UNBALANCED", "Opening balance is not balanced",
                    "Opening balance debit and credit totals must balance");
        }
        return jdbcTemplate.update("update opening_balance set confirmed = true, updated_at = now() "
                + "where ledger_id = ? and confirmed = false", ledgerId);
    }

    @Transactional
    public List<LedgerResponses.OpeningBalance> importOpeningBalances(UUID actorId, UUID ledgerId,
                                                                        InputStream input) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR));
        List<LedgerRequests.OpeningBalanceLine> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            String expected = "periodCode,accountCode,currency,dimensionKey,debitOriginal,creditOriginal,exchangeRate";
            if (!expected.equals(header)) {
                throw csvProblem(1, "header", "expected " + expected);
            }
            String row;
            int rowNumber = 1;
            while ((row = reader.readLine()) != null) {
                rowNumber++;
                if (row.isBlank()) {
                    continue;
                }
                // ponytail: v1 accepts simple CSV cells; quoted comma fields can be added with a CSV library when needed.
                String[] cells = row.split(",", -1);
                if (cells.length != 7) {
                    throw csvProblem(rowNumber, "row", "expected 7 columns");
                }
                UUID period = lookupPeriod(ledgerId, cells[0].trim(), rowNumber);
                UUID account = lookupAccount(ledgerId, cells[1].trim(), rowNumber);
                String currency = cells[2].trim();
                if (!currency.matches("[A-Z]{3}")) {
                    throw csvProblem(rowNumber, "currency", "must be three uppercase letters");
                }
                lines.add(new LedgerRequests.OpeningBalanceLine(account, period, currency, cells[3].trim(),
                        csvDecimal(cells[4], rowNumber, "debitOriginal"),
                        csvDecimal(cells[5], rowNumber, "creditOriginal"),
                        csvDecimal(cells[6], rowNumber, "exchangeRate")));
            }
        } catch (IOException exception) {
            throw problem(422, "OPENING_BALANCE_CSV_INVALID", "Invalid CSV", "The CSV could not be read");
        }
        return replaceOpeningBalances(actorId, ledgerId, lines);
    }

    UUID accountId(UUID ledgerId, String code) {
        return jdbcTemplate.queryForObject("select id from ledger_account where ledger_id = ? and code = ?",
                UUID.class, ledgerId, code);
    }

    UUID periodId(UUID ledgerId, String periodCode) {
        return jdbcTemplate.queryForObject("select id from accounting_period where ledger_id = ? and period_code = ?",
                UUID.class, ledgerId, periodCode);
    }

    @Transactional
    public Member addMember(UUID actorId, UUID ledgerId, LedgerRequests.AddMember request) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER));
        if (!userExists(request.userId())) {
            throw problem(404, "USER_NOT_FOUND", "User not found", "The user must call /v1/me before being added");
        }
        jdbcTemplate.update("""
                insert into ledger_membership (id, ledger_id, user_id, role, created_by, updated_by, deleted_at)
                values (?, ?, ?, ?, ?, ?, null)
                on conflict (ledger_id, user_id) do update set role = excluded.role, status = 'ACTIVE',
                    updated_at = now(), updated_by = excluded.updated_by, deleted_at = null
                """, UUID.randomUUID(), ledgerId, request.userId(), request.role().name(), actorId, actorId);
        return jdbcTemplate.queryForObject("""
                select user_id, role, status from ledger_membership where ledger_id = ? and user_id = ?
                """, (rs, rowNum) -> new Member(rs.getObject("user_id", UUID.class),
                LedgerRole.valueOf(rs.getString("role")), MembershipStatus.valueOf(rs.getString("status"))), ledgerId,
                request.userId());
    }

    @Transactional
    public Member updateMember(UUID actorId, UUID ledgerId, UUID userId, LedgerRequests.UpdateMember request) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER));
        ensureNotRemovingLastOwner(ledgerId, userId, request.role(), request.status());
        int updated = jdbcTemplate.update("""
                update ledger_membership set role = ?, status = ?, updated_at = now(), updated_by = ?
                where ledger_id = ? and user_id = ? and deleted_at is null
                """, request.role().name(), request.status().name(), actorId, ledgerId, userId);
        if (updated == 0) {
            throw problem(404, "MEMBERSHIP_NOT_FOUND", "Membership not found", "The ledger member does not exist");
        }
        return jdbcTemplate.queryForObject("""
                select user_id, role, status from ledger_membership where ledger_id = ? and user_id = ?
                """, (rs, rowNum) -> new Member(rs.getObject("user_id", UUID.class),
                LedgerRole.valueOf(rs.getString("role")), MembershipStatus.valueOf(rs.getString("status"))), ledgerId,
                userId);
    }

    @Transactional
    public void removeMember(UUID actorId, UUID ledgerId, UUID userId) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER));
        ensureNotRemovingLastOwner(ledgerId, userId, LedgerRole.OWNER, MembershipStatus.INACTIVE);
        int updated = jdbcTemplate.update("""
                update ledger_membership set status = 'INACTIVE', deleted_at = now(), updated_at = now(), updated_by = ?
                where ledger_id = ? and user_id = ? and deleted_at is null
                """, actorId, ledgerId, userId);
        if (updated == 0) {
            throw problem(404, "MEMBERSHIP_NOT_FOUND", "Membership not found", "The ledger member does not exist");
        }
    }

    private void requireRole(UUID actorId, UUID ledgerId, Set<LedgerRole> roles) {
        String role = jdbcTemplate.query("""
                select m.role from ledger_membership m join ledger l on l.id = m.ledger_id
                where m.ledger_id = ? and m.user_id = ? and m.status = 'ACTIVE'
                    and m.deleted_at is null and l.deleted_at is null
                """, rs -> rs.next() ? rs.getString(1) : null, ledgerId, actorId);
        if (role == null) {
            throw problem(404, "LEDGER_NOT_FOUND", "Ledger not found", "The ledger is not available to this user");
        }
        if (!roles.contains(LedgerRole.valueOf(role))) {
            throw problem(403, "INSUFFICIENT_LEDGER_ROLE", "Insufficient ledger role",
                    "The current user cannot perform this operation");
        }
    }

    private LedgerResponses.Period changePeriod(UUID actorId, UUID ledgerId, UUID periodId,
                                                 LedgerRequests.PeriodAction request, String expectedStatus,
                                                 String nextStatus, String action) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER));
        LedgerResponses.Period period = jdbcTemplate.query("""
                select id, ledger_id, period_code, start_date, end_date, status
                from accounting_period where ledger_id = ? and id = ?
                """, rs -> rs.next() ? mapPeriod(rs, 0) : null, ledgerId, periodId);
        if (period == null) {
            throw problem(404, "PERIOD_NOT_FOUND", "Period not found", "The period is not available to this ledger");
        }
        if (!expectedStatus.equals(period.status())) {
            throw problem(409, "PERIOD_STATE_INVALID", "Invalid period state",
                    "The period must be " + expectedStatus + " before it can be changed");
        }
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.isEmpty()) {
            throw problem(422, "PERIOD_REASON_REQUIRED", "Reason is required", "A reason is required for period changes");
        }
        jdbcTemplate.update("update accounting_period set status = ? where ledger_id = ? and id = ?",
                nextStatus, ledgerId, periodId);
        jdbcTemplate.update("""
                insert into period_action_audit (id, ledger_id, period_id, action, reason, actor_id)
                values (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), ledgerId, periodId, action, reason, actorId);
        return new LedgerResponses.Period(period.id(), period.ledgerId(), period.periodCode(), period.startDate(),
                period.endDate(), nextStatus);
    }

    private void requireDimensionType(UUID actorId, UUID ledgerId, UUID typeId, boolean write) {
        requireRole(actorId, ledgerId, write
                ? Set.of(LedgerRole.OWNER, LedgerRole.EDITOR)
                : Set.of(LedgerRole.OWNER, LedgerRole.EDITOR, LedgerRole.REVIEWER, LedgerRole.VIEWER, LedgerRole.AGENT));
        boolean exists = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "select exists (select 1 from dimension_type where id = ? and ledger_id = ? and status = 'ACTIVE')",
                Boolean.class, typeId, ledgerId));
        if (!exists) {
            throw problem(404, "DIMENSION_TYPE_NOT_FOUND", "Dimension type not found",
                    "The dimension type is not available to this ledger");
        }
    }

    private void validateOpeningBalanceLine(UUID ledgerId, LedgerRequests.OpeningBalanceLine line) {
        if (line.debitOriginal().signum() < 0 || line.creditOriginal().signum() < 0
                || (line.debitOriginal().signum() > 0 && line.creditOriginal().signum() > 0)
                || line.exchangeRate().signum() <= 0) {
            throw problem(422, "INVALID_OPENING_BALANCE", "Invalid opening balance",
                    "Amounts must be non-negative with one side populated and exchange rate must be positive");
        }
        boolean valid = Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                select exists (
                    select 1 from ledger_account a join accounting_period p on p.ledger_id = a.ledger_id
                    where a.ledger_id = ? and a.id = ? and a.status = 'ACTIVE'
                      and p.id = ? and p.status = 'OPEN')
                """, Boolean.class, ledgerId, line.accountId(), line.periodId()));
        if (!valid) {
            throw problem(422, "INVALID_OPENING_BALANCE_REFERENCE", "Invalid opening balance reference",
                    "The account and period must belong to this ledger and the period must be open");
        }
    }

    private BigDecimal money(BigDecimal amount) {
        return amount.setScale(4, RoundingMode.HALF_UP);
    }

    private LedgerResponses.OpeningBalance mapOpeningBalance(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new LedgerResponses.OpeningBalance(rs.getObject("id", UUID.class),
                rs.getObject("ledger_id", UUID.class), rs.getObject("period_id", UUID.class),
                rs.getObject("account_id", UUID.class), rs.getString("currency"), rs.getString("dimension_key"),
                rs.getBigDecimal("debit_original"), rs.getBigDecimal("credit_original"),
                rs.getBigDecimal("exchange_rate"), rs.getBigDecimal("debit_base"),
                rs.getBigDecimal("credit_base"), rs.getBoolean("confirmed"));
    }

    private LedgerResponses.Period mapPeriod(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new LedgerResponses.Period(rs.getObject("id", UUID.class),
                rs.getObject("ledger_id", UUID.class), rs.getString("period_code"),
                rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
                rs.getString("status"));
    }

    private void ensureNotRemovingLastOwner(UUID ledgerId, UUID userId, LedgerRole nextRole, MembershipStatus nextStatus) {
        if (nextRole == LedgerRole.OWNER && nextStatus == MembershipStatus.ACTIVE) {
            return;
        }
        Integer owners = jdbcTemplate.queryForObject("""
                select count(*) from ledger_membership where ledger_id = ? and role = 'OWNER'
                    and status = 'ACTIVE' and deleted_at is null
                """, Integer.class, ledgerId);
        Integer targetOwners = jdbcTemplate.queryForObject("""
                select count(*) from ledger_membership where ledger_id = ? and user_id = ?
                    and role = 'OWNER' and status = 'ACTIVE' and deleted_at is null
                """, Integer.class, ledgerId, userId);
        if (owners != null && targetOwners != null && owners == 1 && targetOwners == 1) {
            throw problem(409, "LAST_OWNER_REQUIRED", "The ledger needs an owner",
                    "Add another owner before removing or demoting the current owner");
        }
    }

    private boolean userExists(UUID userId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "select exists (select 1 from app_user where id = ? and deleted_at is null)", Boolean.class, userId));
    }

    private void initializeLedger(UUID ledgerId, LocalDate startDate) {
        for (AccountTemplate account : SME_ACCOUNTS) {
            jdbcTemplate.update("""
                    insert into ledger_account (id, ledger_id, code, name, category, normal_balance)
                    values (?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), ledgerId, account.code(), account.name(), account.category(),
                    account.normalBalance());
        }
        LocalDate periodStart = startDate.withDayOfMonth(1);
        for (int month = 0; month < 12; month++) {
            LocalDate current = periodStart.plusMonths(month);
            jdbcTemplate.update("""
                    insert into accounting_period (id, ledger_id, period_code, start_date, end_date)
                    values (?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), ledgerId, current.toString().substring(0, 7), current,
                    current.plusMonths(1).minusDays(1));
        }
        for (FormulaTemplate formula : SME_FORMULAS) {
            jdbcTemplate.update("""
                    insert into report_formula_snapshot (id, ledger_id, code, name, formula_json)
                    values (?, ?, ?, ?, ?::jsonb)
                    """, UUID.randomUUID(), ledgerId, formula.code(), formula.name(), formula.json());
        }
    }

    private UUID lookupAccount(UUID ledgerId, String code, int rowNumber) {
        try {
            return accountId(ledgerId, code);
        } catch (EmptyResultDataAccessException exception) {
            throw csvProblem(rowNumber, "accountCode", "account does not exist: " + code);
        }
    }

    private UUID lookupPeriod(UUID ledgerId, String code, int rowNumber) {
        try {
            return periodId(ledgerId, code);
        } catch (EmptyResultDataAccessException exception) {
            throw csvProblem(rowNumber, "periodCode", "period does not exist: " + code);
        }
    }

    private BigDecimal csvDecimal(String value, int rowNumber, String field) {
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException exception) {
            throw csvProblem(rowNumber, field, "must be a decimal number");
        }
    }

    private ApiProblemException csvProblem(int rowNumber, String field, String detail) {
        return problem(422, "OPENING_BALANCE_CSV_INVALID", "Invalid opening balance CSV",
                "row " + rowNumber + " field " + field + ": " + detail);
    }

    private Ledger mapLedger(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Ledger(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("accounting_standard_code"), rs.getString("accounting_standard_version"),
                rs.getString("base_currency"), rs.getObject("start_date", LocalDate.class),
                rs.getBoolean("approval_enabled"), rs.getString("status"));
    }

    private ApiProblemException problem(int status, String code, String title, String detail) {
        return new ApiProblemException(status, code, title, detail, false);
    }

    private record AccountTemplate(String code, String name, String category, String normalBalance) {
    }

    private record FormulaTemplate(String code, String name, String json) {
    }
}
