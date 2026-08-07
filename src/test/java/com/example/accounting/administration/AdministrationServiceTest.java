package com.example.accounting.administration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.accounting.administration.internal.port.AdministrationRepository;
import com.example.accounting.identity.UserType;
import com.example.accounting.shared.web.ApiProblemException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AdministrationServiceTest {

    private final UUID adminId = UUID.fromString("a2757c7a-fb97-4979-8f4f-abe3e401dacc");
    private final UUID agentId = UUID.fromString("00000000-0000-4000-8000-000000000099");
    private final AdministrationRepository repository = Mockito.mock(AdministrationRepository.class);
    private final PlatformAdminPolicy policy = new PlatformAdminPolicy(true, adminId, agentId);
    private final AdministrationService service = new AdministrationService(repository, policy);

    @Test
    void listsAllUsersAndMarksSystemUsersAsProtected() {
        UUID ordinaryUserId = UUID.randomUUID();
        when(repository.listUsers()).thenReturn(List.of(
                user(adminId, "admin", false),
                user(agentId, "super-agent", false),
                user(ordinaryUserId, "tester", false)));

        List<AdminResponses.User> users = service.listUsers(adminId);

        assertThat(users).filteredOn(AdminResponses.User::protectedUser)
                .extracting(AdminResponses.User::id).containsExactly(adminId, agentId);
        assertThat(users).filteredOn(user -> !user.protectedUser())
                .extracting(AdminResponses.User::id).containsExactly(ordinaryUserId);
    }

    @Test
    void deletesAndRestoresAnOrdinaryUser() {
        UUID userId = UUID.randomUUID();
        when(repository.findUser(userId))
                .thenReturn(Optional.of(user(userId, "tester", false)))
                .thenReturn(Optional.of(user(userId, "tester", true)))
                .thenReturn(Optional.of(user(userId, "tester", false)));

        service.deleteUser(adminId, userId);
        AdminResponses.User restored = service.restoreUser(adminId, userId);

        verify(repository).deleteUser(userId);
        verify(repository).restoreUser(userId);
        assertThat(restored.deleted()).isFalse();
    }

    @Test
    void refusesToDeleteTheAdministratorOrSystemAgent() {
        when(repository.findUser(adminId)).thenReturn(Optional.of(user(adminId, "admin", false)));
        when(repository.findUser(agentId)).thenReturn(Optional.of(user(agentId, "super-agent", false)));

        assertProblem("PROTECTED_USER", () -> service.deleteUser(adminId, adminId));
        assertProblem("PROTECTED_USER", () -> service.deleteUser(adminId, agentId));

        verify(repository, never()).deleteUser(adminId);
        verify(repository, never()).deleteUser(agentId);
    }

    @Test
    void rejectsNonAdministrators() {
        assertProblem("PLATFORM_ADMIN_REQUIRED", () -> service.listUsers(UUID.randomUUID()));
    }

    @Test
    void deletesAndRestoresAnyLedger() {
        UUID ledgerId = UUID.randomUUID();
        when(repository.findLedger(ledgerId))
                .thenReturn(Optional.of(ledger(ledgerId, false)))
                .thenReturn(Optional.of(ledger(ledgerId, true)))
                .thenReturn(Optional.of(ledger(ledgerId, false)));

        service.deleteLedger(adminId, ledgerId);
        AdminResponses.Ledger restored = service.restoreLedger(adminId, ledgerId);

        verify(repository).deleteLedger(ledgerId, adminId);
        verify(repository).restoreLedger(ledgerId, adminId);
        assertThat(restored.deleted()).isFalse();
    }

    private AdminResponses.User user(UUID id, String name, boolean deleted) {
        return new AdminResponses.User(id, "local", id.toString(), name, null,
                UserType.HUMAN, deleted ? "INACTIVE" : "ACTIVE", deleted, false);
    }

    private AdminResponses.Ledger ledger(UUID id, boolean deleted) {
        return new AdminResponses.Ledger(id, "Test", "SME", "2011-17", "CNY",
                LocalDate.of(2026, 1, 1), false, deleted ? "INACTIVE" : "ACTIVE", deleted);
    }

    private void assertProblem(String code, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiProblemException.class,
                        exception -> assertThat(exception.code()).isEqualTo(code));
    }
}
