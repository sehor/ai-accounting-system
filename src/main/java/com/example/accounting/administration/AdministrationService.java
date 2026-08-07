package com.example.accounting.administration;

import com.example.accounting.administration.internal.port.AdministrationRepository;
import com.example.accounting.shared.web.ApiProblemException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdministrationService {

    private final AdministrationRepository repository;
    private final PlatformAdminPolicy policy;

    public AdministrationService(AdministrationRepository repository, PlatformAdminPolicy policy) {
        this.repository = repository;
        this.policy = policy;
    }

    @Transactional(readOnly = true)
    public List<AdminResponses.User> listUsers(UUID actorId) {
        policy.requirePlatformAdmin(actorId);
        return repository.listUsers().stream()
                .map(user -> user.withProtectedUser(policy.isProtectedUser(user.id())))
                .toList();
    }

    @Transactional
    public void deleteUser(UUID actorId, UUID userId) {
        policy.requirePlatformAdmin(actorId);
        AdminResponses.User user = requireUser(userId);
        if (policy.isProtectedUser(userId)) {
            throw problem(409, "PROTECTED_USER", "Protected user",
                    "The platform administrator and system agent cannot be deleted");
        }
        if (!user.deleted()) {
            repository.deleteUser(userId);
        }
    }

    @Transactional
    public AdminResponses.User restoreUser(UUID actorId, UUID userId) {
        policy.requirePlatformAdmin(actorId);
        requireUser(userId);
        repository.restoreUser(userId);
        AdminResponses.User restored = requireUser(userId);
        return restored.withProtectedUser(policy.isProtectedUser(userId));
    }

    @Transactional(readOnly = true)
    public List<AdminResponses.Ledger> listLedgers(UUID actorId) {
        policy.requirePlatformAdmin(actorId);
        return repository.listLedgers();
    }

    @Transactional
    public void deleteLedger(UUID actorId, UUID ledgerId) {
        policy.requirePlatformAdmin(actorId);
        AdminResponses.Ledger ledger = requireLedger(ledgerId);
        if (!ledger.deleted()) {
            repository.deleteLedger(ledgerId, actorId);
        }
    }

    @Transactional
    public AdminResponses.Ledger restoreLedger(UUID actorId, UUID ledgerId) {
        policy.requirePlatformAdmin(actorId);
        requireLedger(ledgerId);
        repository.restoreLedger(ledgerId, actorId);
        return requireLedger(ledgerId);
    }

    private AdminResponses.User requireUser(UUID userId) {
        return repository.findUser(userId).orElseThrow(() ->
                problem(404, "USER_NOT_FOUND", "User not found", "The user does not exist"));
    }

    private AdminResponses.Ledger requireLedger(UUID ledgerId) {
        return repository.findLedger(ledgerId).orElseThrow(() ->
                problem(404, "LEDGER_NOT_FOUND", "Ledger not found", "The ledger does not exist"));
    }

    private ApiProblemException problem(int status, String code, String title, String detail) {
        return new ApiProblemException(status, code, title, detail, false);
    }
}
