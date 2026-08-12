package com.example.accounting.ledger.internal.application;

import com.example.accounting.administration.PlatformAdminPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.identity.IdentityService;
import com.example.accounting.identity.UserResponse;
import com.example.accounting.ledger.AccountCodeRule;
import com.example.accounting.ledger.AccountingStandard;
import com.example.accounting.ledger.AccountingStandardCatalog;
import com.example.accounting.ledger.LedgerAccessService;
import com.example.accounting.ledger.LedgerRequests;
import com.example.accounting.ledger.LedgerResponses;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.ledger.LedgerService;
import com.example.accounting.ledger.MembershipStatus;
import com.example.accounting.ledger.PeriodCloseGuard;
import com.example.accounting.shared.balance.BalanceProjectionService;
import com.example.accounting.ledger.internal.persistence.AccountManagementRepository;
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
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultLedgerService implements LedgerService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultLedgerService.class);

    private static final Set<LedgerRole> VIEW_ROLES = Set.of(
            LedgerRole.OWNER, LedgerRole.EDITOR, LedgerRole.REVIEWER, LedgerRole.VIEWER, LedgerRole.AGENT);
    private static final Set<LedgerRole> MEMBER_VIEW_ROLES = Set.of(
            LedgerRole.OWNER, LedgerRole.EDITOR, LedgerRole.REVIEWER, LedgerRole.VIEWER);
    private static final Set<LedgerRole> WRITE_ROLES = Set.of(LedgerRole.OWNER, LedgerRole.EDITOR);
    private static final Set<LedgerRole> AGENT_ACCOUNT_ROLES = Set.of(
            LedgerRole.OWNER, LedgerRole.EDITOR, LedgerRole.AGENT);
    private static final Set<LedgerRole> OWNER_ROLE = Set.of(LedgerRole.OWNER);
    private static final Set<String> ACCOUNT_CATEGORIES = Set.of(
            "ASSET", "LIABILITY", "EQUITY", "COST", "REVENUE", "EXPENSE");

    private final LedgerRepository ledgers;
    private final AccountManagementRepository accounts;
    private final LedgerAccessService ledgerAccess;
    private final IdentityService identityService;
    private final AccountingStandardCatalog standards;
    private final ObjectProvider<PeriodCloseGuard> periodCloseGuard;
    private final LocalSuperAgentPolicy localSuperAgent;
    private final PlatformAdminPolicy platformAdmin;
    private final BalanceProjectionService balanceProjection;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    public DefaultLedgerService(LedgerRepository ledgers, AccountManagementRepository accounts,
                                LedgerAccessService ledgerAccess, IdentityService identityService,
                                AccountingStandardCatalog standards, ObjectProvider<PeriodCloseGuard> periodCloseGuard,
                                LocalSuperAgentPolicy localSuperAgent,
                                PlatformAdminPolicy platformAdmin,
                                BalanceProjectionService balanceProjection) {
        this.ledgers = ledgers;
        this.accounts = accounts;
        this.ledgerAccess = ledgerAccess;
        this.identityService = identityService;
        this.standards = standards;
        this.periodCloseGuard = periodCloseGuard;
        this.localSuperAgent = localSuperAgent;
        this.platformAdmin = platformAdmin;
        this.balanceProjection = balanceProjection;
    }

    @Override
    @Transactional
    public LedgerResponses.Ledger create(CurrentUserResolver.ResolvedUser actor, LedgerRequests.Create request) {
        UUID actorId = actor.id();
        identityService.ensureUser(actor);
        AccountingStandard.Package standard = requireStandard(
                request.accountingStandardCode().trim(), request.accountingStandardVersion().trim());
        AccountCodeRule rule = request.accountCodeRule() == null
                ? standard.accountCodeRule() : request.accountCodeRule();
        UUID ledgerId = UUID.randomUUID();
        String description = normalizeDescription(request.description());
        ledgers.createLedger(ledgerId, request.name().trim(), description, request.accountingStandardCode().trim(),
                request.accountingStandardVersion().trim(), request.baseCurrency(), request.startDate(),
                Boolean.TRUE.equals(request.approvalEnabled()), actorId);
        ledgers.createOwner(ledgerId, actorId);
        accounts.initializeCodeRule(ledgerId, rule);
        initializeLedger(ledgerId, request.startDate(), standard, actorId);
        return requireLedger(ledgerId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LedgerResponses.Ledger> list(UUID actorId) {
        return platformAdmin.isPlatformAdmin(actorId) ? ledgers.listAllActive() : ledgers.list(actorId);
    }

    @Override
    @Transactional(readOnly = true)
    public LedgerResponses.Ledger findLedger(UUID actorId, UUID ledgerId) {
        requireRole(actorId, ledgerId, VIEW_ROLES);
        return requireLedger(ledgerId);
    }

    @Override
    @Transactional
    public LedgerResponses.Ledger renameLedger(
            UUID actorId, UUID ledgerId, LedgerRequests.Rename request) {
        requireRole(actorId, ledgerId, WRITE_ROLES);
        String name = request == null || request.name() == null ? "" : request.name().trim();
        if (name.isEmpty() || name.length() > 200) {
            throw problem(422, "INVALID_LEDGER_NAME", "Invalid ledger name",
                    "Ledger name must contain between 1 and 200 characters");
        }
        String description = request.description() == null ? null : normalizeDescription(request.description());
        ledgers.updateLedger(ledgerId, name, description, actorId);
        return requireLedger(ledgerId);
    }

    @Override
    @Transactional(readOnly = true)
    public LedgerRole role(UUID actorId, UUID ledgerId) {
        return ledgerAccess.requireMembership(actorId, ledgerId);
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
        localSuperAgent.requireUserManagementAllowed(actorId);
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
        return accounts.list(ledgerId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LedgerResponses.AccountSearchResult> searchAccounts(
            UUID actorId, UUID ledgerId, String query,
            LedgerRequests.AccountMatchMode matchMode, Integer limit) {
        requireRole(actorId, ledgerId, VIEW_ROLES);
        String normalizedQuery = query == null ? "" : query.trim();
        if (normalizedQuery.isEmpty() || normalizedQuery.length() > 200) {
            throw problem(422, "ACCOUNT_SEARCH_QUERY_INVALID", "Invalid account search query",
                    "The account search query must contain 1 to 200 characters");
        }
        int actualLimit = limit == null ? 20 : limit;
        if (actualLimit < 1 || actualLimit > 100) {
            throw problem(422, "ACCOUNT_SEARCH_LIMIT_INVALID", "Invalid account search limit",
                    "The account search limit must be between 1 and 100");
        }
        LedgerRequests.AccountMatchMode actualMode = matchMode == null
                ? LedgerRequests.AccountMatchMode.FUZZY : matchMode;
        return accounts.search(ledgerId, normalizedQuery, actualMode, actualLimit);
    }

    @Override
    @Transactional(readOnly = true)
    public LedgerResponses.Account findAccount(UUID actorId, UUID ledgerId, UUID accountId) {
        requireRole(actorId, ledgerId, VIEW_ROLES);
        return requireAccount(ledgerId, accountId);
    }

    @Override
    @Transactional
    public LedgerResponses.Account createAccount(
            UUID actorId, UUID ledgerId, LedgerRequests.AccountCreate request) {
        requireRole(actorId, ledgerId, WRITE_ROLES);
        return createAccount(actorId, ledgerId, request, false);
    }

    @Override
    @Transactional
    public LedgerResponses.Account updateAccount(
            UUID actorId, UUID ledgerId, UUID accountId, LedgerRequests.AccountPatch request) {
        requireRole(actorId, ledgerId, WRITE_ROLES);
        LedgerResponses.Account before = requireAccount(ledgerId, accountId);
        String code = text(request.code(), before.code()).trim();
        String name = text(request.name(), before.name()).trim();
        String category = text(request.category(), before.category()).toUpperCase(Locale.ROOT);
        String normalBalance = text(request.normalBalance(), before.normalBalance()).toUpperCase(Locale.ROOT);
        String status = text(request.status(), before.status()).toUpperCase(Locale.ROOT);
        boolean cashFlowRequired = request.cashFlowRequired() == null
                ? before.cashFlowRequired() : request.cashFlowRequired();
        UUID defaultCashFlowItemId = request.defaultCashFlowItemId() == null
                ? before.defaultCashFlowItemId() : request.defaultCashFlowItemId();
        boolean quantityEnabled = request.quantityEnabled() == null
                ? before.quantityEnabled() : request.quantityEnabled();
        String unitSource = text(request.unitName(), before.unitName());
        String unitName = quantityEnabled && unitSource != null ? unitSource.trim() : null;
        ParentResolution parent = resolveParent(ledgerId, accountId, code,
                request.parentId() == null ? before.parentId() : request.parentId(),
                category, normalBalance);
        category = parent.category();
        normalBalance = parent.normalBalance();

        validateAccountValues(ledgerId, code, name, category, normalBalance, cashFlowRequired,
                defaultCashFlowItemId, quantityEnabled, unitName, request.dimensionRequirements());
        if (!Set.of("ACTIVE", "INACTIVE").contains(status)) {
            throw accountInvalid("Status must be ACTIVE or INACTIVE");
        }
        boolean structuralChange = !code.equals(before.code())
                || !java.util.Objects.equals(parent.parentId(), before.parentId())
                || !category.equals(before.category()) || !normalBalance.equals(before.normalBalance());
        boolean coreChange = structuralChange
                || cashFlowRequired != before.cashFlowRequired()
                || !java.util.Objects.equals(defaultCashFlowItemId, before.defaultCashFlowItemId())
                || quantityEnabled != before.quantityEnabled()
                || !java.util.Objects.equals(unitName, before.unitName())
                || request.dimensionRequirements() != null;
        if (before.isTemplate() && structuralChange) {
            throw problem(409, "ACCOUNT_TEMPLATE_LOCKED", "Template account is locked",
                    "Template account code, parent, category, and normal balance cannot be changed");
        }
        if (before.coreLocked() && coreChange) {
            throw problem(409, "ACCOUNT_CORE_LOCKED", "Account core attributes are locked",
                    "Posted vouchers or confirmed opening balances lock core account attributes");
        }
        if (!before.isLeaf() && structuralChange) {
            throw problem(409, "ACCOUNT_HAS_CHILDREN", "Account has children",
                    "A parent account cannot change its structural attributes");
        }
        if ("INACTIVE".equals(status) && !"INACTIVE".equals(before.status())
                && accounts.hasActiveDescendants(ledgerId, accountId)) {
            throw problem(409, "ACCOUNT_DESCENDANTS_ACTIVE", "Account descendants are active",
                    "Disable every descendant before disabling its parent");
        }
        if ("ACTIVE".equals(status) && !"ACTIVE".equals(before.status())
                && accounts.hasInactiveAncestors(ledgerId, accountId)) {
            throw problem(409, "ACCOUNT_ANCESTOR_INACTIVE", "Account ancestor is inactive",
                    "Enable every ancestor before enabling this account");
        }
        try {
            if (!accounts.update(ledgerId, accountId, request.expectedVersion(), code, name, category,
                    normalBalance, status, parent.parentId(), parent.level(), cashFlowRequired,
                    defaultCashFlowItemId, quantityEnabled, unitName)) {
                throw versionConflict();
            }
            if (request.dimensionRequirements() != null) {
                accounts.replaceDimensions(ledgerId, accountId, request.dimensionRequirements());
            }
        } catch (DataIntegrityViolationException exception) {
            throw problem(409, "ACCOUNT_CODE_CONFLICT", "Account code conflict",
                    "The account code already exists or a referenced control item is invalid");
        }
        LedgerResponses.Account after = requireAccount(ledgerId, accountId);
        accounts.recordRevision(ledgerId, accountId, "UPDATE", actorId, json(before), json(after));
        return after;
    }

    @Override
    @Transactional
    public void deleteAccount(UUID actorId, UUID ledgerId, UUID accountId, long expectedVersion) {
        requireRole(actorId, ledgerId, WRITE_ROLES);
        LedgerResponses.Account before = requireAccount(ledgerId, accountId);
        if (before.version() != expectedVersion) {
            throw versionConflict();
        }
        if (before.isTemplate() || !before.isLeaf()) {
            throw problem(409, "ACCOUNT_DELETE_FORBIDDEN", "Account cannot be deleted",
                    "Only an unused custom leaf account can be deleted; disable other accounts instead");
        }
        if (accounts.hasVoucherLines(ledgerId, accountId)) {
            throw problem(409, "ACCOUNT_HAS_VOUCHER_LINES", "Account cannot be deleted",
                    "The account is referenced by voucher lines");
        }
        if (accounts.hasOpeningBalances(ledgerId, accountId)) {
            throw problem(409, "ACCOUNT_HAS_OPENING_BALANCE", "Account cannot be deleted",
                    "The account is referenced by opening balances");
        }
        accounts.findConfigurationReference(ledgerId, accountId).ifPresent(reference -> {
            throw problem(409, "ACCOUNT_REFERENCED_BY_CONFIGURATION", "Account cannot be deleted",
                    "The account is referenced by " + reference);
        });
        try {
            if (!accounts.delete(ledgerId, accountId, expectedVersion)) {
                throw problem(409, "ACCOUNT_DELETE_CONFLICT", "Account cannot be deleted",
                        "The account acquired a reference or changed before deletion");
            }
        } catch (DataIntegrityViolationException exception) {
            throw problem(409, "ACCOUNT_REFERENCED_BY_CONFIGURATION", "Account cannot be deleted",
                    "The account is still referenced by a business configuration");
        }
        LOGGER.info("Account deleted: ledgerId={}, accountId={}, actorId={}", ledgerId, accountId, actorId);
    }

    @Override
    @Transactional
    public AccountCodeRule updateAccountCodeRule(
            UUID actorId, UUID ledgerId, LedgerRequests.AccountCodeRuleUpdate request) {
        requireRole(actorId, ledgerId, WRITE_ROLES);
        AccountCodeRule rule;
        try {
            rule = request.toRule();
        } catch (IllegalArgumentException exception) {
            throw accountInvalid(exception.getMessage());
        }
        if (!accounts.updateCodeRule(ledgerId, rule)) {
            throw problem(409, "ACCOUNT_CODE_RULE_LOCKED", "Account code rule is locked",
                    "The account code rule cannot change after a subaccount exists");
        }
        return rule;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LedgerResponses.CashFlowItem> listCashFlowItems(UUID actorId, UUID ledgerId) {
        requireRole(actorId, ledgerId, VIEW_ROLES);
        return accounts.cashFlowItems(ledgerId);
    }

    @Override
    @Transactional
    public LedgerResponses.Account ensureAgentAccount(
            UUID actorId, UUID ledgerId, LedgerRequests.AccountCreate request) {
        requireRole(actorId, ledgerId, AGENT_ACCOUNT_ROLES);
        String code = request.code().trim();
        String name = request.name().trim();
        String category = request.category().trim().toUpperCase(Locale.ROOT);
        String normalBalance = request.normalBalance().trim().toUpperCase(Locale.ROOT);
        LedgerResponses.Account account = accounts.findByCode(ledgerId, code)
                .orElseGet(() -> createAccount(actorId, ledgerId, request, true));
        if (!account.name().equals(name) || !account.category().equals(category)
                || !account.normalBalance().equals(normalBalance)) {
            throw problem(409, "ACCOUNT_CODE_CONFLICT", "Account code conflict",
                    "The account code already exists with different attributes");
        }
        return account;
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
            balanceProjection.requireOpenPeriod(ledgerId, line.periodId());
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
        int confirmed = ledgers.confirmOpeningBalances(ledgerId);
        if (confirmed > 0) {
            ledgers.listOpeningBalances(ledgerId).stream().filter(LedgerResponses.OpeningBalance::confirmed)
                    .forEach(balance -> balanceProjection.publishOpeningBalances(
                            new BalanceProjectionService.OpeningBalanceEvent(
                                    ledgerId, balance.periodId(), balance.id(), 1,
                                    List.of(new BalanceProjectionService.Entry(balance.accountId(),
                                            balance.debitBase(),
                                            balance.creditBase(),
                                            BigDecimal.ZERO,
                                            BigDecimal.ZERO)))));
        }
        return confirmed;
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
        localSuperAgent.requireUserManagementAllowed(actorId);
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
        localSuperAgent.requireUserManagementAllowed(actorId);
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
        localSuperAgent.requireUserManagementAllowed(actorId);
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
        if ("CLOSED".equals(nextStatus)) {
            balanceProjection.requireReadyForClose(ledgerId, periodId);
            List<String> blockers = periodCloseGuard.orderedStream()
                    .flatMap(guard -> guard.blockers(actorId, ledgerId, periodId).stream())
                    .distinct().toList();
            if (!blockers.isEmpty()) {
                throw problem(409, "FIXED_ASSET_PERIOD_INCOMPLETE", "Period close is blocked",
                        String.join("; ", blockers));
            }
        }
        ledgers.updatePeriodStatus(ledgerId, periodId, nextStatus);
        if ("OPEN".equals(nextStatus)) {
            balanceProjection.markReopened(ledgerId, periodId);
        }
        ledgers.recordPeriodAction(ledgerId, periodId, action, reason, actorId);
        return new LedgerResponses.Period(period.id(), period.ledgerId(), period.periodCode(), period.startDate(),
                period.endDate(), nextStatus, period.hasVouchers());
    }

    private void requireDimensionType(UUID actorId, UUID ledgerId, UUID typeId, boolean write) {
        requireRole(actorId, ledgerId, write ? WRITE_ROLES : VIEW_ROLES);
        if (!ledgers.activeDimensionTypeExists(ledgerId, typeId)) {
            throw problem(404, "DIMENSION_TYPE_NOT_FOUND", "Dimension type not found",
                    "The dimension type is not available to this ledger");
        }
    }

    private void validateOpeningBalanceLine(UUID ledgerId, LedgerRequests.OpeningBalanceLine line) {
        if ((line.debitOriginal().signum() != 0 && line.creditOriginal().signum() != 0)
                || line.exchangeRate().signum() <= 0) {
            throw problem(422, "INVALID_OPENING_BALANCE", "Invalid opening balance",
                    "Only one side may be populated and exchange rate must be positive");
        }
        if (!ledgers.validOpeningReference(ledgerId, line.accountId(), line.periodId())) {
            throw problem(422, "INVALID_OPENING_BALANCE_REFERENCE", "Invalid opening balance reference",
                    "The account and period must belong to this ledger and the period must be open");
        }
        UUID firstPeriodId = ledgers.listPeriods(ledgerId).stream()
                .findFirst().map(LedgerResponses.Period::id).orElse(null);
        if (!line.periodId().equals(firstPeriodId)) {
            throw problem(422, "OPENING_BALANCE_PERIOD_INVALID", "Invalid opening balance period",
                    "Opening balances may only be recorded in the ledger's first accounting period");
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

    private LedgerResponses.Account createAccount(
            UUID actorId, UUID ledgerId, LedgerRequests.AccountCreate request, boolean idempotent) {
        String code = request.code() == null ? "" : request.code().trim();
        String name = request.name() == null ? "" : request.name().trim();
        String category = request.category() == null
                ? "" : request.category().trim().toUpperCase(Locale.ROOT);
        String normalBalance = request.normalBalance() == null
                ? "" : request.normalBalance().trim().toUpperCase(Locale.ROOT);
        boolean cashFlowRequired = Boolean.TRUE.equals(request.cashFlowRequired());
        boolean quantityEnabled = Boolean.TRUE.equals(request.quantityEnabled());
        String unitName = request.unitName() == null ? null : request.unitName().trim();
        ParentResolution parent = resolveParent(
                ledgerId, null, code, request.parentId(), category, normalBalance);
        category = parent.category();
        normalBalance = parent.normalBalance();
        validateAccountValues(ledgerId, code, name, category, normalBalance, cashFlowRequired,
                request.defaultCashFlowItemId(), quantityEnabled, unitName,
                request.dimensionRequirements());
        if (parent.parentId() != null) {
            LedgerResponses.Account parentAccount = requireAccount(ledgerId, parent.parentId());
            if (parentAccount.hasBusinessUsage()) {
                throw problem(409, "ACCOUNT_PARENT_HAS_BUSINESS_USAGE", "Parent account has business usage",
                        "An account used by a voucher or opening balance cannot become a parent");
            }
            if (!"ACTIVE".equals(parentAccount.status())) {
                throw problem(409, "ACCOUNT_ANCESTOR_INACTIVE", "Account ancestor is inactive",
                        "Enable the parent before creating an active child");
            }
        }
        UUID accountId = UUID.randomUUID();
        try {
            if (idempotent) {
                boolean inserted = accounts.createIfAbsent(
                        accountId, ledgerId, code, name, category, normalBalance,
                        parent.parentId(), parent.level());
                if (!inserted) {
                    return accounts.findByCode(ledgerId, code).orElseThrow();
                }
            } else {
                if (accounts.findByCode(ledgerId, code).isPresent()) {
                    throw problem(409, "ACCOUNT_CODE_CONFLICT", "Account code conflict",
                            "The account code already exists");
                }
                accounts.create(accountId, ledgerId, code, name, category, normalBalance,
                        parent.parentId(), parent.level(), false, cashFlowRequired,
                        request.defaultCashFlowItemId(), quantityEnabled,
                        quantityEnabled ? unitName : null);
            }
            accounts.replaceDimensions(ledgerId, accountId, request.dimensionRequirements());
        } catch (DataIntegrityViolationException exception) {
            throw problem(409, "ACCOUNT_CODE_CONFLICT", "Account code conflict",
                    "The account code already exists or a referenced control item is invalid");
        }
        LedgerResponses.Account created = requireAccount(ledgerId, accountId);
        accounts.recordRevision(ledgerId, accountId, "CREATE", actorId, "null", json(created));
        return created;
    }

    private ParentResolution resolveParent(
            UUID ledgerId, UUID accountId, String code, UUID requestedParentId,
            String category, String normalBalance) {
        AccountCodeRule rule = accounts.codeRule(ledgerId);
        int level = rule.levelOf(code);
        if (level == 0) {
            throw accountInvalid("The code does not match this ledger's account code rule");
        }
        if (level == 1) {
            if (requestedParentId != null) {
                throw accountInvalid("A level-one account cannot have a parent");
            }
            return new ParentResolution(null, 1, category, normalBalance);
        }
        String parentCode = rule.parentCode(code).orElseThrow();
        LedgerResponses.Account parent = accounts.findByCode(ledgerId, parentCode).orElseThrow(() ->
                problem(422, "ACCOUNT_PARENT_NOT_FOUND", "Account parent not found",
                        "Create the parent account before creating its child"));
        if ((requestedParentId != null && !requestedParentId.equals(parent.id()))
                || (accountId != null && accountId.equals(parent.id()))
                || parent.level() + 1 != level) {
            throw accountInvalid("The parent does not match the account code");
        }
        if (!category.equals(parent.category()) || !normalBalance.equals(parent.normalBalance())) {
            throw accountInvalid("A child must inherit category and normal balance from its parent");
        }
        return new ParentResolution(parent.id(), level, parent.category(), parent.normalBalance());
    }

    private void validateAccountValues(
            UUID ledgerId, String code, String name, String category, String normalBalance,
            boolean cashFlowRequired, UUID defaultCashFlowItemId, boolean quantityEnabled,
            String unitName, List<LedgerRequests.DimensionRequirement> dimensions) {
        if (code.length() > 32 || name.isBlank() || name.length() > 200
                || !ACCOUNT_CATEGORIES.contains(category)
                || !Set.of("DEBIT", "CREDIT").contains(normalBalance)) {
            throw accountInvalid("Code, name, category, and normal balance are invalid");
        }
        if (quantityEnabled && (unitName == null || unitName.isBlank() || unitName.length() > 64)) {
            throw accountInvalid("A quantity account requires a unit name");
        }
        if (!accounts.activeCashFlowItem(ledgerId, defaultCashFlowItemId)) {
            throw accountInvalid("The default cash-flow item is not active in this ledger");
        }
        if (!accounts.validDimensionTypes(ledgerId, dimensions)) {
            throw accountInvalid("Dimension requirements must be unique active types in this ledger");
        }
    }

    private void initializeLedger(
            UUID ledgerId, LocalDate startDate, AccountingStandard.Package standard, UUID actorId) {
        standard.cashFlowItems().forEach(item -> accounts.createCashFlowItem(
                UUID.randomUUID(), ledgerId, item.code(), item.name(), true));
        standard.dimensionTypes().forEach(type -> ledgers.createDimensionType(
                UUID.randomUUID(), ledgerId, type.code(), type.name(), type.required()));
        Map<String, UUID> accountIds = standard.accounts().stream().collect(Collectors.toMap(
                AccountingStandard.Account::code, ignored -> UUID.randomUUID()));
        standard.accounts().stream()
                .sorted(java.util.Comparator.comparingInt(
                        account -> standard.accountCodeRule().levelOf(account.code())))
                .forEach(account -> accounts.create(
                        accountIds.get(account.code()), ledgerId, account.code(), account.name(),
                        account.category(), account.normalBalance(),
                        account.parentCode() == null ? null : accountIds.get(account.parentCode()),
                        standard.accountCodeRule().levelOf(account.code()), true,
                        account.cashFlowRequired(), null, account.quantityEnabled(),
                        account.quantityEnabled() ? account.unitName() : null));
        accountIds.values().forEach(accountId -> accounts.recordRevision(
                ledgerId, accountId, "CREATE", actorId, "null", json(requireAccount(ledgerId, accountId))));
        YearMonth periodStart = YearMonth.from(startDate);
        YearMonth periodEnd = YearMonth.of(YearMonth.now().getYear(), 12);
        if (periodEnd.isBefore(periodStart.plusMonths(11))) {
            periodEnd = periodStart.plusMonths(11);
        }
        for (YearMonth month = periodStart; !month.isAfter(periodEnd); month = month.plusMonths(1)) {
            LocalDate current = month.atDay(1);
            ledgers.createPeriod(ledgerId, current.toString().substring(0, 7),
                    current, current.plusMonths(1).minusDays(1));
        }
        standard.formulas().forEach(formula ->
                ledgers.createFormula(ledgerId, formula.code(), formula.name(),
                        formula.definition().toString()));
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

    private AccountingStandard.Package requireStandard(String code, String version) {
        return standards.find(code, version)
                .or(() -> "SME".equals(code) && "v1".equals(version)
                        ? standards.find("SME", "2011-17") : java.util.Optional.empty())
                .orElseThrow(() -> problem(422, "ACCOUNTING_STANDARD_NOT_FOUND",
                        "Accounting standard not found",
                        "The requested accounting standard version is not installed"));
    }

    private LedgerResponses.Account requireAccount(UUID ledgerId, UUID accountId) {
        return accounts.find(ledgerId, accountId).orElseThrow(() ->
                problem(404, "ACCOUNT_NOT_FOUND", "Account not found",
                        "The account is not available to this ledger"));
    }

    private String text(String provided, String fallback) {
        return provided == null ? fallback : provided;
    }

    private String normalizeDescription(String description) {
        String normalized = description == null ? "" : description.trim();
        if (normalized.length() > 2000) {
            throw problem(422, "INVALID_LEDGER_DESCRIPTION", "Invalid ledger description",
                    "Ledger description must contain at most 2000 characters");
        }
        return normalized;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw problem(500, "ACCOUNT_AUDIT_FAILED", "Account audit failed",
                    "The account change could not be serialized for audit");
        }
    }

    private ApiProblemException accountInvalid(String detail) {
        return problem(422, "ACCOUNT_INVALID", "Invalid account", detail);
    }

    private ApiProblemException versionConflict() {
        return problem(409, "ACCOUNT_VERSION_CONFLICT", "Account version conflict",
                "Reload the account and retry with its current version");
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

    private record ParentResolution(
            UUID parentId, int level, String category, String normalBalance) {
    }
}
