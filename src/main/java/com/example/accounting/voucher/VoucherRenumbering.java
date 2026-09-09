package com.example.accounting.voucher;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class VoucherRenumbering {
    private VoucherRenumbering() { }

    public enum Mode { NUMBER_ORDER, DATE_ORDER }

    @Schema(name = "VoucherRenumberRequest")
    public record Request(@NotNull UUID periodId, @NotNull Mode mode,
                          @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String startNumber) { }

    @Schema(name = "VoucherRenumberExecuteRequest")
    public record ExecuteRequest(@NotNull UUID periodId, @NotNull Mode mode,
                                 @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String startNumber,
                                 @NotBlank @Size(max = 64) String previewFingerprint) {
        public Request parameters() { return new Request(periodId, mode, startNumber); }
    }

    @Schema(name = "VoucherRenumberChange", requiredProperties = {
            "voucherId", "voucherDate", "summary", "oldNumber", "newNumber"})
    public record Change(UUID voucherId, LocalDate voucherDate, @Schema(nullable = true) String summary,
                         String oldNumber, String newNumber) { }

    @Schema(name = "VoucherRenumberPreview", requiredProperties = {
            "periodId", "totalCount", "changedCount", "firstNumber", "lastNumber", "previewFingerprint", "changes"})
    public record Preview(UUID periodId, int totalCount, int changedCount,
                          @Schema(nullable = true) String firstNumber, @Schema(nullable = true) String lastNumber,
                          String previewFingerprint, List<Change> changes) { }

    @Schema(name = "VoucherRenumberResult", requiredProperties = {
            "operationId", "periodId", "changedCount", "firstNumber", "lastNumber"})
    public record Result(UUID operationId, UUID periodId, int changedCount,
                         @Schema(nullable = true) String firstNumber, @Schema(nullable = true) String lastNumber) { }
}
