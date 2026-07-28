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

    List<LedgerResponses.Member> listMembers(UUID actorId, UUID ledgerId);

    List<UserResponse> findMemberCandidates(UUID actorId, UUID ledgerId, String email);

    List<LedgerResponses.Account> listAccounts(UUID actorId, UUID ledgerId);

    List<LedgerResponses.Period> listPeriods(UUID actorId, UUID ledgerId);

    LedgerResponses.Period closePeriod(UUID actorId, UUID ledgerId, UUID periodId,
                                       LedgerRequests.PeriodAction request);

    LedgerResponses.Period reopenPeriod(UUID actorId, UUID ledgerId, UUID periodId,
                                        LedgerRequests.PeriodAction request);

    List<LedgerResponses.DimensionType> listDimensionTypes(UUID actorId, UUID ledgerId);

    LedgerResponses.DimensionType createDimensionType(UUID actorId, UUID ledgerId,
                                                       LedgerRequests.DimensionTypeCreate request);

    List<LedgerResponses.DimensionValue> listDimensionValues(UUID actorId, UUID ledgerId, UUID typeId);

    LedgerResponses.DimensionValue createDimensionValue(UUID actorId, UUID ledgerId, UUID typeId,
                                                         LedgerRequests.DimensionValueCreate request);

    List<LedgerResponses.OpeningBalance> listOpeningBalances(UUID actorId, UUID ledgerId);

    List<LedgerResponses.OpeningBalance> replaceOpeningBalances(
            UUID actorId, UUID ledgerId, List<LedgerRequests.OpeningBalanceLine> lines);

    int confirmOpeningBalances(UUID actorId, UUID ledgerId);

    List<LedgerResponses.OpeningBalance> importOpeningBalances(UUID actorId, UUID ledgerId, InputStream input);

    LedgerResponses.Member addMember(UUID actorId, UUID ledgerId, LedgerRequests.AddMember request);

    LedgerResponses.Member updateMember(UUID actorId, UUID ledgerId, UUID userId,
                                         LedgerRequests.UpdateMember request);

    void removeMember(UUID actorId, UUID ledgerId, UUID userId);

    UUID accountId(UUID ledgerId, String code);

    UUID periodId(UUID ledgerId, String periodCode);
}
