package com.example.accounting.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.shared.web.ApiProblemException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.example.accounting.voucher.VoucherRequests;
import com.example.accounting.voucher.VoucherService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.example.accounting.ledger.internal.persistence.AccountManagementRepository;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class AccountExchangeIntegrationTest {

    @Autowired
    private LedgerService ledgers;

    @Autowired
    private AccountExchangeService exchange;

    @Autowired
    private VoucherService vouchers;

    @Autowired
    private AccountManagementRepository accounts;

    @Autowired
    private TransactionTemplate txTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void rejectsExistingCodeInAppendModeAndInfersStandardKeyInOverwriteMode() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID ledgerId = ledgers.create(user(owner), new LedgerRequests.Create(
                "account-key-update", "SME", "2011-17", "CNY",
                LocalDate.of(2026, 1, 1), false)).id();

        byte[] kingdee;
        try (var source = new HSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = source.createSheet("科目列表");
            var header = sheet.createRow(0);
            List.of("编码", "名称", "类别", "余额方向").forEach(value ->
                    header.createCell(header.getLastCellNum() < 0 ? 0 : header.getLastCellNum()).setCellValue(value));
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("5601");
            row.createCell(1).setCellValue("历史管理费用名称");
            row.createCell(2).setCellValue("期间费用");
            row.createCell(3).setCellValue("借");
            source.write(output);
            kingdee = output.toByteArray();
        }
        // In APPEND mode, 5601 already exists in SME template, so it's rejected with ERROR:CODE_EXISTS
        AccountExchangeService.Preview appendPreview = exchange.preview(owner, ledgerId,
                AccountExchangeService.Format.KINGDEE, AccountExchangeService.ImportMode.APPEND, null,
                "existing-5601.xls", kingdee.length, new ByteArrayInputStream(kingdee));
        assertThat(appendPreview.rows()).singleElement().satisfies(row -> {
            assertThat(row.issues()).contains("ERROR:CODE_EXISTS");
        });

        // In OVERWRITE mode on eligible ledger, 5601 resolves to standard account key EXPENSE.SELLING
        AccountExchangeService.Preview overwritePreview = exchange.preview(owner, ledgerId,
                AccountExchangeService.Format.KINGDEE, AccountExchangeService.ImportMode.OVERWRITE, null,
                "existing-5601.xls", kingdee.length, new ByteArrayInputStream(kingdee));
        assertThat(overwritePreview.rows()).singleElement().satisfies(row -> {
            assertThat(row.issues()).isEmpty();
            assertThat(row.cleanedData()).containsEntry("standardAccountKey", "EXPENSE.SELLING");
        });

        byte[] exported = exchange.export(owner, ledgerId, AccountExchangeService.Format.STANDARD);
        byte[] conflicting;
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(exported));
             var output = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheet("Accounts");
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                if ("1001".equals(sheet.getRow(index).getCell(0).getStringCellValue())) {
                    sheet.getRow(index).getCell(10).setCellValue("ASSET.BANK_DEPOSIT");
                }
            }
            workbook.write(output);
            conflicting = output.toByteArray();
        }
        AccountExchangeService.Preview standardPreview = exchange.preview(owner, ledgerId,
                AccountExchangeService.Format.STANDARD, AccountExchangeService.ImportMode.APPEND, null,
                "conflicting-key.xlsx", conflicting.length, new ByteArrayInputStream(conflicting));
        assertThat(standardPreview.rows()).filteredOn(row -> row.accountCode().equals("1001"))
                .singleElement().satisfies(row ->
                        assertThat(row.issues()).contains("ERROR:CODE_EXISTS"));
    }

    @Test
    void previewsAndCommitsANativeKingdeeAccountList() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID ledgerId = ledgers.create(user(owner), new LedgerRequests.Create(
                "kingdee-native-accounts", "SME", "2011-17", "CNY",
                LocalDate.of(2018, 1, 1), false, new AccountCodeRule(4, 3, 3))).id();
        byte[] workbook;
        try (var source = new HSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = source.createSheet("科目列表");
            var header = sheet.createRow(0);
            List.of("编码", "名称", "类别", "余额方向")
                    .forEach(value -> header.createCell(header.getLastCellNum() < 0 ? 0 : header.getLastCellNum())
                            .setCellValue(value));
            var parent = sheet.createRow(1);
            parent.createCell(0).setCellValue("1002");
            parent.createCell(1).setCellValue("银行存款");
            parent.createCell(2).setCellValue("流动资产");
            parent.createCell(3).setCellValue("借");
            var child = sheet.createRow(2);
            child.createCell(0).setCellValue("10020001");
            child.createCell(1).setCellValue("基本户");
            child.createCell(2).setCellValue("流动资产");
            child.createCell(3).setCellValue("借");
            source.write(output);
            workbook = output.toByteArray();
        }

        AccountExchangeService.Preview preview = exchange.preview(owner, ledgerId,
                AccountExchangeService.Format.KINGDEE, AccountExchangeService.ImportMode.OVERWRITE,
                new AccountCodeRule(4, 3, 3), "accounts.xls", workbook.length,
                new ByteArrayInputStream(workbook));

        assertThat(preview.rows()).hasSize(2);
        assertThat(preview.rows()).allMatch(row -> row.issues().isEmpty());
        // Direct atomic commit without row-by-row decisions
        exchange.commit(owner, ledgerId, preview.id());

        List<LedgerResponses.Account> accounts = ledgers.listAccounts(owner, ledgerId);
        UUID parentId = accounts.stream().filter(account -> account.code().equals("1002"))
                .findFirst().orElseThrow().id();
        assertThat(accounts).filteredOn(account -> account.code().equals("10020001"))
                .singleElement().satisfies(account -> {
                    assertThat(account.parentId()).isEqualTo(parentId);
                    assertThat(account.category()).isEqualTo("CURRENT_ASSET");
                    assertThat(account.normalBalance()).isEqualTo("DEBIT");
                });
    }

    @Test
    void rejectsSameNameAccountsWithinTheSameParentAndImportsDistinctNames() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID ledgerId = ledgers.create(user(owner), new LedgerRequests.Create(
                "account-name-unique", "SME", "2011-17", "CNY",
                LocalDate.of(2026, 1, 1), false)).id();
        byte[] duplicateWorkbook;
        try (var source = new HSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = source.createSheet("科目列表");
            var header = sheet.createRow(0);
            List.of("编码", "名称", "类别", "余额方向")
                    .forEach(value -> header.createCell(header.getLastCellNum() < 0 ? 0 : header.getLastCellNum())
                            .setCellValue(value));
            var first = sheet.createRow(1);
            first.createCell(0).setCellValue("100201");
            first.createCell(1).setCellValue("重复名称");
            first.createCell(2).setCellValue("流动资产");
            first.createCell(3).setCellValue("借");
            var second = sheet.createRow(2);
            second.createCell(0).setCellValue("100202");
            second.createCell(1).setCellValue("重复名称");
            second.createCell(2).setCellValue("流动资产");
            second.createCell(3).setCellValue("借");
            source.write(output);
            duplicateWorkbook = output.toByteArray();
        }

        AccountExchangeService.Preview preview = exchange.preview(owner, ledgerId,
                AccountExchangeService.Format.KINGDEE, "duplicate-name.xls", duplicateWorkbook.length,
                new ByteArrayInputStream(duplicateWorkbook));
        assertThat(preview.rows().get(1).issues()).contains("ERROR:SAME_NAME_EXISTS");
        assertThatThrownBy(() -> exchange.commit(owner, ledgerId, preview.id()))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        ex -> assertThat(ex.code()).isEqualTo("ACCOUNT_IMPORT_BLOCKED"));

        byte[] distinctWorkbook;
        try (var source = new HSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = source.createSheet("科目列表");
            var header = sheet.createRow(0);
            List.of("编码", "名称", "类别", "余额方向")
                    .forEach(value -> header.createCell(header.getLastCellNum() < 0 ? 0 : header.getLastCellNum())
                            .setCellValue(value));
            var first = sheet.createRow(1);
            first.createCell(0).setCellValue("100201");
            first.createCell(1).setCellValue("基本存款户");
            first.createCell(2).setCellValue("流动资产");
            first.createCell(3).setCellValue("借");
            var second = sheet.createRow(2);
            second.createCell(0).setCellValue("100202");
            second.createCell(1).setCellValue("一般存款户");
            second.createCell(2).setCellValue("流动资产");
            second.createCell(3).setCellValue("借");
            source.write(output);
            distinctWorkbook = output.toByteArray();
        }

        AccountExchangeService.Preview validPreview = exchange.preview(owner, ledgerId,
                AccountExchangeService.Format.KINGDEE, "distinct-name.xls", distinctWorkbook.length,
                new ByteArrayInputStream(distinctWorkbook));
        assertThat(validPreview.rows()).allMatch(r -> r.issues().isEmpty());
        assertThat(exchange.commit(owner, ledgerId, validPreview.id()).status()).isEqualTo("COMMITTED");
        assertThat(ledgers.listAccounts(owner, ledgerId))
                .extracting(LedgerResponses.Account::code)
                .contains("100201", "100202");
    }

    @Test
    void exportsPreviewsAndOverwritesAStandardWorkbookWithoutChangingAccounts() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID ledgerId = ledgers.create(user(owner), new LedgerRequests.Create(
                "account-exchange", "SME", "2011-17", "CNY",
                LocalDate.of(2026, 1, 1), false)).id();
        byte[] exported = exchange.export(owner, ledgerId, AccountExchangeService.Format.STANDARD);

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(exported))) {
            assertThat(workbook.getSheet("Metadata")).isNotNull();
            assertThat(workbook.getSheet("Accounts")).isNotNull();
            assertThat(workbook.getSheet("Accounts").getRow(0).getCell(10).getStringCellValue())
                    .isEqualTo("StandardAccountKey");
            assertThat(workbook.getSheet("DimensionTypes")).isNotNull();
            assertThat(workbook.getSheet("AccountDimensions")).isNotNull();
        }

        AccountExchangeService.Preview preview = exchange.preview(
                owner, ledgerId, AccountExchangeService.Format.STANDARD,
                AccountExchangeService.ImportMode.OVERWRITE, null,
                "accounts.xlsx", exported.length, new ByteArrayInputStream(exported));
        assertThat(preview.rows()).hasSize(18);
        assertThat(preview.rows()).allMatch(row -> row.issues().isEmpty());

        AccountExchangeService.Preview committed = exchange.commit(owner, ledgerId, preview.id());
        assertThat(committed.status()).isEqualTo("COMMITTED");
        assertThat(ledgers.listAccounts(owner, ledgerId)).hasSize(18);
        assertThat(ledgers.listAccounts(owner, ledgerId))
                .allMatch(account -> account.standardAccountKey() != null);
    }

    @Test
    void exportsOnlyAccountsCreatedInsideTheSelectedPeriod() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID ledgerId = ledgers.create(user(owner), new LedgerRequests.Create(
                "period-account-export", "SME", "2011-17", "CNY",
                LocalDate.of(2026, 1, 1), false)).id();
        LedgerResponses.Period period = ledgers.listPeriods(owner, ledgerId).stream()
                .filter(candidate -> candidate.periodCode().equals("2026-03"))
                .findFirst().orElseThrow();
        ZoneId accountingZone = ZoneId.of("Asia/Shanghai");
        var startInclusive = period.startDate().atStartOfDay(accountingZone).toOffsetDateTime();
        var endExclusive = period.endDate().plusDays(1).atStartOfDay(accountingZone).toOffsetDateTime();

        jdbc.update("update ledger_account set created_at = ? where ledger_id = ?",
                startInclusive.minusDays(1), ledgerId);
        LedgerResponses.Account parent = ledgers.createAccount(owner, ledgerId,
                new LedgerRequests.AccountCreate("1998", "期间外父科目",
                        "ASSET.CASH", "CURRENT_ASSET", "DEBIT"));
        jdbc.update("update ledger_account set created_at = ? where ledger_id = ? and id = ?",
                startInclusive.minusDays(1), ledgerId, parent.id());
        ledgers.createAccount(owner, ledgerId,
                new LedgerRequests.AccountCreate(
                        "199801", "期间内子科目", "CURRENT_ASSET", "DEBIT", parent.id(),
                        false, null, false, null, List.of()));
        ledgers.createAccount(owner, ledgerId,
                new LedgerRequests.AccountCreate("1997", "期间外科目",
                        "ASSET.CASH", "CURRENT_ASSET", "DEBIT"));
        jdbc.update("update ledger_account set created_at = ? where ledger_id = ? and code = ?",
                startInclusive, ledgerId, "199801");
        jdbc.update("update ledger_account set created_at = ? where ledger_id = ? and code = ?",
                endExclusive, ledgerId, "1997");
        assertThat(ledgers.listAccounts(owner, ledgerId)).anySatisfy(account -> {
            assertThat(account.code()).isEqualTo("199801");
            assertThat(account.createdAt()).isEqualTo(startInclusive);
        });

        byte[] exported = exchange.export(
                owner, ledgerId, AccountExchangeService.Format.STANDARD, period.id());
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(exported))) {
            var sheet = workbook.getSheet("Accounts");
            List<String> codes = new ArrayList<>();
            for (int rowNumber = 1; rowNumber <= sheet.getLastRowNum(); rowNumber++) {
                codes.add(sheet.getRow(rowNumber).getCell(0).getStringCellValue());
            }
            assertThat(codes).containsExactly("199801");
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("1998");
        }

        byte[] kingdeeExported = exchange.export(
                owner, ledgerId, AccountExchangeService.Format.KINGDEE, period.id());
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(kingdeeExported))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(1);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("199801");
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("流动资产");
        }

        UUID otherLedgerId = ledgers.create(user(owner), new LedgerRequests.Create(
                "other-period-account-export", "SME", "2011-17", "CNY",
                LocalDate.of(2026, 1, 1), false)).id();
        UUID otherPeriodId = ledgers.listPeriods(owner, otherLedgerId).getFirst().id();
        assertThatThrownBy(() -> exchange.export(
                owner, ledgerId, AccountExchangeService.Format.STANDARD, otherPeriodId))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        exception -> assertThat(exception.code()).isEqualTo("PERIOD_NOT_FOUND"));
    }

    @Test
    void batchDecisionsAreAppliedAtomically() {
        UUID owner = UUID.randomUUID();
        UUID ledgerId = ledgers.create(user(owner), new LedgerRequests.Create(
                "account-batch-decisions", "SME", "2011-17", "CNY",
                LocalDate.of(2026, 1, 1), false)).id();
        byte[] exported = exchange.export(owner, ledgerId, AccountExchangeService.Format.STANDARD);
        AccountExchangeService.Preview preview = exchange.preview(
                owner, ledgerId, AccountExchangeService.Format.STANDARD,
                "batch.xlsx", exported.length, new ByteArrayInputStream(exported));
        AccountExchangeService.PreviewRow first = preview.rows().getFirst();

        assertThatThrownBy(() -> exchange.decideAll(owner, ledgerId, preview.id(), List.of(
                new AccountExchangeService.RowDecision(first.rowNo(),
                        new AccountExchangeService.Decision("MAP", first.targetAccountId(), null)),
                new AccountExchangeService.RowDecision(999_999,
                        new AccountExchangeService.Decision("SKIP", null, null)))))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        exception -> assertThat(exception.code()).isEqualTo("ACCOUNT_IMPORT_ROW_NOT_FOUND"));

        assertThat(exchange.get(owner, ledgerId, preview.id()).rows().getFirst().confirmed()).isFalse();

        assertThatThrownBy(() -> exchange.decideAll(owner, ledgerId, preview.id(), List.of(
                new AccountExchangeService.RowDecision(first.rowNo(),
                        new AccountExchangeService.Decision("MAP", first.targetAccountId(), null)))))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        exception -> assertThat(exception.code()).isEqualTo("ACCOUNT_IMPORT_DECISIONS_INCOMPLETE"));
        assertThat(exchange.get(owner, ledgerId, preview.id()).rows()).noneMatch(AccountExchangeService.PreviewRow::confirmed);

        AccountExchangeService.Preview decided = exchange.decideAll(owner, ledgerId, preview.id(),
                preview.rows().stream().map(row -> new AccountExchangeService.RowDecision(
                        row.rowNo(), new AccountExchangeService.Decision("MAP", row.targetAccountId(), null)))
                        .toList());
        assertThat(decided.rows()).allMatch(AccountExchangeService.PreviewRow::confirmed);
    }

    @Test
    void rejectsFormulaCellsBeforeCreatingAPreview() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID ledgerId = ledgers.create(user(owner), new LedgerRequests.Create(
                "formula-rejection", "SME", "2011-17", "CNY",
                LocalDate.of(2026, 1, 1), false)).id();
        byte[] template = exchange.template(owner, ledgerId, AccountExchangeService.Format.STANDARD);
        byte[] formula;
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(template));
             var output = new ByteArrayOutputStream()) {
            var row = workbook.getSheet("Accounts").createRow(1);
            row.createCell(0).setCellFormula("1+1");
            workbook.write(output);
            formula = output.toByteArray();
        }
        assertThatThrownBy(() -> exchange.preview(
                owner, ledgerId, AccountExchangeService.Format.STANDARD,
                "formula.xlsx", formula.length, new ByteArrayInputStream(formula)))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        exception -> assertThat(exception.code()).isEqualTo("ACCOUNT_IMPORT_INVALID"));
    }

    @Test
    void rejectsStandardWorkbookWithoutVersionMetadata() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID ledgerId = ledgers.create(user(owner), new LedgerRequests.Create(
                "metadata-rejection", "SME", "2011-17", "CNY",
                LocalDate.of(2026, 1, 1), false)).id();
        byte[] template = exchange.template(owner, ledgerId, AccountExchangeService.Format.STANDARD);
        byte[] missingMetadata;
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(template));
             var output = new ByteArrayOutputStream()) {
            workbook.removeSheetAt(workbook.getSheetIndex("Metadata"));
            workbook.write(output);
            missingMetadata = output.toByteArray();
        }
        assertThatThrownBy(() -> exchange.preview(
                owner, ledgerId, AccountExchangeService.Format.STANDARD,
                "missing-metadata.xlsx", missingMetadata.length, new ByteArrayInputStream(missingMetadata)))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        exception -> assertThat(exception.code()).isEqualTo("ACCOUNT_IMPORT_INVALID"));
    }

    @Test
    void standardWorkbookPreservesCustomDimensionsAndCashFlowDefaultsAcrossLedgers() {
        UUID owner = UUID.randomUUID();
        UUID source = ledgers.create(user(owner), new LedgerRequests.Create(
                "source", "SME", "2011-17", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        LedgerResponses.DimensionType region = ledgers.createDimensionType(owner, source,
                new LedgerRequests.DimensionTypeCreate("REGION", "区域", false));
        UUID operating = ledgers.listCashFlowItems(owner, source).stream()
                .filter(item -> item.code().equals("SME_CF_01_SALES_RECEIPTS")).findFirst().orElseThrow().id();
        ledgers.createAccount(owner, source, new LedgerRequests.AccountCreate(
                "100101", "区域现金", "CURRENT_ASSET", "DEBIT", null, true, operating,
                false, null, List.of(new LedgerRequests.DimensionRequirement(region.id(), true))));
        byte[] exported = exchange.export(owner, source, AccountExchangeService.Format.STANDARD);

        UUID target = ledgers.create(user(owner), new LedgerRequests.Create(
                "target", "SME", "2011-17", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        AccountExchangeService.Preview preview = exchange.preview(owner, target,
                AccountExchangeService.Format.STANDARD, AccountExchangeService.ImportMode.OVERWRITE, null,
                "cross-ledger.xlsx", exported.length,
                new ByteArrayInputStream(exported));
        exchange.commit(owner, target, preview.id());

        LedgerResponses.Account imported = ledgers.listAccounts(owner, target).stream()
                .filter(account -> account.code().equals("100101")).findFirst().orElseThrow();
        assertThat(imported.cashFlowRequired()).isTrue();
        assertThat(ledgers.listCashFlowItems(owner, target)).filteredOn(item ->
                        item.id().equals(imported.defaultCashFlowItemId()))
                .extracting(LedgerResponses.CashFlowItem::code).containsExactly("SME_CF_01_SALES_RECEIPTS");
        assertThat(imported.dimensionRequirements()).singleElement().satisfies(binding -> {
            assertThat(binding.code()).isEqualTo("REGION");
            assertThat(binding.required()).isTrue();
        });
    }

    @Test
    void appendModeSupportsParentAppearingLaterAndRejectsUsedParent() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID ledgerId = ledgers.create(user(owner), new LedgerRequests.Create(
                "append-order-test", "SME", "2011-17", "CNY",
                LocalDate.of(2026, 1, 1), false)).id();

        // 1. Child 199901 in row 1, parent 1999 in row 2 (parent appears later in file)
        byte[] outOfOrderWorkbook;
        try (var source = new HSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = source.createSheet("科目列表");
            var header = sheet.createRow(0);
            List.of("编码", "名称", "类别", "余额方向").forEach(value ->
                    header.createCell(header.getLastCellNum() < 0 ? 0 : header.getLastCellNum()).setCellValue(value));
            var childRow = sheet.createRow(1);
            childRow.createCell(0).setCellValue("140501");
            childRow.createCell(1).setCellValue("乱序子科目");
            childRow.createCell(2).setCellValue("流动资产");
            childRow.createCell(3).setCellValue("借");
            var parentRow = sheet.createRow(2);
            parentRow.createCell(0).setCellValue("1405");
            parentRow.createCell(1).setCellValue("库存商品");
            parentRow.createCell(2).setCellValue("流动资产");
            parentRow.createCell(3).setCellValue("借");
            source.write(output);
            outOfOrderWorkbook = output.toByteArray();
        }

        AccountExchangeService.Preview preview = exchange.preview(owner, ledgerId,
                AccountExchangeService.Format.KINGDEE, "out-of-order.xls", outOfOrderWorkbook.length,
                new ByteArrayInputStream(outOfOrderWorkbook));
        assertThat(preview.rows()).allMatch(r -> r.issues().isEmpty());
        exchange.commit(owner, ledgerId, preview.id());
        assertThat(ledgers.listAccounts(owner, ledgerId))
                .extracting(LedgerResponses.Account::code)
                .contains("1405", "140501");

        // 2. Used parent with voucher cannot become parent for new child
        UUID periodId = ledgers.listPeriods(owner, ledgerId).getFirst().id();
        UUID cashId = ledgers.listAccounts(owner, ledgerId).stream()
                .filter(a -> a.code().equals("1001")).findFirst().orElseThrow().id();
        UUID expenseId = ledgers.listAccounts(owner, ledgerId).stream()
                .filter(a -> a.code().equals("5601")).findFirst().orElseThrow().id();
        vouchers.create(owner, ledgerId, new VoucherRequests.Create(periodId, LocalDate.of(2026, 1, 15),
                "GENERAL", "1", "test voucher", List.of(
                new VoucherRequests.Line(expenseId, "DEBIT", "CNY", new java.math.BigDecimal("100.00"), java.math.BigDecimal.ONE, "test"),
                new VoucherRequests.Line(cashId, "CREDIT", "CNY", new java.math.BigDecimal("100.00"), java.math.BigDecimal.ONE, "test")
        )));

        byte[] appendUnderUsedParent;
        try (var source = new HSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = source.createSheet("科目列表");
            var header = sheet.createRow(0);
            List.of("编码", "名称", "类别", "余额方向").forEach(value ->
                    header.createCell(header.getLastCellNum() < 0 ? 0 : header.getLastCellNum()).setCellValue(value));
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("100101");
            row.createCell(1).setCellValue("新建现金子科目");
            row.createCell(2).setCellValue("流动资产");
            row.createCell(3).setCellValue("借");
            source.write(output);
            appendUnderUsedParent = output.toByteArray();
        }

        AccountExchangeService.Preview usedParentPreview = exchange.preview(owner, ledgerId,
                AccountExchangeService.Format.KINGDEE, "used-parent.xls", appendUnderUsedParent.length,
                new ByteArrayInputStream(appendUnderUsedParent));
        assertThat(usedParentPreview.rows().getFirst().issues())
                .contains("ERROR:USED_ACCOUNT_CANNOT_BECOME_PARENT");
        assertThatThrownBy(() -> exchange.commit(owner, ledgerId, usedParentPreview.id()))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        ex -> assertThat(ex.code()).isEqualTo("ACCOUNT_IMPORT_BLOCKED"));
    }

    @Test
    void overwriteModeEligibilityDisqualifiesWhenVoucherExists() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID ledgerId = ledgers.create(user(owner), new LedgerRequests.Create(
                "overwrite-blocker-test", "SME", "2011-17", "CNY",
                LocalDate.of(2026, 1, 1), false)).id();

        // Fresh ledger is eligible
        var eligibility = exchange.checkReconstructionEligibility(owner, ledgerId);
        assertThat(eligibility.eligible()).isTrue();
        assertThat(eligibility.blockers()).isEmpty();

        // Create a voucher to disqualify
        UUID periodId = ledgers.listPeriods(owner, ledgerId).getFirst().id();
        UUID cashId = ledgers.listAccounts(owner, ledgerId).stream()
                .filter(a -> a.code().equals("1001")).findFirst().orElseThrow().id();
        UUID expenseId = ledgers.listAccounts(owner, ledgerId).stream()
                .filter(a -> a.code().equals("5601")).findFirst().orElseThrow().id();
        vouchers.create(owner, ledgerId, new VoucherRequests.Create(periodId, LocalDate.of(2026, 1, 15),
                "GENERAL", "1", "test voucher", List.of(
                new VoucherRequests.Line(expenseId, "DEBIT", "CNY", new java.math.BigDecimal("50.00"), java.math.BigDecimal.ONE, "test"),
                new VoucherRequests.Line(cashId, "CREDIT", "CNY", new java.math.BigDecimal("50.00"), java.math.BigDecimal.ONE, "test")
        )));

        var disqualified = exchange.checkReconstructionEligibility(owner, ledgerId);
        assertThat(disqualified.eligible()).isFalse();
        assertThat(disqualified.blockers()).anySatisfy(b -> assertThat(b.type()).isEqualTo("VOUCHER"));

        byte[] kingdee;
        try (var source = new HSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = source.createSheet("科目列表");
            var header = sheet.createRow(0);
            List.of("编码", "名称", "类别", "余额方向").forEach(value ->
                    header.createCell(header.getLastCellNum() < 0 ? 0 : header.getLastCellNum()).setCellValue(value));
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("1002");
            row.createCell(1).setCellValue("新银行存款");
            row.createCell(2).setCellValue("流动资产");
            row.createCell(3).setCellValue("借");
            source.write(output);
            kingdee = output.toByteArray();
        }

        AccountExchangeService.Preview preview = exchange.preview(owner, ledgerId,
                AccountExchangeService.Format.KINGDEE, AccountExchangeService.ImportMode.OVERWRITE, null,
                "disqualified.xls", kingdee.length, new ByteArrayInputStream(kingdee));
        assertThat(preview.errorCount()).isGreaterThan(0);
        assertThat(preview.blockers()).anySatisfy(b -> assertThat(b.type()).isEqualTo("VOUCHER"));
        assertThatThrownBy(() -> exchange.commit(owner, ledgerId, preview.id()))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        ex -> assertThat(ex.code()).isEqualTo("ACCOUNT_IMPORT_BLOCKED"));
    }

    @Test
    void infersCodeRuleAndReportsWarningsForUnobservedLevels() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID ledgerId = ledgers.create(user(owner), new LedgerRequests.Create(
                "rule-inference-test", "SME", "2011-17", "CNY",
                LocalDate.of(2026, 1, 1), false)).id();

        byte[] workbook;
        try (var source = new HSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = source.createSheet("科目列表");
            var header = sheet.createRow(0);
            List.of("编码", "名称", "类别", "余额方向").forEach(value ->
                    header.createCell(header.getLastCellNum() < 0 ? 0 : header.getLastCellNum()).setCellValue(value));
            // 4-2-2 observed (level 4 unobserved)
            var r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("1001");
            r1.createCell(1).setCellValue("库存现金");
            r1.createCell(2).setCellValue("流动资产");
            r1.createCell(3).setCellValue("借");
            var r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("100101");
            r2.createCell(1).setCellValue("人民币");
            r2.createCell(2).setCellValue("流动资产");
            r2.createCell(3).setCellValue("借");
            var r3 = sheet.createRow(3);
            r3.createCell(0).setCellValue("10010101");
            r3.createCell(1).setCellValue("总部现金");
            r3.createCell(2).setCellValue("流动资产");
            r3.createCell(3).setCellValue("借");
            source.write(output);
            workbook = output.toByteArray();
        }

        AccountExchangeService.Preview preview = exchange.preview(owner, ledgerId,
                AccountExchangeService.Format.KINGDEE, AccountExchangeService.ImportMode.OVERWRITE, null,
                "rule-infer.xls", workbook.length, new ByteArrayInputStream(workbook));

        assertThat(preview.targetCodeRule().level2Width()).isEqualTo(2);
        assertThat(preview.targetCodeRule().level3Width()).isEqualTo(2);
        assertThat(preview.codeRuleSources()).containsEntry("level2", "OBSERVED");
        assertThat(preview.codeRuleSources()).containsEntry("level3", "OBSERVED");
        assertThat(preview.codeRuleSources()).containsEntry("level4", "LEDGER_DEFAULT");
        assertThat(preview.warnings()).anySatisfy(w -> assertThat(w).contains("第四级级长使用默认值"));
    }

    @Test
    void infersCodeRuleCorrectlyRegardlessOfRowOrder() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID ledgerId = ledgers.create(user(owner), new LedgerRequests.Create(
                "order-independent-inference", "SME", "2011-17", "CNY",
                LocalDate.of(2026, 1, 1), false)).id();

        // 4-3-3 structure where Level 3 (10 digits) row comes BEFORE Level 2 (7 digits) row
        byte[] template = exchange.template(owner, ledgerId, AccountExchangeService.Format.STANDARD);
        byte[] workbook;
        try (var source = WorkbookFactory.create(new ByteArrayInputStream(template));
             var output = new ByteArrayOutputStream()) {
            var sheet = source.getSheet("Accounts");

            // Row 1: Level 3 (Child) first!
            var r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("1002001001");
            r1.createCell(1).setCellValue("人民币基本户");
            r1.createCell(2).setCellValue("1002001");
            r1.createCell(3).setCellValue("CURRENT_ASSET");
            r1.createCell(4).setCellValue("DEBIT");
            r1.createCell(5).setCellValue("ACTIVE");
            r1.createCell(6).setCellValue("false");
            r1.createCell(7).setCellValue("");
            r1.createCell(8).setCellValue("false");
            r1.createCell(9).setCellValue("");
            r1.createCell(10).setCellValue("ASSET.BANK_DEPOSIT");

            // Row 2: Level 2 (Parent) second!
            var r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("1002001");
            r2.createCell(1).setCellValue("工行存款");
            r2.createCell(2).setCellValue("1002");
            r2.createCell(3).setCellValue("CURRENT_ASSET");
            r2.createCell(4).setCellValue("DEBIT");
            r2.createCell(5).setCellValue("ACTIVE");
            r2.createCell(6).setCellValue("false");
            r2.createCell(7).setCellValue("");
            r2.createCell(8).setCellValue("false");
            r2.createCell(9).setCellValue("");
            r2.createCell(10).setCellValue("ASSET.BANK_DEPOSIT");

            // Row 3: Level 1 (Root) third!
            var r3 = sheet.createRow(3);
            r3.createCell(0).setCellValue("1002");
            r3.createCell(1).setCellValue("银行存款");
            r3.createCell(2).setCellValue("");
            r3.createCell(3).setCellValue("CURRENT_ASSET");
            r3.createCell(4).setCellValue("DEBIT");
            r3.createCell(5).setCellValue("ACTIVE");
            r3.createCell(6).setCellValue("false");
            r3.createCell(7).setCellValue("");
            r3.createCell(8).setCellValue("false");
            r3.createCell(9).setCellValue("");
            r3.createCell(10).setCellValue("ASSET.BANK_DEPOSIT");

            source.write(output);
            workbook = output.toByteArray();
        }

        AccountExchangeService.Preview preview = exchange.preview(owner, ledgerId,
                AccountExchangeService.Format.STANDARD, AccountExchangeService.ImportMode.OVERWRITE, null,
                "reverse-order.xlsx", workbook.length, new ByteArrayInputStream(workbook));

        assertThat(preview.targetCodeRule().level2Width()).isEqualTo(3);
        assertThat(preview.targetCodeRule().level3Width()).isEqualTo(3);
        assertThat(preview.errorCount()).isEqualTo(0);
        assertThat(preview.rows()).noneMatch(row -> row.issues().stream().anyMatch(i -> i.startsWith("ERROR:")));

        exchange.commit(owner, ledgerId, preview.id());
        List<LedgerResponses.Account> accounts = ledgers.listAccounts(owner, ledgerId);
        assertThat(accounts).extracting(LedgerResponses.Account::code)
                .contains("1002", "1002001", "1002001001");
    }

    @Test
    void preservesInactiveAccountStatusAfterCommit() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID ledgerId = ledgers.create(user(owner), new LedgerRequests.Create(
                "preserve-status", "SME", "2011-17", "CNY",
                LocalDate.of(2026, 1, 1), false)).id();

        byte[] template = exchange.template(owner, ledgerId, AccountExchangeService.Format.STANDARD);
        byte[] workbook;
        try (var source = WorkbookFactory.create(new ByteArrayInputStream(template));
             var output = new ByteArrayOutputStream()) {
            var sheet = source.getSheet("Accounts");

            var r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("100209");
            r1.createCell(1).setCellValue("待清理旧账户");
            r1.createCell(2).setCellValue("1002");
            r1.createCell(3).setCellValue("CURRENT_ASSET");
            r1.createCell(4).setCellValue("DEBIT");
            r1.createCell(5).setCellValue("INACTIVE");
            r1.createCell(6).setCellValue("false");
            r1.createCell(7).setCellValue("");
            r1.createCell(8).setCellValue("false");
            r1.createCell(9).setCellValue("");
            r1.createCell(10).setCellValue("ASSET.BANK_DEPOSIT");

            source.write(output);
            workbook = output.toByteArray();
        }

        AccountExchangeService.Preview preview = exchange.preview(owner, ledgerId,
                AccountExchangeService.Format.STANDARD, AccountExchangeService.ImportMode.APPEND, null,
                "inactive-account.xlsx", workbook.length, new ByteArrayInputStream(workbook));

        assertThat(preview.rows()).singleElement().satisfies(r -> {
            assertThat(r.cleanedData()).containsEntry("status", "INACTIVE");
            assertThat(r.issues()).isEmpty();
        });

        exchange.commit(owner, ledgerId, preview.id());

        LedgerResponses.Account saved = ledgers.listAccounts(owner, ledgerId).stream()
                .filter(a -> a.code().equals("100209")).findFirst().orElseThrow();
        assertThat(saved.status()).isEqualTo("INACTIVE");
    }

    @Test
    void previewsAreImmutableAndRejectsMismatchedExpectedMode() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID ledgerId = ledgers.create(user(owner), new LedgerRequests.Create(
                "immutable-previews", "SME", "2011-17", "CNY",
                LocalDate.of(2026, 1, 1), false)).id();

        byte[] workbook;
        try (var source = new HSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = source.createSheet("科目列表");
            var header = sheet.createRow(0);
            List.of("编码", "名称", "类别", "余额方向")
                    .forEach(value -> header.createCell(header.getLastCellNum() < 0 ? 0 : header.getLastCellNum()).setCellValue(value));
            var r0 = sheet.createRow(1);
            r0.createCell(0).setCellValue("1002");
            r0.createCell(1).setCellValue("银行存款");
            r0.createCell(2).setCellValue("流动资产");
            r0.createCell(3).setCellValue("借");

            var r1 = sheet.createRow(2);
            r1.createCell(0).setCellValue("100201");
            r1.createCell(1).setCellValue("一般户");
            r1.createCell(2).setCellValue("流动资产");
            r1.createCell(3).setCellValue("借");
            source.write(output);
            workbook = output.toByteArray();
        }

        AccountExchangeService.Preview appendPreview = exchange.preview(owner, ledgerId,
                AccountExchangeService.Format.KINGDEE, AccountExchangeService.ImportMode.APPEND, null,
                "same.xls", workbook.length, new ByteArrayInputStream(workbook));

        AccountExchangeService.Preview overwritePreview = exchange.preview(owner, ledgerId,
                AccountExchangeService.Format.KINGDEE, AccountExchangeService.ImportMode.OVERWRITE, null,
                "same.xls", workbook.length, new ByteArrayInputStream(workbook));

        assertThat(appendPreview.id()).isNotEqualTo(overwritePreview.id());

        AccountExchangeService.Preview fetchedAppend = exchange.get(owner, ledgerId, appendPreview.id());
        assertThat(fetchedAppend.mode()).isEqualTo(AccountExchangeService.ImportMode.APPEND);

        assertThatThrownBy(() -> exchange.commit(owner, ledgerId, appendPreview.id(),
                new AccountExchangeService.CommitRequest(AccountExchangeService.ImportMode.OVERWRITE)))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        ex -> assertThat(ex.code()).isEqualTo("ACCOUNT_IMPORT_MODE_MISMATCH"));
    }

    @Test
    void handlesConcurrentCommitsIdempotently() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID ledgerId = ledgers.create(user(owner), new LedgerRequests.Create(
                "concurrent-commits", "SME", "2011-17", "CNY",
                LocalDate.of(2026, 1, 1), false)).id();

        byte[] workbook;
        try (var source = new HSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = source.createSheet("科目列表");
            var header = sheet.createRow(0);
            List.of("编码", "名称", "类别", "余额方向")
                    .forEach(value -> header.createCell(header.getLastCellNum() < 0 ? 0 : header.getLastCellNum()).setCellValue(value));
            var r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("100201");
            r1.createCell(1).setCellValue("并发测试户");
            r1.createCell(2).setCellValue("流动资产");
            r1.createCell(3).setCellValue("借");
            source.write(output);
            workbook = output.toByteArray();
        }

        AccountExchangeService.Preview preview = exchange.preview(owner, ledgerId,
                AccountExchangeService.Format.KINGDEE, AccountExchangeService.ImportMode.APPEND, null,
                "concurrent.xls", workbook.length, new ByteArrayInputStream(workbook));

        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicReference<Throwable> failureRef = new AtomicReference<>();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    AccountExchangeService.Preview res = exchange.commit(owner, ledgerId, preview.id());
                    if ("COMMITTED".equals(res.status())) {
                        successCount.incrementAndGet();
                    }
                } catch (Throwable t) {
                    failureRef.set(t);
                }
            });
        }

        startLatch.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(failureRef.get()).isNull();
        assertThat(successCount.get()).isEqualTo(threads);

        List<LedgerResponses.Account> accounts = ledgers.listAccounts(owner, ledgerId);
        assertThat(accounts).filteredOn(a -> a.code().equals("100201")).hasSize(1);
    }

    @Test
    void respectsSkipDecisionAndRejectsUpdate() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID ledgerId = ledgers.create(user(owner), new LedgerRequests.Create(
                "skip-decision-test", "SME", "2011-17", "CNY",
                LocalDate.of(2026, 1, 1), false)).id();

        byte[] workbook;
        try (var source = new HSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = source.createSheet("科目列表");
            var header = sheet.createRow(0);
            List.of("编码", "名称", "类别", "余额方向")
                    .forEach(value -> header.createCell(header.getLastCellNum() < 0 ? 0 : header.getLastCellNum()).setCellValue(value));
            var r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("100201");
            r1.createCell(1).setCellValue("要导入的科目");
            r1.createCell(2).setCellValue("流动资产");
            r1.createCell(3).setCellValue("借");

            var r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("100202");
            r2.createCell(1).setCellValue("要跳过的科目");
            r2.createCell(2).setCellValue("流动资产");
            r2.createCell(3).setCellValue("借");
            source.write(output);
            workbook = output.toByteArray();
        }

        AccountExchangeService.Preview preview = exchange.preview(owner, ledgerId,
                AccountExchangeService.Format.KINGDEE, AccountExchangeService.ImportMode.APPEND, null,
                "skip-test.xls", workbook.length, new ByteArrayInputStream(workbook));

        exchange.decide(owner, ledgerId, preview.id(), 2, new AccountExchangeService.Decision("SKIP", null, null));

        assertThatThrownBy(() -> exchange.decide(owner, ledgerId, preview.id(), 1,
                new AccountExchangeService.Decision("UPDATE", UUID.randomUUID(), null)))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        ex -> assertThat(ex.code()).isEqualTo("ACCOUNT_IMPORT_DECISION_UNSUPPORTED"));

        exchange.commit(owner, ledgerId, preview.id());

        List<LedgerResponses.Account> accounts = ledgers.listAccounts(owner, ledgerId);
        assertThat(accounts).filteredOn(a -> a.code().equals("100201")).hasSize(1);
        assertThat(accounts).filteredOn(a -> a.code().equals("100202")).isEmpty();
    }

    @Test
    void rollsBackEntirelyOnReconstructionFailureAndIsolatesOtherLedgers() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID ledgerA = ledgers.create(user(owner), new LedgerRequests.Create(
                "ledger-a-reconstruct", "SME", "2011-17", "CNY",
                LocalDate.of(2026, 1, 1), false, new AccountCodeRule(4, 2, 2))).id();

        UUID ledgerB = ledgers.create(user(owner), new LedgerRequests.Create(
                "ledger-b-isolated", "SME", "2011-17", "CNY",
                LocalDate.of(2026, 1, 1), false, new AccountCodeRule(3, 3, 2))).id();

        List<LedgerResponses.Account> originalAccountsA = ledgers.listAccounts(owner, ledgerA);
        List<LedgerResponses.Account> originalAccountsB = ledgers.listAccounts(owner, ledgerB);

        byte[] workbook;
        try (var source = new HSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = source.createSheet("科目列表");
            var header = sheet.createRow(0);
            List.of("编码", "名称", "类别", "余额方向")
                    .forEach(value -> header.createCell(header.getLastCellNum() < 0 ? 0 : header.getLastCellNum()).setCellValue(value));
            var r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("1002");
            r1.createCell(1).setCellValue("新银行存款");
            r1.createCell(2).setCellValue("流动资产");
            r1.createCell(3).setCellValue("借");
            source.write(output);
            workbook = output.toByteArray();
        }

        AccountExchangeService.Preview previewA = exchange.preview(owner, ledgerA,
                AccountExchangeService.Format.KINGDEE, AccountExchangeService.ImportMode.OVERWRITE, null,
                "reconstruct.xls", workbook.length, new ByteArrayInputStream(workbook));

        UUID periodA = ledgers.listPeriods(owner, ledgerA).getFirst().id();
        UUID cashA = ledgers.listAccounts(owner, ledgerA).stream()
                .filter(a -> a.code().equals("1001")).findFirst().orElseThrow().id();
        UUID expenseA = ledgers.listAccounts(owner, ledgerA).stream()
                .filter(a -> a.code().equals("5601")).findFirst().orElseThrow().id();
        vouchers.create(owner, ledgerA, new VoucherRequests.Create(periodA, LocalDate.of(2026, 1, 15),
                "GENERAL", "1", "blocking voucher", List.of(
                new VoucherRequests.Line(expenseA, "DEBIT", "CNY", new java.math.BigDecimal("10.00"), java.math.BigDecimal.ONE, "test"),
                new VoucherRequests.Line(cashA, "CREDIT", "CNY", new java.math.BigDecimal("10.00"), java.math.BigDecimal.ONE, "test")
        )));

        assertThatThrownBy(() -> exchange.commit(owner, ledgerA, previewA.id()))
                .isInstanceOf(ApiProblemException.class);

        // Ledger A accounts must remain intact with original SME template accounts
        List<LedgerResponses.Account> accountsAAfter = ledgers.listAccounts(owner, ledgerA);
        assertThat(accountsAAfter).hasSameSizeAs(originalAccountsA);

        // Ledger B accounts and rule must be completely unchanged
        List<LedgerResponses.Account> accountsBAfter = ledgers.listAccounts(owner, ledgerB);
        assertThat(accountsBAfter).hasSameSizeAs(originalAccountsB);
        assertThat(accountsBAfter.stream().map(LedgerResponses.Account::code).toList())
                .containsExactlyElementsOf(originalAccountsB.stream().map(LedgerResponses.Account::code).toList());

        Integer w2 = jdbc.queryForObject("select account_level2_width from ledger where id = ?", Integer.class, ledgerB);
        Integer w3 = jdbc.queryForObject("select account_level3_width from ledger where id = ?", Integer.class, ledgerB);
        assertThat(w2).isEqualTo(3);
        assertThat(w3).isEqualTo(3);
    }

    @Test
    void rollsBackDeletedAccountsWhenInsertFailsDuringReconstruction() {
        UUID owner = UUID.randomUUID();
        UUID ledgerId = ledgers.create(user(owner), new LedgerRequests.Create(
                "rollback-after-delete", "SME", "2011-17", "CNY",
                LocalDate.of(2026, 1, 1), false, new AccountCodeRule(4, 2, 2))).id();

        List<LedgerResponses.Account> originalAccounts = ledgers.listAccounts(owner, ledgerId);
        assertThat(originalAccounts).isNotEmpty();

        var seed1 = new AccountManagementRepository.ReconstructAccountSeed(
                "1002", "银行存款", null, "CURRENT_ASSET", "DEBIT", "ACTIVE",
                "ASSET.BANK_DEPOSIT", false, false, null, false, null);
        var seed2 = new AccountManagementRepository.ReconstructAccountSeed(
                "1002", "重复银行存款", null, "CURRENT_ASSET", "DEBIT", "ACTIVE",
                "ASSET.BANK_DEPOSIT", false, false, null, false, null);

        assertThatThrownBy(() -> txTemplate.execute(status -> {
            accounts.reconstructAccounts(ledgerId, new AccountCodeRule(4, 2, 2), List.of(seed1, seed2), List.of(), UUID.randomUUID());
            return null;
        })).isInstanceOf(Exception.class);

        // Verification: all original accounts were restored by rollback despite deletion step executing first
        List<LedgerResponses.Account> restoredAccounts = ledgers.listAccounts(owner, ledgerId);
        assertThat(restoredAccounts).hasSameSizeAs(originalAccounts);
        assertThat(restoredAccounts.stream().map(LedgerResponses.Account::code).toList())
                .containsExactlyElementsOf(originalAccounts.stream().map(LedgerResponses.Account::code).toList());
    }

    private CurrentUserResolver.ResolvedUser user(UUID id) {
        return new CurrentUserResolver.ResolvedUser(id, "test", id.toString());
    }
}
