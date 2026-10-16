package com.example.accounting.ledger;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builds display names from actual parent links, without changing stored account names. */
public final class AccountFullName {

    private AccountFullName() {
    }

    public static List<LedgerResponses.Account> populate(List<LedgerResponses.Account> accounts) {
        Map<UUID, LedgerResponses.Account> byId = new HashMap<>();
        accounts.forEach(account -> byId.put(account.id(), account));
        return accounts.stream().map(account -> account.withFullName(resolve(account, byId))).toList();
    }

    public static String resolve(LedgerResponses.Account account,
                                 Map<UUID, LedgerResponses.Account> byId) {
        var names = new ArrayDeque<String>();
        var visited = new HashSet<UUID>();
        var current = account;
        // Incomplete or malformed trees must not hang a display request or cross ledger boundaries.
        while (current != null && account.ledgerId().equals(current.ledgerId())
                && visited.add(current.id())) {
            names.addFirst(current.name());
            current = current.parentId() == null ? null : byId.get(current.parentId());
        }
        return String.join("-", names);
    }
}
