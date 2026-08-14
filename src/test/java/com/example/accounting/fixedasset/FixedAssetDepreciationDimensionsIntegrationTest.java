package com.example.accounting.fixedasset;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.ledger.LedgerRequests;
import com.example.accounting.ledger.LedgerResponses;
import com.example.accounting.ledger.LedgerService;
import com.example.accounting.voucher.VoucherResponses;
import com.example.accounting.voucher.VoucherService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class FixedAssetDepreciationDimensionsIntegrationTest {

    @Autowired private LedgerService ledgers;
    @Autowired private FixedAssetService fixedAssets;
    @Autowired private VoucherService vouchers;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void omitsAssetDepartmentWhenDepreciationAccountsDoNotSupportIt() {
        UUID user = UUID.randomUUID();
        UUID ledger = ledger(user, "depreciation-without-department-controls");
        LedgerResponses.DimensionType department = department(user, ledger);
        UUID departmentValue = departmentValue(user, ledger, department);
        LedgerResponses.Account expense = account(user, ledger, "9001", "Depreciation expense", "PERIOD_EXPENSE", "DEBIT", List.of());
        LedgerResponses.Account accumulated = account(user, ledger, "9002", "Accumulated depreciation", "NON_CURRENT_ASSET", "CREDIT", List.of());
        UUID period = ledgers.periodId(ledger, "2026-01");
        createAsset(user, ledger, expense.id(), accumulated.id(), departmentValue, "FA-NO-DEPT");

        FixedAssetResponses.DepreciationRun run = fixedAssets.generateDepreciation(user, ledger,
                new FixedAssetRequests.DepreciationAction(period, "test"));

        assertThat(vouchers.find(user, ledger, run.voucherId()).lines())
                .filteredOn(line -> line.accountId().equals(expense.id()) || line.accountId().equals(accumulated.id()))
                .allSatisfy(line -> assertThat(line.dimensions()).isEmpty());
    }

    @Test
    void attachesTheConfiguredDepartmentToBothDepreciationVoucherSides() {
        UUID user = UUID.randomUUID();
        UUID ledger = ledger(user, "depreciation-with-department-controls");
        LedgerResponses.DimensionType department = department(user, ledger);
        UUID departmentValue = departmentValue(user, ledger, department);
        List<LedgerRequests.DimensionRequirement> requirements = List.of(
                new LedgerRequests.DimensionRequirement(department.id(), true));
        LedgerResponses.Account expense = account(user, ledger, "9001", "Depreciation expense", "PERIOD_EXPENSE", "DEBIT", requirements);
        LedgerResponses.Account accumulated = account(user, ledger, "9002", "Accumulated depreciation", "NON_CURRENT_ASSET", "CREDIT", requirements);
        UUID period = ledgers.periodId(ledger, "2026-01");
        createAsset(user, ledger, expense.id(), accumulated.id(), departmentValue, "FA-WITH-DEPT");

        FixedAssetResponses.DepreciationRun run = fixedAssets.generateDepreciation(user, ledger,
                new FixedAssetRequests.DepreciationAction(period, "test"));

        assertThat(vouchers.find(user, ledger, run.voucherId()).lines())
                .filteredOn(line -> line.accountId().equals(expense.id()) || line.accountId().equals(accumulated.id()))
                .allSatisfy(line -> assertThat(line.dimensions()).containsExactly(
                        new VoucherResponses.Dimension(department.id(), departmentValue)));
    }

    @Test
    void blocksPreviewForMissingOrInactiveRequiredDepartments() {
        UUID user = UUID.randomUUID();
        UUID ledger = ledger(user, "depreciation-invalid-required-department");
        LedgerResponses.DimensionType department = department(user, ledger);
        UUID departmentValue = departmentValue(user, ledger, department);
        LedgerResponses.Account expense = account(user, ledger, "9001", "Depreciation expense", "PERIOD_EXPENSE", "DEBIT", List.of(
                new LedgerRequests.DimensionRequirement(department.id(), true)));
        LedgerResponses.Account accumulated = account(user, ledger, "9002", "Accumulated depreciation", "NON_CURRENT_ASSET", "CREDIT", List.of());
        UUID period = ledgers.periodId(ledger, "2026-01");
        createAsset(user, ledger, expense.id(), accumulated.id(), null, "FA-MISSING-DEPT");
        createAsset(user, ledger, expense.id(), accumulated.id(), departmentValue, "FA-INACTIVE-DEPT");
        jdbc.update("update dimension_value set status = 'INACTIVE' where id = ?", departmentValue);

        FixedAssetResponses.DepreciationPreview preview = fixedAssets.previewDepreciation(user, ledger, period);

        assertThat(preview.blockers()).anyMatch(blocker -> blocker.contains("FA-MISSING-DEPT")
                && blocker.contains("要求有效的部门"));
        assertThat(preview.blockers()).anyMatch(blocker -> blocker.contains("FA-INACTIVE-DEPT")
                && blocker.contains("不存在或已停用"));
    }

    @Test
    void blocksPreviewWhenARequiredNonDepartmentDimensionCannotBeProvided() {
        UUID user = UUID.randomUUID();
        UUID ledger = ledger(user, "depreciation-unsupported-required-dimension");
        LedgerResponses.DimensionType customer = ledgers.listDimensionTypes(user, ledger).stream()
                .filter(type -> "CUSTOMER".equals(type.code())).findFirst().orElseThrow();
        LedgerResponses.Account expense = account(user, ledger, "9001", "Depreciation expense", "PERIOD_EXPENSE", "DEBIT", List.of(
                new LedgerRequests.DimensionRequirement(customer.id(), true)));
        LedgerResponses.Account accumulated = account(user, ledger, "9002", "Accumulated depreciation", "NON_CURRENT_ASSET", "CREDIT", List.of());
        UUID period = ledgers.periodId(ledger, "2026-01");
        createAsset(user, ledger, expense.id(), accumulated.id(), null, "FA-CUSTOMER-REQUIRED");

        FixedAssetResponses.DepreciationPreview preview = fixedAssets.previewDepreciation(user, ledger, period);

        assertThat(preview.blockers()).anyMatch(blocker -> blocker.contains("FA-CUSTOMER-REQUIRED")
                && blocker.contains("系统暂不支持的辅助核算维度 CUSTOMER"));
    }

    @Test
    void acceptsNullableImpairmentAccountWhenCreatingAndUpdatingAssets() {
        UUID user = UUID.randomUUID();
        UUID ledger = ledger(user, "nullable-impairment-account");
        LedgerResponses.Account expense = account(user, ledger, "9001", "Depreciation expense", "PERIOD_EXPENSE", "DEBIT", List.of());
        LedgerResponses.Account accumulated = account(user, ledger, "9002", "Accumulated depreciation", "NON_CURRENT_ASSET", "CREDIT", List.of());
        FixedAssetResponses.Category category = fixedAssets.createCategory(user, ledger,
                new FixedAssetRequests.CategoryCreate("FA-NULL-IMPAIR", "nullable impairment category", 36, BigDecimal.ZERO,
                        accumulated.id(), accumulated.id(), expense.id(), null, accumulated.id(), accumulated.id(), accumulated.id()));

        assertThat(category.impairmentAccountId()).isNull();
        FixedAssetResponses.Asset asset = fixedAssets.createAsset(user, ledger, new FixedAssetRequests.AssetCreate(
                category.id(), "FA-NULL-IMPAIR", "nullable impairment asset", BigDecimal.ONE, LocalDate.of(2025, 12, 1),
                new BigDecimal("3600"), BigDecimal.ZERO, 36, BigDecimal.ZERO, BigDecimal.ZERO, 0,
                BigDecimal.ZERO, null, null, null, null, null, null, null, null, null, null));

        assertThat(asset.impairmentAccountId()).isNull();
        FixedAssetResponses.Asset updated = fixedAssets.updateAsset(user, ledger, asset.id(), new FixedAssetRequests.AssetPatch(
                asset.version(), null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, "更新备注"));

        assertThat(updated.impairmentAccountId()).isNull();
        assertThat(updated.note()).isEqualTo("更新备注");
    }

    @Test
    void blocksPreviewWhenDepreciationAccountsAreInactive() {
        UUID user = UUID.randomUUID();
        UUID ledger = ledger(user, "inactive-depreciation-account");
        LedgerResponses.Account expense = account(user, ledger, "9001", "Depreciation expense", "PERIOD_EXPENSE", "DEBIT", List.of());
        LedgerResponses.Account accumulated = account(user, ledger, "9002", "Accumulated depreciation", "NON_CURRENT_ASSET", "CREDIT", List.of());
        UUID period = ledgers.periodId(ledger, "2026-01");
        createAsset(user, ledger, expense.id(), accumulated.id(), null, "FA-INACTIVE-ACCOUNT");
        inactivate(user, ledger, expense);
        inactivate(user, ledger, accumulated);

        FixedAssetResponses.DepreciationPreview preview = fixedAssets.previewDepreciation(user, ledger, period);

        assertThat(preview.blockers()).contains(
                "FA-INACTIVE-ACCOUNT：折旧费用科目必须是启用的末级科目",
                "FA-INACTIVE-ACCOUNT：累计折旧科目必须是启用的末级科目");
    }

    private UUID ledger(UUID user, String name) {
        return ledgers.create(new CurrentUserResolver.ResolvedUser(user, "test", user.toString()),
                new LedgerRequests.Create(name, "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
    }

    private LedgerResponses.DimensionType department(UUID user, UUID ledger) {
        return ledgers.listDimensionTypes(user, ledger).stream()
                .filter(type -> "DEPARTMENT".equals(type.code())).findFirst().orElseThrow();
    }

    private UUID departmentValue(UUID user, UUID ledger, LedgerResponses.DimensionType department) {
        return ledgers.createDimensionValue(user, ledger, department.id(),
                new LedgerRequests.DimensionValueCreate("D001", "Depreciation department")).id();
    }

    private LedgerResponses.Account account(UUID user, UUID ledger, String code, String name, String category,
                                            String normalBalance, List<LedgerRequests.DimensionRequirement> requirements) {
        return ledgers.createAccount(user, ledger, new LedgerRequests.AccountCreate(
                code, name, category, normalBalance, null, false, null, false, null, requirements));
    }

    private void inactivate(UUID user, UUID ledger, LedgerResponses.Account account) {
        ledgers.updateAccount(user, ledger, account.id(), new LedgerRequests.AccountPatch(
                account.version(), null, null, null, null, null, "INACTIVE", null, null, null, null, null));
    }

    private void createAsset(UUID user, UUID ledger, UUID expense, UUID accumulated, UUID departmentValue, String code) {
        UUID category = fixedAssets.createCategory(user, ledger, new FixedAssetRequests.CategoryCreate(
                code + "-CATEGORY", code + " category", 36, BigDecimal.ZERO,
                accumulated, accumulated, expense, expense, expense, expense, expense)).id();
        fixedAssets.createAsset(user, ledger, new FixedAssetRequests.AssetCreate(
                category, code, code + " asset", BigDecimal.ONE, LocalDate.of(2025, 12, 1),
                new BigDecimal("3600"), BigDecimal.ZERO, 36, BigDecimal.ZERO, BigDecimal.ZERO, 0,
                BigDecimal.ZERO, departmentValue, null, accumulated, accumulated, expense, expense, expense, expense, expense, null));
    }
}
