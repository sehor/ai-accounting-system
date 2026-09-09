package com.example.accounting.voucher.internal.persistence;

import com.example.accounting.voucher.VoucherRenumbering;
import com.example.accounting.voucher.internal.port.VoucherRenumberRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcVoucherRenumberRepository implements VoucherRenumberRepository {
    private final JdbcTemplate jdbc;

    public JdbcVoucherRenumberRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<String> periodStatus(UUID ledgerId, UUID periodId, boolean lock) {
        return Optional.ofNullable(jdbc.query("select status from accounting_period where ledger_id = ? and id = ?"
                        + (lock ? " for update" : ""),
                rs -> rs.next() ? rs.getString(1) : null, ledgerId, periodId));
    }

    @Override
    public List<Row> rows(UUID ledgerId, UUID periodId, boolean lock) {
        return jdbc.query("""
                select id, voucher_date, voucher_type, voucher_number, summary, status, approval_required,
                       version, current_revision, source_type, source_id, created_at
                from voucher where ledger_id = ? and period_id = ? and deleted_at is null
                order by id
                """ + (lock ? " for update" : ""), (rs, index) -> new Row(
                rs.getObject("id", UUID.class), rs.getObject("voucher_date", LocalDate.class),
                rs.getString("voucher_type"), rs.getString("voucher_number"), rs.getString("summary"),
                rs.getString("status"), rs.getBoolean("approval_required"), rs.getLong("version"),
                rs.getInt("current_revision"), rs.getString("source_type"), rs.getObject("source_id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class)), ledgerId, periodId);
    }

    @Override
    public void lockLedger(UUID ledgerId) {
        // Checkpoint invalidation references ledger; acquire this before period locks,
        // matching balance rebuild workers without excluding ordinary ledger writes.
        jdbc.queryForObject("select id from ledger where id = ? for key share", UUID.class, ledgerId);
    }

    @Override
    public void lockNumbers(UUID ledgerId, UUID periodId) {
        lock(ledgerId + ":" + periodId + ":记");
    }

    @Override
    public void lockOperation(UUID actorId, UUID ledgerId, String key) {
        lock("voucher-renumber:" + ledgerId + ":" + actorId + ":" + key);
    }

    private void lock(String key) {
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtext(?))", Object.class, key);
    }

    @Override
    public Optional<Operation> operation(UUID actorId, UUID ledgerId, String key) {
        return Optional.ofNullable(jdbc.query("""
                select request_hash, result::text from voucher_renumber_operation
                where ledger_id = ? and actor_id = ? and idempotency_key = ?
                """, rs -> rs.next() ? new Operation(rs.getString(1), rs.getString(2)) : null,
                ledgerId, actorId, key));
    }

    @Override
    public void saveOperation(UUID actorId, UUID ledgerId, String key, String requestHash,
                              VoucherRenumbering.ExecuteRequest request, VoucherRenumbering.Result result,
                              String resultJson) {
        jdbc.update("""
                insert into voucher_renumber_operation
                    (id, ledger_id, period_id, actor_id, idempotency_key, request_hash, mode, start_number, result)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """, result.operationId(), ledgerId, request.periodId(), actorId, key, requestHash,
                request.mode().name(), request.startNumber(), resultJson);
    }

    @Override
    public void renumber(UUID ledgerId, UUID actorId, List<VoucherRenumbering.Change> changes, long temporaryStart) {
        List<Object[]> temporary = new ArrayList<>();
        List<Object[]> finalNumbers = new ArrayList<>();
        for (int index = 0; index < changes.size(); index++) {
            var change = changes.get(index);
            temporary.add(new Object[]{Long.toString(temporaryStart + index), ledgerId, change.voucherId()});
            finalNumbers.add(new Object[]{change.newNumber(), actorId, ledgerId, change.voucherId()});
        }
        // Both phases are in the service transaction. Only the final phase creates a business revision.
        jdbc.batchUpdate("update voucher set voucher_number = ? where ledger_id = ? and id = ?", temporary);
        jdbc.batchUpdate("""
                update voucher set voucher_number = ?, updated_by = ?, updated_at = now(),
                    version = version + 1, current_revision = current_revision + 1
                where ledger_id = ? and id = ?
                """, finalNumbers);
    }
}
