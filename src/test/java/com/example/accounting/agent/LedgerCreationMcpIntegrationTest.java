package com.example.accounting.agent;

import com.example.accounting.ledger.LedgerRequests;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.shared.audit.AuditContext;
import com.example.accounting.shared.web.ApiProblemException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class LedgerCreationMcpIntegrationTest {

    @Autowired
    private FinanceMcpTools tools;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
        AuditContext.clear();
    }

    @Test
    void authenticatedUserCreatesAndOwnsAnInitializedLedger() {
        UUID actorId = UUID.randomUUID();
        authenticate(actorId);

        var ledger = tools.createLedger(new LedgerRequests.Create(
                "MCP ledger", "Created through MCP", "SME", "2011-17", "CNY",
                LocalDate.of(2026, 1, 1), false));

        assertThat(ledger.name()).isEqualTo("MCP ledger");
        assertThat(ledger.description()).isEqualTo("Created through MCP");
        assertThat(ledger.accountingStandardCode()).isEqualTo("SME");
        assertThat(ledger.accountingStandardVersion()).isEqualTo("2011-17");
        assertThat(ledger.baseCurrency()).isEqualTo("CNY");
        assertThat(ledger.status()).isEqualTo("ACTIVE");
        assertThat(tools.getLedgerRole(ledger.id())).containsEntry("role", LedgerRole.OWNER.name());
        assertThat(jdbc.queryForObject(
                "select count(*) from ledger_account where ledger_id = ?", Integer.class, ledger.id()))
                .isPositive();
        assertThat(jdbc.queryForObject(
                "select count(*) from accounting_period where ledger_id = ?", Integer.class, ledger.id()))
                .isPositive();
    }

    @Test
    void creationStillUsesTheLedgerServiceValidationBoundary() {
        UUID actorId = UUID.randomUUID();
        authenticate(actorId);

        assertThatThrownBy(() -> tools.createLedger(new LedgerRequests.Create(
                "Invalid MCP ledger", "SME", "missing", "CNY",
                LocalDate.of(2026, 1, 1), false)))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        exception -> assertThat(exception.code()).isEqualTo("ACCOUNTING_STANDARD_NOT_FOUND"));
        assertThat(jdbc.queryForObject(
                "select count(*) from ledger where created_by = ?", Integer.class, actorId)).isZero();
    }

    private void authenticate(UUID actorId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actorId.toString(), "n/a", List.of()));
    }
}
