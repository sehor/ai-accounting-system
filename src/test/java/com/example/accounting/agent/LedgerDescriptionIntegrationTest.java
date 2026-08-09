package com.example.accounting.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.ledger.LedgerBackupService;
import com.example.accounting.ledger.LedgerRequests;
import com.example.accounting.ledger.LedgerService;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@SpringBootTest
class LedgerDescriptionIntegrationTest {

    @Autowired
    private FinanceMcpTools tools;

    @Autowired
    private LedgerService ledgers;

    @Autowired
    private LedgerBackupService backups;

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void exposesBusinessDescriptionToLedgerAndAgentContexts() {
        UUID ownerId = UUID.randomUUID();
        String description = "研发、生产和销售智能硬件及配套软件";
        var ledger = ledgers.create(user(ownerId), new LedgerRequests.Create(
                "核心业务测试", description, "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false));
        authenticate(ownerId);

        assertThat(ledger.description()).isEqualTo(description);
        assertThat(tools.getLedger(ledger.id()).description()).isEqualTo(description);
        assertThat(tools.getLedgerContext(ledger.id()).ledger().description()).isEqualTo(description);

        var updated = tools.updateLedger(ledger.id(), new LedgerRequests.Rename(
                "核心业务测试", "更新后的主营业务描述"));
        assertThat(updated.description()).isEqualTo("更新后的主营业务描述");
        assertThat(tools.getLedger(ledger.id()).description()).isEqualTo("更新后的主营业务描述");

        byte[] archive = backups.backup(ownerId, ledger.id());
        var restored = backups.restore(user(ownerId), null, archive.length, new ByteArrayInputStream(archive));
        assertThat(restored.description()).isEqualTo("更新后的主营业务描述");
    }

    private CurrentUserResolver.ResolvedUser user(UUID id) {
        return new CurrentUserResolver.ResolvedUser(id, "test", id.toString());
    }

    private void authenticate(UUID userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), "n/a", List.of()));
    }
}
