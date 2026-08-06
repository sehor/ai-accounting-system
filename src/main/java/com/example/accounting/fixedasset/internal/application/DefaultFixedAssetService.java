package com.example.accounting.fixedasset.internal.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.accounting.fixedasset.FixedAssetRequests;
import com.example.accounting.fixedasset.FixedAssetResponses;
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
import com.example.accounting.ledger.PeriodCloseGuard;
import com.example.accounting.shared.web.ApiProblemException;
import com.example.accounting.voucher.VoucherRequests;
import com.example.accounting.voucher.VoucherResponses;
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
public class DefaultFixedAssetService implements FixedAssetService, PeriodCloseGuard {

    private static final Set<LedgerRole> WRITE_ROLES = Set.of(LedgerRole.OWNER, LedgerRole.EDITOR);
    private static final Set<LedgerRole> READ_ROLES = Set.of(LedgerRole.OWNER, LedgerRole.EDITOR,
            LedgerRole.REVIEWER, LedgerRole.VIEWER, LedgerRole.AGENT);

    private final FixedAssetRepository assets;
    private final LedgerAccessService ledgerAccess;
    private final LedgerService ledgers;
    private final VoucherService vouchers;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public DefaultFixedAssetService(FixedAssetRepository assets, LedgerAccessService ledgerAccess,
                                    LedgerService ledgers, VoucherService vouchers) {
        this.assets = assets;
        this.ledgerAccess = ledgerAccess;
        this.ledgers = ledgers;
        this.vouchers = vouchers;
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
        validateAccounts(actorId, ledgerId, List.of(request.assetAccountId(), request.accumulatedDepreciationAccountId(),
                request.depreciationExpenseAccountId(), request.impairmentAccountId(), request.clearingAccountId(),
                request.disposalGainAccountId(), request.disposalLossAccountId()));
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
        validateAccounts(actorId, ledgerId, List.of(next.assetAccountId(), next.accumulatedDepreciationAccountId(),
                next.depreciationExpenseAccountId(), next.impairmentAccountId(), next.clearingAccountId(),
                next.disposalGainAccountId(), next.disposalLossAccountId()));
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
        validateAccounts(actorId, ledgerId, List.of(
                request.assetAccountId() == null ? category.assetAccountId() : request.assetAccountId(),
                request.accumulatedDepreciationAccountId() == null ? category.accumulatedDepreciationAccountId() : request.accumulatedDepreciationAccountId(),
                request.depreciationExpenseAccountId() == null ? category.depreciationExpenseAccountId() : request.depreciationExpenseAccountId(),
                request.impairmentAccountId() == null ? category.impairmentAccountId() : request.impairmentAccountId(),
                request.clearingAccountId() == null ? category.clearingAccountId() : request.clearingAccountId(),
                request.disposalGainAccountId() == null ? category.disposalGainAccountId() : request.disposalGainAccountId(),
                request.disposalLossAccountId() == null ? category.disposalLossAccountId() : request.disposalLossAccountId()));
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
        if (accountingChange && (request.effectivePeriodId() == null || request.reason() == null || request.reason().isBlank())) {
            throw problem(422, "FIXED_ASSET_CHANGE_REASON_REQUIRED", "Change reason is required",
                    "Accounting changes need an effective period and a reason");
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
                current.disposalDate(), request.note() == null ? current.note() : request.note(), current.version());
        validateRate(next.residualRate());
        validateAmounts(next.originalCost(), next.residualRate(), next.openingAccumulatedDepreciation(), next.impairmentAmount(),
                next.usefulLifeMonths(), next.openingDepreciatedMonths());
        validateAccounts(actorId, ledgerId, List.of(next.assetAccountId(), next.accumulatedDepreciationAccountId(),
                next.depreciationExpenseAccountId(), next.impairmentAccountId(), next.clearingAccountId(),
                next.disposalGainAccountId(), next.disposalLossAccountId()));
        validateAcquisitionVoucher(actorId, ledgerId, next.acquisitionVoucherId());
        validateDepartment(actorId, ledgerId, next.departmentValueId());
        if (!assets.updateAsset(ledgerId, assetId, next, request.expectedVersion(), actorId)) {
            throw problem(409, "RESOURCE_VERSION_CONFLICT", "Resource version conflict", "The asset was changed by another request");
        }
        if (accountingChange) {
            assets.insertChange(ledgerId, assetId, request.effectivePeriodId(), request.reason().trim(), actorId,
                    json(current), json(next));
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
        List<AssetRecord> rows = assets.activeAssets(ledgerId);
        List<LineRecord> lines = assets.activeLines(ledgerId, periodId);
        Map<UUID, LineRecord> byAsset = lines.stream().collect(Collectors.toMap(LineRecord::assetId, line -> line, (a, b) -> b));
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
                if (row.departmentValueId() == null && requiresDepartment(actorId, ledgerId, row.depreciationExpenseAccountId())) {
                    blockers.add(row.code() + "：折旧费用科目要求部门");
                }
            }
        }
        boolean stale = assets.currentRun(ledgerId, periodId, "MONTH_END")
                .map(run -> !run.inputFingerprint().equals(fingerprint(rows, period))).orElse(false);
        if (stale) blockers.add("本期折旧批次已失效，请重新生成");
        int pending = eligible - completed;
        return new FixedAssetResponses.DepreciationPreview(periodId, period.periodCode(), total, eligible, completed, pending,
                pending == 0 && blockers.isEmpty(), blockers, preview);
    }

    @Override
    @Transactional
    public FixedAssetResponses.DepreciationRun generateDepreciation(UUID actorId, UUID ledgerId,
                                                                     FixedAssetRequests.DepreciationAction request) {
        requireRole(actorId, ledgerId, WRITE_ROLES);
        return generate(actorId, ledgerId, request.periodId(), request.reason(), false);
    }

    @Override
    @Transactional
    public FixedAssetResponses.DepreciationRun regenerateDepreciation(UUID actorId, UUID ledgerId,
                                                                       FixedAssetRequests.DepreciationAction request) {
        requireRole(actorId, ledgerId, WRITE_ROLES);
        if (request.reason() == null || request.reason().isBlank()) {
            throw problem(422, "FIXED_ASSET_REGENERATION_REASON_REQUIRED", "Regeneration reason is required", "Enter a reason before regenerating");
        }
        RunRecord current = assets.currentRun(ledgerId, request.periodId(), "MONTH_END").orElseThrow(() ->
                problem(409, "FIXED_ASSET_RUN_NOT_FOUND", "No depreciation run to regenerate", "Generate the period depreciation first"));
        vouchers.reverseGenerated(actorId, ledgerId, current.voucherId(), "FIXED_ASSET_DEPRECIATION", current.id());
        assets.supersedeLines(ledgerId, current.id());
        assets.supersedeRun(ledgerId, current.id(), null);
        FixedAssetResponses.DepreciationRun replacement = generate(actorId, ledgerId, request.periodId(), request.reason(), true);
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
            FixedAssetResponses.DepreciationRun run = generate(actorId, ledgerId, period.id(), "处置向导自动补提折旧", false);
            depreciationVoucherId = run.voucherId();
        }
        BigDecimal accumulated = row.openingAccumulatedDepreciation()
                .add(assets.postedDepreciationBefore(ledgerId, assetId, period.id()))
                .add(assets.periodDepreciation(ledgerId, assetId, period.id()));
        BigDecimal residual = residual(row);
        BigDecimal carrying = row.originalCost().subtract(accumulated).subtract(row.impairmentAmount()).setScale(2, RoundingMode.HALF_UP);
        List<VoucherRequests.Line> transfer = new ArrayList<>();
        addLine(transfer, row.accumulatedDepreciationAccountId(), "DEBIT", accumulated, "结转累计折旧");
        addLine(transfer, row.impairmentAccountId(), "DEBIT", row.impairmentAmount(), "结转减值准备");
        addLine(transfer, row.clearingAccountId(), "DEBIT", carrying, "转入固定资产清理");
        addLine(transfer, row.assetAccountId(), "CREDIT", row.originalCost(), "结转固定资产原值");
        VoucherResponses.Voucher transferVoucher = createVoucher(actorId, ledgerId, period, "FA-CLEAR-" + shortId(assetId),
                "固定资产清理结转：" + row.code(), transfer, "FIXED_ASSET_DISPOSAL", assetId);

        List<VoucherRequests.Line> settlement = new ArrayList<>();
        BigDecimal proceedsWithTax = request.proceeds().add(request.outputTax());
        addLine(settlement, request.receiptAccountId(), "DEBIT", proceedsWithTax, "收到处置款");
        addLine(settlement, row.clearingAccountId(), "CREDIT", request.proceeds(), "处置收入结转");
        addLine(settlement, request.outputTaxAccountId(), "CREDIT", request.outputTax(), "处置销项税");
        addLine(settlement, row.clearingAccountId(), "DEBIT", request.clearingCost(), "处置清理费用");
        addLine(settlement, request.inputTaxAccountId(), "DEBIT", request.clearingInputTax(), "清理费用进项税");
        addLine(settlement, request.paymentAccountId(), "CREDIT", request.clearingCost().add(request.clearingInputTax()), "支付清理费用");
        BigDecimal gainOrLoss = request.proceeds().subtract(carrying).subtract(request.clearingCost()).setScale(2, RoundingMode.HALF_UP);
        if (gainOrLoss.signum() >= 0) {
            addLine(settlement, row.clearingAccountId(), "DEBIT", gainOrLoss, "结转处置收益");
            addLine(settlement, row.disposalGainAccountId(), "CREDIT", gainOrLoss, "处置收益");
        } else {
            addLine(settlement, row.disposalLossAccountId(), "DEBIT", gainOrLoss.abs(), "处置损失");
            addLine(settlement, row.clearingAccountId(), "CREDIT", gainOrLoss.abs(), "结转处置损失");
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
    public List<String> blockers(UUID actorId, UUID ledgerId, UUID periodId) {
        return periodBlockers(actorId, ledgerId, periodId);
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

    private FixedAssetResponses.DepreciationRun generate(UUID actorId, UUID ledgerId, UUID periodId,
                                                         String reason, boolean replacing) {
        LedgerResponses.Period period = period(actorId, ledgerId, periodId);
        if (!"OPEN".equals(period.status())) throw problem(409, "FIXED_ASSET_PERIOD_CLOSED", "Period is closed", "Open the period before generating depreciation");
        FixedAssetResponses.DepreciationPreview preview = previewDepreciation(actorId, ledgerId, periodId);
        if (!preview.blockers().isEmpty()) throw problem(422, "FIXED_ASSET_DEPRECIATION_BLOCKED", "Depreciation is blocked", String.join("; ", preview.blockers()));
        if (preview.pendingCount() == 0) {
            return assets.currentRun(ledgerId, periodId, "MONTH_END")
                    .map(run -> new FixedAssetResponses.DepreciationRun(run.id(), run.periodId(), run.runType(), run.status(), run.voucherId(), run.totalAmount(), run.inputFingerprint(), run.createdAt()))
                    .orElseThrow(() -> problem(409, "FIXED_ASSET_NO_DEPRECIATION", "No depreciation is due", "There is no asset to depreciate in this period"));
        }
        List<AssetRecord> rows = assets.activeAssets(ledgerId);
        Map<UUID, LineRecord> existing = assets.activeLines(ledgerId, periodId).stream()
                .collect(Collectors.toMap(LineRecord::assetId, line -> line, (a, b) -> b));
        Map<VoucherGroup, BigDecimal> grouped = new LinkedHashMap<>();
        Map<UUID, BigDecimal> amounts = new HashMap<>();
        for (AssetRecord row : rows) {
            if (existing.containsKey(row.id())) continue;
            BigDecimal amount = monthly(row, period);
            if (amount.signum() <= 0) continue;
            amounts.put(row.id(), amount);
            grouped.merge(new VoucherGroup(row.depreciationExpenseAccountId(), row.departmentValueId(), "DEBIT"), amount, BigDecimal::add);
            grouped.merge(new VoucherGroup(row.accumulatedDepreciationAccountId(), null, "CREDIT"), amount, BigDecimal::add);
        }
        if (grouped.isEmpty()) throw problem(409, "FIXED_ASSET_NO_DEPRECIATION", "No depreciation is due", "There is no asset to depreciate in this period");
        List<VoucherRequests.Line> voucherLines = new ArrayList<>();
        for (Map.Entry<VoucherGroup, BigDecimal> entry : grouped.entrySet()) {
            VoucherGroup group = entry.getKey();
            List<VoucherRequests.Dimension> dimensions = group.departmentValueId() == null ? List.of() : departmentDimension(actorId, ledgerId, group.departmentValueId());
            voucherLines.add(new VoucherRequests.Line(group.accountId(), group.side(), ledgers.findLedger(actorId, ledgerId).baseCurrency(),
                    entry.getValue(), BigDecimal.ONE, "计提固定资产折旧", null, null, null, dimensions));
        }
        UUID runId = UUID.randomUUID();
        String number = "ZJ-" + period.periodCode().replace("-", "") + "-R" + (assets.listRuns(ledgerId, periodId).size() + 1);
        VoucherResponses.Voucher voucher = createVoucher(actorId, ledgerId, period, number, "计提固定资产折旧", voucherLines,
                "FIXED_ASSET_DEPRECIATION", runId);
        String fingerprint = fingerprint(rows, period);
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
            UUID lineId = voucherLineIds.get(new VoucherGroup(row.depreciationExpenseAccountId(), row.departmentValueId(), "DEBIT"));
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

    private void addLine(List<VoucherRequests.Line> lines, UUID accountId, String side, BigDecimal amount, String summary) {
        if (accountId != null && amount != null && amount.signum() > 0) {
            lines.add(new VoucherRequests.Line(accountId, side, "CNY", amount, BigDecimal.ONE, summary));
        }
    }

    private BigDecimal monthly(AssetRecord row, LedgerResponses.Period period) {
        BigDecimal before = assets.postedDepreciationBefore(row.ledgerId(), row.id(), period.id());
        BigDecimal currentAccum = row.openingAccumulatedDepreciation().add(before);
        return FixedAssetCalculation.monthly(new FixedAssetCalculation.Asset(row.serviceDate(), row.originalCost(), residual(row),
                row.usefulLifeMonths(), currentAccum, row.openingDepreciatedMonths(), row.disposalDate(), row.impairmentAmount()), period.endDate());
    }

    private FixedAssetResponses.Asset asset(UUID actorId, UUID ledgerId, AssetRecord row, UUID periodId) {
        LedgerResponses.Period period = period(actorId, ledgerId, periodId);
        BigDecimal before = row.openingAccumulatedDepreciation().add(assets.postedDepreciationBefore(ledgerId, row.id(), period.id()));
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

    private CategoryRecord categoryRow(UUID ledgerId, UUID categoryId) { return assets.findCategory(ledgerId, categoryId).orElseThrow(() -> problem(404, "FIXED_ASSET_CATEGORY_NOT_FOUND", "Category not found", "The category is not available to this ledger")); }
    private AssetRecord assetRow(UUID ledgerId, UUID assetId) { return assets.findAsset(ledgerId, assetId).orElseThrow(() -> problem(404, "FIXED_ASSET_NOT_FOUND", "Fixed asset not found", "The asset is not available to this ledger")); }

    private void validateAccounts(UUID actorId, UUID ledgerId, List<UUID> ids) {
        Map<UUID, LedgerResponses.Account> accounts = ledgers.listAccounts(actorId, ledgerId).stream().collect(Collectors.toMap(LedgerResponses.Account::id, a -> a));
        for (UUID id : ids) if (id != null) {
            LedgerResponses.Account account = accounts.get(id);
            if (account == null || !account.isLeaf() || !"ACTIVE".equals(account.status())) throw problem(422, "FIXED_ASSET_ACCOUNT_INVALID", "Invalid fixed-asset account", "Accounts must be active leaf accounts in this ledger");
        }
    }

    private boolean requiresDepartment(UUID actorId, UUID ledgerId, UUID accountId) {
        return ledgers.listAccounts(actorId, ledgerId).stream().filter(a -> a.id().equals(accountId))
                .flatMap(a -> a.dimensionRequirements().stream()).anyMatch(d -> "DEPARTMENT".equalsIgnoreCase(d.code()) && d.required());
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

    private List<VoucherRequests.Dimension> departmentDimension(UUID actorId, UUID ledgerId, UUID valueId) {
        LedgerResponses.DimensionType type = ledgers.listDimensionTypes(actorId, ledgerId).stream()
                .filter(t -> "DEPARTMENT".equalsIgnoreCase(t.code())).findFirst()
                .orElseThrow(() -> problem(422, "DEPARTMENT_DIMENSION_MISSING", "Department dimension is missing", "Create a DEPARTMENT auxiliary dimension first"));
        boolean valid = ledgers.listDimensionValues(actorId, ledgerId, type.id()).stream().anyMatch(v -> v.id().equals(valueId));
        if (!valid) throw problem(422, "DEPARTMENT_VALUE_INVALID", "Invalid department", "The department value is not active in this ledger");
        return List.of(new VoucherRequests.Dimension(type.id(), valueId));
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
        String value = rows.stream().map(r -> r.id() + ":" + r.version() + ":" + monthly(r, period)).sorted().collect(Collectors.joining("|"));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw problem(500, "FIXED_ASSET_FINGERPRINT_FAILED", "Depreciation fingerprint failed", "The depreciation input could not be fingerprinted"); }
    }
    private String json(Object value) { try { return objectMapper.writeValueAsString(value); } catch (JsonProcessingException e) { return "{}"; } }
    private void requireRole(UUID actorId, UUID ledgerId, Set<LedgerRole> roles) { if (!roles.contains(ledgerAccess.requireMembership(actorId, ledgerId))) throw problem(403, "INSUFFICIENT_LEDGER_ROLE", "Insufficient ledger role", "The current user cannot perform this operation"); }
    private ApiProblemException problem(int status, String code, String title, String detail) { return new ApiProblemException(status, code, title, detail, false); }
    private FixedAssetResponses.Category category(CategoryRecord row) { return new FixedAssetResponses.Category(row.id(), row.ledgerId(), row.code(), row.name(), row.usefulLifeMonths(), row.residualRate(), row.assetAccountId(), row.accumulatedDepreciationAccountId(), row.depreciationExpenseAccountId(), row.impairmentAccountId(), row.clearingAccountId(), row.disposalGainAccountId(), row.disposalLossAccountId(), row.status(), row.version()); }
    private record VoucherGroup(UUID accountId, UUID departmentValueId, String side) { }
}
