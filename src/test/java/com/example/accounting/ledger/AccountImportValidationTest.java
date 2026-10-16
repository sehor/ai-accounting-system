package com.example.accounting.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.accounting.agent.internal.AsyncAgentToolAuditService;
import com.example.accounting.agent.FinanceMcpTools;
import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.ledger.internal.persistence.AccountImportRepository;
import com.example.accounting.shared.web.ApiProblemException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockMultipartFile;

class AccountImportValidationTest {
    private final UUID actor = UUID.randomUUID();
    private final UUID ledger = UUID.randomUUID();
    private final UUID importId = UUID.randomUUID();

    @Test
    void rejectsCodeChangesThroughSingleAndBatchDecisions() {
        var imports = mock(AccountImportRepository.class);
        var service = decisionService(imports);
        var changed = new AccountExchangeService.Decision("CREATE", null, "100202");

        assertThatThrownBy(() -> service.decide(actor, ledger, importId, 1, changed))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        ex -> assertThat(ex.code()).isEqualTo("ACCOUNT_IMPORT_CODE_IMMUTABLE"));
        assertThatThrownBy(() -> service.decideAll(actor, ledger, importId,
                List.of(new AccountExchangeService.RowDecision(1, changed))))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        ex -> assertThat(ex.code()).isEqualTo("ACCOUNT_IMPORT_CODE_IMMUTABLE"));
        verify(imports, never()).decide(any(), anyInt(), any(), any(), any());
    }

    @Test
    void acceptsLegacyDecisionsWithOriginalCodeOrNoCode() {
        var imports = mock(AccountImportRepository.class);
        var service = decisionService(imports);
        when(imports.decide(importId, 1, "CREATE", null, null)).thenReturn(1);
        service.decide(actor, ledger, importId, 1,
                new AccountExchangeService.Decision("CREATE", null, "100201"));
        service.decide(actor, ledger, importId, 1,
                new AccountExchangeService.Decision("CREATE", null, null));
        verify(imports, times(2)).decide(importId, 1, "CREATE", null, null);
    }

    @ParameterizedTest
    @CsvSource(value = {"3,NULL,NULL", "NULL,3,NULL", "NULL,NULL,3",
            "3,2,NULL", "3,NULL,2", "NULL,3,2"}, nullValues = "NULL")
    void rejectsPartialWidthsInRestAndMcp(Integer w2, Integer w3, Integer w4) {
        var users = mock(CurrentUserResolver.class);
        var exchange = mock(AccountExchangeService.class);
        var controller = new AccountExchangeController(users, exchange);
        var tools = tools(users, exchange);
        assertThatThrownBy(() -> controller.preview(null, ledger, AccountExchangeService.Format.KINGDEE,
                AccountExchangeService.ImportMode.OVERWRITE, w2, w3, w4, null))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        ex -> assertThat(ex.code()).isEqualTo("ACCOUNT_CODE_RULE_INCOMPLETE"));
        assertThatThrownBy(() -> tools.previewAccountImport(ledger, AccountExchangeService.Format.KINGDEE,
                "review.xls", "AQ==", AccountExchangeService.ImportMode.OVERWRITE, w2, w3, w4))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        ex -> assertThat(ex.code()).isEqualTo("ACCOUNT_CODE_RULE_INCOMPLETE"));
        verifyNoInteractions(exchange);
    }

    @ParameterizedTest
    @CsvSource(value = {"3,2,2", "NULL,NULL,NULL"}, nullValues = "NULL")
    void passesCompleteOrAbsentWidthsThroughRestAndMcp(Integer w2, Integer w3, Integer w4) throws Exception {
        var users = mock(CurrentUserResolver.class);
        var exchange = mock(AccountExchangeService.class);
        when(users.resolve(null)).thenReturn(actor);
        when(users.resolveAuthenticatedUser()).thenReturn(actor);
        var controller = new AccountExchangeController(users, exchange);
        var file = new MockMultipartFile("file", "review.xls", "application/octet-stream", new byte[] {1});
        controller.preview(null, ledger, AccountExchangeService.Format.KINGDEE,
                AccountExchangeService.ImportMode.OVERWRITE, w2, w3, w4, file);
        tools(users, exchange).previewAccountImport(ledger, AccountExchangeService.Format.KINGDEE,
                "review.xls", "AQ==", AccountExchangeService.ImportMode.OVERWRITE, w2, w3, w4);
        AccountCodeRule expected = w2 == null ? null : new AccountCodeRule(w2, w3, w4);
        verify(exchange, times(2)).preview(eq(actor), eq(ledger), eq(AccountExchangeService.Format.KINGDEE),
                eq(AccountExchangeService.ImportMode.OVERWRITE), eq(expected), eq("review.xls"), eq(1L), any());
    }

    private AccountExchangeService decisionService(AccountImportRepository imports) {
        var access = mock(LedgerAccessService.class);
        when(access.requireMembership(actor, ledger)).thenReturn(LedgerRole.OWNER);
        when(imports.findHeader(ledger, importId)).thenReturn(new AccountImportRepository.Header(
                importId, ledger, "KINGDEE", "APPEND", "PREVIEW", 1L, "review.xls", "hash",
                1, 0, 1, 0, 2, 2, 2, "{}", "[]", "NOT_CONFIGURED"));
        when(imports.previewRows(importId)).thenReturn(List.of(new AccountImportRepository.PreviewRow(
                1, "{}", "{}", "100201", null, null, "CREATE", false, null, "[]")));
        var service = spy(new AccountExchangeService(null, access, null, imports, null, null, null));
        doReturn(null).when(service).get(actor, ledger, importId);
        return service;
    }

    private FinanceMcpTools tools(CurrentUserResolver users, AccountExchangeService exchange) {
        return new FinanceMcpTools(users, null, null, null, null, null, null, exchange,
                null, null, null, null, null, null, mock(AsyncAgentToolAuditService.class), null);
    }
}
