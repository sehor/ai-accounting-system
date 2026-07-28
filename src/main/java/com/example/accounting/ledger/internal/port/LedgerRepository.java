package com.example.accounting.ledger.internal.port;

import com.example.accounting.ledger.LedgerResponses;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.ledger.MembershipStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerRepository {

    void createLedger(UUID ledgerId, String name, String standardCode, String standardVersion,
                      String baseCurrency, LocalDate startDate, boolean approvalEnabled, UUID actorId);

    void createOwner(UUID ledgerId, UUID actorId);

    void createAccount(UUID ledgerId, String code, String name, String category, String normalBalance);

    void createPeriod(UUID ledgerId, String periodCode, LocalDate startDate, LocalDate endDate);

    void createFormula(UUID ledgerId, String code, String name, String json);

    List<LedgerResponses.Ledger> list(UUID actorId);

    Optional<LedgerResponses.Ledger> findLedger(UUID ledgerId);

    List<LedgerResponses.Member> listMembers(UUID ledgerId);

    List<LedgerResponses.Account> listAccounts(UUID ledgerId);

    List<LedgerResponses.Period> listPeriods(UUID ledgerId);

    Optional<LedgerResponses.Period> findPeriod(UUID ledgerId, UUID periodId);

    void updatePeriodStatus(UUID ledgerId, UUID periodId, String status);

    void recordPeriodAction(UUID ledgerId, UUID periodId, String action, String reason, UUID actorId);

    List<LedgerResponses.DimensionType> listDimensionTypes(UUID ledgerId);

    void createDimensionType(UUID id, UUID ledgerId, String code, String name, boolean required);

    Optional<LedgerResponses.DimensionType> findDimensionType(UUID ledgerId, UUID typeId);

    boolean activeDimensionTypeExists(UUID ledgerId, UUID typeId);

    List<LedgerResponses.DimensionValue> listDimensionValues(UUID ledgerId, UUID typeId);

    void createDimensionValue(UUID id, UUID ledgerId, UUID typeId, String code, String name);

    Optional<LedgerResponses.DimensionValue> findDimensionValue(UUID ledgerId, UUID valueId);

    List<LedgerResponses.OpeningBalance> listOpeningBalances(UUID ledgerId);

    boolean hasConfirmedOpeningBalances(UUID ledgerId);

    void deleteUnconfirmedOpeningBalances(UUID ledgerId);

    boolean upsertOpeningBalance(LedgerResponses.OpeningBalance balance);

    boolean validOpeningReference(UUID ledgerId, UUID accountId, UUID periodId);

    OpeningTotals openingTotals(UUID ledgerId);

    int confirmOpeningBalances(UUID ledgerId);

    Optional<UUID> findAccountId(UUID ledgerId, String code);

    Optional<UUID> findPeriodId(UUID ledgerId, String periodCode);

    boolean userExists(UUID userId);

    void upsertMember(UUID ledgerId, UUID userId, LedgerRole role, UUID actorId);

    Optional<LedgerResponses.Member> findMember(UUID ledgerId, UUID userId);

    boolean updateMember(UUID ledgerId, UUID userId, LedgerRole role, MembershipStatus status, UUID actorId);

    boolean removeMember(UUID ledgerId, UUID userId, UUID actorId);

    boolean isSoleActiveOwner(UUID ledgerId, UUID userId);

    void lockLedger(UUID ledgerId);

    record OpeningTotals(BigDecimal debit, BigDecimal credit) {
    }
}
