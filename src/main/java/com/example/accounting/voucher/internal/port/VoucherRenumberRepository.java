package com.example.accounting.voucher.internal.port;

import com.example.accounting.voucher.VoucherRenumbering;
import com.example.accounting.voucher.VoucherResponses;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VoucherRenumberRepository {
    Optional<String> periodStatus(UUID ledgerId, UUID periodId, boolean lock);
    List<Row> rows(UUID ledgerId, UUID periodId, boolean lock);
    void lockLedger(UUID ledgerId);
    void lockNumbers(UUID ledgerId, UUID periodId);
    void lockOperation(UUID actorId, UUID ledgerId, String key);
    Optional<Operation> operation(UUID actorId, UUID ledgerId, String key);
    void saveOperation(UUID actorId, UUID ledgerId, String key, String requestHash,
                       VoucherRenumbering.ExecuteRequest request, VoucherRenumbering.Result result, String resultJson);
    void renumber(UUID ledgerId, UUID actorId, List<VoucherRenumbering.Change> changes, long temporaryStart);

    record Operation(String requestHash, String resultJson) { }
    record Row(UUID id, LocalDate date, String type, String number, String summary, String status,
               boolean approvalRequired, long version, int revision, String sourceType, UUID sourceId,
               OffsetDateTime createdAt) {
        public VoucherResponses.Voucher snapshot(UUID ledgerId, UUID periodId, String snapshotNumber,
                                                  long snapshotVersion, List<VoucherResponses.Line> lines) {
            return new VoucherResponses.Voucher(id, ledgerId, periodId, date, type, snapshotNumber, summary,
                    status, approvalRequired, snapshotVersion, lines, sourceType, sourceId);
        }
    }
}
