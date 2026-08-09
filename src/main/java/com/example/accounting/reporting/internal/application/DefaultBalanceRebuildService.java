package com.example.accounting.reporting.internal.application;

import com.example.accounting.ledger.LedgerAccessService;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.reporting.BalanceRebuildRequests;
import com.example.accounting.reporting.BalanceRebuildResponses;
import com.example.accounting.reporting.BalanceRebuildService;
import com.example.accounting.reporting.internal.port.BalanceRebuildRepository;
import com.example.accounting.shared.web.ApiProblemException;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultBalanceRebuildService implements BalanceRebuildService {

    private final LedgerAccessService ledgerAccess;
    private final BalanceRebuildRepository jobs;

    public DefaultBalanceRebuildService(LedgerAccessService ledgerAccess, BalanceRebuildRepository jobs) {
        this.ledgerAccess = ledgerAccess;
        this.jobs = jobs;
    }

    @Override
    @Transactional
    public BalanceRebuildResponses.Job request(UUID actorId, UUID ledgerId, BalanceRebuildRequests.Create request) {
        requireOwner(actorId, ledgerId);
        String from = normalize(request.periodFrom());
        String to = normalize(request.periodTo());
        if ((from == null) != (to == null) || (from != null && from.compareTo(to) > 0)) {
            throw problem(422, "BALANCE_REBUILD_RANGE_INVALID", "Invalid rebuild range",
                    "periodFrom and periodTo must both be supplied and periodFrom must not be after periodTo");
        }
        if (jobs.hasActiveJob(ledgerId, from, to)) {
            throw problem(409, "BALANCE_REBUILD_ALREADY_RUNNING", "Balance rebuild already running",
                    "An overlapping balance rebuild is already queued or running");
        }
        UUID jobId = UUID.randomUUID();
        jobs.createJob(jobId, ledgerId, from, to, request.reason().trim(), actorId);
        return jobs.findJob(ledgerId, jobId).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public BalanceRebuildResponses.Job find(UUID actorId, UUID ledgerId, UUID jobId) {
        requireOwner(actorId, ledgerId);
        return jobs.findJob(ledgerId, jobId).orElseThrow(() ->
                problem(404, "BALANCE_REBUILD_NOT_FOUND", "Balance rebuild not found",
                        "The requested rebuild job is not available to this ledger"));
    }

    private void requireOwner(UUID actorId, UUID ledgerId) {
        if (ledgerAccess.requireMembership(actorId, ledgerId) != LedgerRole.OWNER) {
            throw problem(403, "INSUFFICIENT_LEDGER_ROLE", "Insufficient ledger role",
                    "Only an OWNER can manage balance rebuilds");
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ApiProblemException problem(int status, String code, String title, String detail) {
        return new ApiProblemException(status, code, title, detail, false);
    }
}
