package com.example.accounting.ledger.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.accounting.administration.PlatformAdminPolicy;
import com.example.accounting.identity.IdentityService;
import com.example.accounting.ledger.AccountingStandardCatalog;
import com.example.accounting.ledger.LedgerAccessService;
import com.example.accounting.ledger.LedgerRequests;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.ledger.PeriodCloseGuard;
import com.example.accounting.ledger.formula.StandardFormulaConverter;
import com.example.accounting.ledger.internal.persistence.AccountManagementRepository;
import com.example.accounting.ledger.internal.port.LedgerRepository;
import com.example.accounting.ledger.internal.port.ReportFormulaRepository;
import com.example.accounting.shared.web.ApiProblemException;
import com.example.accounting.shared.balance.BalanceProjectionService;
import com.example.accounting.shared.accounting.DimensionCombinationStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class DefaultLedgerServiceAccountSearchTest {

    private final LedgerRepository ledgers = mock(LedgerRepository.class);
    private final AccountManagementRepository accounts = mock(AccountManagementRepository.class);
    private final LedgerAccessService ledgerAccess = mock(LedgerAccessService.class);
    private final IdentityService identityService = mock(IdentityService.class);
    private final AccountingStandardCatalog standards = mock(AccountingStandardCatalog.class);
    private final LocalSuperAgentPolicy localSuperAgent = mock(LocalSuperAgentPolicy.class);
    private final PlatformAdminPolicy platformAdmin = mock(PlatformAdminPolicy.class);
    private final BalanceProjectionService balanceProjection = mock(BalanceProjectionService.class);
    private final DimensionCombinationStore dimensionCombinations = mock(DimensionCombinationStore.class);
    private final ReportFormulaRepository reportFormulas = mock(ReportFormulaRepository.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<PeriodCloseGuard> closeGuards = mock(ObjectProvider.class);
    private final DefaultLedgerService service = new DefaultLedgerService(
            ledgers, accounts, ledgerAccess, identityService, standards, closeGuards,
            localSuperAgent, platformAdmin, balanceProjection, dimensionCombinations,
            new com.example.accounting.shared.audit.AuditSnapshotSerializer(),
            reportFormulas, new StandardFormulaConverter());

    private final UUID actorId = UUID.randomUUID();
    private final UUID ledgerId = UUID.randomUUID();

    @BeforeEach
    void allowLedgerRead() {
        when(ledgerAccess.requireMembership(actorId, ledgerId)).thenReturn(LedgerRole.VIEWER);
    }

    @Test
    void trimsQueryAndAppliesSafeDefaults() {
        when(accounts.search(ledgerId, "Bank", LedgerRequests.AccountMatchMode.FUZZY, 20))
                .thenReturn(List.of());

        assertThat(service.searchAccounts(actorId, ledgerId, "  Bank  ", null, null)).isEmpty();

        verify(accounts).search(ledgerId, "Bank", LedgerRequests.AccountMatchMode.FUZZY, 20);
    }

    @Test
    void rejectsBlankQueriesAndOutOfRangeLimits() {
        assertProblem("ACCOUNT_SEARCH_QUERY_INVALID",
                () -> service.searchAccounts(actorId, ledgerId, "  ", LedgerRequests.AccountMatchMode.EXACT, 1));
        assertProblem("ACCOUNT_SEARCH_LIMIT_INVALID",
                () -> service.searchAccounts(actorId, ledgerId, "1002", LedgerRequests.AccountMatchMode.EXACT, 101));
    }

    private void assertProblem(String code, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiProblemException.class,
                        exception -> assertThat(exception.code()).isEqualTo(code));
    }
}
