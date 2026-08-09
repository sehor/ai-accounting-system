package com.example.accounting.agent.internal.application;

import com.example.accounting.agent.AccountingExperienceService;
import com.example.accounting.agent.ExperienceRequests;
import com.example.accounting.agent.ExperienceResponses;
import com.example.accounting.agent.ExperienceScope;
import com.example.accounting.agent.internal.port.AccountingExperienceRepository;
import com.example.accounting.identity.IdentityService;
import com.example.accounting.identity.UserType;
import com.example.accounting.ledger.LedgerAccessService;
import com.example.accounting.shared.web.ApiProblemException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultAccountingExperienceService implements AccountingExperienceService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    private final AccountingExperienceRepository experiences;
    private final IdentityService identities;
    private final LedgerAccessService ledgerAccess;

    public DefaultAccountingExperienceService(AccountingExperienceRepository experiences,
                                              IdentityService identities, LedgerAccessService ledgerAccess) {
        this.experiences = experiences;
        this.identities = identities;
        this.ledgerAccess = ledgerAccess;
    }

    @Override
    @Transactional
    public ExperienceResponses.Experience create(UUID actorId, ExperienceRequests.Create request) {
        requireAgent(actorId);
        if (request == null) {
            throw problem(422, "EXPERIENCE_REQUEST_INVALID", "Invalid experience request",
                    "The experience payload is required", false);
        }
        ExperienceScope scope = requireScope(request.scope());
        UUID ledgerId = requireLedgerId(request.ledgerId());
        requireLedgerMember(actorId, ledgerId);
        String title = text(request.title(), 200, "EXPERIENCE_TITLE_INVALID", "Experience title");
        String content = text(request.content(), 10_000, "EXPERIENCE_CONTENT_INVALID", "Experience content");
        List<String> tags = tags(request.tags());
        return toResponse(experiences.create(scope, ledgerId, title, content, tags, actorId));
    }

    @Override
    @Transactional(readOnly = true)
    public ExperienceResponses.Page search(UUID actorId, ExperienceRequests.Search request) {
        requireAgent(actorId);
        ExperienceRequests.Search input = request == null
                ? new ExperienceRequests.Search(null, null, List.of(), 1, DEFAULT_PAGE_SIZE) : request;
        UUID ledgerId = requireLedgerId(input.ledgerId());
        requireLedgerMember(actorId, ledgerId);
        int page = positive(input.page(), 1, "EXPERIENCE_PAGE_INVALID");
        int pageSize = bounded(input.pageSize(), DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE, "EXPERIENCE_PAGE_SIZE_INVALID");
        String query = optionalText(input.query(), 200, "EXPERIENCE_QUERY_INVALID");
        List<String> tags = tags(input.tags());
        long offsetLong = (long) (page - 1) * pageSize;
        if (offsetLong > Integer.MAX_VALUE) {
            throw problem(422, "EXPERIENCE_PAGE_INVALID", "Invalid experience page",
                    "The requested page is too large", false);
        }
        int offset = (int) offsetLong;
        AccountingExperienceRepository.Page result = experiences.search(ledgerId, query, tags, pageSize, offset);
        int totalPages = result.totalItems() == 0 ? 0 : (int) ((result.totalItems() + pageSize - 1) / pageSize);
        return new ExperienceResponses.Page(result.items().stream().map(this::toResponse).toList(),
                page, pageSize, result.totalItems(), totalPages);
    }

    @Override
    @Transactional
    public ExperienceResponses.Experience update(UUID actorId, UUID experienceId, ExperienceRequests.Update request) {
        requireAgent(actorId);
        if (request == null) {
            throw problem(422, "EXPERIENCE_REQUEST_INVALID", "Invalid experience request",
                    "The experience update payload is required", false);
        }
        AccountingExperienceRepository.Record current = find(experienceId);
        requireLedgerMember(actorId, current.ledgerId());
        String title = text(request.title(), 200, "EXPERIENCE_TITLE_INVALID", "Experience title");
        String content = text(request.content(), 10_000, "EXPERIENCE_CONTENT_INVALID", "Experience content");
        List<String> tags = tags(request.tags());
        if (!experiences.update(experienceId, request.expectedVersion(), title, content, tags, actorId)) {
            throw versionConflict();
        }
        return toResponse(find(experienceId));
    }

    @Override
    @Transactional
    public ExperienceResponses.Experience archive(UUID actorId, UUID experienceId, long expectedVersion) {
        requireAgent(actorId);
        AccountingExperienceRepository.Record current = find(experienceId);
        requireLedgerMember(actorId, current.ledgerId());
        if (!experiences.archive(experienceId, expectedVersion, actorId)) {
            throw versionConflict();
        }
        return toResponse(find(experienceId));
    }

    private void requireAgent(UUID actorId) {
        if (actorId == null || !identities.findUser(actorId).map(user -> user.userType() == UserType.AGENT)
                .orElse(false)) {
            throw problem(403, "AGENT_IDENTITY_REQUIRED", "Agent identity required",
                    "Only an AGENT identity can access accounting experiences", false);
        }
    }

    private void requireLedgerMember(UUID actorId, UUID ledgerId) {
        ledgerAccess.requireMembership(actorId, requireLedgerId(ledgerId));
    }

    private ExperienceScope requireScope(ExperienceScope scope) {
        if (scope == null) {
            throw problem(422, "EXPERIENCE_SCOPE_INVALID", "Invalid experience scope",
                    "The experience scope is required", false);
        }
        return scope;
    }

    private UUID requireLedgerId(UUID ledgerId) {
        if (ledgerId == null) {
            throw problem(422, "EXPERIENCE_LEDGER_REQUIRED", "Ledger required",
                    "Accounting experience must belong to a ledger", false);
        }
        return ledgerId;
    }

    private AccountingExperienceRepository.Record find(UUID experienceId) {
        return experiences.find(experienceId).orElseThrow(() -> problem(404, "EXPERIENCE_NOT_FOUND",
                "Experience not found", "The experience is not available to this agent", false));
    }

    private String text(String value, int max, String code, String label) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > max) {
            throw problem(422, code, "Invalid " + label.toLowerCase(),
                    label + " must contain 1 to " + max + " characters", false);
        }
        return value.trim();
    }

    private String optionalText(String value, int max, String code) {
        if (value == null || value.isBlank()) return null;
        if (value.trim().length() > max) {
            throw problem(422, code, "Invalid experience query",
                    "The query must not exceed " + max + " characters", false);
        }
        return value.trim();
    }

    private List<String> tags(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.trim().isEmpty() || value.trim().length() > 64) {
                throw problem(422, "EXPERIENCE_TAGS_INVALID", "Invalid experience tags",
                        "Each experience tag must contain 1 to 64 characters", false);
            }
            normalized.add(value.trim().toLowerCase(java.util.Locale.ROOT));
        }
        if (normalized.size() > 20) {
            throw problem(422, "EXPERIENCE_TAGS_INVALID", "Invalid experience tags",
                    "An experience can contain at most 20 tags", false);
        }
        return new ArrayList<>(normalized);
    }

    private int positive(Integer value, int defaultValue, String code) {
        int result = value == null ? defaultValue : value;
        if (result < 1) throw problem(422, code, "Invalid experience page", "The page must be positive", false);
        return result;
    }

    private int bounded(Integer value, int defaultValue, int max, String code) {
        int result = value == null ? defaultValue : value;
        if (result < 1 || result > max) {
            throw problem(422, code, "Invalid experience page size",
                    "The page size must be between 1 and " + max, false);
        }
        return result;
    }

    private ExperienceResponses.Experience toResponse(AccountingExperienceRepository.Record record) {
        return new ExperienceResponses.Experience(record.id(), record.scope(), record.ledgerId(), record.title(),
                record.content(), record.tags(), record.status(), record.version(), record.createdBy(),
                record.updatedBy(), record.createdAt(), record.updatedAt());
    }

    private ApiProblemException versionConflict() {
        return problem(409, "EXPERIENCE_VERSION_CONFLICT", "Experience version conflict",
                "The experience changed since it was read", false);
    }

    private ApiProblemException problem(int status, String code, String title, String detail, boolean retryable) {
        return new ApiProblemException(status, code, title, detail, retryable);
    }
}
