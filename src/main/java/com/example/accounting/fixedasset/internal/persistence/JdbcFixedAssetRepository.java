package com.example.accounting.fixedasset.internal.persistence;

import com.example.accounting.fixedasset.internal.port.FixedAssetRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcFixedAssetRepository implements FixedAssetRepository {

    private static final String CATEGORY_COLUMNS = """
            select id, ledger_id, code, name, useful_life_months, residual_rate,
                asset_account_id, accumulated_depreciation_account_id, depreciation_expense_account_id,
                impairment_account_id, clearing_account_id, disposal_gain_account_id, disposal_loss_account_id,
                status, version
            from fixed_asset_category
            """;

    private static final String ASSET_COLUMNS = """
            select a.id, a.ledger_id, a.category_id, c.code category_code, c.name category_name,
                a.code, a.name, a.status, a.quantity, a.service_date, a.original_cost, a.input_tax,
                a.useful_life_months, a.residual_rate, a.opening_accumulated_depreciation,
                a.opening_depreciated_months, a.impairment_amount, a.department_value_id,
                a.acquisition_voucher_id, a.asset_account_id, a.accumulated_depreciation_account_id,
                a.depreciation_expense_account_id, a.impairment_account_id, a.clearing_account_id,
                a.disposal_gain_account_id, a.disposal_loss_account_id, a.disposal_date, a.note, a.version
            from fixed_asset a join fixed_asset_category c on c.ledger_id = a.ledger_id and c.id = a.category_id
            """;

    private final JdbcTemplate jdbc;

    public JdbcFixedAssetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockLedger(UUID ledgerId) {
        jdbc.queryForObject("select id from ledger where id = ? for update", UUID.class, ledgerId);
    }

    @Override
    public List<CategoryRecord> listCategories(UUID ledgerId) {
        return jdbc.query(CATEGORY_COLUMNS + " where ledger_id = ? and deleted_at is null order by code",
                (rs, row) -> mapCategory(rs), ledgerId);
    }

    @Override
    public Optional<CategoryRecord> findCategory(UUID ledgerId, UUID categoryId) {
        return Optional.ofNullable(jdbc.query(CATEGORY_COLUMNS
                + " where ledger_id = ? and id = ? and deleted_at is null",
                rs -> rs.next() ? mapCategory(rs) : null, ledgerId, categoryId));
    }

    @Override
    public void insertCategory(CategoryRecord category, UUID actorId) {
        jdbc.update("""
                insert into fixed_asset_category (id, ledger_id, code, name, useful_life_months, residual_rate,
                    asset_account_id, accumulated_depreciation_account_id, depreciation_expense_account_id,
                    impairment_account_id, clearing_account_id, disposal_gain_account_id, disposal_loss_account_id,
                    status, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, category.id(), category.ledgerId(), category.code(), category.name(), category.usefulLifeMonths(),
                category.residualRate(), category.assetAccountId(), category.accumulatedDepreciationAccountId(),
                category.depreciationExpenseAccountId(), category.impairmentAccountId(), category.clearingAccountId(),
                category.disposalGainAccountId(), category.disposalLossAccountId(), category.status(), actorId, actorId);
    }

    @Override
    public boolean updateCategory(UUID ledgerId, UUID categoryId, CategoryRecord category, long expectedVersion,
                                  UUID actorId) {
        return jdbc.update("""
                update fixed_asset_category set name = ?, useful_life_months = ?, residual_rate = ?,
                    asset_account_id = ?, accumulated_depreciation_account_id = ?,
                    depreciation_expense_account_id = ?, impairment_account_id = ?, clearing_account_id = ?,
                    disposal_gain_account_id = ?, disposal_loss_account_id = ?, status = ?, version = version + 1,
                    updated_at = now(), updated_by = ?
                where ledger_id = ? and id = ? and version = ? and deleted_at is null
                """, category.name(), category.usefulLifeMonths(), category.residualRate(), category.assetAccountId(),
                category.accumulatedDepreciationAccountId(), category.depreciationExpenseAccountId(),
                category.impairmentAccountId(), category.clearingAccountId(), category.disposalGainAccountId(),
                category.disposalLossAccountId(), category.status(), actorId, ledgerId, categoryId, expectedVersion) == 1;
    }

    @Override
    public List<AssetRecord> listAssets(UUID ledgerId, String status, UUID categoryId, UUID departmentValueId,
                                        String search, int limit, int offset) {
        StringBuilder sql = new StringBuilder(ASSET_COLUMNS)
                .append(" where a.ledger_id = ? and a.deleted_at is null ");
        List<Object> args = new java.util.ArrayList<>();
        args.add(ledgerId);
        if (status != null && !status.isBlank()) { sql.append("and a.status = ? "); args.add(status); }
        if (categoryId != null) { sql.append("and a.category_id = ? "); args.add(categoryId); }
        if (departmentValueId != null) { sql.append("and a.department_value_id = ? "); args.add(departmentValueId); }
        if (search != null && !search.isBlank()) {
            sql.append("and (lower(a.code) like lower(?) or lower(a.name) like lower(?)) ");
            String value = "%" + search.trim() + "%"; args.add(value); args.add(value);
        }
        sql.append("order by a.code, a.id limit ? offset ?"); args.add(limit); args.add(offset);
        return jdbc.query(sql.toString(), (rs, row) -> mapAsset(rs), args.toArray());
    }

    @Override
    public long countAssets(UUID ledgerId, String status, UUID categoryId, UUID departmentValueId, String search) {
        StringBuilder sql = new StringBuilder("select count(*) from fixed_asset a where a.ledger_id = ? and a.deleted_at is null ");
        List<Object> args = new java.util.ArrayList<>(); args.add(ledgerId);
        if (status != null && !status.isBlank()) { sql.append("and a.status = ? "); args.add(status); }
        if (categoryId != null) { sql.append("and a.category_id = ? "); args.add(categoryId); }
        if (departmentValueId != null) { sql.append("and a.department_value_id = ? "); args.add(departmentValueId); }
        if (search != null && !search.isBlank()) {
            sql.append("and (lower(a.code) like lower(?) or lower(a.name) like lower(?)) ");
            String value = "%" + search.trim() + "%"; args.add(value); args.add(value);
        }
        return jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
    }

    @Override
    public Optional<AssetRecord> findAsset(UUID ledgerId, UUID assetId) {
        return Optional.ofNullable(jdbc.query(ASSET_COLUMNS
                + " where a.ledger_id = ? and a.id = ? and a.deleted_at is null",
                rs -> rs.next() ? mapAsset(rs) : null, ledgerId, assetId));
    }

    @Override
    public boolean assetCodeExists(UUID ledgerId, String code) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from fixed_asset where ledger_id = ? and code = ? and deleted_at is null)",
                Boolean.class, ledgerId, code));
    }

    @Override
    public void insertAsset(AssetRecord asset, UUID actorId) {
        jdbc.update("""
                insert into fixed_asset (id, ledger_id, category_id, code, name, quantity, service_date,
                    original_cost, input_tax, useful_life_months, residual_rate,
                    opening_accumulated_depreciation, opening_depreciated_months, impairment_amount,
                    department_value_id, acquisition_voucher_id, asset_account_id,
                    accumulated_depreciation_account_id, depreciation_expense_account_id, impairment_account_id,
                    clearing_account_id, disposal_gain_account_id, disposal_loss_account_id, status,
                    disposal_date, note, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, asset.id(), asset.ledgerId(), asset.categoryId(), asset.code(), asset.name(), asset.quantity(),
                asset.serviceDate(), asset.originalCost(), asset.inputTax(), asset.usefulLifeMonths(), asset.residualRate(),
                asset.openingAccumulatedDepreciation(), asset.openingDepreciatedMonths(), asset.impairmentAmount(),
                asset.departmentValueId(), asset.acquisitionVoucherId(), asset.assetAccountId(),
                asset.accumulatedDepreciationAccountId(), asset.depreciationExpenseAccountId(), asset.impairmentAccountId(),
                asset.clearingAccountId(), asset.disposalGainAccountId(), asset.disposalLossAccountId(), asset.status(),
                asset.disposalDate(), asset.note(), actorId, actorId);
    }

    @Override
    public boolean updateAsset(UUID ledgerId, UUID assetId, AssetRecord asset, long expectedVersion, UUID actorId) {
        return jdbc.update("""
                update fixed_asset set name = ?, quantity = ?, service_date = ?, original_cost = ?, input_tax = ?,
                    useful_life_months = ?, residual_rate = ?, impairment_amount = ?, department_value_id = ?,
                    acquisition_voucher_id = ?, asset_account_id = ?, accumulated_depreciation_account_id = ?,
                    depreciation_expense_account_id = ?, impairment_account_id = ?, clearing_account_id = ?,
                    disposal_gain_account_id = ?, disposal_loss_account_id = ?, status = ?, disposal_date = ?, note = ?,
                    version = version + 1, updated_at = now(), updated_by = ?
                where ledger_id = ? and id = ? and version = ? and deleted_at is null
                """, asset.name(), asset.quantity(), asset.serviceDate(), asset.originalCost(), asset.inputTax(),
                asset.usefulLifeMonths(), asset.residualRate(), asset.impairmentAmount(), asset.departmentValueId(),
                asset.acquisitionVoucherId(), asset.assetAccountId(), asset.accumulatedDepreciationAccountId(),
                asset.depreciationExpenseAccountId(), asset.impairmentAccountId(), asset.clearingAccountId(),
                asset.disposalGainAccountId(), asset.disposalLossAccountId(), asset.status(), asset.disposalDate(),
                asset.note(), actorId, ledgerId, assetId, expectedVersion) == 1;
    }

    @Override
    public void softDeleteAsset(UUID ledgerId, UUID assetId, UUID actorId) {
        jdbc.update("update fixed_asset set deleted_at = now(), updated_at = now(), updated_by = ? "
                + "where ledger_id = ? and id = ? and deleted_at is null", actorId, ledgerId, assetId);
    }

    @Override
    public boolean hasAssetUsage(UUID ledgerId, UUID assetId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from fixed_asset_depreciation_line where ledger_id = ? and asset_id = ?)
                    or exists (select 1 from fixed_asset_disposal where ledger_id = ? and asset_id = ?)
                """, Boolean.class, ledgerId, assetId, ledgerId, assetId));
    }

    @Override
    public List<AssetRecord> depreciationCandidates(UUID ledgerId, UUID periodId) {
        return jdbc.query(ASSET_COLUMNS + """
                 join accounting_period p on p.ledger_id = a.ledger_id and p.id = ?
                 where a.ledger_id = ? and a.deleted_at is null
                   and (a.status = 'ACTIVE' or (a.status = 'DISPOSED'
                        and a.disposal_date between p.start_date and p.end_date))
                 order by a.code
                """, (rs, row) -> mapAsset(rs), periodId, ledgerId);
    }

    @Override
    public void insertChange(UUID ledgerId, UUID assetId, UUID periodId, String reason, UUID actorId,
                             String beforeData, String afterData) {
        jdbc.update("""
                insert into fixed_asset_change (id, ledger_id, asset_id, change_period_id, reason,
                    before_data, after_data, actor_id)
                values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                """, UUID.randomUUID(), ledgerId, assetId, periodId, reason, beforeData, afterData, actorId);
    }

    @Override
    public Optional<RunRecord> currentRun(UUID ledgerId, UUID periodId, String runType) {
        return Optional.ofNullable(jdbc.query("""
                select id, ledger_id, period_id, run_type, status, voucher_id, input_fingerprint,
                    total_amount, reason, superseded_by, created_at
                from fixed_asset_depreciation_run
                where ledger_id = ? and period_id = ? and run_type = ? and status = 'POSTED'
                order by created_at desc limit 1
                """, rs -> rs.next() ? mapRun(rs) : null, ledgerId, periodId, runType));
    }

    @Override
    public Optional<RunRecord> findRun(UUID ledgerId, UUID runId) {
        return Optional.ofNullable(jdbc.query("""
                select id, ledger_id, period_id, run_type, status, voucher_id, input_fingerprint,
                    total_amount, reason, superseded_by, created_at
                from fixed_asset_depreciation_run where ledger_id = ? and id = ?
                """, rs -> rs.next() ? mapRun(rs) : null, ledgerId, runId));
    }

    @Override
    public Optional<RunRecord> findRunByVoucher(UUID ledgerId, UUID voucherId) {
        return Optional.ofNullable(jdbc.query("""
                select id, ledger_id, period_id, run_type, status, voucher_id, input_fingerprint,
                    total_amount, reason, superseded_by, created_at
                from fixed_asset_depreciation_run where ledger_id = ? and voucher_id = ?
                """, rs -> rs.next() ? mapRun(rs) : null, ledgerId, voucherId));
    }

    @Override
    public Optional<RunRecord> activeRunForAsset(UUID ledgerId, UUID assetId, UUID periodId, String runType) {
        return Optional.ofNullable(jdbc.query("""
                select r.id, r.ledger_id, r.period_id, r.run_type, r.status, r.voucher_id,
                    r.input_fingerprint, r.total_amount, r.reason, r.superseded_by, r.created_at
                from fixed_asset_depreciation_run r
                join fixed_asset_depreciation_line l on l.ledger_id = r.ledger_id and l.run_id = r.id
                where r.ledger_id = ? and l.asset_id = ? and r.period_id = ? and r.run_type = ?
                    and r.status = 'POSTED' and l.status = 'ACTIVE'
                """, rs -> rs.next() ? mapRun(rs) : null, ledgerId, assetId, periodId, runType));
    }

    @Override
    public List<RunRecord> listRuns(UUID ledgerId, UUID periodId) {
        return jdbc.query("""
                select id, ledger_id, period_id, run_type, status, voucher_id, input_fingerprint,
                    total_amount, reason, superseded_by, created_at
                from fixed_asset_depreciation_run where ledger_id = ? and period_id = ?
                order by created_at desc
                """, (rs, row) -> mapRun(rs), ledgerId, periodId);
    }

    @Override
    public List<LineRecord> activeLines(UUID ledgerId, UUID periodId) {
        return jdbc.query("""
                select id, ledger_id, run_id, asset_id, period_id, amount, expense_account_id,
                    accumulated_account_id, department_value_id, voucher_line_id, status
                from fixed_asset_depreciation_line
                where ledger_id = ? and period_id = ? and status = 'ACTIVE'
                order by asset_id
                """, (rs, row) -> mapLine(rs), ledgerId, periodId);
    }

    @Override
    public List<LineRecord> linesForRun(UUID ledgerId, UUID runId) {
        return jdbc.query("""
                select id, ledger_id, run_id, asset_id, period_id, amount, expense_account_id,
                    accumulated_account_id, department_value_id, voucher_line_id, status
                from fixed_asset_depreciation_line where ledger_id = ? and run_id = ?
                order by asset_id
                """, (rs, row) -> mapLine(rs), ledgerId, runId);
    }

    @Override
    public boolean hasActiveLine(UUID ledgerId, UUID assetId, UUID periodId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from fixed_asset_depreciation_line
                    where ledger_id = ? and asset_id = ? and period_id = ? and status = 'ACTIVE')
                """, Boolean.class, ledgerId, assetId, periodId));
    }

    @Override
    public DepreciationHistory depreciationBefore(UUID ledgerId, UUID assetId, UUID periodStartPeriodId) {
        return jdbc.queryForObject("""
                select coalesce(sum(l.amount), 0) amount, count(*) periods
                from fixed_asset_depreciation_line l
                join accounting_period p on p.ledger_id = l.ledger_id and p.id = l.period_id
                join accounting_period target on target.ledger_id = ? and target.id = ?
                where l.ledger_id = ? and l.asset_id = ? and l.status = 'ACTIVE'
                    and p.start_date < target.start_date
                """, (rs, row) -> new DepreciationHistory(
                rs.getBigDecimal("amount"), rs.getInt("periods")),
                ledgerId, periodStartPeriodId, ledgerId, assetId);
    }

    @Override
    public BigDecimal periodDepreciation(UUID ledgerId, UUID assetId, UUID periodId) {
        return jdbc.queryForObject("""
                select coalesce(sum(amount), 0) from fixed_asset_depreciation_line
                where ledger_id = ? and asset_id = ? and period_id = ? and status = 'ACTIVE'
                """, BigDecimal.class, ledgerId, assetId, periodId);
    }

    @Override
    public void insertRun(RunRecord run, UUID actorId) {
        jdbc.update("""
                insert into fixed_asset_depreciation_run (id, ledger_id, period_id, run_type, status,
                    voucher_id, input_fingerprint, total_amount, reason, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, run.id(), run.ledgerId(), run.periodId(), run.runType(), run.status(), run.voucherId(),
                run.inputFingerprint(), run.totalAmount(), run.reason(), actorId);
    }

    @Override
    public void insertLine(LineRecord line) {
        jdbc.update("""
                insert into fixed_asset_depreciation_line (id, ledger_id, run_id, asset_id, period_id, amount,
                    expense_account_id, accumulated_account_id, department_value_id, voucher_line_id, status)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, line.id(), line.ledgerId(), line.runId(), line.assetId(), line.periodId(), line.amount(),
                line.expenseAccountId(), line.accumulatedAccountId(), line.departmentValueId(), line.voucherLineId(),
                line.status());
    }

    @Override
    public void supersedeRun(UUID ledgerId, UUID runId, UUID supersededBy) {
        jdbc.update("update fixed_asset_depreciation_run set status = 'SUPERSEDED', superseded_by = ?, "
                + "historical_voucher_id = coalesce(historical_voucher_id, voucher_id), voucher_id = null "
                + "where ledger_id = ? and id = ?", supersededBy, ledgerId, runId);
    }

    @Override
    public void supersedeLines(UUID ledgerId, UUID runId) {
        jdbc.update("update fixed_asset_depreciation_line set status = 'SUPERSEDED' "
                + "where ledger_id = ? and run_id = ? and status = 'ACTIVE'", ledgerId, runId);
    }

    @Override
    public boolean cancelRun(UUID ledgerId, UUID runId, String runType, UUID voucherId, UUID actorId,
                             String reason) {
        return jdbc.update("""
                update fixed_asset_depreciation_run
                set status = 'CANCELLED', historical_voucher_id = coalesce(historical_voucher_id, voucher_id),
                    voucher_id = null,
                    cancelled_at = now(), cancelled_by = ?, cancellation_reason = ?
                where ledger_id = ? and id = ? and status = 'POSTED' and run_type = ?
                    and voucher_id = ?
                """, actorId, reason, ledgerId, runId, runType, voucherId) == 1;
    }

    @Override
    public int cancelRunLines(UUID ledgerId, UUID runId) {
        return jdbc.update("""
                update fixed_asset_depreciation_line set status = 'CANCELLED'
                where ledger_id = ? and run_id = ? and status = 'ACTIVE'
                """, ledgerId, runId);
    }

    @Override
    public void insertDisposal(DisposalRecord disposal, UUID actorId) {
        jdbc.update("""
                insert into fixed_asset_disposal (id, ledger_id, asset_id, period_id, disposal_date, reason,
                    proceeds, output_tax, clearing_cost, clearing_input_tax, receipt_account_id, payment_account_id,
                    output_tax_account_id, input_tax_account_id, depreciation_voucher_id, transfer_voucher_id,
                    settlement_voucher_id, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, disposal.id(), disposal.ledgerId(), disposal.assetId(), disposal.periodId(), disposal.disposalDate(),
                disposal.reason(), disposal.proceeds(), disposal.outputTax(), disposal.clearingCost(),
                disposal.clearingInputTax(), disposal.receiptAccountId(), disposal.paymentAccountId(),
                disposal.outputTaxAccountId(), disposal.inputTaxAccountId(), disposal.depreciationVoucherId(),
                disposal.transferVoucherId(), disposal.settlementVoucherId(), actorId);
    }

    @Override
    public boolean hasDisposal(UUID ledgerId, UUID assetId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from fixed_asset_disposal where ledger_id = ? and asset_id = ? and status = 'ACTIVE')",
                Boolean.class, ledgerId, assetId));
    }

    @Override
    public Optional<ActiveDisposalRecord> activeDisposal(UUID ledgerId, UUID assetId) {
        return Optional.ofNullable(jdbc.query("""
                select id, ledger_id, asset_id, period_id, disposal_date, depreciation_voucher_id,
                    transfer_voucher_id, settlement_voucher_id
                from fixed_asset_disposal
                where ledger_id = ? and asset_id = ? and status = 'ACTIVE'
                for update
                """, rs -> rs.next() ? new ActiveDisposalRecord(
                rs.getObject("id", UUID.class), rs.getObject("ledger_id", UUID.class),
                rs.getObject("asset_id", UUID.class), rs.getObject("period_id", UUID.class),
                rs.getObject("disposal_date", LocalDate.class),
                rs.getObject("depreciation_voucher_id", UUID.class),
                rs.getObject("transfer_voucher_id", UUID.class),
                rs.getObject("settlement_voucher_id", UUID.class)) : null, ledgerId, assetId));
    }

    @Override
    public boolean cancelDisposal(UUID ledgerId, UUID disposalId, UUID actorId, String reason) {
        return jdbc.update("""
                update fixed_asset_disposal
                set status = 'CANCELLED', cancelled_at = now(), cancelled_by = ?, cancellation_reason = ?,
                    cancelled_depreciation_voucher_id = depreciation_voucher_id,
                    cancelled_transfer_voucher_id = transfer_voucher_id,
                    cancelled_settlement_voucher_id = settlement_voucher_id,
                    depreciation_voucher_id = null, transfer_voucher_id = null, settlement_voucher_id = null
                where ledger_id = ? and id = ? and status = 'ACTIVE'
                """, actorId, reason, ledgerId, disposalId) == 1;
    }

    private CategoryRecord mapCategory(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CategoryRecord(rs.getObject("id", UUID.class), rs.getObject("ledger_id", UUID.class),
                rs.getString("code"), rs.getString("name"), rs.getInt("useful_life_months"),
                rs.getBigDecimal("residual_rate"), rs.getObject("asset_account_id", UUID.class),
                rs.getObject("accumulated_depreciation_account_id", UUID.class),
                rs.getObject("depreciation_expense_account_id", UUID.class),
                rs.getObject("impairment_account_id", UUID.class), rs.getObject("clearing_account_id", UUID.class),
                rs.getObject("disposal_gain_account_id", UUID.class), rs.getObject("disposal_loss_account_id", UUID.class),
                rs.getString("status"), rs.getLong("version"));
    }

    private AssetRecord mapAsset(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AssetRecord(rs.getObject("id", UUID.class), rs.getObject("ledger_id", UUID.class),
                rs.getObject("category_id", UUID.class), rs.getString("category_code"), rs.getString("category_name"),
                rs.getString("code"), rs.getString("name"), rs.getString("status"), rs.getBigDecimal("quantity"),
                rs.getObject("service_date", LocalDate.class), rs.getBigDecimal("original_cost"), rs.getBigDecimal("input_tax"),
                rs.getInt("useful_life_months"), rs.getBigDecimal("residual_rate"),
                rs.getBigDecimal("opening_accumulated_depreciation"), rs.getInt("opening_depreciated_months"),
                rs.getBigDecimal("impairment_amount"), rs.getObject("department_value_id", UUID.class),
                rs.getObject("acquisition_voucher_id", UUID.class), rs.getObject("asset_account_id", UUID.class),
                rs.getObject("accumulated_depreciation_account_id", UUID.class),
                rs.getObject("depreciation_expense_account_id", UUID.class), rs.getObject("impairment_account_id", UUID.class),
                rs.getObject("clearing_account_id", UUID.class), rs.getObject("disposal_gain_account_id", UUID.class),
                rs.getObject("disposal_loss_account_id", UUID.class), rs.getObject("disposal_date", LocalDate.class),
                rs.getString("note"), rs.getLong("version"));
    }

    private RunRecord mapRun(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RunRecord(rs.getObject("id", UUID.class), rs.getObject("ledger_id", UUID.class),
                rs.getObject("period_id", UUID.class), rs.getString("run_type"), rs.getString("status"),
                rs.getObject("voucher_id", UUID.class), rs.getString("input_fingerprint"), rs.getBigDecimal("total_amount"),
                rs.getString("reason"), rs.getObject("superseded_by", UUID.class), rs.getObject("created_at", OffsetDateTime.class));
    }

    private LineRecord mapLine(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LineRecord(rs.getObject("id", UUID.class), rs.getObject("ledger_id", UUID.class),
                rs.getObject("run_id", UUID.class), rs.getObject("asset_id", UUID.class),
                rs.getObject("period_id", UUID.class), rs.getBigDecimal("amount"),
                rs.getObject("expense_account_id", UUID.class), rs.getObject("accumulated_account_id", UUID.class),
                rs.getObject("department_value_id", UUID.class), rs.getObject("voucher_line_id", UUID.class),
                rs.getString("status"));
    }
}
