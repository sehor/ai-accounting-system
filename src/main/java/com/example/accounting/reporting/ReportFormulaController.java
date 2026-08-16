package com.example.accounting.reporting;

import com.example.accounting.identity.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Report formula workspace, draft, preview, publish, history and rollback endpoints. */
@RestController
@RequestMapping("/v1/ledgers/{ledgerId}/report-formulas")
public class ReportFormulaController {

    private final CurrentUserResolver currentUserResolver;
    private final ReportFormulaService service;

    public ReportFormulaController(CurrentUserResolver currentUserResolver, ReportFormulaService service) {
        this.currentUserResolver = currentUserResolver;
        this.service = service;
    }

    @GetMapping("/{code}")
    public ReportFormulaResponses.Workspace workspace(HttpServletRequest request,
                                                      @PathVariable UUID ledgerId,
                                                      @PathVariable String code) {
        return service.workspace(user(request), ledgerId, code);
    }

    @PostMapping("/{code}/draft")
    public ReportFormulaResponses.Draft createDraft(HttpServletRequest request,
                                                    @PathVariable UUID ledgerId,
                                                    @PathVariable String code) {
        return service.createDraft(user(request), ledgerId, code);
    }

    @PutMapping("/{code}/draft")
    public ReportFormulaResponses.Draft updateDraft(HttpServletRequest request,
                                                    @PathVariable UUID ledgerId,
                                                    @PathVariable String code,
                                                    @Valid @RequestBody ReportFormulaRequests.DraftUpdate body) {
        return service.updateDraft(user(request), ledgerId, code, body);
    }

    @DeleteMapping("/{code}/draft")
    public void deleteDraft(HttpServletRequest request, @PathVariable UUID ledgerId,
                            @PathVariable String code) {
        service.deleteDraft(user(request), ledgerId, code);
    }

    @PostMapping("/{code}/draft:reset")
    public ReportFormulaResponses.Draft resetDraft(HttpServletRequest request,
                                                   @PathVariable UUID ledgerId,
                                                   @PathVariable String code,
                                                   @Valid @RequestBody ReportFormulaRequests.DraftReset body) {
        return service.resetDraft(user(request), ledgerId, code, body);
    }

    @PostMapping("/{code}/draft:preview")
    public ReportFormulaResponses.PreviewResult preview(HttpServletRequest request,
                                                        @PathVariable UUID ledgerId,
                                                        @PathVariable String code,
                                                        @Valid @RequestBody ReportFormulaRequests.PreviewRequest body) {
        return service.preview(user(request), ledgerId, code, body);
    }

    @PostMapping("/{code}:publish")
    public ReportFormulaResponses.PublishResult publish(HttpServletRequest request,
                                                        @PathVariable UUID ledgerId,
                                                        @PathVariable String code,
                                                        @Valid @RequestBody ReportFormulaRequests.PublishRequest body) {
        return service.publish(user(request), ledgerId, code, body);
    }

    @GetMapping("/{code}/versions")
    public ReportFormulaResponses.VersionPage versions(HttpServletRequest request,
                                                       @PathVariable UUID ledgerId,
                                                       @PathVariable String code,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "20") int pageSize) {
        return service.versions(user(request), ledgerId, code, page, pageSize);
    }

    @GetMapping("/{code}/versions/{version}")
    public ReportFormulaResponses.VersionInfo version(HttpServletRequest request,
                                                      @PathVariable UUID ledgerId,
                                                      @PathVariable String code,
                                                      @PathVariable int version) {
        return service.version(user(request), ledgerId, code, version);
    }

    @PostMapping("/{code}/versions/{version}:rollback")
    public ReportFormulaResponses.RollbackResult rollback(HttpServletRequest request,
                                                          @PathVariable UUID ledgerId,
                                                          @PathVariable String code,
                                                          @PathVariable int version,
                                                          @Valid @RequestBody ReportFormulaRequests.RollbackRequest body) {
        return service.rollback(user(request), ledgerId, code, version, body);
    }

    private UUID user(HttpServletRequest request) {
        return currentUserResolver.resolve(request);
    }
}
