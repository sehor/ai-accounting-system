package com.example.accounting.reporting;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.example.accounting.ledger.LedgerAccessService;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.reporting.internal.application.DefaultReportingService;
import com.example.accounting.reporting.internal.port.ReportingRepository;
import com.example.accounting.shared.web.ApiProblemException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultReportingServiceTest {

    private final LedgerAccessService access = org.mockito.Mockito.mock(LedgerAccessService.class);
    private final ReportingRepository repository = org.mockito.Mockito.mock(ReportingRepository.class);
    private final DefaultReportingService service = new DefaultReportingService(access, repository);

    @Test
    void rejectsUnknownPeriodsAndCrossLedgerAccountsAtTheBoundary() {
        UUID actorId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        when(access.requireMembership(actorId, ledgerId)).thenReturn(LedgerRole.VIEWER);
        when(repository.periodsExist(ledgerId, PeriodRange.single("2026-06"))).thenReturn(true);
        when(repository.accountExists(ledgerId, accountId)).thenReturn(false);

        assertThatThrownBy(() -> service.generalLedgerBook(actorId, ledgerId, "bad", 1, 50))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("PERIOD_RANGE_INVALID");
        assertThatThrownBy(() -> service.subLedgerBook(actorId, ledgerId, "2026-06", accountId, 1, 50))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("ACCOUNT_NOT_FOUND");
    }
}
