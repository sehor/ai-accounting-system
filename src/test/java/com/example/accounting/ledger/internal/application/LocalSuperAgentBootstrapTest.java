package com.example.accounting.ledger.internal.application;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.identity.IdentityService;
import com.example.accounting.identity.UserResponse;
import com.example.accounting.identity.UserType;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.ledger.internal.port.LedgerRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalSuperAgentBootstrapTest {

    @Test
    void createsAnAgentAndGrantsEditorAccessToEveryLedger() {
        UUID agentId = UUID.randomUUID();
        UUID firstLedger = UUID.randomUUID();
        UUID secondLedger = UUID.randomUUID();
        IdentityService identities = mock(IdentityService.class);
        LedgerRepository ledgers = mock(LedgerRepository.class);
        when(identities.ensureUser(any())).thenReturn(new UserResponse(
                agentId, "local", "super-agent", "super-agent", null, UserType.AGENT, "ACTIVE"));
        when(ledgers.listAllLedgerIds()).thenReturn(List.of(firstLedger, secondLedger));

        new LocalSuperAgentBootstrap(identities, ledgers, true, agentId, "super-agent").synchronize();

        ArgumentCaptor<CurrentUserResolver.ResolvedUser> user =
                ArgumentCaptor.forClass(CurrentUserResolver.ResolvedUser.class);
        verify(identities).ensureUser(user.capture());
        assertThat(user.getValue().id()).isEqualTo(agentId);
        assertThat(user.getValue().displayName()).isEqualTo("super-agent");
        assertThat(user.getValue().userType()).isEqualTo(UserType.AGENT);
        verify(ledgers).upsertMember(firstLedger, agentId, LedgerRole.EDITOR, agentId);
        verify(ledgers).upsertMember(secondLedger, agentId, LedgerRole.EDITOR, agentId);
    }

    @Test
    void doesNothingWhenTheLocalSuperAgentIsDisabled() {
        IdentityService identities = mock(IdentityService.class);
        LedgerRepository ledgers = mock(LedgerRepository.class);

        new LocalSuperAgentBootstrap(
                identities, ledgers, false, UUID.randomUUID(), "super-agent").synchronize();

        verify(identities, never()).ensureUser(any());
        verify(ledgers, never()).listAllLedgerIds();
    }
}
