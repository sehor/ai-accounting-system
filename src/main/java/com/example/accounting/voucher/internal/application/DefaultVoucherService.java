package com.example.accounting.voucher.internal.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.accounting.ledger.LedgerAccessService;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.shared.balance.BalanceProjectionService;
import com.example.accounting.shared.web.ApiProblemException;
import com.example.accounting.voucher.VoucherRequests;
import com.example.accounting.voucher.VoucherResponses;
import com.example.accounting.voucher.VoucherService;
import com.example.accounting.voucher.internal.port.VoucherRepository;
import com.example.accounting.voucher.internal.port.VoucherRepository.Idempotency;
import com.example.accounting.voucher.internal.port.VoucherRepository.LedgerContext;
import com.example.accounting.voucher.internal.port.VoucherRepository.VoucherState;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultVoucherService implements VoucherService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultVoucherService.class);

    // 同时审批记账开关：改为 false 即恢复原有的人工提交、审批和记账流程。
    private static final boolean AUTO_APPROVE_AND_POST_ON_SAVE = true;

    private final VoucherRepository vouchers;
    private final LedgerAccessService ledgerAccess;
    private final BalanceProjectionService balanceProjection;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public DefaultVoucherService(VoucherRepository vouchers, LedgerAccessService ledgerAccess,
                                 BalanceProjectionService balanceProjection) {
        this.vouchers = vouchers;
        this.ledgerAccess = ledgerAccess;
        this.balanceProjection = balanceProjection;
    }

    @Transactional
    @Override
    public VoucherResponses.Voucher create(UUID actorId, UUID ledgerId, VoucherRequests.Create request) {
        return create(actorId, ledgerId, request, null);
    }

    @Transactional
    @Override
    public VoucherResponses.Voucher create(UUID actorId, UUID ledgerId, VoucherRequests.Create request,
                                           String idempotencyKey) {
        return create(actorId, ledgerId, request, idempotencyKey, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR));
    }

    @Transactional
    @Override
    public VoucherResponses.Voucher createGenerated(UUID actorId, UUID ledgerId, VoucherRequests.Create request,
                                                    String idempotencyKey, String sourceType, UUID sourceId) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR));
        if (sourceType == null || sourceType.isBlank() || sourceId == null) {
            throw problem(422, "VOUCHER_SOURCE_REQUIRED", "Voucher source is required",
                    "Generated vouchers must identify their owning business process");
        }
        return create(actorId, ledgerId, request, idempotencyKey,
                Set.of(LedgerRole.OWNER, LedgerRole.EDITOR), sourceType.trim(), sourceId);
    }

    @Transactional
    @Override
    public VoucherResponses.Voucher createAgentDraft(UUID actorId, UUID ledgerId, VoucherRequests.Create request,
                                                     String idempotencyKey) {
        return create(actorId, ledgerId, request, idempotencyKey,
                Set.of(LedgerRole.OWNER, LedgerRole.EDITOR, LedgerRole.AGENT));
    }

    private VoucherResponses.Voucher create(UUID actorId, UUID ledgerId, VoucherRequests.Create request,
                                             String idempotencyKey, Set<LedgerRole> roles) {
        return create(actorId, ledgerId, request, idempotencyKey, roles, null, null);
    }

    private VoucherResponses.Voucher create(UUID actorId, UUID ledgerId, VoucherRequests.Create request,
                                             String idempotencyKey, Set<LedgerRole> roles,
                                             String sourceType, UUID sourceId) {
        requireRole(actorId, ledgerId, roles);
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        UUID voucherId = UUID.randomUUID();
        if (key != null) {
            if (key.length() > 128) {
                throw problem(400, "IDEMPOTENCY_KEY_INVALID", "Invalid idempotency key", "The idempotency key is too long");
            }
            String hash = requestHash(request);
            if (!vouchers.reserveIdempotency(ledgerId, actorId, key, hash, voucherId)) {
                Idempotency existing = vouchers.findIdempotency(ledgerId, actorId, key).orElseThrow();
                if (!hash.equals(existing.requestHash())) {
                    throw problem(409, "IDEMPOTENCY_KEY_REUSED", "Idempotency key reused",
                            "The idempotency key was used with a different request");
                }
                return find(actorId, ledgerId, existing.voucherId());
            }
        }
        LedgerContext context = ledgerContext(ledgerId, request.periodId(), request.voucherDate());
        String voucherType = request.voucherType().trim();
        String voucherNumber = request.voucherNumber() == null || request.voucherNumber().isBlank()
                ? vouchers.nextVoucherNumber(ledgerId, request.periodId(), voucherType)
                : request.voucherNumber().trim();
        if (sourceType == null) {
            vouchers.createVoucher(voucherId, ledgerId, request.periodId(), request.voucherDate(),
                    voucherType, voucherNumber, request.summary(),
                    context.approvalRequired(), null, actorId);
        } else {
            vouchers.createGeneratedVoucher(voucherId, ledgerId, request.periodId(), request.voucherDate(),
                    voucherType, voucherNumber, request.summary(),
                    context.approvalRequired(), null, actorId, sourceType, sourceId);
        }
        insertLines(ledgerId, voucherId, context, request.lines());
        audit(ledgerId, voucherId, "CREATE", actorId, null, null, snapshot(ledgerId, voucherId));
        return finalizeOnSave(actorId, ledgerId, voucherId, roles);
    }

    @Transactional
    @Override
    public VoucherResponses.Voucher update(UUID actorId, UUID ledgerId, UUID voucherId,
                                           VoucherRequests.Update request) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR));
        VoucherState state = stateWithVersion(ledgerId, voucherId);
        VoucherSnapshot before = snapshot(ledgerId, voucherId);
        requireOpenPeriods(ledgerId, before.periodId(), request.periodId());
        LedgerContext context = ledgerContext(ledgerId, request.periodId(), request.voucherDate());
        if (!vouchers.updateVoucher(ledgerId, voucherId, request.periodId(), request.voucherDate(),
                request.voucherType().trim(), request.voucherNumber().trim(), request.summary(),
                context.approvalRequired(), actorId, request.expectedVersion())) {
            throw problem(409, "RESOURCE_VERSION_CONFLICT", "Resource version conflict",
                    "The voucher was changed by another request");
        }
        vouchers.deleteLines(ledgerId, voucherId);
        insertLines(ledgerId, voucherId, context, request.lines());
        VoucherSnapshot after = snapshot(ledgerId, voucherId);
        audit(ledgerId, voucherId, "UPDATE", actorId, null, before, after);
        if ("POSTED".equals(state.status())) {
            publishUpdate(ledgerId, voucherId, state.version() + 1, before, after);
            return find(actorId, ledgerId, voucherId);
        }
        if ("DRAFT".equals(state.status())) {
            return finalizeOnSave(actorId, ledgerId, voucherId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR));
        }
        return find(actorId, ledgerId, voucherId);
    }

    @Transactional
    @Override
    public VoucherResponses.Voucher replaceGenerated(UUID actorId, UUID ledgerId, UUID voucherId,
                                                     VoucherRequests.Update request, String sourceType,
                                                     UUID expectedSourceId, UUID nextSourceId) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR));
        VoucherState state = stateWithVersion(ledgerId, voucherId);
        if (!state.generated() || !sourceType.equals(state.sourceType()) || !expectedSourceId.equals(state.sourceId())) {
            throw problem(409, "VOUCHER_SOURCE_MISMATCH", "Voucher source mismatch",
                    "The generated voucher is owned by another process");
        }
        VoucherSnapshot before = snapshot(ledgerId, voucherId);
        requireOpenPeriods(ledgerId, before.periodId(), request.periodId());
        LedgerContext context = ledgerContext(ledgerId, request.periodId(), request.voucherDate());
        if (!vouchers.replaceGeneratedVoucher(ledgerId, voucherId, request.periodId(), request.voucherDate(),
                request.voucherType().trim(), request.voucherNumber().trim(), request.summary(),
                context.approvalRequired(), actorId, request.expectedVersion(), sourceType, expectedSourceId,
                nextSourceId)) {
            throw problem(409, "RESOURCE_VERSION_CONFLICT", "Resource version conflict",
                    "The generated voucher was changed by another request");
        }
        vouchers.deleteLines(ledgerId, voucherId);
        insertLines(ledgerId, voucherId, context, request.lines());
        VoucherSnapshot after = snapshot(ledgerId, voucherId);
        audit(ledgerId, voucherId, "UPDATE_GENERATED", actorId, sourceType, before, after);
        if ("POSTED".equals(state.status())) {
            publishUpdate(ledgerId, voucherId, state.version() + 1, before, after);
        }
        return find(actorId, ledgerId, voucherId);
    }

    private void insertLines(UUID ledgerId, UUID voucherId, LedgerContext context, List<VoucherRequests.Line> lines) {
        int lineNo = 1;
        for (VoucherRequests.Line line : lines) {
            BigDecimal original = amount(line.originalAmount());
            BigDecimal rate = rate(line.exchangeRate());
            if (original.signum() == 0 || rate.signum() <= 0) {
                throw problem(422, "INVALID_VOUCHER_AMOUNT", "Invalid voucher amount",
                        "Original amount must be non-zero and exchange rate must be positive");
            }
            if (line.currency().equals(context.baseCurrency()) && rate.compareTo(BigDecimal.ONE) != 0) {
                throw problem(422, "INVALID_BASE_CURRENCY_RATE", "Invalid base currency rate",
                        "The base currency exchange rate must be 1");
            }
            ensureAccount(ledgerId, line.accountId());
            VoucherRepository.AccountControls controls = vouchers.accountControls(ledgerId, line.accountId())
                    .orElseThrow();
            UUID cashFlowItemId = line.cashFlowItemId() == null
                    ? controls.defaultCashFlowItemId() : line.cashFlowItemId();
            List<VoucherRequests.Dimension> dimensions =
                    line.dimensions() == null ? List.of() : line.dimensions();
            validateLineControls(ledgerId, line, original, cashFlowItemId, controls, dimensions);
            UUID lineId = UUID.randomUUID();
            vouchers.createLine(lineId, ledgerId, voucherId, lineNo++, line.accountId(), line.side(),
                    line.currency(), original, rate, original.multiply(rate).setScale(2, RoundingMode.HALF_UP),
                    line.summary(), cashFlowItemId, line.quantity(), line.unitPrice());
            vouchers.createLineDimensions(lineId, ledgerId, dimensions);
        }
    }

    private void validateLineControls(
            UUID ledgerId, VoucherRequests.Line line, BigDecimal original,
            UUID cashFlowItemId, VoucherRepository.AccountControls controls,
            List<VoucherRequests.Dimension> dimensions) {
        if (!vouchers.validCashFlowItem(ledgerId, cashFlowItemId)) {
            throw problem(422, "INVALID_CASH_FLOW_ITEM", "Invalid cash-flow item",
                    "The cash-flow item must be active in this ledger");
        }
        if (controls.quantityEnabled()) {
            boolean bothMissing = line.quantity() == null && line.unitPrice() == null;
            boolean bothValid = line.quantity() != null && line.unitPrice() != null
                    && line.quantity().signum() > 0 && line.unitPrice().signum() > 0
                    && line.quantity().multiply(line.unitPrice()).setScale(4, RoundingMode.HALF_UP)
                    .compareTo(original) == 0;
            if (!bothMissing && !bothValid) {
                throw problem(422, "INVALID_QUANTITY_AMOUNT", "Invalid quantity amount",
                        "Quantity and unit price must be positive and multiply to the line amount");
            }
        } else if (line.quantity() != null || line.unitPrice() != null) {
            throw problem(422, "QUANTITY_ACCOUNT_REQUIRED", "Quantity accounting is not enabled",
                    "Quantity and unit price are only accepted for quantity-enabled accounts");
        }
        Set<UUID> seen = new HashSet<>();
        for (VoucherRequests.Dimension dimension : dimensions) {
            if (!seen.add(dimension.dimensionTypeId())
                    || !controls.dimensionTypeIds().contains(dimension.dimensionTypeId())
                    || !vouchers.validDimensionValue(
                    ledgerId, dimension.dimensionTypeId(), dimension.dimensionValueId())) {
                throw problem(422, "INVALID_VOUCHER_DIMENSION", "Invalid voucher dimension",
                        "Dimensions must be unique bindings with active values in this ledger");
            }
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
    @Override
    public List<VoucherResponses.Voucher> list(UUID actorId, UUID ledgerId) {
        return list(actorId, ledgerId, 100, 0);
    }

    @Transactional(readOnly = true)
    @Override
    public List<VoucherResponses.Voucher> list(UUID actorId, UUID ledgerId, int limit, int offset) {
        return list(actorId, ledgerId, new VoucherRequests.Search(null, null, null, null), limit, offset);
    }

    @Transactional(readOnly = true)
    @Override
    public List<VoucherResponses.Voucher> list(
            UUID actorId, UUID ledgerId, String periodCode, int limit, int offset) {
        return list(actorId, ledgerId, new VoucherRequests.Search(periodCode, null, null, null), limit, offset);
    }

    @Transactional(readOnly = true)
    @Override
    public List<VoucherResponses.Voucher> list(
            UUID actorId, UUID ledgerId, VoucherRequests.Search search, int limit, int offset) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR, LedgerRole.REVIEWER,
                LedgerRole.VIEWER, LedgerRole.AGENT));
        if (limit < 1 || limit > 500 || offset < 0) {
            throw problem(400, "PAGINATION_INVALID", "Invalid pagination",
                    "limit must be between 1 and 500 and offset must be non-negative");
        }
        VoucherRequests.Search normalizedSearch = normalizeSearch(search);
        List<VoucherResponses.Voucher> result = vouchers.list(ledgerId, normalizedSearch, limit, offset);
        if (result.isEmpty()) {
            return result;
        }
        Map<UUID, List<VoucherResponses.Line>> linesByVoucher = vouchers.linesByVoucher(ledgerId,
                result.stream().map(VoucherResponses.Voucher::id).toList());
        return result.stream().map(voucher -> new VoucherResponses.Voucher(
                voucher.id(), voucher.ledgerId(), voucher.periodId(), voucher.voucherDate(), voucher.voucherType(),
                voucher.voucherNumber(), voucher.summary(), voucher.status(), voucher.approvalRequired(),
                voucher.version(), linesByVoucher.getOrDefault(voucher.id(), List.of()),
                voucher.sourceType(), voucher.sourceId())).toList();
    }

    @Transactional(readOnly = true)
    @Override
    public long count(UUID actorId, UUID ledgerId, String periodCode) {
        return count(actorId, ledgerId, new VoucherRequests.Search(periodCode, null, null, null));
    }

    @Transactional(readOnly = true)
    @Override
    public long count(UUID actorId, UUID ledgerId, VoucherRequests.Search search) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR, LedgerRole.REVIEWER,
                LedgerRole.VIEWER, LedgerRole.AGENT));
        return vouchers.count(ledgerId, normalizeSearch(search));
    }

    private VoucherRequests.Search normalizeSearch(VoucherRequests.Search search) {
        VoucherRequests.Search source = search == null
                ? new VoucherRequests.Search(null, null, null, null) : search;
        if (source.startDate() != null && source.endDate() != null && source.startDate().isAfter(source.endDate())) {
            throw problem(400, "VOUCHER_DATE_RANGE_INVALID", "Invalid voucher date range",
                    "startDate must not be after endDate");
        }
        String keyword = source.keyword() == null || source.keyword().isBlank() ? null : source.keyword().trim();
        if (keyword != null && keyword.length() > 100) {
            throw problem(400, "VOUCHER_KEYWORD_INVALID", "Invalid voucher keyword",
                    "keyword must not exceed 100 characters");
        }
        return new VoucherRequests.Search(normalizePeriodCode(source.periodCode()), source.startDate(),
                source.endDate(), keyword);
    }

    private String normalizePeriodCode(String periodCode) {
        if (periodCode == null || periodCode.isBlank()) {
            return null;
        }
        String normalized = periodCode.trim();
        if (!normalized.matches("\\d{4}-(0[1-9]|1[0-2])")) {
            throw problem(400, "PERIOD_CODE_INVALID", "Invalid period code",
                    "periodCode must use YYYY-MM format");
        }
        return normalized;
    }

    @Transactional(readOnly = true)
    @Override
    public VoucherResponses.Voucher find(UUID actorId, UUID ledgerId, UUID voucherId) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR, LedgerRole.REVIEWER,
                LedgerRole.VIEWER, LedgerRole.AGENT));
        return vouchers.find(ledgerId, voucherId, false).orElseThrow(() ->
                problem(404, "VOUCHER_NOT_FOUND", "Voucher not found",
                        "The voucher is not available to this ledger"));
    }

    @Transactional
    @Override
    public VoucherResponses.Voucher validate(UUID actorId, UUID ledgerId, UUID voucherId) {
        return validate(actorId, ledgerId, voucherId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR));
    }

    @Transactional
    @Override
    public VoucherResponses.Voucher validateAgentDraft(UUID actorId, UUID ledgerId, UUID voucherId) {
        return validate(actorId, ledgerId, voucherId,
                Set.of(LedgerRole.OWNER, LedgerRole.EDITOR, LedgerRole.AGENT));
    }

    private VoucherResponses.Voucher validate(UUID actorId, UUID ledgerId, UUID voucherId, Set<LedgerRole> roles) {
        requireRole(actorId, ledgerId, roles);
        VoucherState state = state(ledgerId, voucherId);
        if (!"DRAFT".equals(state.status())) {
            throw problem(409, "VOUCHER_STATE_INVALID", "Invalid voucher state",
                    "Only draft vouchers can be validated");
        }
        ensureControlsComplete(ledgerId, voucherId);
        int lineCount = vouchers.lineCount(ledgerId, voucherId);
        BigDecimal debit = total(ledgerId, voucherId, "DEBIT");
        BigDecimal credit = total(ledgerId, voucherId, "CREDIT");
        if (lineCount < 2 || debit.compareTo(credit) != 0) {
            throw problem(422, "VOUCHER_NOT_BALANCED", "Voucher is not balanced",
                    "A voucher needs at least two lines and equal debit and credit base amounts");
        }
        VoucherSnapshot before = snapshot(ledgerId, voucherId);
        changeStatus(ledgerId, voucherId, "DRAFT", "VALIDATED", actorId);
        audit(ledgerId, voucherId, "VALIDATE", actorId, null, before, snapshot(ledgerId, voucherId));
        return find(actorId, ledgerId, voucherId);
    }

    private VoucherResponses.Voucher finalizeOnSave(
            UUID actorId, UUID ledgerId, UUID voucherId, Set<LedgerRole> roles) {
        VoucherResponses.Voucher validated = validate(actorId, ledgerId, voucherId, roles);
        if (!AUTO_APPROVE_AND_POST_ON_SAVE) {
            return validated;
        }
        VoucherSnapshot beforeSubmit = snapshot(ledgerId, voucherId);
        changeStatus(ledgerId, voucherId, "VALIDATED", "SUBMITTED", actorId);
        approval(ledgerId, voucherId, "SUBMIT", null, actorId);
        audit(ledgerId, voucherId, "SUBMIT", actorId, null, beforeSubmit, snapshot(ledgerId, voucherId));

        String comment = "Automatically approved on save";
        VoucherSnapshot beforeApprove = snapshot(ledgerId, voucherId);
        changeStatus(ledgerId, voucherId, "SUBMITTED", "APPROVED", actorId);
        approval(ledgerId, voucherId, "APPROVE", comment, actorId);
        audit(ledgerId, voucherId, "APPROVE", actorId, comment, beforeApprove, snapshot(ledgerId, voucherId));
        return post(actorId, ledgerId, voucherId, roles, true);
    }

    @Transactional
    @Override
    public VoucherResponses.Voucher submit(UUID actorId, UUID ledgerId, UUID voucherId) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR));
        VoucherState state = state(ledgerId, voucherId);
        ensureManual(state);
        if (!state.approvalRequired() || !"VALIDATED".equals(state.status())) {
            throw problem(409, "VOUCHER_STATE_INVALID", "Invalid voucher state",
                    "Only validated vouchers with approval enabled can be submitted");
        }
        ensureControlsComplete(ledgerId, voucherId);
        VoucherSnapshot before = snapshot(ledgerId, voucherId);
        changeStatus(ledgerId, voucherId, "VALIDATED", "SUBMITTED", actorId);
        approval(ledgerId, voucherId, "SUBMIT", null, actorId);
        audit(ledgerId, voucherId, "SUBMIT", actorId, null, before, snapshot(ledgerId, voucherId));
        return find(actorId, ledgerId, voucherId);
    }

    @Transactional
    @Override
    public VoucherResponses.Voucher approve(UUID actorId, UUID ledgerId, UUID voucherId, String comment) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.REVIEWER));
        ensureManual(state(ledgerId, voucherId));
        ensureComment(comment);
        VoucherSnapshot before = snapshot(ledgerId, voucherId);
        changeStatus(ledgerId, voucherId, "SUBMITTED", "APPROVED", actorId);
        approval(ledgerId, voucherId, "APPROVE", comment.trim(), actorId);
        audit(ledgerId, voucherId, "APPROVE", actorId, comment.trim(), before, snapshot(ledgerId, voucherId));
        return find(actorId, ledgerId, voucherId);
    }

    @Transactional
    @Override
    public VoucherResponses.Voucher reject(UUID actorId, UUID ledgerId, UUID voucherId, String comment) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.REVIEWER));
        ensureManual(state(ledgerId, voucherId));
        ensureComment(comment);
        VoucherSnapshot before = snapshot(ledgerId, voucherId);
        changeStatus(ledgerId, voucherId, "SUBMITTED", "DRAFT", actorId);
        approval(ledgerId, voucherId, "REJECT", comment.trim(), actorId);
        audit(ledgerId, voucherId, "REJECT", actorId, comment.trim(), before, snapshot(ledgerId, voucherId));
        return find(actorId, ledgerId, voucherId);
    }

    @Transactional
    @Override
    public VoucherResponses.Voucher post(UUID actorId, UUID ledgerId, UUID voucherId) {
        ensureManual(state(ledgerId, voucherId));
        return post(actorId, ledgerId, voucherId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR), false);
    }

    @Transactional
    @Override
    public VoucherResponses.Voucher postAgentVoucher(UUID actorId, UUID ledgerId, UUID voucherId) {
        ensureManual(state(ledgerId, voucherId));
        return post(actorId, ledgerId, voucherId,
                Set.of(LedgerRole.OWNER, LedgerRole.EDITOR, LedgerRole.AGENT), true);
    }

    private VoucherResponses.Voucher post(
            UUID actorId, UUID ledgerId, UUID voucherId, Set<LedgerRole> roles, boolean idempotent) {
        requireRole(actorId, ledgerId, roles);
        VoucherState state = state(ledgerId, voucherId);
        if (idempotent && "POSTED".equals(state.status())) {
            return find(actorId, ledgerId, voucherId);
        }
        String requiredStatus = "APPROVED".equals(state.status()) || state.approvalRequired()
                ? "APPROVED" : "VALIDATED";
        if (!requiredStatus.equals(state.status())) {
            throw problem(409, "VOUCHER_STATE_INVALID", "Invalid voucher state",
                    "The voucher must be " + requiredStatus + " before posting");
        }
        ensureControlsComplete(ledgerId, voucherId);
        VoucherSnapshot before = snapshot(ledgerId, voucherId);
        balanceProjection.requireOpenPeriod(ledgerId, before.periodId());
        if (!vouchers.post(ledgerId, voucherId, requiredStatus, actorId)) {
            throw problem(409, "VOUCHER_STATE_INVALID", "Invalid voucher state", "The voucher state has changed");
        }
        balanceProjection.publishVoucher(new BalanceProjectionService.VoucherEvent(
                ledgerId, before.periodId(), voucherId, state.version() + 1,
                BalanceProjectionService.EventType.POST, balanceEntries(before.lines(), BigDecimal.ONE)));
        audit(ledgerId, voucherId, "POST", actorId, null, before, snapshot(ledgerId, voucherId));
        return find(actorId, ledgerId, voucherId);
    }

    @Transactional
    @Override
    public void delete(UUID actorId, UUID ledgerId, UUID voucherId) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR));
        VoucherState state = state(ledgerId, voucherId);
        VoucherSnapshot before = snapshot(ledgerId, voucherId);
        balanceProjection.requireOpenPeriod(ledgerId, before.periodId());
        if ("POSTED".equals(state.status())) {
            balanceProjection.publishVoucher(new BalanceProjectionService.VoucherEvent(
                    ledgerId, before.periodId(), voucherId, state.version() + 1,
                    BalanceProjectionService.EventType.UPDATE, balanceEntries(before.lines(), BigDecimal.ONE.negate())));
        }
        if (!vouchers.deleteVoucher(ledgerId, voucherId, state.version())) {
            throw problem(409, "VOUCHER_STATE_INVALID", "Invalid voucher state", "The voucher state has changed");
        }
        LOGGER.info("Voucher deleted: ledgerId={}, voucherId={}, actorId={}", ledgerId, voucherId, actorId);
    }

    @Transactional(readOnly = true)
    @Override
    public List<VoucherResponses.Revision> listRevisions(UUID actorId, UUID ledgerId, UUID voucherId) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR, LedgerRole.REVIEWER,
                LedgerRole.VIEWER, LedgerRole.AGENT));
        state(ledgerId, voucherId);
        return vouchers.listRevisions(ledgerId, voucherId);
    }

    @Transactional
    @Override
    public VoucherResponses.Voucher restoreRevision(UUID actorId, UUID ledgerId, UUID voucherId, int revision) {
        requireRole(actorId, ledgerId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR));
        VoucherState state = state(ledgerId, voucherId);
        String targetData = vouchers.findRevisionData(ledgerId, voucherId, revision).orElseThrow(() ->
                problem(404, "REVISION_NOT_FOUND", "Revision not found",
                        "The requested voucher revision does not exist"));
        VoucherSnapshot target = fromJson(targetData);
        VoucherSnapshot before = snapshot(ledgerId, voucherId);
        requireOpenPeriods(ledgerId, before.periodId(), target.periodId());
        ledgerContext(ledgerId, target.periodId(), target.voucherDate());
        vouchers.restoreHeader(ledgerId, voucherId, target.periodId(), target.voucherDate(), target.voucherType(),
                target.voucherNumber(), target.summary(), target.approvalRequired(), actorId);
        vouchers.deleteLines(ledgerId, voucherId);
        insertSnapshotLines(ledgerId, voucherId, target.lines());
        VoucherSnapshot after = snapshot(ledgerId, voucherId);
        audit(ledgerId, voucherId, "RESTORE_REVISION", actorId, "revision:" + revision, before, after);
        if ("POSTED".equals(state.status())) {
            publishUpdate(ledgerId, voucherId, state.version() + 1, before, after);
            return find(actorId, ledgerId, voucherId);
        }
        if ("DRAFT".equals(state.status())) {
            return finalizeOnSave(actorId, ledgerId, voucherId, Set.of(LedgerRole.OWNER, LedgerRole.EDITOR));
        }
        return find(actorId, ledgerId, voucherId);
    }

    private VoucherState state(UUID ledgerId, UUID voucherId) {
        return vouchers.findState(ledgerId, voucherId, false).orElseThrow(() ->
                problem(404, "VOUCHER_NOT_FOUND", "Voucher not found",
                        "The voucher is not available to this ledger"));
    }

    private VoucherState stateWithVersion(UUID ledgerId, UUID voucherId) {
        return state(ledgerId, voucherId);
    }

    private void ensureManual(VoucherState state) {
        if (state.generated()) {
            throw problem(409, "VOUCHER_MANAGED_BY_SOURCE", "Voucher is managed by a source process",
                    "Generated vouchers can only be changed by their owning asset or settlement workflow");
        }
    }

    private List<BalanceProjectionService.Entry> balanceEntries(List<VoucherLineSnapshot> lines,
                                                                BigDecimal sign) {
        return lines.stream().map(line -> {
            BigDecimal debit = "DEBIT".equals(line.side()) ? line.baseAmount().multiply(sign) : BigDecimal.ZERO;
            BigDecimal credit = "CREDIT".equals(line.side()) ? line.baseAmount().multiply(sign) : BigDecimal.ZERO;
            return new BalanceProjectionService.Entry(line.accountId(), BigDecimal.ZERO, BigDecimal.ZERO,
                    debit, credit);
        }).toList();
    }

    private void publishUpdate(UUID ledgerId, UUID voucherId, long version,
                               VoucherSnapshot before, VoucherSnapshot after) {
        if (before.periodId().equals(after.periodId())) {
            balanceProjection.publishVoucher(new BalanceProjectionService.VoucherEvent(
                    ledgerId, after.periodId(), voucherId, version, BalanceProjectionService.EventType.UPDATE,
                    balanceDelta(before.lines(), after.lines())));
            return;
        }
        balanceProjection.publishVoucher(new BalanceProjectionService.VoucherEvent(
                ledgerId, before.periodId(), voucherId, version, BalanceProjectionService.EventType.UPDATE,
                balanceEntries(before.lines(), BigDecimal.ONE.negate())));
        balanceProjection.publishVoucher(new BalanceProjectionService.VoucherEvent(
                ledgerId, after.periodId(), voucherId, version, BalanceProjectionService.EventType.UPDATE,
                balanceEntries(after.lines(), BigDecimal.ONE)));
    }

    private List<BalanceProjectionService.Entry> balanceDelta(List<VoucherLineSnapshot> before,
                                                              List<VoucherLineSnapshot> after) {
        Map<UUID, BigDecimal[]> amounts = new HashMap<>();
        addBalance(amounts, before, BigDecimal.ONE.negate());
        addBalance(amounts, after, BigDecimal.ONE);
        return amounts.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry ->
                new BalanceProjectionService.Entry(entry.getKey(), BigDecimal.ZERO, BigDecimal.ZERO,
                        entry.getValue()[0], entry.getValue()[1])).toList();
    }

    private void addBalance(Map<UUID, BigDecimal[]> amounts, List<VoucherLineSnapshot> lines, BigDecimal sign) {
        for (VoucherLineSnapshot line : lines) {
            BigDecimal[] current = amounts.computeIfAbsent(line.accountId(), ignored ->
                    new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            if ("DEBIT".equals(line.side())) {
                current[0] = current[0].add(line.baseAmount().multiply(sign));
            } else {
                current[1] = current[1].add(line.baseAmount().multiply(sign));
            }
        }
    }

    private void requireOpenPeriods(UUID ledgerId, UUID firstPeriodId, UUID secondPeriodId) {
        if (firstPeriodId.equals(secondPeriodId)) {
            balanceProjection.requireOpenPeriod(ledgerId, firstPeriodId);
            return;
        }
        if (firstPeriodId.compareTo(secondPeriodId) < 0) {
            balanceProjection.requireOpenPeriod(ledgerId, firstPeriodId);
            balanceProjection.requireOpenPeriod(ledgerId, secondPeriodId);
        } else {
            balanceProjection.requireOpenPeriod(ledgerId, secondPeriodId);
            balanceProjection.requireOpenPeriod(ledgerId, firstPeriodId);
        }
    }

    private void changeStatus(UUID ledgerId, UUID voucherId, String expected, String next, UUID actorId) {
        if (!vouchers.changeStatus(ledgerId, voucherId, expected, next, actorId)) {
            throw problem(409, "VOUCHER_STATE_INVALID", "Invalid voucher state", "The voucher state has changed");
        }
    }

    private void approval(UUID ledgerId, UUID voucherId, String action, String comment, UUID actorId) {
        vouchers.recordApproval(ledgerId, voucherId, action, comment, actorId);
    }

    private void ensureComment(String comment) {
        if (comment == null || comment.isBlank()) {
            throw problem(422, "APPROVAL_COMMENT_REQUIRED", "Approval comment is required",
                    "A comment is required for approval actions");
        }
    }

    private void audit(UUID ledgerId, UUID voucherId, String action, UUID actorId, String reason,
                       VoucherSnapshot before, VoucherSnapshot after) {
        vouchers.recordRevision(ledgerId, voucherId, vouchers.currentRevision(ledgerId, voucherId), action,
                actorId, reason, toJson(before), toJson(after));
    }

    private VoucherSnapshot snapshot(UUID ledgerId, UUID voucherId) {
        VoucherResponses.Voucher voucher = vouchers.find(ledgerId, voucherId, true).orElseThrow(() ->
                problem(404, "VOUCHER_NOT_FOUND", "Voucher not found",
                        "The voucher is not available to this ledger"));
        return new VoucherSnapshot(voucher.periodId(), voucher.voucherDate(), voucher.voucherType(),
                voucher.voucherNumber(), voucher.summary(), voucher.status(), voucher.approvalRequired(),
                voucher.lines().stream().map(VoucherLineSnapshot::from).toList());
    }

    private void insertSnapshotLines(UUID ledgerId, UUID voucherId, List<VoucherLineSnapshot> lines) {
        for (VoucherLineSnapshot line : lines) {
            UUID lineId = UUID.randomUUID();
            vouchers.createLine(lineId, ledgerId, voucherId, line.lineNo(), line.accountId(), line.side(),
                    line.currency(), line.originalAmount(), line.exchangeRate(), line.baseAmount(), line.summary(),
                    line.cashFlowItemId(), line.quantity(), line.unitPrice());
            vouchers.createLineDimensions(lineId, ledgerId, line.dimensions().stream()
                    .map(dimension -> new VoucherRequests.Dimension(
                            dimension.dimensionTypeId(), dimension.dimensionValueId()))
                    .toList());
        }
    }

    private String toJson(VoucherSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw problem(500, "VOUCHER_SNAPSHOT_FAILED", "Voucher snapshot failed",
                    "The voucher revision could not be serialized");
        }
    }

    private VoucherSnapshot fromJson(String json) {
        try {
            return objectMapper.readValue(json, VoucherSnapshot.class);
        } catch (JsonProcessingException exception) {
            throw problem(422, "REVISION_NOT_RESTORABLE", "Revision cannot be restored",
                    "The requested revision does not contain a complete voucher snapshot");
        }
    }

    private BigDecimal total(UUID ledgerId, UUID voucherId, String side) {
        return vouchers.total(ledgerId, voucherId, side);
    }

    private LedgerContext ledgerContext(UUID ledgerId, UUID periodId, LocalDate voucherDate) {
        LedgerContext context = vouchers.findLedgerContext(ledgerId, periodId).orElseThrow(() ->
                problem(422, "INVALID_VOUCHER_PERIOD", "Invalid voucher period",
                        "The period must belong to this ledger"));
        if (!"OPEN".equals(context.status()) || voucherDate.isBefore(context.startDate())
                || voucherDate.isAfter(context.endDate())) {
            throw problem(422, "INVALID_VOUCHER_PERIOD", "Invalid voucher period",
                    "The voucher date must be inside an open period");
        }
        return context;
    }

    private void ensureAccount(UUID ledgerId, UUID accountId) {
        if (!vouchers.activeAccountExists(ledgerId, accountId)) {
            throw problem(422, "INVALID_VOUCHER_ACCOUNT", "Invalid voucher account",
                    "The account must be an active leaf in this ledger");
        }
    }

    private void ensureControlsComplete(UUID ledgerId, UUID voucherId) {
        if (!vouchers.controlsComplete(ledgerId, voucherId)) {
            throw problem(422, "VOUCHER_CONTROL_INCOMPLETE", "Voucher controls are incomplete",
                    "Complete required cash flow, quantity, and account dimensions before validation");
        }
    }

    private void requireRole(UUID actorId, UUID ledgerId, Set<LedgerRole> roles) {
        if (!roles.contains(ledgerAccess.requireMembership(actorId, ledgerId))) {
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

    private record VoucherSnapshot(UUID periodId, LocalDate voucherDate, String voucherType, String voucherNumber,
                                   String summary, String status, boolean approvalRequired,
                                   List<VoucherLineSnapshot> lines) {
    }

    private record VoucherLineSnapshot(int lineNo, UUID accountId, String side, String currency,
                                       BigDecimal originalAmount, BigDecimal exchangeRate, BigDecimal baseAmount,
                                       String summary, UUID cashFlowItemId, BigDecimal quantity,
                                       BigDecimal unitPrice, List<VoucherResponses.Dimension> dimensions) {
        private static VoucherLineSnapshot from(VoucherResponses.Line line) {
            return new VoucherLineSnapshot(line.lineNo(), line.accountId(), line.side(), line.currency(),
                    line.originalAmount(), line.exchangeRate(), line.baseAmount(), line.summary(),
                    line.cashFlowItemId(), line.quantity(), line.unitPrice(), line.dimensions());
        }
    }
}
