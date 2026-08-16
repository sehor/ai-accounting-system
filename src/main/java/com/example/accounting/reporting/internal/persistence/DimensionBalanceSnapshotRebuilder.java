package com.example.accounting.reporting.internal.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Rebuilds leaf-account rolling balances for every immutable dimension combination. */
@Component
public class DimensionBalanceSnapshotRebuilder {

    private static final String REBUILD_SQL = """
            with recursive input as (
                select cast(? as uuid) ledger_id, cast(? as uuid) source_period_id,
                    cast(? as uuid) through_period_id
            ), source_period as (
                select p.ledger_id, p.period_code source_period_code, through.period_code through_period_code
                from accounting_period p
                join input i on i.ledger_id = p.ledger_id and i.source_period_id = p.id
                join accounting_period through
                  on through.ledger_id = i.ledger_id and through.id = i.through_period_id
            ), periods as materialized (
                select p.ledger_id, p.id period_id, p.period_code, p.status,
                    row_number() over (order by p.period_code) period_no
                from accounting_period p
                join source_period source on source.ledger_id = p.ledger_id
                where p.period_code >= source.source_period_code
                  and p.period_code <= source.through_period_code
            ), previous_period as (
                select p.id period_id
                from accounting_period p
                join source_period source on source.ledger_id = p.ledger_id
                where p.period_code < source.source_period_code
                order by p.period_code desc limit 1
            ), leaf_accounts as (
                select a.id account_id
                from ledger_account a
                join input i on i.ledger_id = a.ledger_id
                where not exists (
                    select 1 from ledger_account child
                    where child.ledger_id = a.ledger_id and child.parent_id = a.id)
            ), anchor as materialized (
                select b.account_id, b.dimension_combination_id, b.currency,
                    b.closing_debit_original opening_debit_original,
                    b.closing_credit_original opening_credit_original,
                    b.closing_debit_base opening_debit_base,
                    b.closing_credit_base opening_credit_base
                from dimension_period_balance b
                join input i on i.ledger_id = b.ledger_id
                join previous_period previous on previous.period_id = b.period_id
            ), opening_lines as materialized (
                select ob.account_id, ob.currency,
                    coalesce(ob.dimension_combination_id, fallback.id) dimension_combination_id,
                    ob.debit_original, ob.credit_original, ob.debit_base, ob.credit_base
                from opening_balance ob
                join input i on i.ledger_id = ob.ledger_id
                left join lateral (
                    select combination.id
                    from dimension_combination combination
                    where combination.ledger_id = ob.ledger_id
                      and combination.canonical_key = case when ob.dimension_key = '' then 'v1;'
                          else 'legacy-v1;' || ob.dimension_key end
                ) fallback on ob.dimension_combination_id is null
                where ob.confirmed and coalesce(ob.dimension_combination_id, fallback.id) is not null
            ), opening_facts as materialized (
                select account_id, dimension_combination_id, currency,
                    sum(debit_original) opening_debit_original,
                    sum(credit_original) opening_credit_original,
                    sum(debit_base) opening_debit_base,
                    sum(credit_base) opening_credit_base
                from opening_lines
                group by account_id, dimension_combination_id, currency
            ), voucher_lines as materialized (
                select v.period_id, v.accounting_role, vl.account_id, vl.currency, vl.side,
                    vl.original_amount, vl.base_amount,
                    coalesce(vl.dimension_combination_id, fallback.id) dimension_combination_id
                from voucher v
                join input i on i.ledger_id = v.ledger_id
                join periods p on p.period_id = v.period_id
                join voucher_line vl on vl.ledger_id = v.ledger_id and vl.voucher_id = v.id
                left join lateral (
                    select combination.id
                    from dimension_combination combination
                    where combination.ledger_id = vl.ledger_id
                      and combination.canonical_key = 'v1;' || coalesce((
                          select string_agg(
                              member.dimension_type_id::text || '=' || member.dimension_value_id::text,
                              ';' order by member.dimension_type_id::text) || ';'
                          from voucher_line_dimension member
                          where member.ledger_id = vl.ledger_id and member.voucher_line_id = vl.id
                      ), '')
                ) fallback on vl.dimension_combination_id is null
                where v.status = 'POSTED' and v.deleted_at is null
                  and coalesce(vl.dimension_combination_id, fallback.id) is not null
            ), movements as materialized (
                select period_id, account_id, dimension_combination_id, currency,
                    sum(case when side = 'DEBIT' then original_amount else 0 end) period_debit_original,
                    sum(case when side = 'CREDIT' then original_amount else 0 end) period_credit_original,
                    sum(case when side = 'DEBIT' then base_amount else 0 end) period_debit_base,
                    sum(case when side = 'CREDIT' then base_amount else 0 end) period_credit_base,
                    sum(case when accounting_role = 'OPERATING' and side = 'DEBIT'
                             then base_amount else 0 end) operating_debit_base,
                    sum(case when accounting_role = 'OPERATING' and side = 'CREDIT'
                             then base_amount else 0 end) operating_credit_base
                from voucher_lines
                group by period_id, account_id, dimension_combination_id, currency
            ), keys as materialized (
                select account_id, dimension_combination_id, currency from anchor
                union
                select account_id, dimension_combination_id, currency from opening_facts
                union
                select account_id, dimension_combination_id, currency from movements
            ), leaf_keys as materialized (
                select keys.* from keys join leaf_accounts leaf on leaf.account_id = keys.account_id
            ), leaf_rollup as (
                select p.ledger_id, p.period_id, p.period_no, p.status,
                    key.account_id, key.dimension_combination_id, key.currency,
                    coalesce(anchor.opening_debit_original, opening.opening_debit_original, 0::numeric)
                        opening_debit_original,
                    coalesce(anchor.opening_credit_original, opening.opening_credit_original, 0::numeric)
                        opening_credit_original,
                    coalesce(movement.period_debit_original, 0::numeric) period_debit_original,
                    coalesce(movement.period_credit_original, 0::numeric) period_credit_original,
                    coalesce(anchor.opening_debit_base, opening.opening_debit_base, 0::numeric) opening_debit_base,
                    coalesce(anchor.opening_credit_base, opening.opening_credit_base, 0::numeric) opening_credit_base,
                    coalesce(movement.period_debit_base, 0::numeric) period_debit_base,
                    coalesce(movement.period_credit_base, 0::numeric) period_credit_base,
                    coalesce(movement.operating_debit_base, 0::numeric) operating_debit_base,
                    coalesce(movement.operating_credit_base, 0::numeric) operating_credit_base
                from periods p
                join leaf_keys key on true
                left join anchor on anchor.account_id = key.account_id
                    and anchor.dimension_combination_id = key.dimension_combination_id
                    and anchor.currency = key.currency
                left join opening_facts opening on opening.account_id = key.account_id
                    and opening.dimension_combination_id = key.dimension_combination_id
                    and opening.currency = key.currency
                    and not exists (select 1 from previous_period)
                left join movements movement on movement.period_id = p.period_id
                    and movement.account_id = key.account_id
                    and movement.dimension_combination_id = key.dimension_combination_id
                    and movement.currency = key.currency
                where p.period_no = 1

                union all

                select p.ledger_id, p.period_id, p.period_no, p.status,
                    previous.account_id, previous.dimension_combination_id, previous.currency,
                    previous.opening_debit_original + previous.period_debit_original,
                    previous.opening_credit_original + previous.period_credit_original,
                    coalesce(movement.period_debit_original, 0::numeric),
                    coalesce(movement.period_credit_original, 0::numeric),
                    previous.opening_debit_base + previous.period_debit_base,
                    previous.opening_credit_base + previous.period_credit_base,
                    coalesce(movement.period_debit_base, 0::numeric),
                    coalesce(movement.period_credit_base, 0::numeric),
                    coalesce(movement.operating_debit_base, 0::numeric),
                    coalesce(movement.operating_credit_base, 0::numeric)
                from leaf_rollup previous
                join periods p on p.ledger_id = previous.ledger_id and p.period_no = previous.period_no + 1
                left join movements movement on movement.period_id = p.period_id
                    and movement.account_id = previous.account_id
                    and movement.dimension_combination_id = previous.dimension_combination_id
                    and movement.currency = previous.currency
            )
            insert into dimension_period_balance (
                ledger_id, period_id, account_id, dimension_combination_id, currency,
                opening_debit_original, opening_credit_original,
                period_debit_original, period_credit_original,
                closing_debit_original, closing_credit_original,
                opening_debit_base, opening_credit_base,
                period_debit_base, period_credit_base,
                operating_debit_base, operating_credit_base,
                closing_debit_base, closing_credit_base,
                finalized_at, version, updated_at)
            select ledger_id, period_id, account_id, dimension_combination_id, currency,
                opening_debit_original, opening_credit_original,
                period_debit_original, period_credit_original,
                opening_debit_original + period_debit_original,
                opening_credit_original + period_credit_original,
                opening_debit_base, opening_credit_base,
                period_debit_base, period_credit_base,
                operating_debit_base, operating_credit_base,
                opening_debit_base + period_debit_base,
                opening_credit_base + period_credit_base,
                case when status = 'CLOSED' then now() else null end,
                1, now()
            from leaf_rollup
            where opening_debit_original <> 0 or opening_credit_original <> 0
               or period_debit_original <> 0 or period_credit_original <> 0
               or opening_debit_base <> 0 or opening_credit_base <> 0
               or period_debit_base <> 0 or period_credit_base <> 0
               or operating_debit_base <> 0 or operating_credit_base <> 0
            """;

    private final JdbcTemplate jdbc;

    public DimensionBalanceSnapshotRebuilder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int rebuildFrom(UUID ledgerId, UUID sourcePeriodId) {
        UUID throughPeriodId = jdbc.query("""
                select id from accounting_period where ledger_id = ? order by period_code desc limit 1
                """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, ledgerId);
        return rebuildFrom(ledgerId, sourcePeriodId, throughPeriodId);
    }

    public int rebuildFrom(UUID ledgerId, UUID sourcePeriodId, UUID throughPeriodId) {
        jdbc.update("""
                delete from dimension_period_balance b
                using accounting_period target, accounting_period source
                where b.ledger_id = ? and target.ledger_id = b.ledger_id and target.id = b.period_id
                  and source.ledger_id = b.ledger_id and source.id = ?
                  and target.period_code >= source.period_code
                  and target.period_code <= (select period_code from accounting_period
                                               where ledger_id = ? and id = ?)
                """, ledgerId, sourcePeriodId, ledgerId, throughPeriodId);
        return jdbc.update(REBUILD_SQL, ledgerId, sourcePeriodId, throughPeriodId);
    }
}
