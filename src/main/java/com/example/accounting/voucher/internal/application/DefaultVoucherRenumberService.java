package com.example.accounting.voucher.internal.application;

import com.example.accounting.ledger.LedgerAccessService;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.shared.web.ApiProblemException;
import com.example.accounting.voucher.VoucherRenumbering;
import com.example.accounting.voucher.VoucherRenumberService;
import com.example.accounting.voucher.internal.port.VoucherRenumberRepository;
import com.example.accounting.voucher.internal.port.VoucherRenumberRepository.Row;
import com.example.accounting.voucher.internal.port.VoucherRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultVoucherRenumberService implements VoucherRenumberService {
    private final VoucherRenumberRepository repository;
    private final VoucherRepository vouchers;
    private final LedgerAccessService access;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public DefaultVoucherRenumberService(VoucherRenumberRepository repository, VoucherRepository vouchers,
                                         LedgerAccessService access) {
        this.repository = repository;
        this.vouchers = vouchers;
        this.access = access;
    }

    @Override
    @Transactional(readOnly = true)
    public VoucherRenumbering.Preview preview(UUID actorId, UUID ledgerId, VoucherRenumbering.Request request) {
        authorize(actorId, ledgerId);
        requireOpen(ledgerId, request.periodId(), false);
        List<Row> rows = repository.rows(ledgerId, request.periodId(), false);
        var plan = VoucherRenumberPlanner.plan(request, rows);
        return new VoucherRenumbering.Preview(request.periodId(), rows.size(), plan.changes().size(),
                plan.first(), plan.last(), fingerprint(ledgerId, request, rows), plan.changes());
    }

    @Override
    @Transactional
    public VoucherRenumbering.Result execute(UUID actorId, UUID ledgerId, VoucherRenumbering.ExecuteRequest request,
                                             String idempotencyKey) {
        authorize(actorId, ledgerId);
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.trim().length() > 128) {
            throw problem(400, "IDEMPOTENCY_KEY_INVALID", "执行整理需要有效的幂等键");
        }
        String key = idempotencyKey.trim();
        String requestHash = hash(serialize(request));
        repository.lockOperation(actorId, ledgerId, key);
        var previous = repository.operation(actorId, ledgerId, key);
        if (previous.isPresent()) {
            if (!previous.get().requestHash().equals(requestHash)) {
                throw problem(409, "IDEMPOTENCY_KEY_REUSED", "该请求标识已用于不同的整理参数");
            }
            return readResult(previous.get().resultJson());
        }
        // Ledger first coordinates with balance rebuilds, then period -> number as in voucher creation.
        repository.lockLedger(ledgerId);
        requireOpen(ledgerId, request.periodId(), true);
        repository.lockNumbers(ledgerId, request.periodId());
        List<Row> rows = repository.rows(ledgerId, request.periodId(), true);
        if (!fingerprint(ledgerId, request.parameters(), rows).equals(request.previewFingerprint())) {
            throw problem(409, "VOUCHER_RENUMBER_PREVIEW_STALE", "凭证已发生变化，请重新预览");
        }
        var plan = VoucherRenumberPlanner.plan(request.parameters(), rows);
        UUID operationId = UUID.randomUUID();
        if (!plan.changes().isEmpty()) {
            Map<UUID, Row> byId = rows.stream().collect(Collectors.toMap(Row::id, Function.identity()));
            var lines = vouchers.linesByVoucher(ledgerId, plan.changes().stream()
                    .map(VoucherRenumbering.Change::voucherId).toList());
            repository.renumber(ledgerId, actorId, plan.changes(), plan.temporaryStart());
            for (var change : plan.changes()) {
                Row row = byId.get(change.voucherId());
                var voucherLines = lines.getOrDefault(row.id(), List.of());
                vouchers.recordRevision(ledgerId, row.id(), row.revision() + 1, "RENUMBER", actorId,
                        "voucher-renumber:" + operationId,
                        serialize(row.snapshot(ledgerId, request.periodId(), row.number(), row.version(), voucherLines)),
                        serialize(row.snapshot(ledgerId, request.periodId(), change.newNumber(), row.version() + 1, voucherLines)));
            }
        }
        var result = new VoucherRenumbering.Result(operationId, request.periodId(), plan.changes().size(),
                plan.first(), plan.last());
        repository.saveOperation(actorId, ledgerId, key, requestHash, request, result, serialize(result));
        return result;
    }

    private void authorize(UUID actorId, UUID ledgerId) {
        if (!Set.of(LedgerRole.OWNER, LedgerRole.EDITOR).contains(access.requireMembership(actorId, ledgerId))) {
            throw problem(403, "LEDGER_ROLE_FORBIDDEN", "只有账套所有者或编辑者可以整理凭证号");
        }
    }

    private void requireOpen(UUID ledgerId, UUID periodId, boolean lock) {
        String status = repository.periodStatus(ledgerId, periodId, lock).orElseThrow(() ->
                problem(404, "ACCOUNTING_PERIOD_NOT_FOUND", "所选期间不存在或不属于当前账套"));
        if (!"OPEN".equals(status)) {
            throw problem(409, "ACCOUNTING_PERIOD_CLOSED", "已结账期间不能整理凭证号，请先反结账");
        }
    }

    private String fingerprint(UUID ledgerId, VoucherRenumbering.Request request, List<Row> rows) {
        return hash(serialize(List.of(ledgerId, request,
                rows.stream().sorted(Comparator.comparing(Row::id)).toList())));
    }

    private String serialize(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Cannot serialize voucher renumbering", exception); }
    }

    private VoucherRenumbering.Result readResult(String value) {
        try { return json.readValue(value, VoucherRenumbering.Result.class); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Cannot read voucher renumbering result", exception); }
    }

    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private ApiProblemException problem(int status, String code, String detail) {
        return new ApiProblemException(status, code, "无法整理凭证号", detail, false);
    }
}
