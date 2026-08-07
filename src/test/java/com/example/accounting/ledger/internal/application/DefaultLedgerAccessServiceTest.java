package com.example.accounting.ledger.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.example.accounting.administration.PlatformAdminPolicy;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.ledger.internal.port.LedgerAccessRepository;
import com.example.accounting.shared.web.ApiProblemException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class DefaultLedgerAccessServiceTest {

    private final UUID adminId = UUID.fromString("a2757c7a-fb97-4979-8f4f-abe3e401dacc");
    private final LedgerAccessRepository memberships = Mockito.mock(LedgerAccessRepository.class);
    private final LocalSuperAgentPolicy superAgent = new LocalSuperAgentPolicy(false, UUID.randomUUID());
    private final PlatformAdminPolicy admin = new PlatformAdminPolicy(true, adminId, UUID.randomUUID());
    private final DefaultLedgerAccessService service =
            new DefaultLedgerAccessService(memberships, superAgent, admin);

    @Test
    void grantsThePlatformAdministratorOwnerAccessWithoutMembership() {
        UUID ledgerId = UUID.randomUUID();
        when(memberships.activeLedgerExists(ledgerId)).thenReturn(true);

        assertThat(service.requireMembership(adminId, ledgerId)).isEqualTo(LedgerRole.OWNER);
    }

    @Test
    void hidesDeletedLedgersFromThePlatformAdministrator() {
        UUID ledgerId = UUID.randomUUID();
        when(memberships.activeLedgerExists(ledgerId)).thenReturn(false);

        assertThatThrownBy(() -> service.requireMembership(adminId, ledgerId))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        exception -> assertThat(exception.code()).isEqualTo("LEDGER_NOT_FOUND"));
    }

    @Test
    void keepsStoredRolesForOrdinaryUsers() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        when(memberships.findRole(userId, ledgerId)).thenReturn(Optional.of(LedgerRole.VIEWER));

        assertThat(service.requireMembership(userId, ledgerId)).isEqualTo(LedgerRole.VIEWER);
    }
}
