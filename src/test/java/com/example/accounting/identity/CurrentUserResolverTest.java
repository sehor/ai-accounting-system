package com.example.accounting.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.accounting.shared.web.ApiProblemException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;

class CurrentUserResolverTest {

    @Test
    void acceptsTheLocalDevelopmentUserHeaderWhenEnabled() {
        UUID userId = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", userId.toString());

        assertEquals(userId, new CurrentUserResolver(true).resolve(request));
    }

    @Test
    void usesTheLocalUsernameAsTheDisplayName() {
        UUID userId = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", userId.toString());
        request.addHeader("X-User-Name", "alice");

        CurrentUserResolver.ResolvedUser user = new CurrentUserResolver(true).resolveUser(request);

        assertEquals(userId, user.id());
        assertEquals("alice", user.displayName());
    }

    @Test
    void rejectsAnInvalidLocalUsername() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", UUID.randomUUID().toString());
        request.addHeader("X-User-Name", "<script>");

        ApiProblemException exception = assertThrows(ApiProblemException.class,
                () -> new CurrentUserResolver(true).resolveUser(request));

        assertEquals(400, exception.status());
        assertEquals("INVALID_USER_NAME", exception.code());
    }

    @Test
    void rejectsMissingIdentity() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        ApiProblemException exception = assertThrows(ApiProblemException.class,
                () -> new CurrentUserResolver(false).resolve(request));

        assertEquals(401, exception.status());
        assertEquals("UNAUTHENTICATED", exception.code());
    }

    @Test
    void doesNotAcceptTheLocalHeaderWhenDisabled() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", UUID.randomUUID().toString());

        assertThrows(ApiProblemException.class, () -> new CurrentUserResolver(false).resolve(request));
        SecurityContextHolder.clearContext();
    }
}
