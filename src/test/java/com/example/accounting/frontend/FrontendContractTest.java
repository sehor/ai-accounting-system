package com.example.accounting.frontend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.accounting.documents.DocumentResponses;
import com.example.accounting.documents.DocumentService;
import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.identity.IdentityService;
import com.example.accounting.identity.UserType;
import com.example.accounting.ledger.LedgerRequests;
import com.example.accounting.ledger.LedgerService;
import com.example.accounting.shared.web.ApiProblemException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;

@SpringBootTest
@org.junit.jupiter.api.Disabled("Creates ledgers; disabled until tests use an isolated database")
class FrontendContractTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private IdentityService identityService;

    @Autowired
    private CurrentUserResolver currentUserResolver;

    @Test
    void listsDocumentsAndStreamsContentForMembers() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(new CurrentUserResolver.ResolvedUser(owner, "test", owner.toString()),
                new LedgerRequests.Create("frontend-documents", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        byte[] bytes = "contract".getBytes(StandardCharsets.UTF_8);
        DocumentResponses.Document uploaded = documentService.upload(owner, ledgerId, "contract.pdf", "application/pdf",
                bytes.length, new ByteArrayInputStream(bytes));

        assertThat(uploaded.createdAt()).isNotNull();
        assertThat(documentService.list(owner, ledgerId, 10, 0)).extracting(DocumentResponses.Document::id)
                .contains(uploaded.id());
        assertThat(documentService.content(owner, ledgerId, uploaded.id()).bytes()).containsExactly(bytes);
    }

    @Test
    void findsOnlyAnExistingActiveUserByExactEmail() {
        UUID owner = UUID.randomUUID();
        UUID candidate = UUID.randomUUID();
        String unique = UUID.randomUUID().toString();
        String email = "candidate+" + unique + "@example.com";
        identityService.ensureUser(new CurrentUserResolver.ResolvedUser(candidate, "oidc", "candidate-" + unique,
                "Candidate", email));
        UUID ledgerId = ledgerService.create(new CurrentUserResolver.ResolvedUser(owner, "test", owner.toString()),
                new LedgerRequests.Create("frontend-members", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();

        assertThat(ledgerService.findMemberCandidates(owner, ledgerId, email.toUpperCase()))
                .extracting(user -> user.id()).containsExactly(candidate);
        assertThat(ledgerService.findMemberCandidates(owner, ledgerId, "missing@example.com")).isEmpty();
    }

    @Test
    void reusesTheExistingLocalUserForTheSameUsername() {
        String username = "local-" + UUID.randomUUID();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();

        identityService.ensureUser(new CurrentUserResolver.ResolvedUser(
                firstId, "local", firstId.toString(), username, null));
        var repeated = identityService.ensureUser(new CurrentUserResolver.ResolvedUser(
                secondId, "local", secondId.toString(), username.toUpperCase(), null));

        assertThat(repeated.id()).isEqualTo(firstId);
    }

    @Test
    void resolvesAStaleBrowserIdToTheExistingLocalUserBeforeListingLedgers() {
        String username = "local-" + UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        identityService.ensureUser(new CurrentUserResolver.ResolvedUser(
                ownerId, "local", ownerId.toString(), username, null));
        UUID ledgerId = ledgerService.create(new CurrentUserResolver.ResolvedUser(
                ownerId, "local", ownerId.toString(), username, null),
                new LedgerRequests.Create("canonical-user-ledger", "SME", "v1", "CNY",
                        LocalDate.of(2026, 1, 1), false)).id();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", UUID.randomUUID().toString());
        request.addHeader("X-User-Name", username.toUpperCase());

        UUID resolvedUserId = currentUserResolver.resolve(request);

        assertThat(resolvedUserId).isEqualTo(ownerId);
        assertThat(ledgerService.list(resolvedUserId))
                .extracting(ledger -> ledger.id()).containsExactly(ledgerId);
    }

    @Test
    void rejectsAnUnknownLocalUsernameWithoutCreatingAnId() {
        String username = "local-" + UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Name", username);

        ApiProblemException exception = assertThrows(ApiProblemException.class,
                () -> currentUserResolver.resolve(request));

        assertThat(exception.status()).isEqualTo(401);
        assertThat(exception.code()).isEqualTo("UNKNOWN_LOCAL_USER");
    }

    @Test
    void rejectsAnUnknownLocalIdWithoutCreatingAUser() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", UUID.randomUUID().toString());

        ApiProblemException exception = assertThrows(ApiProblemException.class,
                () -> currentUserResolver.resolve(request));

        assertThat(exception.status()).isEqualTo(401);
        assertThat(exception.code()).isEqualTo("UNKNOWN_LOCAL_USER");
    }

    @Test
    void storesWhetherAUserIsHumanOrAgent() {
        UUID humanId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();

        var human = identityService.ensureUser(new CurrentUserResolver.ResolvedUser(
                humanId, "test", humanId.toString()));
        var agent = identityService.ensureUser(new CurrentUserResolver.ResolvedUser(
                agentId, "test", agentId.toString(), "Agent", null, UserType.AGENT));

        assertThat(human.userType()).isEqualTo(UserType.HUMAN);
        assertThat(agent.userType()).isEqualTo(UserType.AGENT);
    }
}
