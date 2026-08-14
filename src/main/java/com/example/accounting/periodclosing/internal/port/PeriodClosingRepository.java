package com.example.accounting.periodclosing.internal.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.example.accounting.periodclosing.PeriodClosingStepStatus;
import com.example.accounting.periodclosing.PeriodClosingStepType;

public interface PeriodClosingRepository {
    Optional<SettingRecord> setting(UUID ledgerId);
    void upsertSetting(UUID ledgerId, UUID profitAccountId, UUID retainedEarningsAccountId);
    Optional<StepRecord> step(UUID ledgerId, UUID periodId, PeriodClosingStepType type);
    List<StepRecord> steps(UUID ledgerId, UUID periodId);
    void createStep(UUID id, UUID ledgerId, UUID periodId, PeriodClosingStepType type,
                    PeriodClosingStepStatus status, BigDecimal amount, String fingerprint,
                    UUID voucherId, String blockerCode, String blockerDetail);
    void updateStep(UUID ledgerId, UUID periodId, PeriodClosingStepType type,
                    PeriodClosingStepStatus status, BigDecimal amount, String fingerprint,
                    UUID voucherId, String blockerCode, String blockerDetail);
    Optional<PeriodRecord> period(UUID ledgerId, UUID periodId);
    List<PeriodRecord> periods(UUID ledgerId);
    List<AccountAmount> amounts(UUID ledgerId, UUID periodId, String category);
    List<AccountAmount> netAmounts(UUID ledgerId, UUID periodId, String category);
    Optional<AccountAmount> amountThrough(UUID ledgerId, String periodCode, UUID accountId, UUID excludedVoucherId);
    Optional<AccountInfo> account(UUID ledgerId, UUID accountId);
    Optional<AccountInfo> accountByCode(UUID ledgerId, String code);
    boolean hasRequiredDimensions(UUID ledgerId, UUID accountId);

    record SettingRecord(UUID ledgerId, UUID profitAccountId, UUID retainedEarningsAccountId,
                         long version) { }
    record StepRecord(UUID id, UUID ledgerId, UUID periodId, PeriodClosingStepType type,
                      PeriodClosingStepStatus status, BigDecimal amount, String fingerprint,
                      UUID voucherId, String blockerCode, String blockerDetail,
                      OffsetDateTime updatedAt) { }
    record PeriodRecord(UUID id, UUID ledgerId, String code, LocalDate startDate,
                        LocalDate endDate, String status) { }
    record AccountInfo(UUID id, UUID ledgerId, String code, String name, String category,
                       String status, UUID parentId, boolean leaf) { }
    record AccountAmount(UUID accountId, String code, String name, String category,
                        BigDecimal debit, BigDecimal credit) { }
}
