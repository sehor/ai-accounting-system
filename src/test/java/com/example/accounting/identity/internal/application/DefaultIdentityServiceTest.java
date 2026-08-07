package com.example.accounting.identity.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.identity.UserResponse;
import com.example.accounting.identity.UserType;
import com.example.accounting.identity.internal.port.IdentityRepository;
import com.example.accounting.shared.web.ApiProblemException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class DefaultIdentityServiceTest {

    private final IdentityRepository users = Mockito.mock(IdentityRepository.class);
    private final DefaultIdentityService service = new DefaultIdentityService(users);

    @Test
    void doesNotReactivateADeletedUserDuringLogin() {
        UUID userId = UUID.randomUUID();
        when(users.findByIdIncludingDeleted(userId)).thenReturn(Optional.of(new UserResponse(
                userId, "oidc", "subject", "Deleted", "deleted@example.com", UserType.HUMAN, "INACTIVE")));

        assertThatThrownBy(() -> service.ensureUser(new CurrentUserResolver.ResolvedUser(
                userId, "oidc", "subject", "Deleted", "deleted@example.com")))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        exception -> assertThat(exception.code()).isEqualTo("USER_INACTIVE"));

        verify(users, never()).upsert(userId, "oidc", "subject", "Deleted", "deleted@example.com", UserType.HUMAN);
    }
}
