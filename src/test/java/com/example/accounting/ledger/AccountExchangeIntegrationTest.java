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
                                row.targetAccountId() == null ? "CREATE" : "MAP", row.targetAccountId(), null)))
                .toList());
        exchange.commit(owner, ledgerId, preview.id());

        List<LedgerResponses.Account> accounts = ledgers.listAccounts(owner, ledgerId);
        UUID parentId = accounts.stream().filter(account -> account.code().equals("1002"))
                .findFirst().orElseThrow().id();
        assertThat(accounts).filteredOn(account -> account.code().equals("10020001"))
                .singleElement().satisfies(account -> {
                    assertThat(account.parentId()).isEqualTo(parentId);
                    assertThat(account.category()).isEqualTo("ASSET");
                    assertThat(account.normalBalance()).isEqualTo("DEBIT");
                });
    }

    @Test
    void exportsPreviewsAndMapsAStandardWorkbookWithoutChangingAccounts() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID ledgerId = ledgers.create(user(owner), new LedgerRequests.Create(
                "account-exchange", "SME", "2011-17", "CNY",
                LocalDate.of(2026, 1, 1), false)).id();
        byte[] exported = exchange.export(owner, ledgerId, AccountExchangeService.Format.STANDARD);

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(exported))) {
            assertThat(workbook.getSheet("Metadata")).isNotNull();
            assertThat(workbook.getSheet("Accounts")).isNotNull();
            assertThat(workbook.getSheet("DimensionTypes")).isNotNull();
            assertThat(workbook.getSheet("AccountDimensions")).isNotNull();
        }

        AccountExchangeService.Preview preview = exchange.preview(
                owner, ledgerId, AccountExchangeService.Format.STANDARD,
                "accounts.xlsx", exported.length, new ByteArrayInputStream(exported));
        assertThat(preview.rows()).hasSize(15);
        assertThat(preview.rows()).allMatch(row -> "MAP".equals(row.action())
                && row.targetAccountId() != null && row.issues().isEmpty());
        exchange.decideAll(owner, ledgerId, preview.id(), preview.rows().stream()
                .map(row -> new AccountExchangeService.RowDecision(row.rowNo(),
                        new AccountExchangeService.Decision("MAP", row.targetAccountId(), null)))
                .toList());
        AccountExchangeService.Preview committed = exchange.commit(owner, ledgerId, preview.id());
        assertThat(committed.status()).isEqualTo("COMMITTED");
        assertThat(ledgers.listAccounts(owner, ledgerId)).hasSize(15);
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
                new LedgerRequests.AccountCreate("1998", "期间外父科目", "ASSET", "DEBIT"));
        jdbc.update("update ledger_account set created_at = ? where ledger_id = ? and id = ?",
                startInclusive.minusDays(1), ledgerId, parent.id());
        ledgers.createAccount(owner, ledgerId,
                new LedgerRequests.AccountCreate(
                        "199801", "期间内子科目", "ASSET", "DEBIT", parent.id(),
                        false, null, false, null, List.of()));
        ledgers.createAccount(owner, ledgerId,
                new LedgerRequests.AccountCreate("1997", "期间外科目", "ASSET", "DEBIT"));
        jdbc.update("update ledger_account set created_at = ? where ledger_id = ? and code = ?",
                startInclusive, ledgerId, "199801");
        jdbc.update("update ledger_account set created_at = ? where ledger_id = ? and code = ?",
                endExclusive, ledgerId, "1997");

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
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("1998");
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
                "100101", "区域现金", "ASSET", "DEBIT", null, true, operating,
                false, null, List.of(new LedgerRequests.DimensionRequirement(region.id(), true))));
        byte[] exported = exchange.export(owner, source, AccountExchangeService.Format.STANDARD);

        UUID target = ledgers.create(user(owner), new LedgerRequests.Create(
                "target", "SME", "2011-17", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        AccountExchangeService.Preview preview = exchange.preview(owner, target,
                AccountExchangeService.Format.STANDARD, "cross-ledger.xlsx", exported.length,
                new ByteArrayInputStream(exported));
        for (AccountExchangeService.PreviewRow row : preview.rows()) {
            exchange.decide(owner, target, preview.id(), row.rowNo(),
                    new AccountExchangeService.Decision(row.targetAccountId() == null ? "CREATE" : "MAP",
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
