package com.example.accounting.reporting;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Read-only voucher contract derived from the ledger's published cash-flow formula. */
public interface CashFlowClassificationReader {

    Contract contract(UUID ledgerId);

    record Contract(
            boolean required,
            Set<UUID> cashAccountLeafIds,
            Set<String> reportableItemCodes,
            Map<UUID, CashFlowItemState> itemsById) {

        public Contract {
            cashAccountLeafIds = Set.copyOf(cashAccountLeafIds);
            reportableItemCodes = Set.copyOf(reportableItemCodes);
            itemsById = Map.copyOf(itemsById);
        }

        public static Contract none() {
            return new Contract(false, Set.of(), Set.of(), Map.of());
        }
    }

    record CashFlowItemState(String code, String status) {

        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }
}
