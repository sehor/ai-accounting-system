package com.example.accounting.voucher.internal.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.accounting.ledger.LedgerAccessService;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.shared.web.ApiProblemException;
import com.example.accounting.voucher.VoucherRenumbering;
import com.example.accounting.voucher.internal.port.VoucherRenumberRepository;
import com.example.accounting.voucher.internal.port.VoucherRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VoucherRenumberServiceTest {
    private final VoucherRenumberRepository repository = mock(VoucherRenumberRepository.class);
    private final VoucherRepository vouchers = mock(VoucherRepository.class);
    private final LedgerAccessService access = mock(LedgerAccessService.class);
    private final DefaultVoucherRenumberService service = new DefaultVoucherRenumberService(repository, vouchers, access);
    private final UUID actor = UUID.randomUUID(), ledger = UUID.randomUUID(), period = UUID.randomUUID();
    private final VoucherRenumbering.Request request = new VoucherRenumbering.Request(period, VoucherRenumbering.Mode.DATE_ORDER, "1");

    @Test
    void deniesReadOnlyAndAgentRolesBeforeReadingOrWritingVouchers() {
        for (LedgerRole role : List.of(LedgerRole.VIEWER, LedgerRole.REVIEWER, LedgerRole.AGENT)) {
            when(access.requireMembership(actor, ledger)).thenReturn(role);
            assertThatThrownBy(() -> service.preview(actor, ledger, request)).isInstanceOf(ApiProblemException.class);
            assertThatThrownBy(() -> service.execute(actor, ledger,
                    new VoucherRenumbering.ExecuteRequest(period, request.mode(), "1", "stale"), "key"))
                    .isInstanceOf(ApiProblemException.class);
        }
        verifyNoInteractions(repository, vouchers);
    }

    @Test
    void rejectsClosedAndForeignPeriodsWithoutChangingVouchers() {
        when(access.requireMembership(actor, ledger)).thenReturn(LedgerRole.EDITOR);
        when(repository.periodStatus(ledger, period, false)).thenReturn(Optional.of("CLOSED"), Optional.empty());
        assertThatThrownBy(() -> service.preview(actor, ledger, request)).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> service.preview(actor, ledger, request)).isInstanceOf(ApiProblemException.class);
        verifyNoInteractions(vouchers);
    }

    @Test
    void rejectsMissingExecutionKeyAndStalePreviewBeforeChangingAnything() {
        when(access.requireMembership(actor, ledger)).thenReturn(LedgerRole.OWNER);
        var execute = new VoucherRenumbering.ExecuteRequest(period, request.mode(), "1", "stale");
        assertThatThrownBy(() -> service.execute(actor, ledger, execute, null)).isInstanceOf(ApiProblemException.class);
        verifyNoInteractions(repository, vouchers);
        when(repository.periodStatus(ledger, period, true)).thenReturn(Optional.of("OPEN"));
        when(repository.operation(actor, ledger, "key")).thenReturn(Optional.empty());
        when(repository.rows(ledger, period, true)).thenReturn(List.of());
        assertThatThrownBy(() -> service.execute(actor, ledger, execute, "key")).isInstanceOf(ApiProblemException.class);
        verifyNoInteractions(vouchers);
    }
}
