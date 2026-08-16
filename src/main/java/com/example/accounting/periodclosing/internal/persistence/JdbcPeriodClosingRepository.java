package com.example.accounting.periodclosing.internal.persistence;

import com.example.accounting.periodclosing.PeriodClosingStepStatus;
import com.example.accounting.periodclosing.PeriodClosingStepType;
import com.example.accounting.periodclosing.internal.port.PeriodClosingRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPeriodClosingRepository implements PeriodClosingRepository {
    private final JdbcTemplate jdbc;

    public JdbcPeriodClosingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<SettingRecord> setting(UUID ledgerId) {
        return Optional.ofNullable(jdbc.query("""
                select ledger_id, profit_account_id, retained_earnings_account_id, version
                from period_closing_setting where ledger_id = ?
                """, rs -> rs.next() ? new SettingRecord(ledgerId,
                rs.getObject("profit_account_id", UUID.class),
                rs.getObject("retained_earnings_account_id", UUID.class), rs.getLong("version")) : null,
                ledgerId));
    }

    @Override
    public void upsertSetting(UUID ledgerId, UUID profitAccountId, UUID retainedEarningsAccountId) {
        jdbc.update("""
                insert into period_closing_setting (ledger_id, profit_account_id, retained_earnings_account_id)
                values (?, ?, ?)
                on conflict (ledger_id) do update set profit_account_id = excluded.profit_account_id,
                    retained_earnings_account_id = excluded.retained_earnings_account_id,
                    version = period_closing_setting.version + 1, updated_at = now()
                """, ledgerId, profitAccountId, retainedEarningsAccountId);
    }

    @Override
    public Optional<StepRecord> step(UUID ledgerId, UUID periodId, PeriodClosingStepType type) {
        return Optional.ofNullable(jdbc.query("""
                select id, ledger_id, period_id, step_type, status, amount, input_fingerprint,
                    voucher_id, blocker_code, blocker_detail, updated_at
                from period_closing_step where ledger_id = ? and period_id = ? and step_type = ?
                """, rs -> rs.next() ? mapStep(rs) : null, ledgerId, periodId, type.name()));
    }

    @Override
    public List<StepRecord> steps(UUID ledgerId, UUID periodId) {
        return jdbc.query("""
                select id, ledger_id, period_id, step_type, status, amount, input_fingerprint,
                    voucher_id, blocker_code, blocker_detail, updated_at
                from period_closing_step where ledger_id = ? and period_id = ? order by step_type
                """, (rs, row) -> mapStep(rs), ledgerId, periodId);
    }

    @Override
    public void createStep(UUID id, UUID ledgerId, UUID periodId, PeriodClosingStepType type,
                           PeriodClosingStepStatus status, BigDecimal amount, String fingerprint,
                           UUID voucherId, String blockerCode, String blockerDetail) {
        jdbc.update("""
                insert into period_closing_step (id, ledger_id, period_id, step_type, status, amount,
                    input_fingerprint, voucher_id, blocker_code, blocker_detail)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, ledgerId, periodId, type.name(), status.name(), amount, fingerprint, voucherId,
                blockerCode, blockerDetail);
    }

    @Override
    public void updateStep(UUID ledgerId, UUID periodId, PeriodClosingStepType type,
                           PeriodClosingStepStatus status, BigDecimal amount, String fingerprint,
                           UUID voucherId, String blockerCode, String blockerDetail) {
        jdbc.update("""
                update period_closing_step set status = ?, amount = ?, input_fingerprint = ?, voucher_id = ?,
                    blocker_code = ?, blocker_detail = ?, updated_at = now()
                where ledger_id = ? and period_id = ? and step_type = ?
                """, status.name(), amount, fingerprint, voucherId, blockerCode, blockerDetail,
                ledgerId, periodId, type.name());
    }

    @Override
    public Optional<PeriodRecord> period(UUID ledgerId, UUID periodId) {
        return Optional.ofNullable(jdbc.query("""
                select id, ledger_id, period_code, start_date, end_date, status
                from accounting_period where ledger_id = ? and id = ?
                """, rs -> rs.next() ? mapPeriod(rs) : null, ledgerId, periodId));
    }

    @Override
    public List<PeriodRecord> periods(UUID ledgerId) {
        return jdbc.query("""
                select id, ledger_id, period_code, start_date, end_date, status
                from accounting_period where ledger_id = ? order by period_code
                """, (rs, row) -> mapPeriod(rs), ledgerId);
    }

    @Override
    public List<AccountAmount> amounts(UUID ledgerId, UUID periodId, String category) {
        return jdbc.query("""
                select a.id, a.code, a.name, a.category,
                    coalesce(sum(case when v.id is not null and vl.side = 'DEBIT' then vl.base_amount else 0 end), 0) debit,
                    coalesce(sum(case when v.id is not null and vl.side = 'CREDIT' then vl.base_amount else 0 end), 0) credit
                from ledger_account a
                left join voucher_line vl on vl.ledger_id = a.ledger_id and vl.account_id = a.id
                left join voucher v on v.ledger_id = vl.ledger_id and v.id = vl.voucher_id
                    and v.period_id = ? and v.status = 'POSTED' and v.deleted_at is null
                    and v.accounting_role = 'OPERATING'
                where a.ledger_id = ? and a.category = ? and a.status = 'ACTIVE'
                  and not exists (select 1 from ledger_account child
                                  where child.ledger_id = a.ledger_id and child.parent_id = a.id)
                group by a.id, a.code, a.name, a.category order by a.code
                """, (rs, row) -> new AccountAmount(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("name"), rs.getString("category"), rs.getBigDecimal("debit"),
                rs.getBigDecimal("credit")), periodId, ledgerId, category);
    }

    @Override
    public List<AccountAmount> netAmounts(UUID ledgerId, UUID periodId, String category) {
        return jdbc.query("""
                select a.id, a.code, a.name, a.category,
                    coalesce(sum(case when v.id is not null and vl.side = 'DEBIT' then vl.base_amount else 0 end), 0) debit,
                    coalesce(sum(case when v.id is not null and vl.side = 'CREDIT' then vl.base_amount else 0 end), 0) credit
                from ledger_account a
                left join voucher_line vl on vl.ledger_id = a.ledger_id and vl.account_id = a.id
                left join voucher v on v.ledger_id = vl.ledger_id and v.id = vl.voucher_id
                    and v.period_id = ? and v.status = 'POSTED' and v.deleted_at is null
                where a.ledger_id = ? and a.category = ? and a.status = 'ACTIVE'
                  and not exists (select 1 from ledger_account child
                                  where child.ledger_id = a.ledger_id and child.parent_id = a.id)
                group by a.id, a.code, a.name, a.category order by a.code
                """, (rs, row) -> new AccountAmount(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("name"), rs.getString("category"), rs.getBigDecimal("debit"),
                rs.getBigDecimal("credit")), periodId, ledgerId, category);
    }

    @Override
    public Optional<AccountAmount> amountThrough(UUID ledgerId, String periodCode, UUID accountId, UUID excludedVoucherId) {
        return Optional.ofNullable(jdbc.query("""
                select a.id, a.code, a.name, a.category,
                    coalesce(sum(case when p.id is not null and vl.side = 'DEBIT' then vl.base_amount else 0 end), 0) debit,
                    coalesce(sum(case when p.id is not null and vl.side = 'CREDIT' then vl.base_amount else 0 end), 0) credit
                from ledger_account a
                left join voucher_line vl on vl.ledger_id = a.ledger_id and vl.account_id = a.id
                left join voucher v on v.ledger_id = vl.ledger_id and v.id = vl.voucher_id
                    and v.status = 'POSTED' and v.deleted_at is null
                left join accounting_period p on p.ledger_id = v.ledger_id and p.id = v.period_id
                    and p.period_code between ? and ?
                where a.ledger_id = ? and a.id = ? and (?::uuid is null or v.id <> ?::uuid)
                group by a.id, a.code, a.name, a.category
                """, rs -> rs.next() ? new AccountAmount(rs.getObject("id", UUID.class),
                rs.getString("code"), rs.getString("name"), rs.getString("category"),
                rs.getBigDecimal("debit"), rs.getBigDecimal("credit")) : null,
                periodCode.substring(0, 4) + "-01", periodCode, ledgerId, accountId, excludedVoucherId, excludedVoucherId));
    }

    @Override
    public Optional<AccountInfo> account(UUID ledgerId, UUID accountId) {
        return Optional.ofNullable(jdbc.query("""
                select a.id, a.ledger_id, a.code, a.name, a.category, a.status, a.parent_id,
                    not exists (select 1 from ledger_account child
                               where child.ledger_id = a.ledger_id and child.parent_id = a.id) leaf
                from ledger_account a where a.ledger_id = ? and a.id = ?
                """, rs -> rs.next() ? mapAccount(rs) : null, ledgerId, accountId));
    }

    @Override
    public Optional<AccountInfo> accountByCode(UUID ledgerId, String code) {
        return Optional.ofNullable(jdbc.query("""
                select a.id, a.ledger_id, a.code, a.name, a.category, a.status, a.parent_id,
                    not exists (select 1 from ledger_account child
                               where child.ledger_id = a.ledger_id and child.parent_id = a.id) leaf
                from ledger_account a where a.ledger_id = ? and a.code = ?
                """, rs -> rs.next() ? mapAccount(rs) : null, ledgerId, code));
    }

    @Override
    public boolean hasRequiredDimensions(UUID ledgerId, UUID accountId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from ledger_account_dimension
                               where ledger_id = ? and account_id = ? and required)
                """, Boolean.class, ledgerId, accountId));
    }

    @Override
    public Optional<StepRecord> stepForUpdate(UUID ledgerId, UUID periodId, PeriodClosingStepType type) {
        return Optional.ofNullable(jdbc.query("""
                select id, ledger_id, period_id, step_type, status, amount, input_fingerprint,
                    voucher_id, blocker_code, blocker_detail, updated_at
                from period_closing_step where ledger_id = ? and period_id = ? and step_type = ?
                for update
                """, rs -> rs.next() ? mapStep(rs) : null, ledgerId, periodId, type.name()));
    }

    @Override
    public StepRecord ensureStep(UUID id, UUID ledgerId, UUID periodId, PeriodClosingStepType type) {
        jdbc.update("""
                insert into period_closing_step (id, ledger_id, period_id, step_type, status, amount)
                values (?, ?, ?, ?, 'PENDING', 0)
                on conflict (ledger_id, period_id, step_type) do nothing
                """, id, ledgerId, periodId, type.name());
        return stepForUpdate(ledgerId, periodId, type).orElseThrow();
    }

    @Override
    public String baseCurrency(UUID ledgerId) {
        return jdbc.queryForObject("select base_currency from ledger where id = ?", String.class, ledgerId);
    }

    @Override
    public TrialBalanceAmounts trialBalanceAmounts(UUID ledgerId, String periodCode) {
        return jdbc.queryForObject("""
                with opening_amounts as (
                    select coalesce(sum(ob.debit_base), 0) opening_debit,
                        coalesce(sum(ob.credit_base), 0) opening_credit
                    from opening_balance ob
                    join ledger_account a on a.ledger_id = ob.ledger_id and a.id = ob.account_id
                    where ob.ledger_id = ? and ob.confirmed
                      and not exists (select 1 from ledger_account child
                                      where child.ledger_id = a.ledger_id and child.parent_id = a.id)
                ), prior_voucher_amounts as (
                    select coalesce(sum(case when vl.side = 'DEBIT' then vl.base_amount else 0 end), 0) debit,
                        coalesce(sum(case when vl.side = 'CREDIT' then vl.base_amount else 0 end), 0) credit
                    from voucher_line vl
                    join voucher v on v.ledger_id = vl.ledger_id and v.id = vl.voucher_id
                    join accounting_period p on p.ledger_id = v.ledger_id and p.id = v.period_id
                    join ledger_account a on a.ledger_id = vl.ledger_id and a.id = vl.account_id
                    where v.ledger_id = ? and v.status = 'POSTED' and v.deleted_at is null
                      and p.period_code < ?
                      and not exists (select 1 from ledger_account child
                                      where child.ledger_id = a.ledger_id and child.parent_id = a.id)
                ), period_voucher_amounts as (
                    select coalesce(sum(case when vl.side = 'DEBIT' then vl.base_amount else 0 end), 0) debit,
                        coalesce(sum(case when vl.side = 'CREDIT' then vl.base_amount else 0 end), 0) credit
                    from voucher_line vl
                    join voucher v on v.ledger_id = vl.ledger_id and v.id = vl.voucher_id
                    join accounting_period p on p.ledger_id = v.ledger_id and p.id = v.period_id
                    join ledger_account a on a.ledger_id = vl.ledger_id and a.id = vl.account_id
                    where v.ledger_id = ? and v.status = 'POSTED' and v.deleted_at is null
                      and p.period_code = ?
                      and not exists (select 1 from ledger_account child
                                      where child.ledger_id = a.ledger_id and child.parent_id = a.id)
                )
                select opening_amounts.opening_debit + prior_voucher_amounts.debit opening_debit,
                    opening_amounts.opening_credit + prior_voucher_amounts.credit opening_credit,
                    period_voucher_amounts.debit period_debit,
                    period_voucher_amounts.credit period_credit
                from opening_amounts, prior_voucher_amounts, period_voucher_amounts
                """, (rs, rowNum) -> new TrialBalanceAmounts(
                rs.getBigDecimal("opening_debit"), rs.getBigDecimal("opening_credit"),
                rs.getBigDecimal("period_debit"), rs.getBigDecimal("period_credit")),
                ledgerId, ledgerId, periodCode, ledgerId, periodCode);
    }

    @Override
    public Optional<UUID> depreciationRunId(UUID ledgerId, UUID periodId, UUID voucherId) {
        return Optional.ofNullable(jdbc.query("""
                select id from fixed_asset_depreciation_run
                where ledger_id = ? and period_id = ? and voucher_id = ?
                  and run_type = 'MONTH_END' and status = 'POSTED'
                """, rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                ledgerId, periodId, voucherId));
    }

    private StepRecord mapStep(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StepRecord(rs.getObject("id", UUID.class), rs.getObject("ledger_id", UUID.class),
                rs.getObject("period_id", UUID.class), PeriodClosingStepType.valueOf(rs.getString("step_type")),
                PeriodClosingStepStatus.valueOf(rs.getString("status")), rs.getBigDecimal("amount"),
                rs.getString("input_fingerprint"), rs.getObject("voucher_id", UUID.class),
                rs.getString("blocker_code"), rs.getString("blocker_detail"),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private PeriodRecord mapPeriod(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PeriodRecord(rs.getObject("id", UUID.class), rs.getObject("ledger_id", UUID.class),
                rs.getString("period_code"), rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class), rs.getString("status"));
    }

    private AccountInfo mapAccount(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AccountInfo(rs.getObject("id", UUID.class), rs.getObject("ledger_id", UUID.class),
                rs.getString("code"), rs.getString("name"), rs.getString("category"),
                rs.getString("status"), rs.getObject("parent_id", UUID.class), rs.getBoolean("leaf"));
    }
}
