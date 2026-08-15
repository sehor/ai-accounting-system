package com.example.accounting.ledger;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.identity.UserResponse;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

public interface LedgerService {

    LedgerResponses.Ledger create(CurrentUserResolver.ResolvedUser actor, LedgerRequests.Create request);

    List<LedgerResponses.Ledger> list(UUID actorId);

    LedgerResponses.Ledger findLedger(UUID actorId, UUID ledgerId);

    LedgerResponses.Ledger renameLedger(UUID actorId, UUID ledgerId, LedgerRequests.Rename request);

    LedgerRole role(UUID actorId, UUID ledgerId);

    List<LedgerResponses.Member> listMembers(UUID actorId, UUID ledgerId);

    List<UserResponse> findMemberCandidates(UUID actorId, UUID ledgerId, String email);

    List<LedgerResponses.Account> listAccounts(UUID actorId, UUID ledgerId);

    List<LedgerResponses.AccountSearchResult> searchAccounts(
            UUID actorId, UUID ledgerId, String query, LedgerRequests.AccountMatchMode matchMode, Integer limit);

    LedgerResponses.Account findAccount(UUID actorId, UUID ledgerId, UUID accountId);

    LedgerResponses.Account createAccount(UUID actorId, UUID ledgerId, LedgerRequests.AccountCreate request);

    LedgerResponses.Account updateAccount(
            UUID actorId, UUID ledgerId, UUID accountId, LedgerRequests.AccountPatch request);

    LedgerResponses.Account overwriteAccount(
            UUID actorId, UUID ledgerId, UUID accountId, LedgerRequests.AccountPatch request);

    void deleteAccount(UUID actorId, UUID ledgerId, UUID accountId, long expectedVersion);

    AccountCodeRule updateAccountCodeRule(
            UUID actorId, UUID ledgerId, LedgerRequests.AccountCodeRuleUpdate request);

    List<LedgerResponses.CashFlowItem> listCashFlowItems(UUID actorId, UUID ledgerId);

    LedgerResponses.Account ensureAgentAccount(
            UUID actorId, UUID ledgerId, LedgerRequests.AccountCreate request);

    List<LedgerResponses.Period> listPeriods(UUID actorId, UUID ledgerId);

    LedgerResponses.Period closePeriod(UUID actorId, UUID ledgerId, UUID periodId,
                                       LedgerRequests.PeriodAction request);

    LedgerResponses.Period reopenPeriod(UUID actorId, UUID ledgerId, UUID periodId,
                                        LedgerRequests.PeriodAction request);

    List<LedgerResponses.DimensionType> listDimensionTypes(UUID actorId, UUID ledgerId);

    LedgerResponses.DimensionType createDimensionType(UUID actorId, UUID ledgerId,
                                                       LedgerRequests.DimensionTypeCreate request);

    LedgerResponses.DimensionType updateDimensionType(UUID actorId, UUID ledgerId, UUID typeId,
                                                       LedgerRequests.DimensionTypePatch request);

    List<LedgerResponses.DimensionValue> listDimensionValues(UUID actorId, UUID ledgerId, UUID typeId);

    LedgerResponses.DimensionValuesBatch listDimensionValues(
            UUID actorId, UUID ledgerId, LedgerRequests.DimensionValuesBatch request);

    LedgerResponses.DimensionValue createDimensionValue(UUID actorId, UUID ledgerId, UUID typeId,
                                                         LedgerRequests.DimensionValueCreate request);

    LedgerResponses.DimensionValue updateDimensionValue(UUID actorId, UUID ledgerId, UUID typeId, UUID valueId,
                                                         LedgerRequests.DimensionValuePatch request);

    List<LedgerResponses.OpeningBalance> listOpeningBalances(UUID actorId, UUID ledgerId);

    List<LedgerResponses.OpeningBalance> replaceOpeningBalances(
            UUID actorId, UUID ledgerId, List<LedgerRequests.OpeningBalanceLine> lines);

    default List<LedgerResponses.OpeningBalance> replaceOpeningBalances(
            UUID actorId, UUID ledgerId, List<LedgerRequests.OpeningBalanceLine> lines, String reason) {
        return replaceOpeningBalances(actorId, ledgerId, lines);
    }

    int confirmOpeningBalances(UUID actorId, UUID ledgerId);

    default int confirmOpeningBalances(UUID actorId, UUID ledgerId, String reason) {
        return confirmOpeningBalances(actorId, ledgerId);
    }

    List<LedgerResponses.OpeningBalance> importOpeningBalances(UUID actorId, UUID ledgerId, InputStream input);

    default List<LedgerResponses.OpeningBalance> importOpeningBalances(
            UUID actorId, UUID ledgerId, InputStream input, String reason) {
        return importOpeningBalances(actorId, ledgerId, input);
    }

    LedgerResponses.Member addMember(UUID actorId, UUID ledgerId, LedgerRequests.AddMember request);

    LedgerResponses.Member updateMember(UUID actorId, UUID ledgerId, UUID userId,
                                         LedgerRequests.UpdateMember request);

    void removeMember(UUID actorId, UUID ledgerId, UUID userId);

    UUID accountId(UUID ledgerId, String code);

    UUID periodId(UUID ledgerId, String periodCode);
}
