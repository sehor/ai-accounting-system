package com.example.accounting.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Response models for the report formula workspace, draft, preview and version APIs. */
public final class ReportFormulaResponses {

    private ReportFormulaResponses() {
    }

    @Schema(name = "ReportFormulaWorkspace", requiredProperties = {"code", "name", "kind", "reportType",
            "templateCode", "publishedVersion", "publishedDefinition"})
    public record Workspace(
            String code,
            String name,
            String kind,
            String reportType,
            String templateCode,
            int publishedVersion,
            Object publishedDefinition,
            Draft draft) {
    }

    @Schema(name = "ReportFormulaDraft", requiredProperties = {"version", "basePublishedVersion",
            "definition"})
    public record Draft(
            long version,
            int basePublishedVersion,
            Object definition,
            Long lastPreviewedDraftVersion,
            boolean previewHasWarnings,
            OffsetDateTime updatedAt) {
    }

    @Schema(name = "ReportFormulaPreviewResult", requiredProperties = {"draftVersion",
            "previewedDraftVersion", "previewHasWarnings"})
    public record PreviewResult(
            long draftVersion,
            Long previewedDraftVersion,
            boolean previewHasWarnings,
            List<Issue> blockingIssues,
            List<Warning> warnings,
            Object statement) {
    }

    @Schema(name = "ReportFormulaIssue", requiredProperties = {"code", "path", "message"})
    public record Issue(String code, String path, String message) {
    }

    @Schema(name = "ReportFormulaWarning", requiredProperties = {"code", "name", "difference"})
    public record Warning(String code, String name, java.math.BigDecimal difference) {
    }

    @Schema(name = "ReportFormulaPublishResult", requiredProperties = {"formulaCode", "publishedVersion"})
    public record PublishResult(String formulaCode, int publishedVersion) {
    }

    @Schema(name = "ReportFormulaVersionPage", requiredProperties = {"page", "pageSize", "totalItems",
            "totalPages", "items"})
    public record VersionPage(int page, int pageSize, long totalItems, int totalPages,
                              List<VersionInfo> items) {
    }

    @Schema(name = "ReportFormulaVersionInfo", requiredProperties = {"version", "source", "definition"})
    public record VersionInfo(
            int version,
            String source,
            Integer rollbackOfVersion,
            UUID createdBy,
            OffsetDateTime createdAt,
            Object definition) {
    }

    @Schema(name = "ReportFormulaRollbackResult", requiredProperties = {"formulaCode", "publishedVersion"})
    public record RollbackResult(String formulaCode, int publishedVersion) {
    }
}
