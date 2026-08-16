package com.example.accounting.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Request models for the report formula workspace, draft, preview and version APIs. */
public final class ReportFormulaRequests {

    private ReportFormulaRequests() {
    }

    @Schema(name = "ReportFormulaDraftUpdate", requiredProperties = {"expectedDraftVersion"})
    public record DraftUpdate(
            @NotNull Long expectedDraftVersion,
            List<LineEdit> lines,
            List<RuleEdit> rules) {
    }

    @Schema(name = "ReportFormulaLineEdit", requiredProperties = {"lineKey", "name", "expression"})
    public record LineEdit(
            @NotBlank String lineKey,
            @NotBlank @Size(max = 200) String name,
            @NotNull Object expression) {
    }

    @Schema(name = "ReportFormulaRuleEdit", requiredProperties = {"key", "side"})
    public record RuleEdit(
            @NotBlank String key,
            @NotBlank String side,
            List<String> categories,
            List<com.example.accounting.ledger.formula.ReportFormulaDefinition.AccountReference> accounts) {
    }

    @Schema(name = "ReportFormulaDraftReset", requiredProperties = {"expectedDraftVersion"})
    public record DraftReset(@NotNull Long expectedDraftVersion) {
    }

    @Schema(name = "ReportFormulaPreviewRequest", requiredProperties = {"expectedDraftVersion"})
    public record PreviewRequest(
            @NotNull Long expectedDraftVersion,
            @Schema(nullable = true) String periodCode,
            @Schema(nullable = true) String periodFrom,
            @Schema(nullable = true) String periodTo) {
    }

    @Schema(name = "ReportFormulaPublishRequest", requiredProperties = {"expectedPublishedVersion",
            "expectedDraftVersion"})
    public record PublishRequest(
            @NotNull Integer expectedPublishedVersion,
            @NotNull Long expectedDraftVersion,
            boolean acknowledgeWarnings) {
    }

    @Schema(name = "ReportFormulaRollbackRequest", requiredProperties = {"expectedPublishedVersion"})
    public record RollbackRequest(@NotNull Integer expectedPublishedVersion) {
    }
}
