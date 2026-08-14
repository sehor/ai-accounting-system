package com.example.accounting.periodclosing.internal.application;

import com.example.accounting.fixedasset.FixedAssetRequests;
import com.example.accounting.fixedasset.FixedAssetResponses;
import com.example.accounting.fixedasset.FixedAssetService;
import com.example.accounting.ledger.LedgerAccessService;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.shared.accounting.ProfitLossTransferCategories;
import com.example.accounting.ledger.PeriodCloseGuard;
import com.example.accounting.periodclosing.PeriodClosingRequests;
import com.example.accounting.periodclosing.PeriodClosingResponses;
import com.example.accounting.periodclosing.PeriodClosingService;
import com.example.accounting.periodclosing.PeriodClosingStepStatus;
import com.example.accounting.periodclosing.PeriodClosingStepType;
import com.example.accounting.periodclosing.internal.port.PeriodClosingRepository;
import com.example.accounting.shared.balance.BalanceProjectionService;
import com.example.accounting.shared.web.ApiProblemException;
import com.example.accounting.voucher.VoucherRequests;
import com.example.accounting.voucher.VoucherResponses;
import com.example.accounting.voucher.VoucherService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultPeriodClosingService implements PeriodClosingService, PeriodCloseGuard {
    private static final Set<LedgerRole> READ_ROLES = Set.of(LedgerRole.OWNER, LedgerRole.EDITOR,
            LedgerRole.REVIEWER, LedgerRole.VIEWER, LedgerRole.AGENT);
    private static final Set<LedgerRole> WRITE_ROLES = Set.of(LedgerRole.OWNER, LedgerRole.EDITOR);
    private final PeriodClosingRepository closing;
    private final LedgerAccessService ledgerAccess;
    private final FixedAssetService fixedAssets;
    private final VoucherService vouchers;
    private final BalanceProjectionService projection;

    public DefaultPeriodClosingService(PeriodClosingRepository closing, LedgerAccessService ledgerAccess,
                                       FixedAssetService fixedAssets, VoucherService vouchers,
                                       BalanceProjectionService projection) {
        this.closing = closing;
        this.ledgerAccess = ledgerAccess;
        this.fixedAssets = fixedAssets;
        this.vouchers = vouchers;
        this.projection = projection;
    }

    @Override
    @Transactional
    public PeriodClosingResponses.Status status(UUID actorId, UUID ledgerId, UUID periodId) {
        requireRole(actorId, ledgerId, READ_ROLES);
        PeriodClosingRepository.PeriodRecord period = period(ledgerId, periodId);
        List<PeriodClosingStepType> required = requiredSteps(period.code());
        List<PeriodClosingResponses.Blocker> blockers = new ArrayList<>();
        List<PeriodClosingResponses.Step> steps = new ArrayList<>();
        for (PeriodClosingStepType type : required) {
            PeriodClosingRepository.StepRecord record = ensureStep(ledgerId, periodId, type);
            record = normalizeNotRequired(actorId, ledgerId, period, record);
            if (type == PeriodClosingStepType.DEPRECIATION
                    && record.status() == PeriodClosingStepStatus.GENERATED
                    && fixedAssets.previewDepreciation(actorId, ledgerId, period.id()).blockers().stream()
                    .anyMatch(value -> value.contains("失效") || value.toLowerCase().contains("stale"))) {
                closing.updateStep(ledgerId, periodId, type, PeriodClosingStepStatus.STALE, record.amount(),
                        record.fingerprint(), record.voucherId(), "PERIOD_CLOSING_STALE", "固定资产输入发生变化，请重新生成折旧凭证");
                record = closing.step(ledgerId, periodId, type).orElse(record);
            }
            String currentFingerprint = fingerprint(actorId, ledgerId, period, type);
            if (type != PeriodClosingStepType.DEPRECIATION
                    && record.status() == PeriodClosingStepStatus.GENERATED
                    && record.fingerprint() != null
                    && currentFingerprint != null
                    && !record.fingerprint().equals(currentFingerprint)) {
                closing.updateStep(ledgerId, periodId, type, PeriodClosingStepStatus.STALE, record.amount(),
                        record.fingerprint(), record.voucherId(), "PERIOD_CLOSING_STALE",
                        "参与计算的业务事实已经变化，请重新生成凭证");
                record = closing.step(ledgerId, periodId, type).orElse(record);
            }
            if (record.status() == PeriodClosingStepStatus.GENERATED && record.voucherId() == null) {
                closing.updateStep(ledgerId, periodId, type, PeriodClosingStepStatus.BLOCKED,
                        record.amount(), record.fingerprint(), null,
                        "PERIOD_CLOSING_INCOMPLETE", "生成步骤缺少已记账凭证");
                record = closing.step(ledgerId, periodId, type).orElse(record);
            }
            if (record.status() == PeriodClosingStepStatus.GENERATED && record.voucherId() != null) {
                try {
                    if (!"POSTED".equals(vouchers.find(actorId, ledgerId, record.voucherId()).status())) {
                        closing.updateStep(ledgerId, periodId, type, PeriodClosingStepStatus.BLOCKED,
                                record.amount(), record.fingerprint(), record.voucherId(),
                                "PERIOD_CLOSING_INCOMPLETE", "生成凭证已失效或尚未记账");
                        record = closing.step(ledgerId, periodId, type).orElse(record);
                    }
                } catch (ApiProblemException ignored) {
                    closing.updateStep(ledgerId, periodId, type, PeriodClosingStepStatus.BLOCKED,
                            record.amount(), record.fingerprint(), record.voucherId(),
                            "PERIOD_CLOSING_INCOMPLETE", "生成凭证不存在或已失效");
                    record = closing.step(ledgerId, periodId, type).orElse(record);
                }
            }
            PeriodClosingResponses.Step response = step(record);
            steps.add(response);
        }

        PeriodClosingResponses.TrialBalanceTotals totals = trialBalance(ledgerId, period.code());
        if (!totals.balanced()) {
            blockers.add(new PeriodClosingResponses.Blocker("TRIAL_BALANCE_UNBALANCED", "试算平衡未通过",
                    "期初差额 " + totals.openingDifference() + "，本期差额 " + totals.periodDifference()
                            + "，期末差额 " + totals.closingDifference()));
        }
        BalanceProjectionService.ProjectionStatus projectionStatus = projection.status(ledgerId, period.code());
        boolean hasFacts = totals.openingDebit().signum() != 0 || totals.openingCredit().signum() != 0
                || totals.periodDebit().signum() != 0 || totals.periodCredit().signum() != 0;
        if (projectionStatus != null && !projectionStatus.fresh() && hasFacts) {
            blockers.add(new PeriodClosingResponses.Blocker("BALANCE_PROJECTION_NOT_READY", "余额投影未就绪",
                    "余额投影仍有待处理或失败事件"));
        }
        enforcePeriodOrder(periods(ledgerId), period, blockers);
        return new PeriodClosingResponses.Status(ledgerId, periodId, period.code(), steps,
                blockers.stream().distinct().toList(), totals, blockers.isEmpty() && "OPEN".equals(period.status()));
    }

    @Override
    @Transactional
    public PeriodClosingResponses.Step generate(UUID actorId, UUID ledgerId, UUID periodId,
                                                PeriodClosingStepType type) {
        requireRole(actorId, ledgerId, WRITE_ROLES);
        PeriodClosingRepository.PeriodRecord period = period(ledgerId, periodId);
        if (!"OPEN".equals(period.status())) {
            throw problem(409, "PERIOD_STATE_INVALID", "期间状态无效", "只能在开放期间生成结账凭证");
        }
        enforceGenerationOrder(periods(ledgerId), period);
        if (type == PeriodClosingStepType.YEAR_END_PROFIT_TRANSFER && !period.code().endsWith("-12")) {
            throw problem(409, "YEAR_END_STEP_NOT_ALLOWED", "非年末期间不允许此步骤", "本年利润结转仅能在 12 月执行");
        }
        PeriodClosingRepository.StepRecord record = ensureStep(ledgerId, periodId, type);
        return switch (type) {
            case DEPRECIATION -> generateDepreciation(actorId, ledgerId, period, record);
            case EXPENSE_TRANSFER -> generateTransfer(actorId, ledgerId, period, record, false);
            case REVENUE_TRANSFER -> generateTransfer(actorId, ledgerId, period, record, true);
            case YEAR_END_PROFIT_TRANSFER -> generateProfitTransfer(actorId, ledgerId, period, record);
        };
    }

    @Override
    @Transactional(readOnly = true)
    public PeriodClosingResponses.Settings settings(UUID actorId, UUID ledgerId) {
        requireRole(actorId, ledgerId, READ_ROLES);
        PeriodClosingRepository.SettingRecord configured = closing.setting(ledgerId).orElse(null);
        UUID defaultProfit = defaultAccount(ledgerId, "3103", "4103");
        UUID defaultRetained = defaultAccount(ledgerId, "3104", "4104");
        return new PeriodClosingResponses.Settings(ledgerId, configured == null ? null : configured.profitAccountId(),
                configured == null ? null : configured.retainedEarningsAccountId(), defaultProfit, defaultRetained,
                configured == null ? 0 : configured.version());
    }

    @Override
    @Transactional
    public PeriodClosingResponses.Settings updateSettings(UUID actorId, UUID ledgerId,
                                                          PeriodClosingRequests.SettingsPatch request) {
        requireRole(actorId, ledgerId, WRITE_ROLES);
        validateConfiguredAccount(ledgerId, request.profitAccountId(), "本年利润", Set.of("EQUITY"));
        validateConfiguredAccount(ledgerId, request.retainedEarningsAccountId(), "未分配利润", Set.of("EQUITY"));
        closing.upsertSetting(ledgerId, request.profitAccountId(), request.retainedEarningsAccountId());
        return settings(actorId, ledgerId);
    }

    @Override
    @Transactional
    public List<String> blockers(UUID actorId, UUID ledgerId, UUID periodId) {
        PeriodClosingResponses.Status status = status(actorId, ledgerId, periodId);
        return status.blockers().stream().map(b -> b.code() + ": " + b.detail()).toList();
    }

    private PeriodClosingResponses.Step generateDepreciation(UUID actorId, UUID ledgerId,
                                                              PeriodClosingRepository.PeriodRecord period,
                                                              PeriodClosingRepository.StepRecord record) {
        FixedAssetResponses.DepreciationPreview preview = fixedAssets.previewDepreciation(actorId, ledgerId, period.id());
        if (!preview.blockers().isEmpty()) {
            return blocked(ledgerId, period.id(), record, "PERIOD_CLOSING_AUXILIARY_DETAIL_MISSING",
                    String.join("；", preview.blockers()));
        }
        if (preview.pendingCount() == 0) {
            closing.updateStep(ledgerId, period.id(), record.type(), PeriodClosingStepStatus.NOT_REQUIRED,
                    BigDecimal.ZERO, fingerprint(actorId, ledgerId, period, record.type()), null, null, null);
            return step(closing.step(ledgerId, period.id(), record.type()).orElse(record));
        }
        FixedAssetResponses.DepreciationRun run = record.voucherId() != null
                ? fixedAssets.regenerateDepreciation(actorId, ledgerId,
                new FixedAssetRequests.DepreciationAction(period.id(), "期末结账重新生成折旧"))
                : fixedAssets.generateDepreciation(actorId, ledgerId,
                new FixedAssetRequests.DepreciationAction(period.id(), "期末结账计提折旧"));
        closing.updateStep(ledgerId, period.id(), record.type(), PeriodClosingStepStatus.GENERATED,
                run.totalAmount(), run.inputFingerprint(), run.voucherId(), null, null);
        return step(closing.step(ledgerId, period.id(), record.type()).orElse(record));
    }

    private PeriodClosingResponses.Step generateTransfer(UUID actorId, UUID ledgerId,
                                                         PeriodClosingRepository.PeriodRecord period,
                                                         PeriodClosingRepository.StepRecord record,
                                                         boolean revenue) {
        UUID profitAccount = effectiveProfitAccount(actorId, ledgerId);
        List<PeriodClosingRepository.AccountAmount> amounts = transferAmounts(ledgerId, period.id(), revenue);
        List<PeriodClosingRepository.AccountAmount> nonZero = amounts.stream()
                .filter(a -> a.debit().subtract(a.credit()).signum() != 0).toList();
        if (nonZero.isEmpty()) {
            closing.updateStep(ledgerId, period.id(), record.type(), PeriodClosingStepStatus.NOT_REQUIRED,
                    BigDecimal.ZERO, fingerprint(actorId, ledgerId, period, record.type()), null, null, null);
            return step(closing.step(ledgerId, period.id(), record.type()).orElse(record));
        }
        requireConfigured(profitAccount, "PERIOD_CLOSING_ACCOUNT_CONFIG_MISSING", "请先配置本年利润科目");
        if (nonZero.stream().anyMatch(amount -> closing.hasRequiredDimensions(ledgerId, amount.accountId())
                || closing.hasRequiredDimensions(ledgerId, profitAccount))) {
            return blocked(ledgerId, period.id(), record, "PERIOD_CLOSING_AUXILIARY_DETAIL_MISSING",
                    "结转科目要求辅助核算，但结转规则无法提供明细");
        }
        String fingerprint = fingerprint(nonZero, profitAccount, revenue);
        if (record.status() == PeriodClosingStepStatus.GENERATED && fingerprint.equals(record.fingerprint())) {
            return step(record);
        }
        List<VoucherRequests.Line> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (PeriodClosingRepository.AccountAmount amount : nonZero) {
            BigDecimal net = amount.debit().subtract(amount.credit());
            BigDecimal value = net.abs();
            total = total.add(value);
            String accountSide = revenue ? (net.signum() < 0 ? "DEBIT" : "CREDIT")
                    : (net.signum() > 0 ? "CREDIT" : "DEBIT");
            String profitSide = "DEBIT".equals(accountSide) ? "CREDIT" : "DEBIT";
            lines.add(line(ledgerId, amount.accountId(), accountSide, value, "结转" + (revenue ? "收入" : "费用")));
            lines.add(line(ledgerId, profitAccount, profitSide, value, "结转" + (revenue ? "收入" : "费用")));
        }
        VoucherResponses.Voucher voucher = generatedVoucher(actorId, ledgerId, period, record, lines,
                revenue ? "结转本期收入" : "结转本期费用");
        closing.updateStep(ledgerId, period.id(), record.type(), PeriodClosingStepStatus.GENERATED,
                total, fingerprint, voucher.id(), null, null);
        return step(closing.step(ledgerId, period.id(), record.type()).orElse(record));
    }

    private PeriodClosingResponses.Step generateProfitTransfer(UUID actorId, UUID ledgerId,
                                                                PeriodClosingRepository.PeriodRecord period,
                                                                PeriodClosingRepository.StepRecord record) {
        UUID profitAccount = effectiveProfitAccount(actorId, ledgerId);
        UUID retainedAccount = effectiveRetainedEarningsAccount(actorId, ledgerId);
        requireConfigured(profitAccount, "PERIOD_CLOSING_ACCOUNT_CONFIG_MISSING", "请先配置本年利润科目");
        requireConfigured(retainedAccount, "PERIOD_CLOSING_ACCOUNT_CONFIG_MISSING", "请先配置未分配利润科目");
        PeriodClosingRepository.AccountAmount amount = closing.amountThrough(ledgerId, period.code(), profitAccount, record.voucherId())
                .orElseThrow(() -> problem(422, "PERIOD_CLOSING_ACCOUNT_CONFIG_MISSING", "结账科目不存在", "本年利润科目不存在"));
        BigDecimal net = amount.debit().subtract(amount.credit());
        if (net.signum() == 0) {
            closing.updateStep(ledgerId, period.id(), record.type(), PeriodClosingStepStatus.NOT_REQUIRED,
                    BigDecimal.ZERO, fingerprint(actorId, ledgerId, period, record.type()), null, null, null);
            return step(closing.step(ledgerId, period.id(), record.type()).orElse(record));
        }
        String fp = fingerprint(List.of(amount), retainedAccount, false);
        if (record.status() == PeriodClosingStepStatus.GENERATED && fp.equals(record.fingerprint())) return step(record);
        BigDecimal value = net.abs();
        String profitSide = net.signum() > 0 ? "CREDIT" : "DEBIT";
        String retainedSide = "DEBIT".equals(profitSide) ? "CREDIT" : "DEBIT";
        List<VoucherRequests.Line> lines = List.of(line(ledgerId, profitAccount, profitSide, value, "结转本年利润"),
                line(ledgerId, retainedAccount, retainedSide, value, "结转本年利润"));
        VoucherResponses.Voucher voucher = generatedVoucher(actorId, ledgerId, period, record, lines, "结转本年利润");
        closing.updateStep(ledgerId, period.id(), record.type(), PeriodClosingStepStatus.GENERATED,
                value, fp, voucher.id(), null, null);
        return step(closing.step(ledgerId, period.id(), record.type()).orElse(record));
    }

    private VoucherResponses.Voucher generatedVoucher(UUID actorId, UUID ledgerId,
                                                      PeriodClosingRepository.PeriodRecord period,
                                                      PeriodClosingRepository.StepRecord record,
                                                      List<VoucherRequests.Line> lines, String summary) {
        String number = "PC-" + record.type().name() + "-" + period.code().replace('-', '_');
        if (record.voucherId() != null) {
            VoucherResponses.Voucher current = vouchers.find(actorId, ledgerId, record.voucherId());
            return vouchers.replaceGenerated(actorId, ledgerId, record.voucherId(),
                    new VoucherRequests.Update(current.version(), period.id(), period.endDate(),
                            "PERIOD_CLOSING", current.voucherNumber(), summary, lines),
                    "PERIOD_CLOSING", record.id(), record.id());
        }
        return vouchers.createGenerated(actorId, ledgerId,
                new VoucherRequests.Create(period.id(), period.endDate(), "PERIOD_CLOSING", number, summary, lines),
                "period-closing:" + ledgerId + ":" + period.id() + ":" + record.type(),
                "PERIOD_CLOSING", record.id());
    }

    private VoucherRequests.Line line(UUID ledgerId, UUID accountId, String side, BigDecimal amount, String summary) {
        return new VoucherRequests.Line(accountId, side, closing.baseCurrency(ledgerId), amount, BigDecimal.ONE, summary);
    }

    private PeriodClosingResponses.Step blocked(UUID ledgerId, UUID periodId,
                                                PeriodClosingRepository.StepRecord record,
                                                String code, String detail) {
        closing.updateStep(ledgerId, periodId, record.type(), PeriodClosingStepStatus.BLOCKED,
                record.amount(), record.fingerprint(), record.voucherId(), code, detail);
        return step(closing.step(ledgerId, periodId, record.type()).orElse(record));
    }

    private PeriodClosingRepository.StepRecord ensureStep(UUID ledgerId, UUID periodId, PeriodClosingStepType type) {
        return closing.step(ledgerId, periodId, type).orElseGet(() -> {
            UUID id = UUID.randomUUID();
            closing.createStep(id, ledgerId, periodId, type, PeriodClosingStepStatus.PENDING,
                    BigDecimal.ZERO, null, null, null, null);
            return closing.step(ledgerId, periodId, type).orElseThrow();
        });
    }

    private PeriodClosingResponses.Step step(PeriodClosingRepository.StepRecord record) {
        List<PeriodClosingResponses.Blocker> blockers = record.blockerCode() == null ? List.of()
                : List.of(new PeriodClosingResponses.Blocker(record.blockerCode(), "结账步骤受阻", record.blockerDetail()));
        return new PeriodClosingResponses.Step(record.type(), record.status(), record.amount(), record.voucherId(),
                record.fingerprint(), blockers, record.updatedAt());
    }

    private List<PeriodClosingStepType> requiredSteps(String periodCode) {
        EnumSet<PeriodClosingStepType> result = EnumSet.of(PeriodClosingStepType.DEPRECIATION,
                PeriodClosingStepType.EXPENSE_TRANSFER, PeriodClosingStepType.REVENUE_TRANSFER);
        if (periodCode.endsWith("-12")) result.add(PeriodClosingStepType.YEAR_END_PROFIT_TRANSFER);
        return result.stream().toList();
    }

    private PeriodClosingResponses.TrialBalanceTotals trialBalance(UUID ledgerId, String periodCode) {
        PeriodClosingRepository.TrialBalanceAmounts amounts = closing.trialBalanceAmounts(ledgerId, periodCode);
        BigDecimal od = amounts.openingDebit();
        BigDecimal oc = amounts.openingCredit();
        BigDecimal pd = amounts.periodDebit();
        BigDecimal pc = amounts.periodCredit();
        BigDecimal cd = od.add(pd);
        BigDecimal cc = oc.add(pc);
        BigDecimal odiff = od.subtract(oc).abs(), pdiff = pd.subtract(pc).abs(), cdiff = cd.subtract(cc).abs();
        return new PeriodClosingResponses.TrialBalanceTotals(od, oc, pd, pc, cd, cc, odiff, pdiff, cdiff,
                odiff.signum() == 0 && pdiff.signum() == 0 && cdiff.signum() == 0);
    }

    private String fingerprint(UUID actorId, UUID ledgerId, PeriodClosingRepository.PeriodRecord period, PeriodClosingStepType type) {
        if (type == PeriodClosingStepType.DEPRECIATION) return null;
        if (type == PeriodClosingStepType.EXPENSE_TRANSFER || type == PeriodClosingStepType.REVENUE_TRANSFER) {
            boolean revenue = type == PeriodClosingStepType.REVENUE_TRANSFER;
            UUID profit = effectiveProfitAccount(actorId, ledgerId);
            List<PeriodClosingRepository.AccountAmount> amounts = transferAmounts(ledgerId, period.id(), revenue)
                    .stream().filter(a -> a.debit().subtract(a.credit()).signum() != 0).toList();
            return fingerprint(amounts, profit, revenue);
        }
        UUID profit = effectiveProfitAccount(actorId, ledgerId);
        UUID retained = effectiveRetainedEarningsAccount(actorId, ledgerId);
        if (profit == null) return digest("YEAR_END:" + period.code() + ":missing");
        UUID excludedVoucher = closing.step(ledgerId, period.id(), PeriodClosingStepType.YEAR_END_PROFIT_TRANSFER)
                .map(PeriodClosingRepository.StepRecord::voucherId).orElse(null);
        PeriodClosingRepository.AccountAmount amount = closing.amountThrough(ledgerId, period.code(), profit, excludedVoucher).orElse(null);
        return amount == null ? digest("YEAR_END:" + period.code() + ":none")
                : fingerprint(List.of(amount), retained, false);
    }

    private String fingerprint(List<PeriodClosingRepository.AccountAmount> amounts, UUID accountId, boolean revenue) {
        return digest(revenue + ":" + accountId + ":" + amounts);
    }

    private PeriodClosingRepository.StepRecord normalizeNotRequired(UUID actorId, UUID ledgerId,
                                                                     PeriodClosingRepository.PeriodRecord period,
                                                                     PeriodClosingRepository.StepRecord record) {
        if (record.status() != PeriodClosingStepStatus.PENDING) return record;
        boolean noAmount = switch (record.type()) {
            case DEPRECIATION -> fixedAssets.previewDepreciation(actorId, ledgerId, period.id()).pendingCount() == 0;
            case EXPENSE_TRANSFER -> transferNetAmounts(ledgerId, period.id(), false).stream()
                    .noneMatch(a -> a.debit().subtract(a.credit()).signum() != 0);
            case REVENUE_TRANSFER -> transferNetAmounts(ledgerId, period.id(), true).stream()
                    .noneMatch(a -> a.debit().subtract(a.credit()).signum() != 0);
            case YEAR_END_PROFIT_TRANSFER -> {
                if (!period.code().endsWith("-12")) {
                    yield true;
                }
                UUID profitAccount = effectiveProfitAccount(actorId, ledgerId);
                yield profitAccount != null && closing.amountThrough(ledgerId, period.code(), profitAccount, record.voucherId())
                        .map(amount -> amount.debit().subtract(amount.credit()).signum() == 0)
                        .orElse(true);
            }
        };
        if (noAmount) {
            closing.updateStep(ledgerId, period.id(), record.type(), PeriodClosingStepStatus.NOT_REQUIRED,
                    BigDecimal.ZERO, fingerprint(actorId, ledgerId, period, record.type()), null, null, null);
            return closing.step(ledgerId, period.id(), record.type()).orElse(record);
        }
        return record;
    }

    private List<PeriodClosingRepository.AccountAmount> transferAmounts(
            UUID ledgerId, UUID periodId, boolean revenue) {
        List<PeriodClosingRepository.AccountAmount> result = new ArrayList<>();
        for (String category : revenue ? ProfitLossTransferCategories.revenue()
                : ProfitLossTransferCategories.expense()) {
            result.addAll(closing.amounts(ledgerId, periodId, category));
        }
        return result;
    }

    private List<PeriodClosingRepository.AccountAmount> transferNetAmounts(
            UUID ledgerId, UUID periodId, boolean revenue) {
        List<PeriodClosingRepository.AccountAmount> result = new ArrayList<>();
        for (String category : revenue ? ProfitLossTransferCategories.revenue()
                : ProfitLossTransferCategories.expense()) {
            result.addAll(closing.netAmounts(ledgerId, periodId, category));
        }
        return result;
    }

    private String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw problem(500, "PERIOD_CLOSING_FINGERPRINT_FAILED", "指纹计算失败", "无法计算结账输入指纹"); }
    }

    private UUID defaultAccount(UUID ledgerId, String firstCode, String secondCode) {
        return closing.accountByCode(ledgerId, firstCode)
                .filter(a -> a.leaf() && "ACTIVE".equals(a.status()) && "EQUITY".equals(a.category()))
                .map(PeriodClosingRepository.AccountInfo::id)
                .orElseGet(() -> closing.accountByCode(ledgerId, secondCode)
                        .filter(a -> a.leaf() && "ACTIVE".equals(a.status()) && "EQUITY".equals(a.category()))
                        .map(PeriodClosingRepository.AccountInfo::id).orElse(null));
    }

    private UUID effectiveProfitAccount(UUID actorId, UUID ledgerId) {
        PeriodClosingResponses.Settings value = settings(actorId, ledgerId);
        if (value.profitAccountId() != null) {
            return usableEquityLeaf(ledgerId, value.profitAccountId()) ? value.profitAccountId() : null;
        }
        return value.defaultProfitAccountId();
    }

    private UUID effectiveRetainedEarningsAccount(UUID actorId, UUID ledgerId) {
        PeriodClosingResponses.Settings value = settings(actorId, ledgerId);
        if (value.retainedEarningsAccountId() != null) {
            return usableEquityLeaf(ledgerId, value.retainedEarningsAccountId())
                    ? value.retainedEarningsAccountId() : null;
        }
        return value.defaultRetainedEarningsAccountId();
    }

    private boolean usableEquityLeaf(UUID ledgerId, UUID accountId) {
        return closing.account(ledgerId, accountId)
                .map(account -> account.leaf() && "ACTIVE".equals(account.status())
                        && "EQUITY".equals(account.category()))
                .orElse(false);
    }

    private void validateConfiguredAccount(UUID ledgerId, UUID accountId, String label, Set<String> categories) {
        if (accountId == null) return;
        PeriodClosingRepository.AccountInfo account = closing.account(ledgerId, accountId).orElseThrow(() ->
                problem(422, "PERIOD_CLOSING_ACCOUNT_CONFIG_MISSING", "结账科目无效", label + "科目不属于当前账套"));
        if (!account.leaf() || !"ACTIVE".equals(account.status()) || !categories.contains(account.category())) {
            throw problem(422, "PERIOD_CLOSING_ACCOUNT_CONFIG_MISSING", "结账科目无效", label + "科目必须是有效的权益类叶子科目");
        }
    }

    private void requireConfigured(UUID accountId, String code, String detail) {
        if (accountId == null) throw problem(409, code, "结账科目配置缺失", detail);
    }

    private void enforceGenerationOrder(List<PeriodClosingRepository.PeriodRecord> periods,
                                        PeriodClosingRepository.PeriodRecord period) {
        int index = periods.indexOf(period);
        if (index > 0 && !"CLOSED".equals(periods.get(index - 1).status())) {
            throw problem(409, "PERIOD_ORDER_INVALID", "期间顺序无效", "必须先完成上一期间结账");
        }
    }

    private void enforcePeriodOrder(List<PeriodClosingRepository.PeriodRecord> periods,
                                    PeriodClosingRepository.PeriodRecord period,
                                    List<PeriodClosingResponses.Blocker> blockers) {
        int index = periods.indexOf(period);
        if (index > 0 && !"CLOSED".equals(periods.get(index - 1).status())) {
            blockers.add(new PeriodClosingResponses.Blocker("PERIOD_ORDER_INVALID", "期间顺序无效", "必须先完成上一期间结账"));
        }
    }

    private PeriodClosingRepository.PeriodRecord period(UUID ledgerId, UUID periodId) {
        return closing.period(ledgerId, periodId).orElseThrow(() ->
                problem(404, "PERIOD_NOT_FOUND", "期间不存在", "该期间不属于当前账套"));
    }

    private List<PeriodClosingRepository.PeriodRecord> periods(UUID ledgerId) { return closing.periods(ledgerId); }

    private void requireRole(UUID actorId, UUID ledgerId, Set<LedgerRole> roles) {
        if (!roles.contains(ledgerAccess.requireMembership(actorId, ledgerId))) {
            throw problem(403, "INSUFFICIENT_LEDGER_ROLE", "账套角色不足", "当前用户无权执行此操作");
        }
    }

    private ApiProblemException problem(int status, String code, String title, String detail) {
        return new ApiProblemException(status, code, title, detail, false);
    }
}
