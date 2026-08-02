package com.example.accounting.frontend;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.accounting.documents.DocumentResponses;
import com.example.accounting.documents.DocumentService;
import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.identity.IdentityService;
import com.example.accounting.ledger.LedgerRequests;
import com.example.accounting.ledger.LedgerService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class FrontendContractTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private IdentityService identityService;

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
}
