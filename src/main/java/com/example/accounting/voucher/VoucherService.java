package com.example.accounting.voucher;

import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.shared.web.ApiProblemException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VoucherService {

    private final JdbcTemplate jdbcTemplate;

    public VoucherService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public VoucherResponses.Voucher create(UUID actorId, UUID ledgerId, VoucherRequests.Create request) {
        return create(actorId, ledgerId, request, null);
    }

    @Transactional
    public VoucherResponses.Voucher create(UUID actorId, UUID ledgerId, VoucherRequests.Create request,
                                           String idempotencyKey) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR));
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        UUID voucherId = UUID.randomUUID();
        if (key != null) {
            if (key.length() > 128) {
                throw problem(400, "IDEMPOTENCY_KEY_INVALID", "Invalid idempotency key", "The idempotency key is too long");
            }
            String hash = requestHash(request);
            int inserted = jdbcTemplate.update("""
                    insert into voucher_idempotency (ledger_id, actor_id, idempotency_key, request_hash, voucher_id)
                    values (?, ?, ?, ?, ?) on conflict (ledger_id, actor_id, idempotency_key) do nothing
                    """, ledgerId, actorId, key, hash, voucherId);
            if (inserted == 0) {
                Idempotency existing = jdbcTemplate.queryForObject("""
                        select request_hash, voucher_id from voucher_idempotency
                        where ledger_id = ? and actor_id = ? and idempotency_key = ?
                        """, (rs, rowNum) -> new Idempotency(rs.getString("request_hash"),
                        rs.getObject("voucher_id", UUID.class)), ledgerId, actorId, key);
                if (!hash.equals(existing.requestHash())) {
                    throw problem(409, "IDEMPOTENCY_KEY_REUSED", "Idempotency key reused",
                            "The idempotency key was used with a different request");
                }
                return find(actorId, ledgerId, existing.voucherId());
            }
        }
        LedgerContext context = ledgerContext(ledgerId, request.periodId(), request.voucherDate());
        jdbcTemplate.update("""
                insert into voucher (id, ledger_id, period_id, voucher_date, voucher_type, voucher_number,
                    summary, approval_required, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, voucherId, ledgerId, request.periodId(), request.voucherDate(), request.voucherType().trim(),
                request.voucherNumber().trim(), request.summary(), context.approvalRequired(), actorId, actorId);
        insertLines(ledgerId, voucherId, context, request.lines());
        return find(actorId, ledgerId, voucherId);
    }

    @Transactional
    public VoucherResponses.Voucher update(UUID actorId, UUID ledgerId, UUID voucherId,
                                           VoucherRequests.Update request) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR));
        VoucherState state = stateWithVersion(ledgerId, voucherId);
        if (!"DRAFT".equals(state.status())) {
            throw problem(409, "VOUCHER_STATE_INVALID", "Invalid voucher state",
                    "Only draft vouchers can be updated");
        }
        LedgerContext context = ledgerContext(ledgerId, request.periodId(), request.voucherDate());
        int updated = jdbcTemplate.update("""
                update voucher set period_id = ?, voucher_date = ?, voucher_type = ?, voucher_number = ?,
                    summary = ?, approval_required = ?, current_revision = current_revision + 1,
                    version = version + 1, updated_at = now(), updated_by = ?
                where ledger_id = ? and id = ? and status = 'DRAFT' and version = ?
                """, request.periodId(), request.voucherDate(), request.voucherType().trim(),
                request.voucherNumber().trim(), request.summary(), context.approvalRequired(), actorId,
                ledgerId, voucherId, request.expectedVersion());
        if (updated == 0) {
            throw problem(409, "RESOURCE_VERSION_CONFLICT", "Resource version conflict",
                    "The voucher was changed by another request");
        }
        jdbcTemplate.update("delete from voucher_line where ledger_id = ? and voucher_id = ?", ledgerId, voucherId);
        insertLines(ledgerId, voucherId, context, request.lines());
        audit(ledgerId, voucherId, "UPDATE", actorId, null, "DRAFT", "DRAFT");
        return find(actorId, ledgerId, voucherId);
    }

    private void insertLines(UUID ledgerId, UUID voucherId, LedgerContext context, List<VoucherRequests.Line> lines) {
        int lineNo = 1;
        for (VoucherRequests.Line line : lines) {
            BigDecimal original = amount(line.originalAmount());
            BigDecimal rate = rate(line.exchangeRate());
            if (original.signum() <= 0 || rate.signum() <= 0) {
                throw problem(422, "INVALID_VOUCHER_AMOUNT", "Invalid voucher amount",
                        "Original amount and exchange rate must be positive");
            }
            if (line.currency().equals(context.baseCurrency()) && rate.compareTo(BigDecimal.ONE) != 0) {
                throw problem(422, "INVALID_BASE_CURRENCY_RATE", "Invalid base currency rate",
                        "The base currency exchange rate must be 1");
            }
            ensureAccount(ledgerId, line.accountId());
            jdbcTemplate.update("""
                    insert into voucher_line (id, ledger_id, voucher_id, line_no, account_id, side, currency,
                        original_amount, exchange_rate, base_amount, summary)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), ledgerId, voucherId, lineNo++, line.accountId(), line.side(),
                    line.currency(), original, rate, original.multiply(rate).setScale(2, RoundingMode.HALF_UP),
                    line.summary());
        }
    }

    private String requestHash(VoucherRequests.Create request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(request.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw problem(500, "IDEMPOTENCY_HASH_FAILED", "Idempotency hash failed", "The request could not be hashed");
        }
    }

    @Transactional(readOnly = true)
    public List<VoucherResponses.Voucher> list(UUID actorId, UUID ledgerId) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR, LedgerRole.REVIEWER,
                LedgerRole.VIEWER, LedgerRole.AGENT));
        return jdbcTemplate.query("""
                select id from voucher where ledger_id = ? and deleted_at is null order by voucher_date, voucher_number
                """, (rs, rowNum) -> find(actorId, ledgerId, rs.getObject("id", UUID.class)), ledgerId);
    }

    @Transactional(readOnly = true)
    public VoucherResponses.Voucher find(UUID actorId, UUID ledgerId, UUID voucherId) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR, LedgerRole.REVIEWER,
                LedgerRole.VIEWER, LedgerRole.AGENT));
        VoucherResponses.Voucher voucher = jdbcTemplate.query("""
                select id, ledger_id, period_id, voucher_date, voucher_type, voucher_number, summary, status,
                    approval_required
                from voucher where ledger_id = ? and id = ? and deleted_at is null
                """, rs -> rs.next() ? new VoucherResponses.Voucher(rs.getObject("id", UUID.class),
                rs.getObject("ledger_id", UUID.class), rs.getObject("period_id", UUID.class),
                rs.getObject("voucher_date", LocalDate.class), rs.getString("voucher_type"),
                rs.getString("voucher_number"), rs.getString("summary"), rs.getString("status"),
                rs.getBoolean("approval_required"), lines(ledgerId, voucherId)) : null, ledgerId, voucherId);
        if (voucher == null) {
            throw problem(404, "VOUCHER_NOT_FOUND", "Voucher not found", "The voucher is not available to this ledger");
        }
        return voucher;
    }

    @Transactional
    public VoucherResponses.Voucher validate(UUID actorId, UUID ledgerId, UUID voucherId) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR));
        VoucherState state = state(ledgerId, voucherId);
        if (!"DRAFT".equals(state.status())) {
            throw problem(409, "VOUCHER_STATE_INVALID", "Invalid voucher state",
                    "Only draft vouchers can be validated");
        }
        Integer lineCount = jdbcTemplate.queryForObject(
                "select count(*) from voucher_line where ledger_id = ? and voucher_id = ?", Integer.class,
                ledgerId, voucherId);
        BigDecimal debit = total(ledgerId, voucherId, "DEBIT");
        BigDecimal credit = total(ledgerId, voucherId, "CREDIT");
        if (lineCount == null || lineCount < 2 || debit.compareTo(credit) != 0) {
            throw problem(422, "VOUCHER_NOT_BALANCED", "Voucher is not balanced",
                    "A voucher needs at least two lines and equal debit and credit base amounts");
        }
        jdbcTemplate.update("update voucher set status = 'VALIDATED', updated_at = now(), updated_by = ? "
                + "where ledger_id = ? and id = ? and status = 'DRAFT'", actorId, ledgerId, voucherId);
        return find(actorId, ledgerId, voucherId);
    }

    @Transactional
    public VoucherResponses.Voucher submit(UUID actorId, UUID ledgerId, UUID voucherId) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR));
        VoucherState state = state(ledgerId, voucherId);
        if (!state.approvalRequired() || !"VALIDATED".equals(state.status())) {
            throw problem(409, "VOUCHER_STATE_INVALID", "Invalid voucher state",
                    "Only validated vouchers with approval enabled can be submitted");
        }
        changeStatus(ledgerId, voucherId, "VALIDATED", "SUBMITTED", actorId);
        approval(ledgerId, voucherId, "SUBMIT", null, actorId);
        return find(actorId, ledgerId, voucherId);
    }

    @Transactional
    public VoucherResponses.Voucher approve(UUID actorId, UUID ledgerId, UUID voucherId, String comment) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.REVIEWER));
        ensureComment(comment);
        changeStatus(ledgerId, voucherId, "SUBMITTED", "APPROVED", actorId);
        approval(ledgerId, voucherId, "APPROVE", comment.trim(), actorId);
        return find(actorId, ledgerId, voucherId);
    }

    @Transactional
    public VoucherResponses.Voucher reject(UUID actorId, UUID ledgerId, UUID voucherId, String comment) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.REVIEWER));
        ensureComment(comment);
        changeStatus(ledgerId, voucherId, "SUBMITTED", "DRAFT", actorId);
        approval(ledgerId, voucherId, "REJECT", comment.trim(), actorId);
        return find(actorId, ledgerId, voucherId);
    }

    @Transactional
    public VoucherResponses.Voucher post(UUID actorId, UUID ledgerId, UUID voucherId) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR));
        VoucherState state = state(ledgerId, voucherId);
        String requiredStatus = state.approvalRequired() ? "APPROVED" : "VALIDATED";
        if (!requiredStatus.equals(state.status())) {
            throw problem(409, "VOUCHER_STATE_INVALID", "Invalid voucher state",
                    "The voucher must be " + requiredStatus + " before posting");
        }
        jdbcTemplate.update("update voucher set status = 'POSTED', posted_at = now(), posted_by = ?, "
                + "current_revision = current_revision + 1, version = version + 1, updated_at = now(), "
                + "updated_by = ? where ledger_id = ? and id = ?",
                actorId, actorId, ledgerId, voucherId);
        audit(ledgerId, voucherId, "POST", actorId, null, "VALIDATED", "POSTED");
        return find(actorId, ledgerId, voucherId);
    }

    @Transactional
    public VoucherResponses.Voucher unpost(UUID actorId, UUID ledgerId, UUID voucherId, String reason) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR));
        ensureReason(reason);
        state(ledgerId, voucherId);
        changeStatus(ledgerId, voucherId, "POSTED", "DRAFT", actorId);
        audit(ledgerId, voucherId, "UNPOST", actorId, reason.trim(), "POSTED", "DRAFT");
        return find(actorId, ledgerId, voucherId);
    }

    @Transactional
    public VoucherResponses.Voucher reverse(UUID actorId, UUID ledgerId, UUID voucherId) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR));
        VoucherState state = state(ledgerId, voucherId);
        if (!"POSTED".equals(state.status())) {
            throw problem(409, "VOUCHER_STATE_INVALID", "Invalid voucher state",
                    "Only posted vouchers can be reversed");
        }
        VoucherResponses.Voucher original = find(actorId, ledgerId, voucherId);
        UUID reversalId = UUID.randomUUID();
        String number = (original.voucherNumber() + "-R-" + reversalId.toString().substring(0, 8));
        number = number.substring(0, Math.min(32, number.length()));
        UUID periodId = original.periodId();
        jdbcTemplate.update("""
                insert into voucher (id, ledger_id, period_id, voucher_date, voucher_type, voucher_number,
                    summary, approval_required, reversal_of_id, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, reversalId, ledgerId, periodId, original.voucherDate(), original.voucherType(), number,
                "Reversal of " + original.voucherNumber(), original.approvalRequired(), voucherId, actorId, actorId);
        for (VoucherResponses.Line line : original.lines()) {
            jdbcTemplate.update("""
                    insert into voucher_line (id, ledger_id, voucher_id, line_no, account_id, side, currency,
                        original_amount, exchange_rate, base_amount, summary)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), ledgerId, reversalId, line.lineNo(), line.accountId(),
                    "DEBIT".equals(line.side()) ? "CREDIT" : "DEBIT", line.currency(), line.originalAmount(),
                    line.exchangeRate(), line.baseAmount(), "Reversal of " + line.summary());
        }
        changeStatus(ledgerId, voucherId, "POSTED", "REVERSED", actorId);
        jdbcTemplate.update("update voucher set reversed_by_id = ? where ledger_id = ? and id = ?",
                reversalId, ledgerId, voucherId);
        audit(ledgerId, voucherId, "REVERSE", actorId, null, "POSTED", "REVERSED");
        return find(actorId, ledgerId, reversalId);
    }

    @Transactional
    public void delete(UUID actorId, UUID ledgerId, UUID voucherId) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR));
        VoucherState state = state(ledgerId, voucherId);
        if (!Set.of("DRAFT", "VALIDATED").contains(state.status())) {
            throw problem(409, "VOUCHER_STATE_INVALID", "Invalid voucher state",
                    "Only draft or validated vouchers can be deleted");
        }
        changeStatus(ledgerId, voucherId, state.status(), "DELETED", actorId);
        jdbcTemplate.update("update voucher set deleted_at = now() where ledger_id = ? and id = ?", ledgerId, voucherId);
        audit(ledgerId, voucherId, "DELETE", actorId, null, state.status(), "DELETED");
    }

    @Transactional
    public VoucherResponses.Voucher restoreDeleted(UUID actorId, UUID ledgerId, UUID voucherId) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR));
        VoucherState state = jdbcTemplate.query("""
                select status, approval_required from voucher where ledger_id = ? and id = ? and deleted_at is not null
                """, rs -> rs.next() ? new VoucherState(rs.getString("status"), rs.getBoolean("approval_required")) : null,
                ledgerId, voucherId);
        if (state == null || !"DELETED".equals(state.status())) {
            throw problem(404, "VOUCHER_NOT_FOUND", "Deleted voucher not found", "The deleted voucher is not available");
        }
        jdbcTemplate.update("update voucher set status = 'DRAFT', deleted_at = null, current_revision = current_revision + 1, "
                + "version = version + 1, updated_at = now(), updated_by = ? where ledger_id = ? and id = ?",
                actorId, ledgerId, voucherId);
        audit(ledgerId, voucherId, "RESTORE_DELETED", actorId, null, "DELETED", "DRAFT");
        return find(actorId, ledgerId, voucherId);
    }

    @Transactional(readOnly = true)
    public List<VoucherResponses.Revision> listRevisions(UUID actorId, UUID ledgerId, UUID voucherId) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR, LedgerRole.REVIEWER,
                LedgerRole.VIEWER, LedgerRole.AGENT));
        state(ledgerId, voucherId);
        return jdbcTemplate.query("""
                select id, revision, action, actor_id, reason, before_data::text, after_data::text, created_at
                from audit_revision where ledger_id = ? and aggregate_type = 'VOUCHER' and aggregate_id = ?
                order by revision
                """, (rs, rowNum) -> new VoucherResponses.Revision(rs.getObject("id", UUID.class),
                rs.getInt("revision"), rs.getString("action"), rs.getObject("actor_id", UUID.class),
                rs.getString("reason"), rs.getString("before_data"), rs.getString("after_data"),
                rs.getObject("created_at", OffsetDateTime.class)), ledgerId, voucherId);
    }

    private List<VoucherResponses.Line> lines(UUID ledgerId, UUID voucherId) {
        return jdbcTemplate.query("""
                select id, line_no, account_id, side, currency, original_amount, exchange_rate, base_amount, summary
                from voucher_line where ledger_id = ? and voucher_id = ? order by line_no
                """, (rs, rowNum) -> new VoucherResponses.Line(rs.getObject("id", UUID.class),
                rs.getInt("line_no"), rs.getObject("account_id", UUID.class), rs.getString("side"),
                rs.getString("currency"), rs.getBigDecimal("original_amount"),
                rs.getBigDecimal("exchange_rate"), rs.getBigDecimal("base_amount"), rs.getString("summary")),
                ledgerId, voucherId);
    }

    @Transactional
    public VoucherResponses.Voucher restoreRevision(UUID actorId, UUID ledgerId, UUID voucherId, int revision) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR));
        state(ledgerId, voucherId);
        String targetStatus = jdbcTemplate.query("""
                select coalesce(after_data->>'status', before_data->>'status')
                from audit_revision where ledger_id = ? and aggregate_type = 'VOUCHER'
                    and aggregate_id = ? and revision = ?
                """, rs -> rs.next() ? rs.getString(1) : null, ledgerId, voucherId, revision);
        if (targetStatus == null) {
            throw problem(404, "REVISION_NOT_FOUND", "Revision not found", "The requested voucher revision does not exist");
        }
        String before = state(ledgerId, voucherId).status();
        jdbcTemplate.update("""
                update voucher set status = 'DRAFT', current_revision = current_revision + 1,
                    version = version + 1, updated_at = now(), updated_by = ? where ledger_id = ? and id = ?
                """, actorId, ledgerId, voucherId);
        audit(ledgerId, voucherId, "RESTORE_REVISION", actorId, "revision:" + revision, before, "DRAFT");
        return find(actorId, ledgerId, voucherId);
    }

    private VoucherState state(UUID ledgerId, UUID voucherId) {
        VoucherState state = jdbcTemplate.query("""
                select status, approval_required from voucher where ledger_id = ? and id = ? and deleted_at is null
                """, rs -> rs.next() ? new VoucherState(rs.getString("status"), rs.getBoolean("approval_required")) : null,
                ledgerId, voucherId);
        if (state == null) {
            throw problem(404, "VOUCHER_NOT_FOUND", "Voucher not found", "The voucher is not available to this ledger");
        }
        return state;
    }

    private VoucherState stateWithVersion(UUID ledgerId, UUID voucherId) {
        VoucherState state = jdbcTemplate.query("""
                select status, approval_required, version from voucher where ledger_id = ? and id = ? and deleted_at is null
                """, rs -> rs.next() ? new VoucherState(rs.getString("status"), rs.getBoolean("approval_required"),
                rs.getLong("version")) : null, ledgerId, voucherId);
        if (state == null) {
            throw problem(404, "VOUCHER_NOT_FOUND", "Voucher not found", "The voucher is not available to this ledger");
        }
        return state;
    }

    private void changeStatus(UUID ledgerId, UUID voucherId, String expected, String next, UUID actorId) {
        int updated = jdbcTemplate.update("update voucher set status = ?, current_revision = current_revision + 1, "
                + "version = version + 1, updated_at = now(), updated_by = ? "
                + "where ledger_id = ? and id = ? and status = ?", next, actorId, ledgerId, voucherId, expected);
        if (updated == 0) {
            throw problem(409, "VOUCHER_STATE_INVALID", "Invalid voucher state", "The voucher state has changed");
        }
    }

    private void approval(UUID ledgerId, UUID voucherId, String action, String comment, UUID actorId) {
        jdbcTemplate.update("""
                insert into voucher_approval (id, ledger_id, voucher_id, action, comment, actor_id)
                values (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), ledgerId, voucherId, action, comment, actorId);
    }

    private void ensureComment(String comment) {
        if (comment == null || comment.isBlank()) {
            throw problem(422, "APPROVAL_COMMENT_REQUIRED", "Approval comment is required",
                    "A comment is required for approval actions");
        }
    }

    private void ensureReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw problem(422, "VOUCHER_REASON_REQUIRED", "Reason is required", "A reason is required for this action");
        }
    }

    private void audit(UUID ledgerId, UUID voucherId, String action, UUID actorId, String reason,
                       String before, String after) {
        Integer revision = jdbcTemplate.queryForObject(
                "select current_revision from voucher where ledger_id = ? and id = ?", Integer.class,
                ledgerId, voucherId);
        jdbcTemplate.update("""
                insert into audit_revision (id, ledger_id, aggregate_type, aggregate_id, revision, action,
                    actor_id, reason, before_data, after_data)
                values (?, ?, 'VOUCHER', ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                """, UUID.randomUUID(), ledgerId, voucherId, revision, action, actorId, reason,
                "{\"status\":\"" + before + "\"}", "{\"status\":\"" + after + "\"}");
    }

    private BigDecimal total(UUID ledgerId, UUID voucherId, String side) {
        return jdbcTemplate.queryForObject(
                "select coalesce(sum(base_amount), 0) from voucher_line where ledger_id = ? and voucher_id = ? and side = ?",
                BigDecimal.class, ledgerId, voucherId, side);
    }

    private LedgerContext ledgerContext(UUID ledgerId, UUID periodId, LocalDate voucherDate) {
        LedgerContext context = jdbcTemplate.query("""
                select l.base_currency, l.approval_enabled, p.status, p.start_date, p.end_date
                from ledger l join accounting_period p on p.ledger_id = l.id
                where l.id = ? and p.id = ? and l.deleted_at is null
                """, rs -> rs.next() ? new LedgerContext(rs.getString("base_currency"),
                rs.getBoolean("approval_enabled"), rs.getString("status"),
                rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class)) : null,
                ledgerId, periodId);
        if (context == null) {
            throw problem(422, "INVALID_VOUCHER_PERIOD", "Invalid voucher period",
                    "The period must belong to this ledger");
        }
        if (!"OPEN".equals(context.status()) || voucherDate.isBefore(context.startDate())
                || voucherDate.isAfter(context.endDate())) {
            throw problem(422, "INVALID_VOUCHER_PERIOD", "Invalid voucher period",
                    "The voucher date must be inside an open period");
        }
        return context;
    }

    private void ensureAccount(UUID ledgerId, UUID accountId) {
        boolean exists = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "select exists (select 1 from ledger_account where ledger_id = ? and id = ? and status = 'ACTIVE')",
                Boolean.class, ledgerId, accountId));
        if (!exists) {
            throw problem(422, "INVALID_VOUCHER_ACCOUNT", "Invalid voucher account",
                    "The account must belong to this ledger and be active");
        }
    }

    private void requireRole(UUID actorId, UUID ledgerId, Set<LedgerRole> roles) {
        String role = jdbcTemplate.query("""
                select m.role from ledger_membership m join ledger l on l.id = m.ledger_id
                where m.ledger_id = ? and m.user_id = ? and m.status = 'ACTIVE'
                    and m.deleted_at is null and l.deleted_at is null
                """, rs -> rs.next() ? rs.getString(1) : null, ledgerId, actorId);
        if (role == null) {
            throw problem(404, "LEDGER_NOT_FOUND", "Ledger not found", "The ledger is not available to this user");
        }
        if (!roles.contains(LedgerRole.valueOf(role))) {
            throw problem(403, "INSUFFICIENT_LEDGER_ROLE", "Insufficient ledger role",
                    "The current user cannot perform this operation");
        }
    }

    private BigDecimal amount(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal rate(BigDecimal value) {
        return value.setScale(8, RoundingMode.HALF_UP);
    }

    private ApiProblemException problem(int status, String code, String title, String detail) {
        return new ApiProblemException(status, code, title, detail, false);
    }

    private record VoucherState(String status, boolean approvalRequired, long version) {
        private VoucherState(String status, boolean approvalRequired) {
            this(status, approvalRequired, 0);
        }
    }

    private record Idempotency(String requestHash, UUID voucherId) {
    }

    private record LedgerContext(String baseCurrency, boolean approvalRequired, String status,
                                 LocalDate startDate, LocalDate endDate) {
    }
}
