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
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class AccountExchangeIntegrationTest {

    @Autowired
    private LedgerService ledgers;

    @Autowired
    private AccountExchangeService exchange;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void reusesExistingKeyForKingdeeUpdatesAndRejectsStandardKeyConflicts() throws Exception {
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
        AccountExchangeService.Preview kingdeePreview = exchange.preview(owner, ledgerId,
                AccountExchangeService.Format.KINGDEE, "existing-5601.xls", kingdee.length,
                new ByteArrayInputStream(kingdee));
        assertThat(kingdeePreview.rows()).singleElement().satisfies(row -> {
            assertThat(row.action()).isEqualTo("UPDATE");
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
                AccountExchangeService.Format.STANDARD, "conflicting-key.xlsx", conflicting.length,
                new ByteArrayInputStream(conflicting));
        assertThat(standardPreview.rows()).filteredOn(row -> row.accountCode().equals("1001"))
                .singleElement().satisfies(row ->
                        assertThat(row.issues()).contains("ERROR:STANDARD_ACCOUNT_KEY_CONFLICT"));
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
            child.createCell(2).setCellValue("营业收入");
            child.createCell(3).setCellValue("贷");
            source.write(output);
            workbook = output.toByteArray();
        }

        AccountExchangeService.Preview preview = exchange.preview(owner, ledgerId,
                AccountExchangeService.Format.KINGDEE, "accounts.xls", workbook.length,
                new ByteArrayInputStream(workbook));

        assertThat(preview.rows()).hasSize(2);
        assertThat(preview.rows()).allMatch(row -> row.issues().isEmpty());
        exchange.decideAll(owner, ledgerId, preview.id(), preview.rows().stream()
                .map(row -> new AccountExchangeService.RowDecision(row.rowNo(),
                        new AccountExchangeService.Decision(
                                row.targetAccountId() == null ? "CREATE" : "UPDATE", row.targetAccountId(), null)))
                .toList());
        exchange.commit(owner, ledgerId, preview.id());

        List<LedgerResponses.Account> accounts = ledgers.listAccounts(owner, ledgerId);
        UUID parentId = accounts.stream().filter(account -> account.code().equals("1002"))
                .findFirst().orElseThrow().id();
        assertThat(accounts).filteredOn(account -> account.code().equals("10020001"))
                .singleElement().satisfies(account -> {
                    assertThat(account.parentId()).isEqualTo(parentId);
                    assertThat(account.category()).isEqualTo("CURRENT_ASSET");
                    assertThat(account.normalBalance()).isEqualTo("CREDIT");
                });
    }

    @Test
    void rejectsDuplicateNamesWithinTheSameParentAndRollsBack() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID ledgerId = ledgers.create(user(owner), new LedgerRequests.Create(
                "account-name-unique", "SME", "2011-17", "CNY",
                LocalDate.of(2026, 1, 1), false)).id();
        byte[] workbook;
        try (var source = new HSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = source.createSheet("科目列表");
            var header = sheet.createRow(0);
            List.of("编码", "名称", "类别", "余额方向")
                    .forEach(value -> header.createCell(header.getLastCellNum() < 0 ? 0 : header.getLastCellNum())
                            .setCellValue(value));
            var first = sheet.createRow(1);
            first.createCell(0).setCellValue("1998");
            first.createCell(1).setCellValue("重复名称");
            first.createCell(2).setCellValue("流动资产");
            first.createCell(3).setCellValue("借");
            var second = sheet.createRow(2);
            second.createCell(0).setCellValue("1999");
            second.createCell(1).setCellValue("重复名称");
            second.createCell(2).setCellValue("流动资产");
            second.createCell(3).setCellValue("借");
            source.write(output);
            workbook = output.toByteArray();
        }

        AccountExchangeService.Preview preview = exchange.preview(owner, ledgerId,
                AccountExchangeService.Format.KINGDEE, "duplicate-name.xls", workbook.length,
                new ByteArrayInputStream(workbook));
        exchange.decideAll(owner, ledgerId, preview.id(), preview.rows().stream()
                .map(row -> new AccountExchangeService.RowDecision(row.rowNo(),
                        new AccountExchangeService.Decision("CREATE", null, null)))
                .toList());

        assertThatThrownBy(() -> exchange.commit(owner, ledgerId, preview.id()))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        exception -> assertThat(exception.code()).isEqualTo("ACCOUNT_NAME_CONFLICT"));
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
                "accounts.xlsx", exported.length, new ByteArrayInputStream(exported));
        assertThat(preview.rows()).hasSize(18);
        assertThat(preview.rows()).allMatch(row -> "UPDATE".equals(row.action())
                && row.targetAccountId() != null && row.issues().isEmpty());
        exchange.decideAll(owner, ledgerId, preview.id(), preview.rows().stream()
                .map(row -> new AccountExchangeService.RowDecision(row.rowNo(),
                        new AccountExchangeService.Decision("UPDATE", row.targetAccountId(), null)))
                .toList());
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
                .filter(item -> item.code().equals("OPERATING")).findFirst().orElseThrow().id();
        ledgers.createAccount(owner, source, new LedgerRequests.AccountCreate(
                "100101", "区域现金", "CURRENT_ASSET", "DEBIT", null, true, operating,
                false, null, List.of(new LedgerRequests.DimensionRequirement(region.id(), true))));
        byte[] exported = exchange.export(owner, source, AccountExchangeService.Format.STANDARD);

        UUID target = ledgers.create(user(owner), new LedgerRequests.Create(
                "target", "SME", "2011-17", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        AccountExchangeService.Preview preview = exchange.preview(owner, target,
                AccountExchangeService.Format.STANDARD, "cross-ledger.xlsx", exported.length,
                new ByteArrayInputStream(exported));
        for (AccountExchangeService.PreviewRow row : preview.rows()) {
            exchange.decide(owner, target, preview.id(), row.rowNo(),
                    new AccountExchangeService.Decision(row.targetAccountId() == null ? "CREATE" : "UPDATE",
                            row.targetAccountId(), null));
        }
        exchange.commit(owner, target, preview.id());

        LedgerResponses.Account imported = ledgers.listAccounts(owner, target).stream()
                .filter(account -> account.code().equals("100101")).findFirst().orElseThrow();
        assertThat(imported.cashFlowRequired()).isTrue();
        assertThat(ledgers.listCashFlowItems(owner, target)).filteredOn(item ->
                        item.id().equals(imported.defaultCashFlowItemId()))
                .extracting(LedgerResponses.CashFlowItem::code).containsExactly("OPERATING");
        assertThat(imported.dimensionRequirements()).singleElement().satisfies(binding -> {
            assertThat(binding.code()).isEqualTo("REGION");
            assertThat(binding.required()).isTrue();
        });
    }

    private CurrentUserResolver.ResolvedUser user(UUID id) {
        return new CurrentUserResolver.ResolvedUser(id, "test", id.toString());
    }
}
