package com.example.accounting.periodclosing;

import java.util.List;
import java.util.UUID;

public interface PeriodClosingService extends PeriodClosingStepResetCommand {
    PeriodClosingResponses.Status status(UUID actorId, UUID ledgerId, UUID periodId);

    PeriodClosingResponses.Step generate(UUID actorId, UUID ledgerId, UUID periodId,
                                         PeriodClosingStepType step);

    PeriodClosingResponses.Settings settings(UUID actorId, UUID ledgerId);

    PeriodClosingResponses.Settings updateSettings(UUID actorId, UUID ledgerId,
                                                   PeriodClosingRequests.SettingsPatch request);

    List<String> blockers(UUID actorId, UUID ledgerId, UUID periodId);
}
