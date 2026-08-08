package com.example.accounting.agent;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.identity.IdentityService;
import com.example.accounting.identity.UserType;
import com.example.accounting.ledger.LedgerRequests;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.ledger.LedgerService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "app.security.local-super-agent-enabled=true",
        "app.security.local-super-agent-user-id=00000000-0000-4000-8000-000000000099"
})
@Transactional
class LedgerRenameMcpIntegrationTest {

    private static final UUID SUPER_AGENT_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000099");

    @Autowired
    private FinanceMcpTools tools;

    @Autowired
    private LedgerService ledgers;

    @Autowired
    private IdentityService identities;

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void agentEditorCanRenameALedgerThroughMcp() {
        UUID ownerId = UUID.randomUUID();
        UUID agentId = SUPER_AGENT_ID;
        UUID ledgerId = ledgers.create(user(ownerId, UserType.HUMAN, "owner"),
                new LedgerRequests.Create("before", "SME", "2011-17", "CNY",
                        LocalDate.of(2026, 1, 1), false)).id();
        identities.ensureUser(user(agentId, UserType.AGENT, "super-agent"));
        ledgers.addMember(ownerId, ledgerId, new LedgerRequests.AddMember(agentId, LedgerRole.EDITOR));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(agentId.toString(), "n/a", List.of()));

        var updated = tools.updateLedger(ledgerId, new LedgerRequests.Rename("after"));

        assertThat(updated.name()).isEqualTo("after");
        assertThat(ledgers.findLedger(agentId, ledgerId).name()).isEqualTo("after");
        assertThat(ledgers.role(agentId, ledgerId)).isEqualTo(LedgerRole.OWNER);
    }

    @Test
    void superAgentCannotManageLedgerMembers() {
        UUID ownerId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        UUID ledgerId = ledgers.create(user(ownerId, UserType.HUMAN, "owner"),
                new LedgerRequests.Create("members", "SME", "2011-17", "CNY",
                        LocalDate.of(2026, 1, 1), false)).id();
        identities.ensureUser(user(SUPER_AGENT_ID, UserType.AGENT, "super-agent"));
        identities.ensureUser(user(candidateId, UserType.HUMAN, "candidate"));
        ledgers.addMember(ownerId, ledgerId, new LedgerRequests.AddMember(SUPER_AGENT_ID, LedgerRole.EDITOR));

        assertThatThrownBy(() -> ledgers.addMember(SUPER_AGENT_ID, ledgerId,
                new LedgerRequests.AddMember(candidateId, LedgerRole.VIEWER)))
                .hasMessageContaining("cannot manage ledger members");
    }

    private CurrentUserResolver.ResolvedUser user(UUID id, UserType type, String name) {
        return new CurrentUserResolver.ResolvedUser(id, "test", id.toString(), name, null, type);
    }
}
