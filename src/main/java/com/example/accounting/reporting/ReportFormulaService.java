package com.example.accounting.reporting;

import java.util.UUID;

/** Report formula workspace, draft, preview, publish, history and rollback API. */
public interface ReportFormulaService {

    ReportFormulaResponses.Workspace workspace(UUID actorId, UUID ledgerId, String code);

    ReportFormulaResponses.Draft createDraft(UUID actorId, UUID ledgerId, String code);

    ReportFormulaResponses.Draft updateDraft(UUID actorId, UUID ledgerId, String code,
                                             ReportFormulaRequests.DraftUpdate request);

    void deleteDraft(UUID actorId, UUID ledgerId, String code);

    ReportFormulaResponses.Draft resetDraft(UUID actorId, UUID ledgerId, String code,
                                            ReportFormulaRequests.DraftReset request);

    ReportFormulaResponses.PreviewResult preview(UUID actorId, UUID ledgerId, String code,
                                                 ReportFormulaRequests.PreviewRequest request);

    ReportFormulaResponses.PublishResult publish(UUID actorId, UUID ledgerId, String code,
                                                 ReportFormulaRequests.PublishRequest request);

    ReportFormulaResponses.VersionPage versions(UUID actorId, UUID ledgerId, String code,
                                                int page, int pageSize);

    ReportFormulaResponses.VersionInfo version(UUID actorId, UUID ledgerId, String code, int version);

    ReportFormulaResponses.RollbackResult rollback(UUID actorId, UUID ledgerId, String code, int version,
                                                   ReportFormulaRequests.RollbackRequest request);
}
