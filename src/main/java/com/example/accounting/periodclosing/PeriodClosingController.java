package com.example.accounting.periodclosing;

import com.example.accounting.identity.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/ledgers/{ledgerId}")
public class PeriodClosingController {
    private final CurrentUserResolver currentUserResolver;
    private final PeriodClosingService service;

    public PeriodClosingController(CurrentUserResolver currentUserResolver, PeriodClosingService service) {
        this.currentUserResolver = currentUserResolver;
        this.service = service;
    }

    @GetMapping("/period-closings/{periodId}")
    public PeriodClosingResponses.Status status(HttpServletRequest request,
                                                @PathVariable UUID ledgerId,
                                                @PathVariable UUID periodId) {
        return service.status(user(request), ledgerId, periodId);
    }

    @PostMapping("/period-closings/{periodId}/steps/{step}:generate")
    public PeriodClosingResponses.Step generate(HttpServletRequest request,
                                                @PathVariable UUID ledgerId,
                                                @PathVariable UUID periodId,
                                                @PathVariable PeriodClosingStepType step) {
        return service.generate(user(request), ledgerId, periodId, step);
    }

    @PostMapping("/period-closings/{periodId}/steps/{step}:reset")
    public PeriodClosingResponses.Step reset(HttpServletRequest request,
                                             @PathVariable UUID ledgerId,
                                             @PathVariable UUID periodId,
                                             @PathVariable PeriodClosingStepType step,
                                             @Valid @RequestBody PeriodClosingRequests.Reset body) {
        return service.resetStep(user(request), ledgerId, periodId, step, body.reason());
    }

    @GetMapping("/period-closing-settings")
    public PeriodClosingResponses.Settings settings(HttpServletRequest request,
                                                    @PathVariable UUID ledgerId) {
        return service.settings(user(request), ledgerId);
    }

    @PatchMapping("/period-closing-settings")
    public PeriodClosingResponses.Settings updateSettings(HttpServletRequest request,
                                                         @PathVariable UUID ledgerId,
                                                         @Valid @RequestBody PeriodClosingRequests.SettingsPatch body) {
        return service.updateSettings(user(request), ledgerId, body);
    }

    private UUID user(HttpServletRequest request) {
        return currentUserResolver.resolve(request);
    }
}
