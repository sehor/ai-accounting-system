package com.example.accounting.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.identity.IdentityService;
import com.example.accounting.identity.UserType;
import com.example.accounting.ledger.LedgerRequests;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.ledger.LedgerService;
import com.example.accounting.shared.audit.AuditContext;
import com.example.accounting.shared.web.ApiProblemException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@SpringBootTest
class AccountingExperienceIntegrationTest {

    @Autowired
    private FinanceMcpTools tools;

    @Autowired
    private IdentityService identities;

    @Autowired
    private LedgerService ledgers;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        AuditContext.clear();
    }

    @Test
    void agentCanCreateMergeUpdateAndArchiveExperiences() {
        UUID ownerId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID secondAgentId = UUID.randomUUID();
        identities.ensureUser(agentUser(agentId));
        identities.ensureUser(agentUser(secondAgentId));
        UUID ledgerId = ledgers.create(humanUser(ownerId), new LedgerRequests.Create(
                "experience-ledger", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        ledgers.addMember(ownerId, ledgerId, new LedgerRequests.AddMember(agentId, LedgerRole.AGENT));
        ledgers.addMember(ownerId, ledgerId, new LedgerRequests.AddMember(secondAgentId, LedgerRole.AGENT));

        authenticate(agentId);
        String traceId = "experience-" + UUID.randomUUID();
        String runTag = "exp-" + UUID.randomUUID().toString().substring(0, 8);
        AuditContext.setTraceId(traceId);
        var general = tools.createAccountingExperience(new ExperienceRequests.Create(
                ExperienceScope.GENERAL, null, "发票税率核对", "先核对票面税率再入账", List.of(runTag, "税率")));
        var ledger = tools.createAccountingExperience(new ExperienceRequests.Create(
                ExperienceScope.LEDGER, ledgerId, "本账套差旅费", "差旅费进入管理费用", List.of(runTag, "差旅")));

        var merged = tools.searchAccountingExperiences(new ExperienceRequests.Search(
                ledgerId, null, List.of(runTag), 1, 20));
        assertThat(merged.items()).extracting(ExperienceResponses.Experience::id)
                .containsExactlyInAnyOrder(general.id(), ledger.id());
        var keywordPage = tools.searchAccountingExperiences(new ExperienceRequests.Search(
                ledgerId, "税率", List.of(runTag), 1, 1));
        assertThat(keywordPage.totalItems()).isEqualTo(1L);
        assertThat(keywordPage.totalPages()).isEqualTo(1);
        assertThat(keywordPage.items()).extracting(ExperienceResponses.Experience::id)
                .containsExactly(general.id());

        authenticate(secondAgentId);
        var updated = tools.updateAccountingExperience(general.id(), new ExperienceRequests.Update(
                general.version(), "发票税率核对（更新）", "复核税率和费用归属",
                List.of(runTag, "发票", "税率", "复核")));
        assertThat(updated.version()).isEqualTo(general.version() + 1);

        var archived = tools.archiveAccountingExperience(ledger.id(), ledger.version());
        assertThat(archived.status()).isEqualTo("ARCHIVED");
        assertThat(tools.searchAccountingExperiences(new ExperienceRequests.Search(
                ledgerId, null, List.of(runTag), 1, 20)).items())
                .extracting(ExperienceResponses.Experience::id)
                .containsExactly(updated.id());
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(jdbc.queryForObject(
                        "select count(*) from agent_tool_audit where trace_id = ? and outcome = 'SUCCESS'",
                        Long.class, traceId)).isEqualTo(7L));
    }

    @Test
    void humanCannotUseExperienceTools() {
        UUID ownerId = UUID.randomUUID();
        ledgers.create(humanUser(ownerId), new LedgerRequests.Create(
                "human-experience", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false));
        authenticate(ownerId);

        assertThatThrownBy(() -> tools.searchAccountingExperiences(new ExperienceRequests.Search(
                null, "税率", List.of(), 1, 20)))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("AGENT_IDENTITY_REQUIRED"));
    }

    @Test
    void nonMemberAgentCannotReadLedgerExperiences() {
        UUID ownerId = UUID.randomUUID();
        UUID outsiderId = UUID.randomUUID();
        UUID ledgerId = ledgers.create(humanUser(ownerId), new LedgerRequests.Create(
                "isolated-experience", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        identities.ensureUser(agentUser(outsiderId));
        authenticate(outsiderId);

        assertThatThrownBy(() -> tools.searchAccountingExperiences(new ExperienceRequests.Search(
                ledgerId, null, List.of(), 1, 20)))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("LEDGER_NOT_FOUND"));
    }

    private CurrentUserResolver.ResolvedUser agentUser(UUID id) {
        return new CurrentUserResolver.ResolvedUser(id, "test", id.toString(), "Agent", null, UserType.AGENT);
    }

    private CurrentUserResolver.ResolvedUser humanUser(UUID id) {
        return new CurrentUserResolver.ResolvedUser(id, "test", id.toString(), "Human", null, UserType.HUMAN);
    }

    private void authenticate(UUID id) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(id.toString(), "n/a", List.of()));
    }
}
