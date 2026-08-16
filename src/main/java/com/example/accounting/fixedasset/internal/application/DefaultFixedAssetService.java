package com.example.accounting.fixedasset.internal.application;

import com.example.accounting.fixedasset.FixedAssetRequests;
import com.example.accounting.fixedasset.FixedAssetResponses;
import com.example.accounting.fixedasset.FixedAssetDepreciationCancellationCommand;
import com.example.accounting.shared.audit.AuditSnapshotSerializer;
import com.example.accounting.fixedasset.FixedAssetDisposalReversalCommand;
import com.example.accounting.fixedasset.FixedAssetService;
import com.example.accounting.fixedasset.internal.port.FixedAssetRepository;
import com.example.accounting.fixedasset.internal.port.FixedAssetRepository.AssetRecord;
import com.example.accounting.fixedasset.internal.port.FixedAssetRepository.CategoryRecord;
import com.example.accounting.fixedasset.internal.port.FixedAssetRepository.LineRecord;
import com.example.accounting.fixedasset.internal.port.FixedAssetRepository.RunRecord;
import com.example.accounting.ledger.LedgerAccessService;
import com.example.accounting.ledger.LedgerResponses;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.ledger.LedgerService;
import com.example.accounting.shared.web.ApiProblemException;
import com.example.accounting.voucher.VoucherRequests;
import com.example.accounting.voucher.VoucherResponses;
import com.example.accounting.voucher.GeneratedVoucherCommandService;
import com.example.accounting.voucher.VoucherService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultFixedAssetService implements FixedAssetService, FixedAssetDisposalReversalCommand,
        FixedAssetDepreciationCancellationCommand {

    private static final Set<LedgerRole> WRITE_ROLES = Set.of(LedgerRole.OWNER, LedgerRole.EDITOR);
    private static final Set<LedgerRole> READ_ROLES = Set.of(LedgerRole.OWNER, LedgerRole.EDITOR,
            LedgerRole.REVIEWER, LedgerRole.VIEWER, LedgerRole.AGENT);

    private final FixedAssetRepository assets;
    private final LedgerAccessService ledgerAccess;
    private final LedgerService ledgers;
    private final VoucherService vouchers;
    private final GeneratedVoucherCommandService generatedVouchers;
    private final AuditSnapshotSerializer auditSnapshots;

    public DefaultFixedAssetService(FixedAssetRepository assets, LedgerAccessService ledgerAccess,
                                    LedgerService ledgers, VoucherService vouchers,
                                    GeneratedVoucherCommandService generatedVouchers,
                                    AuditSnapshotSerializer auditSnapshots) {
        this.assets = assets;
        this.ledgerAccess = ledgerAccess;
        this.ledgers = ledgers;
        this.vouchers = vouchers;
        this.generatedVouchers = generatedVouchers;
        this.auditSnapshots = auditSnapshots;
    }

    @Override
    @Transactional(readOnly = true)
    public List<FixedAssetResponses.Category> listCategories(UUID actorId, UUID ledgerId) {
        requireRole(actorId, ledgerId, READ_ROLES);
        return assets.listCategories(ledgerId).stream().map(this::category).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public FixedAssetResponses.Category findCategory(UUID actorId, UUID ledgerId, UUID categoryId) {
        requireRole(actorId, ledgerId, READ_ROLES);
        return category(categoryRow(ledgerId, categoryId));
    }

    @Override
    @Transactional
    public FixedAssetResponses.Category createCategory(UUID actorId, UUID ledgerId,
                                                       FixedAssetRequests.CategoryCreate request) {
        requireRole(actorId, ledgerId, WRITE_ROLES);
        validateAccounts(actorId, ledgerId, request.assetAccountId(), request.accumulatedDepreciationAccountId(),
                request.depreciationExpenseAccountId(), request.impairmentAccountId(), request.clearingAccountId(),
                request.disposalGainAccountId(), request.disposalLossAccountId());
        validateRate(request.residualRate());
        CategoryRecord row = new CategoryRecord(UUID.randomUUID(), ledgerId, request.code().trim(), request.name().trim(),
                request.usefulLifeMonths(), scaleRate(request.residualRate()), request.assetAccountId(),
                request.accumulatedDepreciationAccountId(), request.depreciationExpenseAccountId(), request.impairmentAccountId(),
                request.clearingAccountId(), request.disposalGainAccountId(), request.disposalLossAccountId(), "ACTIVE", 0);
        try {
            assets.insertCategory(row, actorId);
        } catch (org.springframework.dao.DataIntegrityViolationException exception) {
            throw problem(409, "FIXED_ASSET_CATEGORY_CONFLICT", "Fixed-asset category already exists",
                    "The category code is already used in this ledger");
        }
        return category(assets.findCategory(ledgerId, row.id()).orElseThrow());
    }

    @Override
    @Transactional
    public FixedAssetResponses.Category updateCategory(UUID actorId, UUID ledgerId, UUID categoryId,
                                                       FixedAssetRequests.CategoryPatch request) {
        requireRole(actorId, ledgerId, WRITE_ROLES);
        CategoryRecord current = categoryRow(ledgerId, categoryId);
        CategoryRecord next = new CategoryRecord(current.id(), current.ledgerId(), current.code(),
                request.name() == null ? current.name() : request.name().trim(),
                request.usefulLifeMonths() == null ? current.usefulLifeMonths() : request.usefulLifeMonths(),
                request.residualRate() == null ? current.residualRate() : scaleRate(request.residualRate()),
                request.assetAccountId() == null ? current.assetAccountId() : request.assetAccountId(),
                request.accumulatedDepreciationAccountId() == null ? current.accumulatedDepreciationAccountId() : request.accumulatedDepreciationAccountId(),
                request.depreciationExpenseAccountId() == null ? current.depreciationExpenseAccountId() : request.depreciationExpenseAccountId(),
                request.impairmentAccountId() == null ? current.impairmentAccountId() : request.impairmentAccountId(),
                request.clearingAccountId() == null ? current.clearingAccountId() : request.clearingAccountId(),
                request.disposalGainAccountId() == null ? current.disposalGainAccountId() : request.disposalGainAccountId(),
                request.disposalLossAccountId() == null ? current.disposalLossAccountId() : request.disposalLossAccountId(),
                request.status() == null ? current.status() : request.status(), current.version());
        validateRate(next.residualRate());
        validateAccounts(actorId, ledgerId, next.assetAccountId(), next.accumulatedDepreciationAccountId(),
                next.depreciationExpenseAccountId(), next.impairmentAccountId(), next.clearingAccountId(),
                next.disposalGainAccountId(), next.disposalLossAccountId());
        if (!Set.of("ACTIVE", "INACTIVE").contains(next.status())) {
            throw problem(422, "FIXED_ASSET_CATEGORY_STATUS_INVALID", "Invalid category status", "Status must be ACTIVE or INACTIVE");
        }
        if (!assets.updateCategory(ledgerId, categoryId, next, request.expectedVersion(), actorId)) {
            throw problem(409, "RESOURCE_VERSION_CONFLICT", "Resource version conflict", "The category was changed by another request");
        }
        return category(categoryRow(ledgerId, categoryId));
    }

    @Override
    @Transactional(readOnly = true)
    public FixedAssetResponses.Page listAssets(UUID actorId, UUID ledgerId, UUID periodId, String status,
                                               UUID categoryId, UUID departmentValueId, String search,
                                               int page, int pageSize) {
        requireRole(actorId, ledgerId, READ_ROLES);
        if (page < 1 || pageSize < 1 || pageSize > 500) {
            throw problem(400, "PAGINATION_INVALID", "Invalid pagination", "page must be positive and pageSize must be between 1 and 500");
        }
        UUID selectedPeriod = periodId == null ? currentPeriod(actorId, ledgerId).id() : periodId;
        List<AssetRecord> rows = assets.listAssets(ledgerId, status, categoryId, departmentValueId, search,
                pageSize, (page - 1) * pageSize);
        long total = assets.countAssets(ledgerId, status, categoryId, departmentValueId, search);
        return new FixedAssetResponses.Page(rows.stream().map(row -> asset(actorId, ledgerId, row, selectedPeriod)).toList(),
                page, pageSize, total, (int) Math.ceil((double) total / pageSize));
    }

    @Override
    @Transactional(readOnly = true)
    public FixedAssetResponses.Asset findAsset(UUID actorId, UUID ledgerId, UUID assetId, UUID periodId) {
        requireRole(actorId, ledgerId, READ_ROLES);
        UUID selectedPeriod = periodId == null ? currentPeriod(actorId, ledgerId).id() : periodId;
        return asset(actorId, ledgerId, assetRow(ledgerId, assetId), selectedPeriod);
    }

    @Override
    @Transactional
    public FixedAssetResponses.Asset createAsset(UUID actorId, UUID ledgerId, FixedAssetRequests.AssetCreate request) {
        requireRole(actorId, ledgerId, WRITE_ROLES);
        CategoryRecord category = categoryRow(ledgerId, request.categoryId());
        if (!"ACTIVE".equals(category.status())) {
            throw problem(422, "FIXED_ASSET_CATEGORY_INACTIVE", "Category is inactive", "Choose an active fixed-asset category");
        }
        validateRate(request.residualRate());
        validateAmounts(request.originalCost(), request.residualRate(), request.openingAccumulatedDepreciation(),
                request.impairmentAmount(), request.usefulLifeMonths(), request.openingDepreciatedMonths());
        validateAccounts(actorId, ledgerId,
                request.assetAccountId() == null ? category.assetAccountId() : request.assetAccountId(),
                request.accumulatedDepreciationAccountId() == null ? category.accumulatedDepreciationAccountId() : request.accumulatedDepreciationAccountId(),
                request.depreciationExpenseAccountId() == null ? category.depreciationExpenseAccountId() : request.depreciationExpenseAccountId(),
                request.impairmentAccountId() == null ? category.impairmentAccountId() : request.impairmentAccountId(),
                request.clearingAccountId() == null ? category.clearingAccountId() : request.clearingAccountId(),
                request.disposalGainAccountId() == null ? category.disposalGainAccountId() : request.disposalGainAccountId(),
                request.disposalLossAccountId() == null ? category.disposalLossAccountId() : request.disposalLossAccountId());
        validateAcquisitionVoucher(actorId, ledgerId, request.acquisitionVoucherId());
        validateDepartment(actorId, ledgerId, request.departmentValueId());
        AssetRecord row = new AssetRecord(UUID.randomUUID(), ledgerId, category.id(), category.code(), category.name(),
                request.code().trim(), request.name().trim(), "ACTIVE", request.quantity(), request.serviceDate(),
                money(request.originalCost()), money(request.inputTax()), request.usefulLifeMonths(), scaleRate(request.residualRate()),
                money(request.openingAccumulatedDepreciation()), request.openingDepreciatedMonths(), money(request.impairmentAmount()),
                request.departmentValueId(), request.acquisitionVoucherId(),
                request.assetAccountId() == null ? category.assetAccountId() : request.assetAccountId(),
                request.accumulatedDepreciationAccountId() == null ? category.accumulatedDepreciationAccountId() : request.accumulatedDepreciationAccountId(),
                request.depreciationExpenseAccountId() == null ? category.depreciationExpenseAccountId() : request.depreciationExpenseAccountId(),
                request.impairmentAccountId() == null ? category.impairmentAccountId() : request.impairmentAccountId(),
                request.clearingAccountId() == null ? category.clearingAccountId() : request.clearingAccountId(),
                request.disposalGainAccountId() == null ? category.disposalGainAccountId() : request.disposalGainAccountId(),
                request.disposalLossAccountId() == null ? category.disposalLossAccountId() : request.disposalLossAccountId(),
                null, request.note(), 0);
        try {
            assets.insertAsset(row, actorId);
        } catch (org.springframework.dao.DataIntegrityViolationException exception) {
            throw problem(409, "FIXED_ASSET_CODE_CONFLICT", "Fixed asset code already exists", "The code is already used in this ledger");
        }
        return asset(actorId, ledgerId, assetRow(ledgerId, row.id()), currentPeriod(actorId, ledgerId).id());
    }

    @Override
    @Transactional
    public FixedAssetResponses.Asset updateAsset(UUID actorId, UUID ledgerId, UUID assetId,
                                                 FixedAssetRequests.AssetPatch request) {
        requireRole(actorId, ledgerId, WRITE_ROLES);
        AssetRecord current = assetRow(ledgerId, assetId);
        if ("DISPOSED".equals(current.status())) {
            throw problem(409, "FIXED_ASSET_DISPOSED", "Disposed asset is locked", "A disposed asset cannot be changed");
        }
        boolean accountingChange = request.serviceDate() != null || request.originalCost() != null
                || request.usefulLifeMonths() != null || request.residualRate() != null
                || request.impairmentAmount() != null || request.departmentValueId() != null
                || request.acquisitionVoucherId() != null || request.assetAccountId() != null
                || request.accumulatedDepreciationAccountId() != null || request.depreciationExpenseAccountId() != null
                || request.impairmentAccountId() != null || request.clearingAccountId() != null
                || request.disposalGainAccountId() != null || request.disposalLossAccountId() != null;
        if (accountingChange && (request.changePeriodId() == null || request.reason() == null || request.reason().isBlank())) {
            throw problem(422, "FIXED_ASSET_CHANGE_REASON_REQUIRED", "Change reason is required",
                    "Accounting changes need the current open period and a reason");
        }
        if (accountingChange) {
            validateChangePeriod(actorId, ledgerId, request.changePeriodId());
        }
        if (request.originalCost() != null || request.serviceDate() != null) {
            if (assets.hasAssetUsage(ledgerId, assetId)) {
                throw problem(409, "FIXED_ASSET_ORIGINAL_LOCKED", "Original asset data is locked",
                        "Original cost and service date cannot change after depreciation has been generated");
            }
        }
        AssetRecord next = new AssetRecord(current.id(), current.ledgerId(), current.categoryId(), current.categoryCode(), current.categoryName(),
                current.code(), request.name() == null ? current.name() : request.name().trim(), current.status(),
                request.quantity() == null ? current.quantity() : request.quantity(),
                request.serviceDate() == null ? current.serviceDate() : request.serviceDate(),
                request.originalCost() == null ? current.originalCost() : money(request.originalCost()),
                request.inputTax() == null ? current.inputTax() : money(request.inputTax()),
                request.usefulLifeMonths() == null ? current.usefulLifeMonths() : request.usefulLifeMonths(),
                request.residualRate() == null ? current.residualRate() : scaleRate(request.residualRate()),
                current.openingAccumulatedDepreciation(), current.openingDepreciatedMonths(),
                request.impairmentAmount() == null ? current.impairmentAmount() : money(request.impairmentAmount()),
                request.departmentValueId() == null ? current.departmentValueId() : request.departmentValueId(),
                request.acquisitionVoucherId() == null ? current.acquisitionVoucherId() : request.acquisitionVoucherId(),
                request.assetAccountId() == null ? current.assetAccountId() : request.assetAccountId(),
                request.accumulatedDepreciationAccountId() == null ? current.accumulatedDepreciationAccountId() : request.accumulatedDepreciationAccountId(),
                request.depreciationExpenseAccountId() == null ? current.depreciationExpenseAccountId() : request.depreciationExpenseAccountId(),
                request.impairmentAccountId() == null ? current.impairmentAccountId() : request.impairmentAccountId(),
                request.clearingAccountId() == null ? current.clearingAccountId() : request.clearingAccountId(),
                request.disposalGainAccountId() == null ? current.disposalGainAccountId() : request.disposalGainAccountId(),
                request.disposalLossAccountId() == null ? current.disposalLossAccountId() : request.disposalLossAccountId(),
                current.disposalDate(), request.note() == null ? current.note() : request.note(), current.version() + 1);
        validateRate(next.residualRate());
        validateAmounts(next.originalCost(), next.residualRate(), next.openingAccumulatedDepreciation(), next.impairmentAmount(),
                next.usefulLifeMonths(), next.openingDepreciatedMonths());
        validateAccounts(actorId, ledgerId, next.assetAccountId(), next.accumulatedDepreciationAccountId(),
                next.depreciationExpenseAccountId(), next.impairmentAccountId(), next.clearingAccountId(),
                next.disposalGainAccountId(), next.disposalLossAccountId());
        validateAcquisitionVoucher(actorId, ledgerId, next.acquisitionVoucherId());
        validateDepartment(actorId, ledgerId, next.departmentValueId());
        String beforeData = null;
        String afterData = null;
        if (accountingChange) {
            beforeData = fixedAssetAuditSnapshot(current);
            afterData = fixedAssetAuditSnapshot(next);
        }
        if (!assets.updateAsset(ledgerId, assetId, next, request.expectedVersion(), actorId)) {
            throw problem(409, "RESOURCE_VERSION_CONFLICT", "Resource version conflict", "The asset was changed by another request");
        }
        if (accountingChange) {
            assets.insertChange(ledgerId, assetId, request.changePeriodId(), request.reason().trim(), actorId,
                    beforeData, afterData);
        }
        return asset(actorId, ledgerId, assetRow(ledgerId, assetId), currentPeriod(actorId, ledgerId).id());
    }

    @Override
    @Transactional
    public FixedAssetResponses.Asset copyAsset(UUID actorId, UUID ledgerId, UUID assetId) {
        requireRole(actorId, ledgerId, WRITE_ROLES);
        AssetRecord source = assetRow(ledgerId, assetId);
        String code = (source.code() + "-COPY");
        if (code.length() > 64) code = code.substring(0, 64);
        AssetRecord copy = new AssetRecord(UUID.randomUUID(), ledgerId, source.categoryId(), source.categoryCode(), source.categoryName(),
                code, source.name() + "（复制）", "ACTIVE", source.quantity(), source.serviceDate(), source.originalCost(), source.inputTax(),
                source.usefulLifeMonths(), source.residualRate(), BigDecimal.ZERO, 0, BigDecimal.ZERO, source.departmentValueId(),
                null, source.assetAccountId(), source.accumulatedDepreciationAccountId(), source.depreciationExpenseAccountId(),
                source.impairmentAccountId(), source.clearingAccountId(), source.disposalGainAccountId(), source.disposalLossAccountId(),
                null, source.note(), 0);
        try { assets.insertAsset(copy, actorId); }
        catch (org.springframework.dao.DataIntegrityViolationException exception) {
            throw problem(409, "FIXED_ASSET_CODE_CONFLICT", "Copy code already exists", "Change the copied code before saving");
        }
        return asset(actorId, ledgerId, assetRow(ledgerId, copy.id()), currentPeriod(actorId, ledgerId).id());
    }

    @Override
    @Transactional
    public void deleteAsset(UUID actorId, UUID ledgerId, UUID assetId) {
        requireRole(actorId, ledgerId, WRITE_ROLES);
        assetRow(ledgerId, assetId);
        if (assets.hasAssetUsage(ledgerId, assetId)) {
            throw problem(409, "FIXED_ASSET_IN_USE", "Asset cannot be deleted", "Use disposal for an asset with accounting history");
        }
        assets.softDeleteAsset(ledgerId, assetId, actorId);
    }

    @Override
    @Transactional(readOnly = true)
    public FixedAssetResponses.DepreciationPreview previewDepreciation(UUID actorId, UUID ledgerId, UUID periodId) {
        requireRole(actorId, ledgerId, READ_ROLES);
        LedgerResponses.Period period = period(actorId, ledgerId, periodId);
        List<AssetRecord> rows = assets.depreciationCandidates(ledgerId, periodId);
        List<LineRecord> lines = assets.activeLines(ledgerId, periodId);
        Map<UUID, LineRecord> byAsset = lines.stream().collect(Collectors.toMap(LineRecord::assetId, line -> line, (a, b) -> b));
        DepreciationControls controls = depreciationControls(actorId, ledgerId, rows);
        List<FixedAssetResponses.PreviewLine> preview = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int eligible = 0, completed = 0;
        List<String> blockers = new ArrayList<>();
        for (AssetRecord row : rows) {
            BigDecimal amount = monthly(row, period);
            if (amount.signum() <= 0) continue;
            eligible++;
            if (byAsset.containsKey(row.id())) {
                completed++;
                preview.add(new FixedAssetResponses.PreviewLine(row.id(), row.code(), row.name(), byAsset.get(row.id()).amount(), "COMPLETED", "已生成"));
            } else {
                total = total.add(amount);
                preview.add(new FixedAssetResponses.PreviewLine(row.id(), row.code(), row.name(), amount, "PENDING", "待生成"));
                blockers.addAll(depreciationBlockers(row, controls));
            }
        }
        boolean stale = assets.currentRun(ledgerId, periodId, "MONTH_END")
                .map(run -> {
                    Set<UUID> participatingIds = lines.stream()
                            .filter(line -> line.runId().equals(run.id()))
                            .map(LineRecord::assetId).collect(Collectors.toSet());
                    List<AssetRecord> participatingRows = rows.stream()
                            .filter(row -> participatingIds.contains(row.id())).toList();
                    return !run.inputFingerprint().equals(fingerprint(participatingRows, period));
                }).orElse(false);
        if (stale) blockers.add("本期折旧批次已失效，请重新生成");
        int pending = eligible - completed;
        return new FixedAssetResponses.DepreciationPreview(periodId, period.periodCode(), total, eligible, completed, pending,
                pending == 0 && blockers.isEmpty(), blockers.stream().distinct().toList(), preview);
    }

    @Override
    @Transactional
    public FixedAssetResponses.DepreciationRun generateDepreciation(UUID actorId, UUID ledgerId,
                                                                     FixedAssetRequests.DepreciationAction request) {
        requireRole(actorId, ledgerId, WRITE_ROLES);
        return generate(actorId, ledgerId, request.periodId(), request.reason(), null, 0, null);
    }

    @Override
    @Transactional
    public FixedAssetResponses.DepreciationRun regenerateDepreciation(UUID actorId, UUID ledgerId,
                                                                       FixedAssetRequests.DepreciationAction request) {
        requireRole(actorId, ledgerId, WRITE_ROLES);
        if (request.reason() == null || request.reason().isBlank()) {
            throw problem(422, "FIXED_ASSET_REGENERATION_REASON_REQUIRED", "Regeneration reason is required", "Enter a reason before regenerating");
        }
        assets.lockLedger(ledgerId);
        RunRecord current = assets.currentRun(ledgerId, request.periodId(), "MONTH_END").orElseThrow(() ->
                problem(409, "FIXED_ASSET_RUN_NOT_FOUND", "No depreciation run to regenerate", "Generate the period depreciation first"));
        VoucherResponses.Voucher currentVoucher = vouchers.find(actorId, ledgerId, current.voucherId());
        assets.supersedeLines(ledgerId, current.id());
        assets.supersedeRun(ledgerId, current.id(), null);
        FixedAssetResponses.DepreciationRun replacement = generate(actorId, ledgerId, request.periodId(), request.reason(),
                current.voucherId(), currentVoucher.version(), current.id());
        assets.supersedeRun(ledgerId, current.id(), replacement.id());
        return replacement;
    }

    @Override
    @Transactional(readOnly = true)
    public List<FixedAssetResponses.DepreciationRun> listDepreciationRuns(UUID actorId, UUID ledgerId, UUID periodId) {
        requireRole(actorId, ledgerId, READ_ROLES);
        return assets.listRuns(ledgerId, periodId).stream().map(run -> new FixedAssetResponses.DepreciationRun(
                run.id(), run.periodId(), run.runType(), run.status(), run.voucherId(), run.totalAmount(),
                run.inputFingerprint(), run.createdAt())).toList();
    }

    @Override
    @Transactional
    public FixedAssetResponses.Disposal disposeAsset(UUID actorId, UUID ledgerId, UUID assetId,
                                                     FixedAssetRequests.Disposal request) {
        requireRole(actorId, ledgerId, WRITE_ROLES);
        AssetRecord row = assetRow(ledgerId, assetId);
        if ("DISPOSED".equals(row.status()) || assets.hasDisposal(ledgerId, assetId)) {
            throw problem(409, "FIXED_ASSET_ALREADY_DISPOSED", "Asset is already disposed", "An asset can only be disposed once");
        }
        LedgerResponses.Period period = period(actorId, ledgerId, request.periodId());
        if (!"OPEN".equals(period.status()) || request.disposalDate().isBefore(period.startDate())
                || request.disposalDate().isAfter(period.endDate())) {
            throw problem(422, "FIXED_ASSET_DISPOSAL_PERIOD_INVALID", "Invalid disposal period", "The disposal date must be inside an open period");
        }
        UUID depreciationVoucherId = null;
        if (monthly(row, period).signum() > 0 && !assets.hasActiveLine(ledgerId, assetId, period.id())) {
            FixedAssetResponses.DepreciationRun run = generateDisposalDepreciation(
                    actorId, ledgerId, row, period, request.disposalDate());
            depreciationVoucherId = run.voucherId();
        }
        String baseCurrency = ledgers.findLedger(actorId, ledgerId).baseCurrency();
        BigDecimal accumulated = row.openingAccumulatedDepreciation()
                .add(assets.depreciationBefore(ledgerId, assetId, period.id()).amount())
                .add(assets.periodDepreciation(ledgerId, assetId, period.id()));
        BigDecimal residual = residual(row);
        BigDecimal carrying = row.originalCost().subtract(accumulated).subtract(row.impairmentAmount()).setScale(2, RoundingMode.HALF_UP);
        List<VoucherRequests.Line> transfer = new ArrayList<>();
        addLine(transfer, row.accumulatedDepreciationAccountId(), "DEBIT", accumulated, baseCurrency, "结转累计折旧");
        addLine(transfer, row.impairmentAccountId(), "DEBIT", row.impairmentAmount(), baseCurrency, "结转减值准备");
        addLine(transfer, row.clearingAccountId(), "DEBIT", carrying, baseCurrency, "转入固定资产清理");
        addLine(transfer, row.assetAccountId(), "CREDIT", row.originalCost(), baseCurrency, "结转固定资产原值");
        VoucherResponses.Voucher transferVoucher = createVoucher(actorId, ledgerId, period, "FA-CLEAR-" + shortId(assetId),
                "固定资产清理结转：" + row.code(), transfer, "FIXED_ASSET_DISPOSAL", assetId);

        List<VoucherRequests.Line> settlement = new ArrayList<>();
        BigDecimal proceedsWithTax = request.proceeds().add(request.outputTax());
        addLine(settlement, request.receiptAccountId(), "DEBIT", proceedsWithTax, baseCurrency, "收到处置款");
        addLine(settlement, row.clearingAccountId(), "CREDIT", request.proceeds(), baseCurrency, "处置收入结转");
        addLine(settlement, request.outputTaxAccountId(), "CREDIT", request.outputTax(), baseCurrency, "处置销项税");
        addLine(settlement, row.clearingAccountId(), "DEBIT", request.clearingCost(), baseCurrency, "处置清理费用");
        addLine(settlement, request.inputTaxAccountId(), "DEBIT", request.clearingInputTax(), baseCurrency, "清理费用进项税");
        addLine(settlement, request.paymentAccountId(), "CREDIT", request.clearingCost().add(request.clearingInputTax()), baseCurrency, "支付清理费用");
        BigDecimal gainOrLoss = request.proceeds().subtract(carrying).subtract(request.clearingCost()).setScale(2, RoundingMode.HALF_UP);
        if (gainOrLoss.signum() >= 0) {
            addLine(settlement, row.clearingAccountId(), "DEBIT", gainOrLoss, baseCurrency, "结转处置收益");
            addLine(settlement, row.disposalGainAccountId(), "CREDIT", gainOrLoss, baseCurrency, "处置收益");
        } else {
            addLine(settlement, row.disposalLossAccountId(), "DEBIT", gainOrLoss.abs(), baseCurrency, "处置损失");
            addLine(settlement, row.clearingAccountId(), "CREDIT", gainOrLoss.abs(), baseCurrency, "结转处置损失");
        }
        VoucherResponses.Voucher settlementVoucher = createVoucher(actorId, ledgerId, period, "FA-SETTLE-" + shortId(assetId),
                "固定资产处置结算：" + row.code(), settlement, "FIXED_ASSET_DISPOSAL", assetId);
        AssetRecord disposed = new AssetRecord(row.id(), row.ledgerId(), row.categoryId(), row.categoryCode(), row.categoryName(),
                row.code(), row.name(), "DISPOSED", row.quantity(), row.serviceDate(), row.originalCost(), row.inputTax(),
                row.usefulLifeMonths(), row.residualRate(), row.openingAccumulatedDepreciation(), row.openingDepreciatedMonths(),
                row.impairmentAmount(), row.departmentValueId(), row.acquisitionVoucherId(), row.assetAccountId(),
                row.accumulatedDepreciationAccountId(), row.depreciationExpenseAccountId(), row.impairmentAccountId(),
                row.clearingAccountId(), row.disposalGainAccountId(), row.disposalLossAccountId(), request.disposalDate(), row.note(), row.version());
        if (!assets.updateAsset(ledgerId, assetId, disposed, row.version(), actorId)) {
            throw problem(409, "RESOURCE_VERSION_CONFLICT", "Resource version conflict", "The asset was changed by another request");
        }
        FixedAssetRepository.DisposalRecord disposal = new FixedAssetRepository.DisposalRecord(UUID.randomUUID(), ledgerId, assetId,
                period.id(), request.disposalDate(), request.reason().trim(), request.proceeds(), request.outputTax(),
                request.clearingCost(), request.clearingInputTax(), request.receiptAccountId(), request.paymentAccountId(),
                request.outputTaxAccountId(), request.inputTaxAccountId(), depreciationVoucherId, transferVoucher.id(),
                settlementVoucher.id(), carrying, gainOrLoss);
        assets.insertDisposal(disposal, actorId);
        return new FixedAssetResponses.Disposal(disposal.id(), assetId, period.id(), depreciationVoucherId,
                transferVoucher.id(), settlementVoucher.id(), carrying, gainOrLoss);
    }

    @Override
    @Transactional
    public FixedAssetResponses.Asset cancelDisposal(UUID actorId, UUID ledgerId, UUID assetId,
                                                    long expectedVersion, String reason) {
        requireRole(actorId, ledgerId, WRITE_ROLES);
        if (reason == null || reason.isBlank()) {
            throw problem(422, "FIXED_ASSET_DISPOSAL_CANCELLATION_REASON_REQUIRED",
                    "Cancellation reason is required", "Enter a reason before cancelling the disposal");
        }
        assets.lockLedger(ledgerId);
        AssetRecord row = assetRow(ledgerId, assetId);
        FixedAssetRepository.ActiveDisposalRecord disposal = assets.activeDisposal(ledgerId, assetId)
                .orElseThrow(this::disposalIntegrityProblem);
        LedgerResponses.Period disposalPeriod = period(actorId, ledgerId, disposal.periodId());
        if (!"OPEN".equals(disposalPeriod.status())) {
            throw problem(409, "FIXED_ASSET_DISPOSAL_PERIOD_CLOSED", "Disposal period is closed",
                    "Reopen the disposal period before cancelling the disposal");
        }
        if (!"DISPOSED".equals(row.status()) || !disposal.disposalDate().equals(row.disposalDate())) {
            throw disposalIntegrityProblem();
        }
        if (row.version() != expectedVersion) {
            throw problem(409, "RESOURCE_VERSION_CONFLICT", "Resource version conflict",
                    "The asset was changed by another request");
        }

        VoucherResponses.Voucher transfer = ownedVoucher(actorId, ledgerId, disposal.transferVoucherId(),
                disposal.periodId(), "FIXED_ASSET_DISPOSAL", assetId, disposalIntegrityProblem());
        VoucherResponses.Voucher settlement = ownedVoucher(actorId, ledgerId, disposal.settlementVoucherId(),
                disposal.periodId(), "FIXED_ASSET_DISPOSAL", assetId, disposalIntegrityProblem());
        if (transfer.id().equals(settlement.id())) throw disposalIntegrityProblem();
        RunRecord depreciationRun = null;
        VoucherResponses.Voucher depreciation = null;
        List<LineRecord> depreciationLines = List.of();
        RunRecord activeDisposalRun = assets.activeRunForAsset(
                ledgerId, assetId, disposal.periodId(), "DISPOSAL").orElse(null);
        if (disposal.depreciationVoucherId() != null) {
            depreciationRun = assets.findRunByVoucher(ledgerId, disposal.depreciationVoucherId())
                    .orElseThrow(this::disposalIntegrityProblem);
            List<LineRecord> lines = assets.linesForRun(ledgerId, depreciationRun.id());
            depreciationLines = lines;
            if (!"DISPOSAL".equals(depreciationRun.runType()) || !"POSTED".equals(depreciationRun.status())
                    || !depreciationRun.periodId().equals(disposal.periodId()) || lines.size() != 1
                    || !lines.get(0).assetId().equals(assetId)
                    || !"ACTIVE".equals(lines.get(0).status())) {
                throw disposalIntegrityProblem();
            }
            if (activeDisposalRun == null || !activeDisposalRun.id().equals(depreciationRun.id())) {
                throw disposalIntegrityProblem();
            }
            depreciation = ownedVoucher(actorId, ledgerId, disposal.depreciationVoucherId(),
                    disposal.periodId(), "FIXED_ASSET_DEPRECIATION", depreciationRun.id(),
                    disposalIntegrityProblem());
            if (depreciation.id().equals(transfer.id()) || depreciation.id().equals(settlement.id())
                    || depreciationRun.totalAmount().compareTo(lines.get(0).amount()) != 0
                    || depreciation.lines().stream()
                    .noneMatch(line -> line.id().equals(lines.get(0).voucherLineId()))) {
                throw disposalIntegrityProblem();
            }
        } else if (activeDisposalRun != null) {
            throw disposalIntegrityProblem();
        }

        String cancellationReason = reason.trim();
        if (!assets.cancelDisposal(ledgerId, disposal.id(), actorId, cancellationReason)) {
            throw disposalIntegrityProblem();
        }
        if (depreciationRun != null) {
            if (!assets.cancelRun(ledgerId, depreciationRun.id(), "DISPOSAL", depreciation.id(), actorId,
                    cancellationReason)) {
                throw disposalIntegrityProblem();
            }
            if (assets.cancelRunLines(ledgerId, depreciationRun.id()) != depreciationLines.size()) {
                throw disposalIntegrityProblem();
            }
            generatedVouchers.deleteGenerated(actorId, ledgerId, depreciation.id(),
                    "FIXED_ASSET_DEPRECIATION", depreciationRun.id(), depreciation.version(), cancellationReason);
        }
        generatedVouchers.deleteGenerated(actorId, ledgerId, transfer.id(), "FIXED_ASSET_DISPOSAL",
                assetId, transfer.version(), cancellationReason);
        generatedVouchers.deleteGenerated(actorId, ledgerId, settlement.id(), "FIXED_ASSET_DISPOSAL",
                assetId, settlement.version(), cancellationReason);

        AssetRecord restored = new AssetRecord(row.id(), row.ledgerId(), row.categoryId(), row.categoryCode(),
                row.categoryName(), row.code(), row.name(), "ACTIVE", row.quantity(), row.serviceDate(),
                row.originalCost(), row.inputTax(), row.usefulLifeMonths(), row.residualRate(),
                row.openingAccumulatedDepreciation(), row.openingDepreciatedMonths(), row.impairmentAmount(),
                row.departmentValueId(), row.acquisitionVoucherId(), row.assetAccountId(),
                row.accumulatedDepreciationAccountId(), row.depreciationExpenseAccountId(), row.impairmentAccountId(),
                row.clearingAccountId(), row.disposalGainAccountId(), row.disposalLossAccountId(), null, row.note(),
                row.version() + 1);
        String beforeData = fixedAssetAuditSnapshot(row);
        String afterData = fixedAssetAuditSnapshot(restored);
        if (!assets.updateAsset(ledgerId, assetId, restored, expectedVersion, actorId)) {
            throw problem(409, "RESOURCE_VERSION_CONFLICT", "Resource version conflict",
                    "The asset was changed by another request");
        }
        assets.insertChange(ledgerId, assetId, disposal.periodId(), cancellationReason, actorId,
                beforeData, afterData);
        return asset(actorId, ledgerId, assetRow(ledgerId, assetId), disposal.periodId());
    }

    @Override
    @Transactional
    public FixedAssetResponses.DepreciationRun cancelDepreciation(UUID actorId, UUID ledgerId, UUID runId,
                                                                  String reason) {
        requireRole(actorId, ledgerId, WRITE_ROLES);
        if (reason == null || reason.isBlank()) {
            throw problem(422, "FIXED_ASSET_DEPRECIATION_CANCELLATION_REASON_REQUIRED",
                    "Cancellation reason is required", "Enter a reason before cancelling depreciation");
        }
        assets.lockLedger(ledgerId);
        RunRecord run = assets.findRun(ledgerId, runId)
                .orElseThrow(() -> problem(404, "FIXED_ASSET_RUN_NOT_FOUND", "Depreciation run not found",
                        "The depreciation run is not available to this ledger"));
        if ("DISPOSAL".equals(run.runType())) {
            throw problem(409, "FIXED_ASSET_DISPOSAL_REVERSAL_REQUIRED", "Whole disposal reversal is required",
                    "Cancel the complete disposal instead of deleting its depreciation source alone");
        }
        if (!"POSTED".equals(run.status()) || !"MONTH_END".equals(run.runType())) {
            throw problem(409, "FIXED_ASSET_RUN_NOT_CANCELLABLE", "Depreciation run cannot be cancelled",
                    "Only a complete posted month-end depreciation run can be cancelled");
        }
        LedgerResponses.Period runPeriod = period(actorId, ledgerId, run.periodId());
        if (!"OPEN".equals(runPeriod.status())) {
            throw problem(409, "FIXED_ASSET_PERIOD_CLOSED", "Period is closed",
                    "Reopen the period before cancelling depreciation");
        }
        List<LineRecord> lines = assets.linesForRun(ledgerId, run.id());
        if (lines.isEmpty() || lines.stream().anyMatch(line -> !"ACTIVE".equals(line.status()))) {
            throw problem(409, "FIXED_ASSET_DEPRECIATION_DATA_INTEGRITY", "Depreciation data is incomplete",
                    "The run, lines and generated voucher must match before cancellation");
        }
        ApiProblemException integrityProblem = problem(409, "FIXED_ASSET_DEPRECIATION_DATA_INTEGRITY",
                "Depreciation data is incomplete",
                "The run, lines and generated voucher must match before cancellation");
        VoucherResponses.Voucher voucher = ownedVoucher(actorId, ledgerId, run.voucherId(), run.periodId(),
                "FIXED_ASSET_DEPRECIATION", run.id(), integrityProblem);
        BigDecimal lineTotal = lines.stream().map(LineRecord::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        Set<UUID> voucherLineIds = voucher.lines().stream().map(VoucherResponses.Line::id).collect(Collectors.toSet());
        if (run.totalAmount().compareTo(lineTotal) != 0
                || lines.stream().anyMatch(line -> !voucherLineIds.contains(line.voucherLineId()))) {
            throw integrityProblem;
        }
        String cancellationReason = reason.trim();
        if (!assets.cancelRun(ledgerId, run.id(), "MONTH_END", voucher.id(), actorId, cancellationReason)) {
            throw integrityProblem;
        }
        if (assets.cancelRunLines(ledgerId, run.id()) != lines.size()) {
            throw integrityProblem;
        }
        generatedVouchers.deleteGenerated(actorId, ledgerId, voucher.id(), "FIXED_ASSET_DEPRECIATION",
                run.id(), voucher.version(), cancellationReason);
        return new FixedAssetResponses.DepreciationRun(run.id(), run.periodId(), run.runType(), "CANCELLED",
                null, run.totalAmount(), run.inputFingerprint(), run.createdAt());
    }

    private String fixedAssetAuditSnapshot(Object value) {
        return auditSnapshots.serialize(value, "FIXED_ASSET_AUDIT_SNAPSHOT_FAILED",
                "Fixed-asset audit snapshot failed",
                "The fixed-asset change could not be serialized");
    }

    @Override
    @Transactional(readOnly = true)
    public boolean periodComplete(UUID actorId, UUID ledgerId, UUID periodId) {
        return periodBlockers(actorId, ledgerId, periodId).isEmpty();
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> periodBlockers(UUID actorId, UUID ledgerId, UUID periodId) {
        FixedAssetResponses.DepreciationPreview preview = previewDepreciation(actorId, ledgerId, periodId);
        List<String> blockers = new ArrayList<>(preview.blockers());
        if (preview.pendingCount() > 0) blockers.add("尚有 " + preview.pendingCount() + " 项资产未计提折旧");
        for (RunRecord run : assets.listRuns(ledgerId, periodId)) {
            if ("POSTED".equals(run.status()) && !"POSTED".equals(vouchers.find(actorId, ledgerId, run.voucherId()).status())) {
                blockers.add("折旧凭证未记账：" + run.voucherId());
            }
        }
        return blockers.stream().distinct().toList();
    }

    @Override
    public byte[] importTemplate(UUID actorId, UUID ledgerId) {
        requireRole(actorId, ledgerId, READ_ROLES);
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("固定资产导入");
            String[] headers = {"类别编码", "资产编码", "资产名称", "数量", "启用日期", "原值", "进项税额",
                    "使用期限（月）", "残值率（%）", "期初累计折旧", "期初已折旧月数", "期初减值", "备注"};
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
                sheet.setColumnWidth(i, 18 * 256);
            }
            Row example = sheet.createRow(1);
            example.createCell(0).setCellValue("EQUIPMENT");
            example.createCell(1).setCellValue("FA-0001");
            example.createCell(2).setCellValue("示例设备");
            example.createCell(3).setCellValue(1);
            example.createCell(4).setCellValue("2026-01-01");
            example.createCell(5).setCellValue(10000);
            example.createCell(6).setCellValue(0);
            example.createCell(7).setCellValue(36);
            example.createCell(8).setCellValue(5);
            example.createCell(9).setCellValue(0);
            example.createCell(10).setCellValue(0);
            example.createCell(11).setCellValue(0);
            example.createCell(12).setCellValue("请删除示例行");
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw problem(500, "FIXED_ASSET_TEMPLATE_FAILED", "Template generation failed", "The import template could not be generated");
        }
    }

    @Override
    @Transactional
    public FixedAssetResponses.ImportResult importAssets(UUID actorId, UUID ledgerId, MultipartFile file) {
        requireRole(actorId, ledgerId, WRITE_ROLES);
        if (file == null || file.isEmpty() || file.getSize() > 10L * 1024 * 1024) {
            throw problem(413, "FIXED_ASSET_IMPORT_TOO_LARGE", "Import file is too large", "Use a non-empty .xlsx file up to 10 MiB");
        }
        List<String> errors = new ArrayList<>();
        List<FixedAssetRequests.AssetCreate> rows = new ArrayList<>();
        Set<String> codes = new java.util.HashSet<>();
        Map<String, CategoryRecord> byCategory = assets.listCategories(ledgerId).stream()
                .collect(Collectors.toMap(CategoryRecord::code, item -> item, (a, b) -> a));
        DataFormatter formatter = new DataFormatter();
        try (var input = file.getInputStream(); XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            var sheet = workbook.getSheetAt(0);
            if (sheet.getLastRowNum() > 10000) throw problem(413, "FIXED_ASSET_IMPORT_TOO_MANY_ROWS", "Too many rows", "An import may contain at most 10,000 rows");
            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null || row.getCell(0) == null || formatter.formatCellValue(row.getCell(0)).isBlank()) continue;
                int line = rowIndex + 1;
                try {
                    for (Cell cell : row) if (cell.getCellType() == CellType.FORMULA) throw new IllegalArgumentException("公式单元格不允许导入");
                    String categoryCode = text(row, 0, formatter); String code = text(row, 1, formatter); String name = text(row, 2, formatter);
                    CategoryRecord category = byCategory.get(categoryCode);
                    if (category == null) throw new IllegalArgumentException("类别编码不存在");
                    if (!codes.add(code) || assets.assetCodeExists(ledgerId, code)) throw new IllegalArgumentException("资产编码重复");
                    LocalDate serviceDate = parseDate(row.getCell(4), formatter);
                    BigDecimal quantity = decimal(row, 3, formatter, "数量"); BigDecimal cost = decimal(row, 5, formatter, "原值");
                    BigDecimal inputTax = decimal(row, 6, formatter, "进项税额"); int life = decimal(row, 7, formatter, "使用期限").intValueExact();
                    BigDecimal residualRate = decimal(row, 8, formatter, "残值率"); BigDecimal opening = decimal(row, 9, formatter, "期初累计折旧");
                    int openingMonths = decimal(row, 10, formatter, "期初已折旧月数").intValueExact(); BigDecimal impairment = decimal(row, 11, formatter, "期初减值");
                    rows.add(new FixedAssetRequests.AssetCreate(category.id(), code, name, quantity, serviceDate, cost, inputTax, life,
                            residualRate, opening, openingMonths, impairment, null, null, null, null, null, null, null, null, null,
                            textOrNull(row, 12, formatter)));
                } catch (Exception exception) { errors.add("第" + line + "行：" + exception.getMessage()); }
            }
        } catch (IOException exception) {
            throw problem(422, "FIXED_ASSET_IMPORT_INVALID", "Invalid import file", "Only a readable .xlsx file is accepted");
        }
        if (!errors.isEmpty()) return new FixedAssetResponses.ImportResult(rows.size() + errors.size(), errors.size(), false, errors);
        for (FixedAssetRequests.AssetCreate row : rows) createAsset(actorId, ledgerId, row);
        return new FixedAssetResponses.ImportResult(rows.size(), 0, true, List.of());
    }

    private String text(Row row, int index, DataFormatter formatter) {
        String value = textOrNull(row, index, formatter);
        if (value == null) throw new IllegalArgumentException("必填字段为空");
        return value;
    }

    private String textOrNull(Row row, int index, DataFormatter formatter) {
        Cell cell = row.getCell(index);
        String value = cell == null ? null : formatter.formatCellValue(cell).trim();
        return value == null || value.isBlank() ? null : value;
    }

    private BigDecimal decimal(Row row, int index, DataFormatter formatter, String label) {
        String value = text(row, index, formatter);
        try { return new BigDecimal(value.replace(",", "")); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException(label + "不是有效数字"); }
    }

    private LocalDate parseDate(Cell cell, DataFormatter formatter) {
        if (cell == null) throw new IllegalArgumentException("启用日期为空");
        if (DateUtil.isCellDateFormatted(cell)) return cell.getLocalDateTimeCellValue().toLocalDate();
        try { return LocalDate.parse(formatter.formatCellValue(cell).trim()); }
        catch (Exception exception) { throw new IllegalArgumentException("启用日期应为 YYYY-MM-DD"); }
    }

    private FixedAssetResponses.DepreciationRun generateDisposalDepreciation(
            UUID actorId, UUID ledgerId, AssetRecord row, LedgerResponses.Period period,
            LocalDate disposalDate) {
        BigDecimal amount = monthly(row, period);
        DepreciationControls controls = depreciationControls(actorId, ledgerId, List.of(row));
        List<String> blockers = depreciationBlockers(row, controls);
        if (!blockers.isEmpty()) {
            throw problem(422, "FIXED_ASSET_DEPRECIATION_BLOCKED", "Depreciation is blocked",
                    String.join("; ", blockers));
        }
        UUID runId = UUID.randomUUID();
        String currency = ledgers.findLedger(actorId, ledgerId).baseCurrency();
        UUID expenseDepartment = controls.departmentValueId(
                row.depreciationExpenseAccountId(), row.departmentValueId());
        UUID accumulatedDepartment = controls.departmentValueId(
                row.accumulatedDepreciationAccountId(), row.departmentValueId());
        List<VoucherRequests.Line> voucherLines = List.of(
                new VoucherRequests.Line(row.depreciationExpenseAccountId(), "DEBIT", currency, amount,
                        BigDecimal.ONE, "处置当月补提固定资产折旧", null, null, null,
                        controls.dimensions(row.depreciationExpenseAccountId(), expenseDepartment)),
                new VoucherRequests.Line(row.accumulatedDepreciationAccountId(), "CREDIT", currency, amount,
                        BigDecimal.ONE, "处置当月补提固定资产折旧", null, null, null,
                        controls.dimensions(row.accumulatedDepreciationAccountId(), accumulatedDepartment)));
        String number = "ZJ-D-" + period.periodCode().replace("-", "") + "-" + shortId(runId);
        VoucherResponses.Voucher voucher = createVoucher(actorId, ledgerId, period, number,
                "处置当月补提固定资产折旧：" + row.code(), voucherLines,
                "FIXED_ASSET_DEPRECIATION", runId);
        RunRecord run = new RunRecord(runId, ledgerId, period.id(), "DISPOSAL", "POSTED", voucher.id(),
                disposalFingerprint(row, period, disposalDate, amount), amount, "处置向导自动补提折旧", null,
                java.time.OffsetDateTime.now());
        assets.insertRun(run, actorId);
        UUID voucherLineId = voucher.lines().stream()
                .filter(line -> line.accountId().equals(row.depreciationExpenseAccountId())
                        && "DEBIT".equals(line.side()))
                .map(VoucherResponses.Line::id).findFirst().orElseThrow(this::disposalIntegrityProblem);
        assets.insertLine(new LineRecord(UUID.randomUUID(), ledgerId, runId, row.id(), period.id(), amount,
                row.depreciationExpenseAccountId(), row.accumulatedDepreciationAccountId(), row.departmentValueId(),
                voucherLineId, "ACTIVE"));
        return new FixedAssetResponses.DepreciationRun(run.id(), run.periodId(), run.runType(), run.status(),
                run.voucherId(), run.totalAmount(), run.inputFingerprint(), run.createdAt());
    }

    private FixedAssetResponses.DepreciationRun generate(UUID actorId, UUID ledgerId, UUID periodId,
                                                         String reason, UUID replacingVoucherId,
                                                         long expectedVoucherVersion, UUID expectedSourceId) {
        LedgerResponses.Period period = period(actorId, ledgerId, periodId);
        if (!"OPEN".equals(period.status())) throw problem(409, "FIXED_ASSET_PERIOD_CLOSED", "Period is closed", "Open the period before generating depreciation");
        FixedAssetResponses.DepreciationPreview preview = previewDepreciation(actorId, ledgerId, periodId);
        if (!preview.blockers().isEmpty()) throw problem(422, "FIXED_ASSET_DEPRECIATION_BLOCKED", "Depreciation is blocked", String.join("; ", preview.blockers()));
        if (preview.pendingCount() == 0) {
            return assets.currentRun(ledgerId, periodId, "MONTH_END")
                    .map(run -> new FixedAssetResponses.DepreciationRun(run.id(), run.periodId(), run.runType(), run.status(), run.voucherId(), run.totalAmount(), run.inputFingerprint(), run.createdAt()))
                    .orElseThrow(() -> problem(409, "FIXED_ASSET_NO_DEPRECIATION", "No depreciation is due", "There is no asset to depreciate in this period"));
        }
        List<AssetRecord> rows = assets.depreciationCandidates(ledgerId, periodId);
        Map<UUID, LineRecord> existing = assets.activeLines(ledgerId, periodId).stream()
                .collect(Collectors.toMap(LineRecord::assetId, line -> line, (a, b) -> b));
        DepreciationControls controls = depreciationControls(actorId, ledgerId, rows);
        Map<VoucherGroup, BigDecimal> grouped = new LinkedHashMap<>();
        Map<UUID, BigDecimal> amounts = new HashMap<>();
        for (AssetRecord row : rows) {
            if (existing.containsKey(row.id())) continue;
            BigDecimal amount = monthly(row, period);
            if (amount.signum() <= 0) continue;
            amounts.put(row.id(), amount);
            grouped.merge(new VoucherGroup(row.depreciationExpenseAccountId(),
                    controls.departmentValueId(row.depreciationExpenseAccountId(), row.departmentValueId()), "DEBIT"), amount, BigDecimal::add);
            grouped.merge(new VoucherGroup(row.accumulatedDepreciationAccountId(),
                    controls.departmentValueId(row.accumulatedDepreciationAccountId(), row.departmentValueId()), "CREDIT"), amount, BigDecimal::add);
        }
        if (grouped.isEmpty()) throw problem(409, "FIXED_ASSET_NO_DEPRECIATION", "No depreciation is due", "There is no asset to depreciate in this period");
        List<VoucherRequests.Line> voucherLines = new ArrayList<>();
        for (Map.Entry<VoucherGroup, BigDecimal> entry : grouped.entrySet()) {
            VoucherGroup group = entry.getKey();
            List<VoucherRequests.Dimension> dimensions = controls.dimensions(group.accountId(), group.departmentValueId());
            voucherLines.add(new VoucherRequests.Line(group.accountId(), group.side(), ledgers.findLedger(actorId, ledgerId).baseCurrency(),
                    entry.getValue(), BigDecimal.ONE, "计提固定资产折旧", null, null, null, dimensions));
        }
        UUID runId = UUID.randomUUID();
        VoucherResponses.Voucher voucher;
        if (replacingVoucherId == null) {
            String number = "ZJ-" + period.periodCode().replace("-", "") + "-R" + (assets.listRuns(ledgerId, periodId).size() + 1);
            voucher = createVoucher(actorId, ledgerId, period, number, "计提固定资产折旧", voucherLines,
                    "FIXED_ASSET_DEPRECIATION", runId);
        } else {
            VoucherResponses.Voucher currentVoucher = vouchers.find(actorId, ledgerId, replacingVoucherId);
            voucher = vouchers.replaceGenerated(actorId, ledgerId, replacingVoucherId,
                    new VoucherRequests.Update(expectedVoucherVersion, period.id(), period.endDate(),
                            currentVoucher.voucherType(), currentVoucher.voucherNumber(), "计提固定资产折旧", voucherLines),
                    "FIXED_ASSET_DEPRECIATION", expectedSourceId, runId);
        }
        Set<UUID> participatingIds = amounts.keySet();
        String fingerprint = fingerprint(rows.stream()
                .filter(row -> participatingIds.contains(row.id())).toList(), period);
        RunRecord run = new RunRecord(runId, ledgerId, periodId, "MONTH_END", "POSTED", voucher.id(), fingerprint,
                amounts.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add), reason, null, java.time.OffsetDateTime.now());
        assets.insertRun(run, actorId);
        Map<VoucherGroup, UUID> voucherLineIds = new HashMap<>();
        for (VoucherResponses.Line line : voucher.lines()) {
            UUID department = line.dimensions().isEmpty() ? null : line.dimensions().get(0).dimensionValueId();
            voucherLineIds.put(new VoucherGroup(line.accountId(), department,
                    line.side()), line.id());
        }
        for (AssetRecord row : rows) {
            BigDecimal amount = amounts.get(row.id());
            if (amount == null) continue;
            UUID lineId = voucherLineIds.get(new VoucherGroup(row.depreciationExpenseAccountId(),
                    controls.departmentValueId(row.depreciationExpenseAccountId(), row.departmentValueId()), "DEBIT"));
            assets.insertLine(new LineRecord(UUID.randomUUID(), ledgerId, runId, row.id(), periodId, amount,
                    row.depreciationExpenseAccountId(), row.accumulatedDepreciationAccountId(), row.departmentValueId(), lineId, "ACTIVE"));
        }
        return new FixedAssetResponses.DepreciationRun(run.id(), run.periodId(), run.runType(), run.status(), run.voucherId(),
                run.totalAmount(), run.inputFingerprint(), run.createdAt());
    }

    private VoucherResponses.Voucher createVoucher(UUID actorId, UUID ledgerId, LedgerResponses.Period period,
                                                   String number, String summary, List<VoucherRequests.Line> lines,
                                                   String sourceType, UUID sourceId) {
        return vouchers.createGenerated(actorId, ledgerId,
                new VoucherRequests.Create(period.id(), period.endDate(), "记", number, summary, lines),
                "fixed-asset:" + ledgerId + ":" + number, sourceType, sourceId);
    }

    private void addLine(List<VoucherRequests.Line> lines, UUID accountId, String side, BigDecimal amount,
                         String currency, String summary) {
        if (accountId != null && amount != null && amount.signum() > 0) {
            lines.add(new VoucherRequests.Line(accountId, side, currency, amount, BigDecimal.ONE, summary));
        }
    }

    private BigDecimal monthly(AssetRecord row, LedgerResponses.Period period) {
        FixedAssetRepository.DepreciationHistory history =
                assets.depreciationBefore(row.ledgerId(), row.id(), period.id());
        BigDecimal currentAccum = row.openingAccumulatedDepreciation().add(history.amount());
        int depreciatedMonths = row.openingDepreciatedMonths() + history.periods();
        return FixedAssetCalculation.monthly(new FixedAssetCalculation.Asset(
                row.serviceDate(), row.originalCost(), residual(row), row.usefulLifeMonths(),
                row.openingAccumulatedDepreciation(), row.openingDepreciatedMonths(), currentAccum,
                depreciatedMonths, row.disposalDate(), row.impairmentAmount()), period.endDate());
    }

    private FixedAssetResponses.Asset asset(UUID actorId, UUID ledgerId, AssetRecord row, UUID periodId) {
        LedgerResponses.Period period = period(actorId, ledgerId, periodId);
        BigDecimal before = row.openingAccumulatedDepreciation()
                .add(assets.depreciationBefore(ledgerId, row.id(), period.id()).amount());
        BigDecimal current = assets.periodDepreciation(ledgerId, row.id(), period.id());
        BigDecimal ending = before.add(current);
        BigDecimal residual = residual(row);
        return new FixedAssetResponses.Asset(row.id(), row.ledgerId(), row.categoryId(), row.categoryCode(), row.categoryName(),
                row.code(), row.name(), row.status(), row.quantity(), row.serviceDate(), row.originalCost(), row.inputTax(),
                row.usefulLifeMonths(), row.residualRate(), residual, row.openingAccumulatedDepreciation(), row.openingDepreciatedMonths(),
                row.impairmentAmount(), monthly(row, period), current, ending,
                row.originalCost().subtract(before).subtract(row.impairmentAmount()).setScale(2, RoundingMode.HALF_UP),
                row.originalCost().subtract(ending).subtract(row.impairmentAmount()).setScale(2, RoundingMode.HALF_UP),
                row.departmentValueId(), row.acquisitionVoucherId(), row.assetAccountId(), row.accumulatedDepreciationAccountId(),
                row.depreciationExpenseAccountId(), row.impairmentAccountId(), row.clearingAccountId(), row.disposalGainAccountId(),
                row.disposalLossAccountId(), row.disposalDate(), row.note(), row.version());
    }

    private BigDecimal residual(AssetRecord row) { return row.originalCost().multiply(row.residualRate()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP); }

    private LedgerResponses.Period currentPeriod(UUID actorId, UUID ledgerId) {
        return ledgers.listPeriods(actorId, ledgerId).stream().filter(p -> "OPEN".equals(p.status())).findFirst()
                .orElseThrow(() -> problem(409, "ACCOUNTING_PERIOD_NOT_OPEN", "No open period", "Create or reopen an accounting period"));
    }

    private LedgerResponses.Period period(UUID actorId, UUID ledgerId, UUID periodId) {
        return ledgers.listPeriods(actorId, ledgerId).stream().filter(p -> p.id().equals(periodId)).findFirst()
                .orElseThrow(() -> problem(404, "PERIOD_NOT_FOUND", "Period not found", "The period is not available to this ledger"));
    }

    private void validateChangePeriod(UUID actorId, UUID ledgerId, UUID changePeriodId) {
        LedgerResponses.Period changePeriod = period(actorId, ledgerId, changePeriodId);
        if (!"OPEN".equals(changePeriod.status())) {
            throw problem(409, "FIXED_ASSET_CHANGE_PERIOD_CLOSED", "Change period is closed",
                    "Reopen the period before changing accounting parameters");
        }
        LedgerResponses.Period current = currentPeriod(actorId, ledgerId);
        if (!current.id().equals(changePeriod.id())) {
            boolean past = changePeriod.startDate().isBefore(current.startDate());
            throw problem(422, past ? "FIXED_ASSET_CHANGE_PERIOD_PAST" : "FIXED_ASSET_CHANGE_PERIOD_FUTURE",
                    past ? "Past change period is not allowed" : "Future change period is not allowed",
                    "Accounting parameters can only change in the current open period");
        }
    }

    private VoucherResponses.Voucher ownedVoucher(UUID actorId, UUID ledgerId, UUID voucherId, UUID periodId,
                                                   String sourceType, UUID sourceId,
                                                   ApiProblemException integrityProblem) {
        if (voucherId == null) throw integrityProblem;
        VoucherResponses.Voucher voucher;
        try {
            voucher = vouchers.find(actorId, ledgerId, voucherId);
        } catch (RuntimeException exception) {
            throw integrityProblem;
        }
        if (!periodId.equals(voucher.periodId()) || !sourceType.equals(voucher.sourceType())
                || !sourceId.equals(voucher.sourceId())) {
            throw integrityProblem;
        }
        return voucher;
    }

    private ApiProblemException disposalIntegrityProblem() {
        return problem(409, "FIXED_ASSET_DISPOSAL_DATA_INTEGRITY", "Disposal data is incomplete",
                "The active disposal, depreciation, transfer and settlement sources must all match");
    }

    private CategoryRecord categoryRow(UUID ledgerId, UUID categoryId) { return assets.findCategory(ledgerId, categoryId).orElseThrow(() -> problem(404, "FIXED_ASSET_CATEGORY_NOT_FOUND", "Category not found", "The category is not available to this ledger")); }
    private AssetRecord assetRow(UUID ledgerId, UUID assetId) { return assets.findAsset(ledgerId, assetId).orElseThrow(() -> problem(404, "FIXED_ASSET_NOT_FOUND", "Fixed asset not found", "The asset is not available to this ledger")); }

    private void validateAccounts(UUID actorId, UUID ledgerId, UUID... ids) {
        Map<UUID, LedgerResponses.Account> accounts = ledgers.listAccounts(actorId, ledgerId).stream().collect(Collectors.toMap(LedgerResponses.Account::id, a -> a));
        for (UUID id : ids) if (id != null) {
            LedgerResponses.Account account = accounts.get(id);
            if (account == null || !account.isLeaf() || !"ACTIVE".equals(account.status())) throw problem(422, "FIXED_ASSET_ACCOUNT_INVALID", "Invalid fixed-asset account", "Accounts must be active leaf accounts in this ledger");
        }
    }

    private void validateDepartment(UUID actorId, UUID ledgerId, UUID valueId) {
        if (valueId == null) return;
        LedgerResponses.DimensionType department = ledgers.listDimensionTypes(actorId, ledgerId).stream()
                .filter(type -> "DEPARTMENT".equalsIgnoreCase(type.code())).findFirst()
                .orElseThrow(() -> problem(422, "DEPARTMENT_DIMENSION_MISSING", "Department dimension is missing", "Create a DEPARTMENT auxiliary dimension first"));
        boolean active = ledgers.listDimensionValues(actorId, ledgerId, department.id()).stream()
                .anyMatch(value -> value.id().equals(valueId) && "ACTIVE".equals(value.status()));
        if (!active) throw problem(422, "DEPARTMENT_VALUE_INVALID", "Invalid department", "The department value must be active");
    }

    private DepreciationControls depreciationControls(UUID actorId, UUID ledgerId, List<AssetRecord> rows) {
        Map<UUID, LedgerResponses.Account> accounts = ledgers.listAccounts(actorId, ledgerId).stream()
                .collect(Collectors.toMap(LedgerResponses.Account::id, account -> account));
        Map<UUID, AccountControl> controls = new HashMap<>();
        Set<UUID> departmentTypeIds = new java.util.HashSet<>();
        for (AssetRecord row : rows) {
            configureDepreciationAccount(row.depreciationExpenseAccountId(), accounts.get(row.depreciationExpenseAccountId()), controls, departmentTypeIds);
            configureDepreciationAccount(row.accumulatedDepreciationAccountId(), accounts.get(row.accumulatedDepreciationAccountId()), controls, departmentTypeIds);
        }
        Map<UUID, Set<UUID>> activeDepartmentValues = new HashMap<>();
        for (UUID typeId : departmentTypeIds) {
            activeDepartmentValues.put(typeId, ledgers.listDimensionValues(actorId, ledgerId, typeId).stream()
                    .filter(value -> "ACTIVE".equals(value.status()))
                    .map(LedgerResponses.DimensionValue::id)
                    .collect(Collectors.toSet()));
        }
        return new DepreciationControls(controls, activeDepartmentValues);
    }

    private void configureDepreciationAccount(UUID accountId, LedgerResponses.Account account,
                                              Map<UUID, AccountControl> controls,
                                              Set<UUID> departmentTypeIds) {
        if (controls.containsKey(accountId)) return;
        if (account == null) {
            controls.put(accountId, AccountControl.invalid());
            return;
        }
        LedgerResponses.DimensionRequirement department = account.dimensionRequirements().stream()
                .filter(requirement -> "DEPARTMENT".equalsIgnoreCase(requirement.code()))
                .findFirst().orElse(null);
        List<String> unsupportedRequiredDimensions = account.dimensionRequirements().stream()
                .filter(requirement -> requirement.required() && !"DEPARTMENT".equalsIgnoreCase(requirement.code()))
                .map(LedgerResponses.DimensionRequirement::code)
                .toList();
        AccountControl control = new AccountControl(department == null ? null : department.dimensionTypeId(),
                department != null && department.required(), unsupportedRequiredDimensions,
                account.isLeaf() && "ACTIVE".equals(account.status()));
        controls.put(accountId, control);
        if (control.departmentTypeId() != null) departmentTypeIds.add(control.departmentTypeId());
    }

    private List<String> depreciationBlockers(AssetRecord row, DepreciationControls controls) {
        List<String> blockers = new ArrayList<>();
        addDepreciationAccountBlockers(blockers, row, "折旧费用", row.depreciationExpenseAccountId(), controls);
        addDepreciationAccountBlockers(blockers, row, "累计折旧", row.accumulatedDepreciationAccountId(), controls);
        return blockers;
    }

    private void addDepreciationAccountBlockers(List<String> blockers, AssetRecord row, String accountLabel,
                                                UUID accountId, DepreciationControls controls) {
        AccountControl control = controls.account(accountId);
        if (!control.activeLeaf()) {
            blockers.add(row.code() + "：" + accountLabel + "科目必须是启用的末级科目");
        }
        if (!control.unsupportedRequiredDimensions().isEmpty()) {
            blockers.add(row.code() + "：" + accountLabel + "科目要求系统暂不支持的辅助核算维度 "
                    + String.join(", ", control.unsupportedRequiredDimensions()));
        }
        if (control.departmentRequired()) {
            if (row.departmentValueId() == null) {
                blockers.add(row.code() + "：" + accountLabel + "科目要求有效的部门");
            } else if (!controls.isActiveDepartment(control.departmentTypeId(), row.departmentValueId())) {
                blockers.add(row.code() + "：" + accountLabel + "科目的部门不存在或已停用");
            }
        }
    }

    private void validateAcquisitionVoucher(UUID actorId, UUID ledgerId, UUID voucherId) {
        if (voucherId != null && !"POSTED".equals(vouchers.find(actorId, ledgerId, voucherId).status())) throw problem(422, "ACQUISITION_VOUCHER_NOT_POSTED", "Acquisition voucher is not posted", "Link only a posted voucher");
    }

    private void validateAmounts(BigDecimal cost, BigDecimal rate, BigDecimal accumulated, BigDecimal impairment, int life, int used) {
        BigDecimal residual = cost.multiply(rate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        if (used > life || accumulated.add(impairment).add(residual).compareTo(cost) > 0) throw problem(422, "FIXED_ASSET_AMOUNT_INVALID", "Invalid fixed-asset amounts", "Accumulated depreciation, impairment and residual cannot exceed original cost");
    }

    private void validateRate(BigDecimal rate) { if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.valueOf(100)) > 0) throw problem(422, "FIXED_ASSET_RATE_INVALID", "Invalid residual rate", "Residual rate must be between 0 and 100"); }
    private BigDecimal money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP); }
    private BigDecimal scaleRate(BigDecimal value) { return value.setScale(4, RoundingMode.HALF_UP); }
    private String shortId(UUID value) { return value.toString().substring(0, 8); }
    private String fingerprint(List<AssetRecord> rows, LedgerResponses.Period period) {
        String value = rows.stream().filter(r -> monthly(r, period).signum() > 0).map(r -> {
            BigDecimal amount = monthly(r, period);
            String disposalPeriod = "DISPOSED".equals(r.status()) && r.disposalDate() != null
                    && !r.disposalDate().isBefore(period.startDate()) && !r.disposalDate().isAfter(period.endDate())
                    ? period.id().toString() : "-";
            return fingerprintEntry(r.id(), r.status(), r.disposalDate(), disposalPeriod, r.version(), amount);
        }).sorted().collect(Collectors.joining("|"));
        return hashFingerprint(value);
    }
    private String disposalFingerprint(AssetRecord row, LedgerResponses.Period period,
                                       LocalDate disposalDate, BigDecimal amount) {
        return hashFingerprint(fingerprintEntry(row.id(), "DISPOSED", disposalDate, period.id().toString(),
                row.version() + 1, amount));
    }
    private String fingerprintEntry(UUID assetId, String status, LocalDate disposalDate,
                                    String disposalPeriod, long version, BigDecimal amount) {
        return assetId + ":" + status + ":" + disposalDate + ":" + disposalPeriod + ":" + version + ":" + amount;
    }
    private String hashFingerprint(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw problem(500, "FIXED_ASSET_FINGERPRINT_FAILED", "Depreciation fingerprint failed", "The depreciation input could not be fingerprinted"); }
    }
    private void requireRole(UUID actorId, UUID ledgerId, Set<LedgerRole> roles) { if (!roles.contains(ledgerAccess.requireMembership(actorId, ledgerId))) throw problem(403, "INSUFFICIENT_LEDGER_ROLE", "Insufficient ledger role", "The current user cannot perform this operation"); }
    private ApiProblemException problem(int status, String code, String title, String detail) { return new ApiProblemException(status, code, title, detail, false); }
    private FixedAssetResponses.Category category(CategoryRecord row) { return new FixedAssetResponses.Category(row.id(), row.ledgerId(), row.code(), row.name(), row.usefulLifeMonths(), row.residualRate(), row.assetAccountId(), row.accumulatedDepreciationAccountId(), row.depreciationExpenseAccountId(), row.impairmentAccountId(), row.clearingAccountId(), row.disposalGainAccountId(), row.disposalLossAccountId(), row.status(), row.version()); }
    private record VoucherGroup(UUID accountId, UUID departmentValueId, String side) { }
    private record AccountControl(UUID departmentTypeId, boolean departmentRequired,
                                  List<String> unsupportedRequiredDimensions, boolean activeLeaf) {
        private static AccountControl invalid() {
            return new AccountControl(null, false, List.of(), false);
        }
    }
    private record DepreciationControls(Map<UUID, AccountControl> accounts,
                                        Map<UUID, Set<UUID>> activeDepartmentValues) {
        private AccountControl account(UUID accountId) {
            return accounts.getOrDefault(accountId, AccountControl.invalid());
        }

        private boolean isActiveDepartment(UUID typeId, UUID valueId) {
            return typeId != null && activeDepartmentValues.getOrDefault(typeId, Set.of()).contains(valueId);
        }

        private UUID departmentValueId(UUID accountId, UUID valueId) {
            AccountControl control = account(accountId);
            return control.departmentTypeId() != null && isActiveDepartment(control.departmentTypeId(), valueId)
                    ? valueId : null;
        }

        private List<VoucherRequests.Dimension> dimensions(UUID accountId, UUID valueId) {
            AccountControl control = account(accountId);
            return valueId == null || control.departmentTypeId() == null ? List.of()
                    : List.of(new VoucherRequests.Dimension(control.departmentTypeId(), valueId));
        }
    }
}
