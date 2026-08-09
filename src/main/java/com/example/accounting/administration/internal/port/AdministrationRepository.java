package com.example.accounting.administration.internal.port;

import com.example.accounting.administration.AdminResponses;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdministrationRepository {

    List<AdminResponses.User> listUsers();

    Optional<AdminResponses.User> findUser(UUID userId);

    void deleteUser(UUID userId);

    void restoreUser(UUID userId);

    List<AdminResponses.Ledger> listLedgers();

    Optional<AdminResponses.Ledger> findLedger(UUID ledgerId);

    void deleteLedger(UUID ledgerId, UUID actorId);

    void restoreLedger(UUID ledgerId, UUID actorId);
}
