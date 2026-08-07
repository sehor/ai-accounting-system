package com.example.accounting.administration;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.shared.web.ProblemDetailExceptionHandler;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdministrationControllerTest {

    private final AdministrationService administration = org.mockito.Mockito.mock(AdministrationService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
            new AdministrationController(new CurrentUserResolver(true), administration))
            .setControllerAdvice(new ProblemDetailExceptionHandler())
            .build();

    @Test
    void exposesUserAdministrationRoutes() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(administration.listUsers(actorId)).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/users").header("X-User-Id", actorId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        mockMvc.perform(delete("/v1/admin/users/{userId}", userId).header("X-User-Id", actorId))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/v1/admin/users/{userId}:restore", userId).header("X-User-Id", actorId))
                .andExpect(status().isOk());

        verify(administration).listUsers(actorId);
        verify(administration).deleteUser(actorId, userId);
        verify(administration).restoreUser(actorId, userId);
    }

    @Test
    void exposesLedgerAdministrationRoutes() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        when(administration.listLedgers(actorId)).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/ledgers").header("X-User-Id", actorId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        mockMvc.perform(delete("/v1/admin/ledgers/{ledgerId}", ledgerId).header("X-User-Id", actorId))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/v1/admin/ledgers/{ledgerId}:restore", ledgerId).header("X-User-Id", actorId))
                .andExpect(status().isOk());

        verify(administration).listLedgers(actorId);
        verify(administration).deleteLedger(actorId, ledgerId);
        verify(administration).restoreLedger(actorId, ledgerId);
    }
}
