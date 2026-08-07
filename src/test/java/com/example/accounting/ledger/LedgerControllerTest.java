package com.example.accounting.ledger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.shared.web.ProblemDetailExceptionHandler;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LedgerControllerTest {

    private final LedgerService ledgerService = org.mockito.Mockito.mock(LedgerService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
            new LedgerController(new CurrentUserResolver(true), ledgerService))
            .setControllerAdvice(new ProblemDetailExceptionHandler())
            .build();

    @Test
    void createsLedgerForTheLocalUser() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        when(ledgerService.create(any(), any())).thenReturn(
                new LedgerResponses.Ledger(ledgerId, "Demo", "SME", "v1", "CNY",
                        LocalDate.of(2026, 1, 1), false, "ACTIVE"));

        mockMvc.perform(post("/v1/ledgers")
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Demo","accountingStandardCode":"SME",
                                 "accountingStandardVersion":"v1","baseCurrency":"CNY",
                                 "startDate":"2026-01-01","approvalEnabled":false}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ledgerId.toString()))
                .andExpect(jsonPath("$.baseCurrency").value("CNY"));

        verify(ledgerService).create(eq(new com.example.accounting.identity.CurrentUserResolver.ResolvedUser(
                userId, "local", userId.toString())), any());
    }

    @Test
    void rejectsLedgerRequestsWithoutAnIdentity() throws Exception {
        mockMvc.perform(get("/v1/ledgers"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void listsOnlyTheCurrentUsersLedgersThroughTheController() throws Exception {
        UUID userId = UUID.randomUUID();
        when(ledgerService.list(userId)).thenReturn(List.of());

        mockMvc.perform(get("/v1/ledgers").header("X-User-Id", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        verify(ledgerService).list(userId);
    }

    @Test
    void listsLedgerAccountsAndPeriods() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        when(ledgerService.listAccounts(userId, ledgerId)).thenReturn(List.of(
                new LedgerResponses.Account(UUID.randomUUID(), ledgerId, "1002", "银行存款", "ASSET", "DEBIT", "ACTIVE")));
        when(ledgerService.listPeriods(userId, ledgerId)).thenReturn(List.of(
                new LedgerResponses.Period(UUID.randomUUID(), ledgerId, "2026-01",
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), "OPEN")));

        mockMvc.perform(get("/v1/ledgers/{ledgerId}/accounts", ledgerId).header("X-User-Id", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("1002"));
        mockMvc.perform(get("/v1/ledgers/{ledgerId}/periods", ledgerId).header("X-User-Id", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].periodCode").value("2026-01"));

        verify(ledgerService).listAccounts(userId, ledgerId);
        verify(ledgerService).listPeriods(userId, ledgerId);
    }

    @Test
    void searchesAccountsWithParentAndChildren() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        LedgerResponses.Account account = new LedgerResponses.Account(
                accountId, ledgerId, "1002", "Bank deposits", "ASSET", "DEBIT", "ACTIVE");
        LedgerResponses.AccountSummary parent = new LedgerResponses.AccountSummary(
                UUID.randomUUID(), "1000", "Cash and bank", "ACTIVE");
        LedgerResponses.AccountSummary child = new LedgerResponses.AccountSummary(
                UUID.randomUUID(), "100201", "Bank deposit - CCB", "ACTIVE");
        when(ledgerService.searchAccounts(
                userId, ledgerId, "1002", LedgerRequests.AccountMatchMode.EXACT, 5))
                .thenReturn(List.of(new LedgerResponses.AccountSearchResult(account, parent, List.of(child))));

        mockMvc.perform(get("/v1/ledgers/{ledgerId}/accounts/search", ledgerId)
                        .header("X-User-Id", userId)
                        .queryParam("query", "1002")
                        .queryParam("matchMode", "EXACT")
                        .queryParam("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].account.code").value("1002"))
                .andExpect(jsonPath("$[0].parent.code").value("1000"))
                .andExpect(jsonPath("$[0].children[0].code").value("100201"));

        verify(ledgerService).searchAccounts(
                userId, ledgerId, "1002", LedgerRequests.AccountMatchMode.EXACT, 5);
    }

    @Test
    void exposesDimensionAndOpeningBalanceEndpoints() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();
        when(ledgerService.createDimensionType(eq(userId), eq(ledgerId), any())).thenReturn(
                new LedgerResponses.DimensionType(typeId, ledgerId, "CUSTOMER", "Customer", true, "ACTIVE"));
        when(ledgerService.createDimensionValue(eq(userId), eq(ledgerId), eq(typeId), any())).thenReturn(
                new LedgerResponses.DimensionValue(UUID.randomUUID(), ledgerId, typeId, "C001", "Acme", "ACTIVE"));
        when(ledgerService.replaceOpeningBalances(eq(userId), eq(ledgerId), any())).thenReturn(List.of());
        when(ledgerService.confirmOpeningBalances(userId, ledgerId)).thenReturn(2);

        mockMvc.perform(post("/v1/ledgers/{ledgerId}/dimension-types", ledgerId)
                        .header("X-User-Id", userId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"CUSTOMER\",\"name\":\"Customer\",\"required\":true}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/ledgers/{ledgerId}/dimension-types/{typeId}/values", ledgerId, typeId)
                        .header("X-User-Id", userId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"C001\",\"name\":\"Acme\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(put("/v1/ledgers/{ledgerId}/opening-balances", ledgerId)
                        .header("X-User-Id", userId).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"accountId":"%s","periodId":"%s","currency":"CNY",
                                "debitOriginal":100,"creditOriginal":0,"exchangeRate":1}]}
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isOk());
        mockMvc.perform(post("/v1/ledgers/{ledgerId}/opening-balances:confirm", ledgerId)
                        .header("X-User-Id", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmedCount").value(2));

        verify(ledgerService).confirmOpeningBalances(userId, ledgerId);
    }

    @Test
    void exposesPeriodCloseAndReopenEndpoints() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        UUID periodId = UUID.randomUUID();
        LedgerResponses.Period period = new LedgerResponses.Period(periodId, ledgerId, "2026-01",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), "CLOSED");
        when(ledgerService.closePeriod(eq(userId), eq(ledgerId), eq(periodId), any())).thenReturn(period);
        when(ledgerService.reopenPeriod(eq(userId), eq(ledgerId), eq(periodId), any())).thenReturn(
                new LedgerResponses.Period(periodId, ledgerId, "2026-01", LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 1, 31), "OPEN"));

        mockMvc.perform(post("/v1/ledgers/{ledgerId}/periods/{periodId}:close", ledgerId, periodId)
                        .header("X-User-Id", userId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"month end\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        mockMvc.perform(post("/v1/ledgers/{ledgerId}/periods/{periodId}:reopen", ledgerId, periodId)
                        .header("X-User-Id", userId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"correction\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void exposesOpeningBalanceCsvImportEndpoint() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        when(ledgerService.importOpeningBalances(eq(userId), eq(ledgerId), any())).thenReturn(List.of());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(
                        "/v1/ledgers/{ledgerId}/opening-balances:import-csv", ledgerId)
                        .file(new MockMultipartFile("file", "opening.csv", "text/csv", "header\n".getBytes()))
                        .header("X-User-Id", userId))
                .andExpect(status().isOk());
    }
}
