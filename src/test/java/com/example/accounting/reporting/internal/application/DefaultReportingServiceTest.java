package com.example.accounting.reporting.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.accounting.ledger.AccountingStandardCatalog;
import com.example.accounting.ledger.LedgerAccessService;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.reporting.PeriodRange;
import com.example.accounting.reporting.ReportResponses;
import com.example.accounting.reporting.internal.port.ReportingRepository;
import com.example.accounting.shared.web.ApiProblemException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultReportingServiceTest {

    @Test
    void rejectsNonZeroUnmappedLeafInsteadOfSilentlyReportingZero() {
        UUID actorId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        LedgerAccessService access = mock(LedgerAccessService.class);
        ReportingRepository reports = mock(ReportingRepository.class);
        when(access.requireMembership(actorId, ledgerId)).thenReturn(LedgerRole.VIEWER);
        when(reports.periodsExist(eq(ledgerId), any(PeriodRange.class))).thenReturn(true);
        when(reports.ledgerProfile(ledgerId)).thenReturn(new ReportResponses.LedgerProfile("SME", "v1", "CNY"));
        when(reports.firstPeriodOfYear(ledgerId, "2026-01")).thenReturn("2026-01");
        when(reports.statutoryProjectionReady(eq(ledgerId), any(PeriodRange.class))).thenReturn(true);
        when(reports.statutoryAccountAmounts(eq(ledgerId), any(PeriodRange.class), eq(false)))
                .thenReturn(List.of(new ReportingRepository.StatutoryAccountAmount(
                        UUID.randomUUID(), "renamed-custom", null,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.ZERO,
                        BigDecimal.TEN, BigDecimal.ZERO)));
        DefaultReportingService service = new DefaultReportingService(
                access, reports, new AccountingStandardCatalog());

        assertThatThrownBy(() -> service.statutoryStatement(
                actorId, ledgerId, "balance-sheet", "2026-01"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("STATUTORY_ACCOUNT_MAPPING_REQUIRED"));
    }
}
