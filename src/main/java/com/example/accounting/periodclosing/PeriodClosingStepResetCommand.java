package com.example.accounting.periodclosing;

import java.util.UUID;

/** Capability boundary for resetting a generated period-closing step and its source voucher. */
public interface PeriodClosingStepResetCommand {

    default PeriodClosingResponses.Step resetStep(
            UUID actorId, UUID ledgerId, UUID periodId, PeriodClosingStepType step) {
        return resetStep(actorId, ledgerId, periodId, step, "Period closing step reset");
    }

    PeriodClosingResponses.Step resetStep(
            UUID actorId, UUID ledgerId, UUID periodId, PeriodClosingStepType step, String reason);
}
