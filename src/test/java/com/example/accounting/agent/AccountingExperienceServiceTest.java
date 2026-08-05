package com.example.accounting.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.accounting.agent.internal.port.AccountingExperienceRepository;
import com.example.accounting.agent.internal.application.DefaultAccountingExperienceService;
import com.example.accounting.identity.IdentityService;
import com.example.accounting.identity.UserResponse;
import com.example.accounting.identity.UserType;
import com.example.accounting.ledger.LedgerAccessService;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.shared.web.ApiProblemException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountingExperienceServiceTest {

    private final AccountingExperienceRepository repository = mock(AccountingExperienceRepository.class);
    private final IdentityService identities = mock(IdentityService.class);
    private final LedgerAccessService ledgerAccess = mock(LedgerAccessService.class);
    private final AccountingExperienceService service =
            new DefaultAccountingExperienceService(repository, identities, ledgerAccess);
    private final UUID agentId = UUID.randomUUID();
    private final UUID ledgerId = UUID.randomUUID();

    @BeforeEach
    void agentIdentity() {
        when(identities.findUser(agentId)).thenReturn(Optional.of(
                new UserResponse(agentId, "local", agentId.toString(), "Agent", null, UserType.AGENT, "ACTIVE")));
    }

    @Test
    void createsGeneralExperienceWithNormalizedTextAndTags() {
        UUID id = UUID.randomUUID();
        when(repository.create(eq(ExperienceScope.GENERAL), eq(null), eq("如何处理进项发票"),
                eq("先核对税率，再确认费用归属"), eq(List.of("发票", "差旅")), eq(agentId)))
                .thenReturn(record(id, ExperienceScope.GENERAL, null, "如何处理进项发票",
                        "先核对税率，再确认费用归属", List.of("发票", "差旅"), 0));

        ExperienceResponses.Experience result = service.create(agentId,
                new ExperienceRequests.Create(ExperienceScope.GENERAL, null,
                        "  如何处理进项发票 ", " 先核对税率，再确认费用归属 ",
                        List.of("发票", "发票", "差旅")));

        assertThat(result.id()).isEqualTo(id);
        assertThat(result.scope()).isEqualTo(ExperienceScope.GENERAL);
        verify(repository).create(ExperienceScope.GENERAL, null, "如何处理进项发票",
                "先核对税率，再确认费用归属", List.of("发票", "差旅"), agentId);
    }

    @Test
    void searchesGeneralAndLedgerExperiencesTogether() {
        AccountingExperienceRepository.Page page = new AccountingExperienceRepository.Page(
                List.of(
                        record(UUID.randomUUID(), ExperienceScope.LEDGER, ledgerId, "本账套税率",
                                "使用 6%", List.of("税率"), 0),
                        record(UUID.randomUUID(), ExperienceScope.GENERAL, null, "发票核对",
                                "核对税率", List.of("税率"), 1)), 2);
        when(ledgerAccess.requireMembership(agentId, ledgerId)).thenReturn(LedgerRole.AGENT);
        when(repository.search(eq(ledgerId), eq("税率"), eq(List.of("税率")), eq(20), eq(0)))
                .thenReturn(page);

        ExperienceResponses.Page result = service.search(agentId,
                new ExperienceRequests.Search(ledgerId, "税率", List.of("税率"), 1, 20));

        assertThat(result.items()).hasSize(2);
        assertThat(result.totalItems()).isEqualTo(2);
        assertThat(result.totalPages()).isEqualTo(1);
    }

    @Test
    void rejectsHumanIdentityBeforeAccessingExperienceData() {
        when(identities.findUser(agentId)).thenReturn(Optional.of(
                new UserResponse(agentId, "local", agentId.toString(), "Human", null, UserType.HUMAN, "ACTIVE")));

        assertThatThrownBy(() -> service.search(agentId,
                new ExperienceRequests.Search(null, null, List.of(), 1, 20)))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("AGENT_IDENTITY_REQUIRED"));
    }

    @Test
    void rejectsStaleVersionWhenUpdatingExperience() {
        UUID id = UUID.randomUUID();
        when(repository.find(id)).thenReturn(Optional.of(record(id, ExperienceScope.GENERAL, null,
                "原经验", "原内容", List.of(), 3)));
        when(repository.update(eq(id), eq(2L), any(), any(), any(), eq(agentId))).thenReturn(false);

        assertThatThrownBy(() -> service.update(agentId, id,
                new ExperienceRequests.Update(2, "新标题", "新内容", List.of())))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("EXPERIENCE_VERSION_CONFLICT"));
    }

    private AccountingExperienceRepository.Record record(UUID id, ExperienceScope scope, UUID ledgerId,
                                                         String title, String content, List<String> tags,
                                                         long version) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-05T00:00:00Z");
        return new AccountingExperienceRepository.Record(id, scope, ledgerId, title, content, tags,
                "ACTIVE", version, agentId, agentId, now, now);
    }
}
