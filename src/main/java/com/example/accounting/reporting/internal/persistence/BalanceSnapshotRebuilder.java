package com.example.accounting.reporting.internal.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Rebuilds rolling leaf and parent account snapshots from an affected period onward. */
@Component
public class BalanceSnapshotRebuilder {

    private static final String REBUILD_SQL = """
            with recursive input as (
                select cast(? as uuid) ledger_id, cast(? as uuid) source_period_id
            ), source_period as (
                select p.ledger_id, p.period_code
                from accounting_period p
                join input i on i.ledger_id = p.ledger_id and i.source_period_id = p.id
            ), periods as materialized (
                select p.ledger_id, p.id period_id, p.period_code, p.status,
                    row_number() over (order by p.period_code) period_no
                from accounting_period p
                join source_period source on source.ledger_id = p.ledger_id
                where p.period_code >= source.period_code
            ), previous_period as (
                select p.id period_id
                from accounting_period p
                join source_period source on source.ledger_id = p.ledger_id
                where p.period_code < source.period_code
                order by p.period_code desc limit 1
            ), anchor as materialized (
                select b.account_id, b.closing_debit_base opening_debit,
                    b.closing_credit_base opening_credit
                from account_period_balance b
                join input i on i.ledger_id = b.ledger_id
                join previous_period previous on previous.period_id = b.period_id
            ), leaf_accounts as (
                select a.ledger_id, a.id account_id
                from ledger_account a
                join input i on i.ledger_id = a.ledger_id
                where not exists (
                    select 1 from ledger_account child
                    where child.ledger_id = a.ledger_id and child.parent_id = a.id)
            ), opening_facts as (
                select ob.account_id, sum(ob.debit_base) opening_debit,
                    sum(ob.credit_base) opening_credit
                from opening_balance ob
                join input i on i.ledger_id = ob.ledger_id
                where ob.confirmed
                group by ob.account_id
            ), movements as (
                select v.period_id, vl.account_id,
                    sum(case when vl.side = 'DEBIT' then vl.base_amount else 0 end) period_debit,
                    sum(case when vl.side = 'CREDIT' then vl.base_amount else 0 end) period_credit,
                    sum(case when v.accounting_role = 'OPERATING' and vl.side = 'DEBIT'
                             then vl.base_amount else 0 end) operating_debit,
                    sum(case when v.accounting_role = 'OPERATING' and vl.side = 'CREDIT'
                             then vl.base_amount else 0 end) operating_credit
                from voucher v
                join input i on i.ledger_id = v.ledger_id
                join periods p on p.period_id = v.period_id
                join voucher_line vl on vl.ledger_id = v.ledger_id and vl.voucher_id = v.id
                where v.status = 'POSTED' and v.deleted_at is null
                group by v.period_id, vl.account_id
            ), leaf_rollup as (
                select p.ledger_id, p.period_id, p.period_no, p.status, leaf.account_id,
                    coalesce(anchor.opening_debit, opening.opening_debit, 0::numeric) opening_debit,
                    coalesce(anchor.opening_credit, opening.opening_credit, 0::numeric) opening_credit,
                    coalesce(movement.period_debit, 0::numeric) period_debit,
                    coalesce(movement.period_credit, 0::numeric) period_credit,
                    coalesce(movement.operating_debit, 0::numeric) operating_debit,
                    coalesce(movement.operating_credit, 0::numeric) operating_credit,
                    coalesce(anchor.opening_debit, opening.opening_debit, 0::numeric)
                        + coalesce(movement.period_debit, 0::numeric) closing_debit,
                    coalesce(anchor.opening_credit, opening.opening_credit, 0::numeric)
                        + coalesce(movement.period_credit, 0::numeric) closing_credit
                from periods p
                join leaf_accounts leaf on leaf.ledger_id = p.ledger_id
                left join anchor on anchor.account_id = leaf.account_id
                left join opening_facts opening
                  on opening.account_id = leaf.account_id and not exists (select 1 from previous_period)
                left join movements movement
                  on movement.period_id = p.period_id and movement.account_id = leaf.account_id
                where p.period_no = 1

                union all

                select p.ledger_id, p.period_id, p.period_no, p.status, previous.account_id,
                    previous.closing_debit opening_debit,
                    previous.closing_credit opening_credit,
                    coalesce(movement.period_debit, 0::numeric) period_debit,
                    coalesce(movement.period_credit, 0::numeric) period_credit,
                    coalesce(movement.operating_debit, 0::numeric) operating_debit,
                    coalesce(movement.operating_credit, 0::numeric) operating_credit,
                    previous.closing_debit
                        + coalesce(movement.period_debit, 0::numeric) closing_debit,
                    previous.closing_credit
                        + coalesce(movement.period_credit, 0::numeric) closing_credit
                from leaf_rollup previous
                join periods p
                  on p.ledger_id = previous.ledger_id and p.period_no = previous.period_no + 1
                left join movements movement
                  on movement.period_id = p.period_id and movement.account_id = previous.account_id
            ), account_path as (
                select a.ledger_id, a.id source_id, a.id account_id, a.parent_id
                from ledger_account a
                join input i on i.ledger_id = a.ledger_id

                union all

                select path.ledger_id, path.source_id, parent.id, parent.parent_id
                from account_path path
                join ledger_account parent
                  on parent.ledger_id = path.ledger_id and parent.id = path.parent_id
            ), snapshots as (
                select leaf.ledger_id, leaf.period_id, leaf.status, path.account_id,
                    sum(leaf.opening_debit) opening_debit,
                    sum(leaf.opening_credit) opening_credit,
                    sum(leaf.period_debit) period_debit,
                    sum(leaf.period_credit) period_credit,
                    sum(leaf.operating_debit) operating_debit,
                    sum(leaf.operating_credit) operating_credit,
                    sum(leaf.closing_debit) closing_debit,
                    sum(leaf.closing_credit) closing_credit
                from leaf_rollup leaf
                join account_path path
                  on path.ledger_id = leaf.ledger_id and path.source_id = leaf.account_id
                group by leaf.ledger_id, leaf.period_id, leaf.status, path.account_id
            )
            insert into account_period_balance (
                ledger_id, period_id, account_id,
                opening_debit_base, opening_credit_base,
                period_debit_base, period_credit_base,
                operating_debit_base, operating_credit_base,
                closing_debit_base, closing_credit_base,
                finalized_at, version, updated_at)
            select ledger_id, period_id, account_id,
                opening_debit, opening_credit,
                period_debit, period_credit,
                operating_debit, operating_credit,
                closing_debit, closing_credit,
                case when status = 'CLOSED' then now() else null end,
                1, now()
            from snapshots
            where opening_debit <> 0
               or opening_credit <> 0
               or period_debit <> 0
               or period_credit <> 0
               or operating_debit <> 0
               or operating_credit <> 0
               or closing_debit <> 0
               or closing_credit <> 0
            """;

    private final JdbcTemplate jdbc;

    public BalanceSnapshotRebuilder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int rebuildAll(UUID ledgerId) {
        UUID firstPeriodId = jdbc.query("""
                select id from accounting_period where ledger_id = ? order by period_code limit 1
                """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, ledgerId);
        return firstPeriodId == null ? 0 : rebuildFrom(ledgerId, firstPeriodId);
    }

    public int rebuildFrom(UUID ledgerId, UUID sourcePeriodId) {
        jdbc.update("""
                delete from account_period_balance b
                using accounting_period target, accounting_period source
                where b.ledger_id = ? and target.ledger_id = b.ledger_id and target.id = b.period_id
                  and source.ledger_id = b.ledger_id and source.id = ?
                  and target.period_code >= source.period_code
                """, ledgerId, sourcePeriodId);
        return jdbc.update(REBUILD_SQL, ledgerId, sourcePeriodId);
    }
}
