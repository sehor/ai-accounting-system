package com.example.accounting.reporting;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.accounting.identity.CurrentUserResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ReportFormulaControllerTest {

    private final ReportFormulaService service = org.mockito.Mockito.mock(ReportFormulaService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
            new ReportFormulaController(new CurrentUserResolver(true), service)).build();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void exposesWorkspaceDraftPublishAndVersionEndpoints() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        ObjectNode definition = mapper.createObjectNode().put("schemaVersion", 1).put("kind", "FIXED_LINES");
        ReportFormulaResponses.Workspace workspace = new ReportFormulaResponses.Workspace(
                "BALANCE_SHEET", "资产负债表", "FIXED_LINES", "BALANCE_SHEET", "SME-2011-17", 1,
                definition, null);
        ReportFormulaResponses.Draft draft = new ReportFormulaResponses.Draft(1, 1, definition,
                null, false, java.time.OffsetDateTime.now());
        when(service.workspace(userId, ledgerId, "BALANCE_SHEET")).thenReturn(workspace);
        when(service.createDraft(userId, ledgerId, "BALANCE_SHEET")).thenReturn(draft);
        when(service.publish(userId, ledgerId, "BALANCE_SHEET",
                new ReportFormulaRequests.PublishRequest(1, 1L, false)))
                .thenReturn(new ReportFormulaResponses.PublishResult("BALANCE_SHEET", 2));
        when(service.versions(userId, ledgerId, "BALANCE_SHEET", 1, 20))
                .thenReturn(new ReportFormulaResponses.VersionPage(1, 20, 1, 1, List.of(
                        new ReportFormulaResponses.VersionInfo(1, "STANDARD", null, userId,
                                java.time.OffsetDateTime.now(), definition))));

        mockMvc.perform(get("/v1/ledgers/{ledgerId}/report-formulas/BALANCE_SHEET", ledgerId)
                        .header("X-User-Id", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("FIXED_LINES"))
                .andExpect(jsonPath("$.publishedVersion").value(1));
        mockMvc.perform(post("/v1/ledgers/{ledgerId}/report-formulas/BALANCE_SHEET/draft", ledgerId)
                        .header("X-User-Id", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(post("/v1/ledgers/{ledgerId}/report-formulas/BALANCE_SHEET:publish", ledgerId)
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedPublishedVersion\":1,\"expectedDraftVersion\":1,\"acknowledgeWarnings\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(2));
        mockMvc.perform(get("/v1/ledgers/{ledgerId}/report-formulas/BALANCE_SHEET/versions", ledgerId)
                        .header("X-User-Id", userId).queryParam("page", "1").queryParam("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].version").value(1));

        verify(service).workspace(userId, ledgerId, "BALANCE_SHEET");
        verify(service).createDraft(userId, ledgerId, "BALANCE_SHEET");
        verify(service).publish(userId, ledgerId, "BALANCE_SHEET",
                new ReportFormulaRequests.PublishRequest(1, 1L, false));
        verify(service).versions(userId, ledgerId, "BALANCE_SHEET", 1, 20);
    }

    @Test
    void exposesPreviewResetRollbackAndDiscardEndpoints() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        ObjectNode definition = mapper.createObjectNode().put("schemaVersion", 1).put("kind", "FIXED_LINES");
        ReportFormulaResponses.Draft draft = new ReportFormulaResponses.Draft(2, 1, definition,
                null, false, java.time.OffsetDateTime.now());
        ReportFormulaResponses.PreviewResult preview = new ReportFormulaResponses.PreviewResult(
                2L, 2L, false, List.of(), List.of(), definition);
        when(service.updateDraft(userId, ledgerId, "BALANCE_SHEET",
                new ReportFormulaRequests.DraftUpdate(1L, List.of(
                        new ReportFormulaRequests.LineEdit("bs-1", "货币资金", definition)), null)))
                .thenReturn(draft);
        when(service.preview(userId, ledgerId, "BALANCE_SHEET",
                new ReportFormulaRequests.PreviewRequest(2L, "2026-01", null, null))).thenReturn(preview);
        when(service.resetDraft(userId, ledgerId, "BALANCE_SHEET",
                new ReportFormulaRequests.DraftReset(2L))).thenReturn(draft);
        when(service.rollback(userId, ledgerId, "BALANCE_SHEET", 1,
                new ReportFormulaRequests.RollbackRequest(2)))
                .thenReturn(new ReportFormulaResponses.RollbackResult("BALANCE_SHEET", 3));

        mockMvc.perform(put("/v1/ledgers/{ledgerId}/report-formulas/BALANCE_SHEET/draft", ledgerId)
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedDraftVersion\":1,\"lines\":[{\"lineKey\":\"bs-1\",\"name\":\"货币资金\",\"expression\":{\"type\":\"ACCOUNT_AMOUNT\"}}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(post("/v1/ledgers/{ledgerId}/report-formulas/BALANCE_SHEET/draft:preview", ledgerId)
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedDraftVersion\":2,\"periodCode\":\"2026-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previewedDraftVersion").value(2));
        mockMvc.perform(post("/v1/ledgers/{ledgerId}/report-formulas/BALANCE_SHEET/draft:reset", ledgerId)
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedDraftVersion\":2}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/v1/ledgers/{ledgerId}/report-formulas/BALANCE_SHEET/versions/1:rollback", ledgerId)
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedPublishedVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(3));
        mockMvc.perform(delete("/v1/ledgers/{ledgerId}/report-formulas/BALANCE_SHEET/draft", ledgerId)
                        .header("X-User-Id", userId))
                .andExpect(status().isOk());
    }
}
