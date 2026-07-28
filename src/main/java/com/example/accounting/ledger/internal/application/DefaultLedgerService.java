package com.example.accounting.ledger.internal.application;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.identity.IdentityService;
import com.example.accounting.identity.UserResponse;
import com.example.accounting.ledger.LedgerAccessService;
import com.example.accounting.ledger.LedgerRequests;
import com.example.accounting.ledger.LedgerResponses;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.ledger.LedgerService;
import com.example.accounting.ledger.MembershipStatus;
import com.example.accounting.ledger.internal.port.LedgerRepository;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultLedgerService implements LedgerService {

    private static final Set<LedgerRole> VIEW_ROLES = Set.of(
            LedgerRole.OWNER, LedgerRole.EDITOR, LedgerRole.REVIEWER, LedgerRole.VIEWER, LedgerRole.AGENT);
    private static final Set<LedgerRole> MEMBER_VIEW_ROLES = Set.of(
            LedgerRole.OWNER, LedgerRole.EDITOR, LedgerRole.REVIEWER, LedgerRole.VIEWER);
    private static final Set<LedgerRole> WRITE_ROLES = Set.of(LedgerRole.OWNER, LedgerRole.EDITOR);
    private static final Set<LedgerRole> OWNER_ROLE = Set.of(LedgerRole.OWNER);

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
                    "{\"type\":\"balance-sheet\",\"debitCategories\":[\"ASSET\"],"
                            + "\"creditCategories\":[\"LIABILITY\",\"EQUITY\"]}"),
            new FormulaTemplate("INCOME_STATEMENT", "Income Statement",
                    "{\"type\":\"income-statement\",\"revenueCategories\":[\"REVENUE\"],"
                            + "\"expenseCategories\":[\"COST\",\"EXPENSE\"]}"));

    private final LedgerRepository ledgers;
    private final LedgerAccessService ledgerAccess;
    private final IdentityService identityService;

    public DefaultLedgerService(LedgerRepository ledgers, LedgerAccessService ledgerAccess,
                                IdentityService identityService) {
        this.ledgers = ledgers;
        this.ledgerAccess = ledgerAccess;
        this.identityService = identityService;
    }

    @Override
    @Transactional
    public LedgerResponses.Ledger create(CurrentUserResolver.ResolvedUser actor, LedgerRequests.Create request) {
        UUID actorId = actor.id();
        identityService.ensureUser(actor);
        UUID ledgerId = UUID.randomUUID();
        ledgers.createLedger(ledgerId, request.name().trim(), request.accountingStandardCode().trim(),
                request.accountingStandardVersion().trim(), request.baseCurrency(), request.startDate(),
                Boolean.TRUE.equals(request.approvalEnabled()), actorId);
        ledgers.createOwner(ledgerId, actorId);
        initializeLedger(ledgerId, request.startDate());
        return requireLedger(ledgerId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LedgerResponses.Ledger> list(UUID actorId) {
        return ledgers.list(actorId);
    }

    @Override
    @Transactional(readOnly = true)
    public LedgerResponses.Ledger findLedger(UUID actorId, UUID ledgerId) {
        requireRole(actorId, ledgerId, VIEW_ROLES);
        return requireLedger(ledgerId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LedgerResponses.Member> listMembers(UUID actorId, UUID ledgerId) {
        requireRole(actorId, ledgerId, MEMBER_VIEW_ROLES);
        return ledgers.listMembers(ledgerId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserResponse> findMemberCandidates(UUID actorId, UUID ledgerId, String email) {
        requireRole(actorId, ledgerId, OWNER_ROLE);
        if (email == null || email.isBlank() || email.length() > 320 || !email.contains("@")) {
            throw problem(400, "EMAIL_INVALID", "Invalid email", "A valid email is required");
        }
        return identityService.findByEmail(email).map(List::of).orElseGet(List::of);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LedgerResponses.Account> listAccounts(UUID actorId, UUID ledgerId) {
        requireRole(actorId, ledgerId, VIEW_ROLES);
        return ledgers.listAccounts(ledgerId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LedgerResponses.Period> listPeriods(UUID actorId, UUID ledgerId) {
        requireRole(actorId, ledgerId, VIEW_ROLES);
        return ledgers.listPeriods(ledgerId);
    }

    @Override
    @Transactional
    public LedgerResponses.Period closePeriod(UUID actorId, UUID ledgerId, UUID periodId,
                                              LedgerRequests.PeriodAction request) {
        return changePeriod(actorId, ledgerId, periodId, request, "OPEN", "CLOSED", "CLOSE");
    }

    @Override
    @Transactional
    public LedgerResponses.Period reopenPeriod(UUID actorId, UUID ledgerId, UUID periodId,
                                               LedgerRequests.PeriodAction request) {
        return changePeriod(actorId, ledgerId, periodId, request, "CLOSED", "OPEN", "REOPEN");
    }

    @Override
    @Transactional(readOnly = true)
    public List<LedgerResponses.DimensionType> listDimensionTypes(UUID actorId, UUID ledgerId) {
        requireRole(actorId, ledgerId, VIEW_ROLES);
        return ledgers.listDimensionTypes(ledgerId);
    }

    @Override
    @Transactional
    public LedgerResponses.DimensionType createDimensionType(
            UUID actorId, UUID ledgerId, LedgerRequests.DimensionTypeCreate request) {
        requireRole(actorId, ledgerId, WRITE_ROLES);
        UUID id = UUID.randomUUID();
        ledgers.createDimensionType(id, ledgerId, request.code().trim().toUpperCase(Locale.ROOT),
                request.name().trim(), Boolean.TRUE.equals(request.required()));
        return ledgers.findDimensionType(ledgerId, id).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LedgerResponses.DimensionValue> listDimensionValues(
            UUID actorId, UUID ledgerId, UUID typeId) {
        requireDimensionType(actorId, ledgerId, typeId, false);
        return ledgers.listDimensionValues(ledgerId, typeId);
    }

    @Override
    @Transactional
    public LedgerResponses.DimensionValue createDimensionValue(
            UUID actorId, UUID ledgerId, UUID typeId, LedgerRequests.DimensionValueCreate request) {
        requireDimensionType(actorId, ledgerId, typeId, true);
        UUID id = UUID.randomUUID();
        ledgers.createDimensionValue(id, ledgerId, typeId, request.code().trim(), request.name().trim());
        return ledgers.findDimensionValue(ledgerId, id).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LedgerResponses.OpeningBalance> listOpeningBalances(UUID actorId, UUID ledgerId) {
        requireRole(actorId, ledgerId, VIEW_ROLES);
        return ledgers.listOpeningBalances(ledgerId);
    }

    @Override
    @Transactional
    public List<LedgerResponses.OpeningBalance> replaceOpeningBalances(
            UUID actorId, UUID ledgerId, List<LedgerRequests.OpeningBalanceLine> lines) {
        requireRole(actorId, ledgerId, WRITE_ROLES);
        ledgers.lockLedger(ledgerId);
        requireRole(actorId, ledgerId, WRITE_ROLES);
        if (ledgers.hasConfirmedOpeningBalances(ledgerId)) {
            throw confirmedOpeningBalance();
        }
        ledgers.deleteUnconfirmedOpeningBalances(ledgerId);
        for (LedgerRequests.OpeningBalanceLine line : lines) {
            validateOpeningBalanceLine(ledgerId, line);
            BigDecimal debit = money(line.debitOriginal());
            BigDecimal credit = money(line.creditOriginal());
            BigDecimal rate = line.exchangeRate().setScale(8, RoundingMode.HALF_UP);
            LedgerResponses.OpeningBalance balance = new LedgerResponses.OpeningBalance(
                    UUID.randomUUID(), ledgerId, line.periodId(), line.accountId(), line.currency(),
                    line.dimensionKey() == null ? "" : line.dimensionKey().trim(), debit, credit, rate,
                    debit.multiply(rate).setScale(2, RoundingMode.HALF_UP),
                    credit.multiply(rate).setScale(2, RoundingMode.HALF_UP), false);
            if (!ledgers.upsertOpeningBalance(balance)) {
                throw confirmedOpeningBalance();
            }
        }
        return ledgers.listOpeningBalances(ledgerId);
    }

    @Override
    @Transactional
    public int confirmOpeningBalances(UUID actorId, UUID ledgerId) {
        requireRole(actorId, ledgerId, WRITE_ROLES);
        ledgers.lockLedger(ledgerId);
        requireRole(actorId, ledgerId, WRITE_ROLES);
        LedgerRepository.OpeningTotals totals = ledgers.openingTotals(ledgerId);
        if (totals.debit().compareTo(totals.credit()) != 0) {
            throw problem(422, "OPENING_BALANCE_UNBALANCED", "Opening balance is not balanced",
                    "Opening balance debit and credit totals must balance");
        }
        return ledgers.confirmOpeningBalances(ledgerId);
    }

    @Override
    @Transactional
    public List<LedgerResponses.OpeningBalance> importOpeningBalances(
            UUID actorId, UUID ledgerId, InputStream input) {
        requireRole(actorId, ledgerId, WRITE_ROLES);
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
                // ponytail: v1 accepts simple CSV cells; add a CSV parser when quoted commas are required.
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

    @Override
    @Transactional(readOnly = true)
    public UUID accountId(UUID ledgerId, String code) {
        return ledgers.findAccountId(ledgerId, code).orElseThrow(() ->
                problem(404, "ACCOUNT_NOT_FOUND", "Account not found", "The account does not exist"));
    }

    @Override
    @Transactional(readOnly = true)
    public UUID periodId(UUID ledgerId, String periodCode) {
        return ledgers.findPeriodId(ledgerId, periodCode).orElseThrow(() ->
                problem(404, "PERIOD_NOT_FOUND", "Period not found", "The period does not exist"));
    }

    @Override
    @Transactional
    public LedgerResponses.Member addMember(UUID actorId, UUID ledgerId, LedgerRequests.AddMember request) {
        requireRole(actorId, ledgerId, OWNER_ROLE);
        if (!ledgers.userExists(request.userId())) {
            throw problem(404, "USER_NOT_FOUND", "User not found",
                    "The user must call /v1/me before being added");
        }
        ledgers.upsertMember(ledgerId, request.userId(), request.role(), actorId);
        return requireMember(ledgerId, request.userId());
    }

    @Override
    @Transactional
    public LedgerResponses.Member updateMember(
            UUID actorId, UUID ledgerId, UUID userId, LedgerRequests.UpdateMember request) {
        requireRole(actorId, ledgerId, OWNER_ROLE);
        ledgers.lockLedger(ledgerId);
        requireRole(actorId, ledgerId, OWNER_ROLE);
        ensureNotRemovingLastOwner(ledgerId, userId, request.role(), request.status());
        if (!ledgers.updateMember(ledgerId, userId, request.role(), request.status(), actorId)) {
            throw membershipNotFound();
        }
        return requireMember(ledgerId, userId);
    }

    @Override
    @Transactional
    public void removeMember(UUID actorId, UUID ledgerId, UUID userId) {
        requireRole(actorId, ledgerId, OWNER_ROLE);
        ledgers.lockLedger(ledgerId);
        requireRole(actorId, ledgerId, OWNER_ROLE);
        ensureNotRemovingLastOwner(ledgerId, userId, LedgerRole.OWNER, MembershipStatus.INACTIVE);
        if (!ledgers.removeMember(ledgerId, userId, actorId)) {
            throw membershipNotFound();
        }
    }

    private LedgerResponses.Period changePeriod(
            UUID actorId, UUID ledgerId, UUID periodId, LedgerRequests.PeriodAction request,
            String expectedStatus, String nextStatus, String action) {
        requireRole(actorId, ledgerId, OWNER_ROLE);
        ledgers.lockLedger(ledgerId);
        requireRole(actorId, ledgerId, OWNER_ROLE);
        LedgerResponses.Period period = ledgers.findPeriod(ledgerId, periodId).orElseThrow(() ->
                problem(404, "PERIOD_NOT_FOUND", "Period not found",
                        "The period is not available to this ledger"));
        if (!expectedStatus.equals(period.status())) {
            throw problem(409, "PERIOD_STATE_INVALID", "Invalid period state",
                    "The period must be " + expectedStatus + " before it can be changed");
        }
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.isEmpty()) {
            throw problem(422, "PERIOD_REASON_REQUIRED", "Reason is required",
                    "A reason is required for period changes");
        }
        ledgers.updatePeriodStatus(ledgerId, periodId, nextStatus);
        ledgers.recordPeriodAction(ledgerId, periodId, action, reason, actorId);
        return new LedgerResponses.Period(period.id(), period.ledgerId(), period.periodCode(), period.startDate(),
                period.endDate(), nextStatus);
    }

    private void requireDimensionType(UUID actorId, UUID ledgerId, UUID typeId, boolean write) {
        requireRole(actorId, ledgerId, write ? WRITE_ROLES : VIEW_ROLES);
        if (!ledgers.activeDimensionTypeExists(ledgerId, typeId)) {
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
        if (!ledgers.validOpeningReference(ledgerId, line.accountId(), line.periodId())) {
            throw problem(422, "INVALID_OPENING_BALANCE_REFERENCE", "Invalid opening balance reference",
                    "The account and period must belong to this ledger and the period must be open");
        }
    }

    private void ensureNotRemovingLastOwner(
            UUID ledgerId, UUID userId, LedgerRole nextRole, MembershipStatus nextStatus) {
        if ((nextRole != LedgerRole.OWNER || nextStatus != MembershipStatus.ACTIVE)
                && ledgers.isSoleActiveOwner(ledgerId, userId)) {
            throw problem(409, "LAST_OWNER_REQUIRED", "The ledger needs an owner",
                    "Add another owner before removing or demoting the current owner");
        }
    }

    private void initializeLedger(UUID ledgerId, LocalDate startDate) {
        SME_ACCOUNTS.forEach(account -> ledgers.createAccount(
                ledgerId, account.code(), account.name(), account.category(), account.normalBalance()));
        LocalDate periodStart = startDate.withDayOfMonth(1);
        for (int month = 0; month < 12; month++) {
            LocalDate current = periodStart.plusMonths(month);
            ledgers.createPeriod(ledgerId, current.toString().substring(0, 7),
                    current, current.plusMonths(1).minusDays(1));
        }
        SME_FORMULAS.forEach(formula ->
                ledgers.createFormula(ledgerId, formula.code(), formula.name(), formula.json()));
    }

    private UUID lookupAccount(UUID ledgerId, String code, int rowNumber) {
        return ledgers.findAccountId(ledgerId, code).orElseThrow(() ->
                csvProblem(rowNumber, "accountCode", "account does not exist: " + code));
    }

    private UUID lookupPeriod(UUID ledgerId, String code, int rowNumber) {
        return ledgers.findPeriodId(ledgerId, code).orElseThrow(() ->
                csvProblem(rowNumber, "periodCode", "period does not exist: " + code));
    }

    private BigDecimal money(BigDecimal amount) {
        return amount.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal csvDecimal(String value, int rowNumber, String field) {
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException exception) {
            throw csvProblem(rowNumber, field, "must be a decimal number");
        }
    }

    private LedgerResponses.Ledger requireLedger(UUID ledgerId) {
        return ledgers.findLedger(ledgerId).orElseThrow(() ->
                problem(404, "LEDGER_NOT_FOUND", "Ledger not found",
                        "The ledger is not available to this user"));
    }

    private LedgerResponses.Member requireMember(UUID ledgerId, UUID userId) {
        return ledgers.findMember(ledgerId, userId).orElseThrow(this::membershipNotFound);
    }

    private void requireRole(UUID actorId, UUID ledgerId, Set<LedgerRole> roles) {
        if (!roles.contains(ledgerAccess.requireMembership(actorId, ledgerId))) {
            throw problem(403, "INSUFFICIENT_LEDGER_ROLE", "Insufficient ledger role",
                    "The current user cannot perform this operation");
        }
    }

    private ApiProblemException membershipNotFound() {
        return problem(404, "MEMBERSHIP_NOT_FOUND", "Membership not found",
                "The ledger member does not exist");
    }

    private ApiProblemException confirmedOpeningBalance() {
        return problem(409, "OPENING_BALANCE_CONFIRMED", "Opening balance is confirmed",
                "Confirmed opening balances cannot be changed");
    }

    private ApiProblemException csvProblem(int rowNumber, String field, String detail) {
        return problem(422, "OPENING_BALANCE_CSV_INVALID", "Invalid opening balance CSV",
                "row " + rowNumber + " field " + field + ": " + detail);
    }

    private ApiProblemException problem(int status, String code, String title, String detail) {
        return new ApiProblemException(status, code, title, detail, false);
    }

    private record AccountTemplate(String code, String name, String category, String normalBalance) {
    }

    private record FormulaTemplate(String code, String name, String json) {
    }
}
