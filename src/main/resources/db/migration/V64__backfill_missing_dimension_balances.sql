-- V44 created the detail projection without populating historical periods.
-- Queue only ledgers with a missing nonzero leaf balance, including the empty
-- dimension combination used by ordinary accounts. Rebuild both projections
-- from the first period so old directional/netted snapshots cannot seed the repair.
with missing_details as (
    select distinct b.ledger_id
    from account_period_balance b
    join ledger_account a on a.ledger_id = b.ledger_id and a.id = b.account_id
    where not exists (select 1 from ledger_account child
                      where child.ledger_id = a.ledger_id and child.parent_id = a.id)
      and (b.opening_debit_base <> 0 or b.opening_credit_base <> 0
        or b.period_debit_base <> 0 or b.period_credit_base <> 0
        or b.operating_debit_base <> 0 or b.operating_credit_base <> 0
        or b.closing_debit_base <> 0 or b.closing_credit_base <> 0)
      and not exists (select 1 from dimension_period_balance d
                      where d.ledger_id = b.ledger_id and d.period_id = b.period_id
                        and d.account_id = b.account_id)
), queued as (
    insert into balance_rebuild_job (id, ledger_id, period_from, period_to, reason, requested_by)
    select gen_random_uuid(), l.id, min(p.period_code), max(p.period_code),
        'V64: backfill historical detail balances omitted by V44', l.created_by
    from missing_details missing
    join ledger l on l.id = missing.ledger_id and l.deleted_at is null
    join accounting_period p on p.ledger_id = l.id
    where not exists (select 1 from balance_rebuild_job job
                      where job.ledger_id = l.id and job.status in ('QUEUED', 'RUNNING'))
    group by l.id, l.created_by
    returning id, ledger_id, requested_by, reason
)
insert into balance_rebuild_audit (ledger_id, job_id, actor_id, action, reason)
select ledger_id, id, requested_by, 'REQUEST', reason from queued;
