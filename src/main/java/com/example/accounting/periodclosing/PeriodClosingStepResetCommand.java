package com.example.accounting.periodclosing;

import java.util.UUID;

/** Capability boundary for resetting a generated period-closing step and its source voucher. */
public interface PeriodClosingStepResetCommand {

    PeriodClosingResponses.Step resetStep(
            UUID actorId, UUID ledgerId, UUID periodId, PeriodClosingStepType step);
}
