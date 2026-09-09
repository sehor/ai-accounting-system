-- Keep existing numeric 记 numbers. Append legacy business identifiers to the
-- same ledger/period sequence without changing voucher IDs or accounting lines.
lock table voucher in share row exclusive mode;

with maxima as (
    select ledger_id, period_id,
           coalesce(max(voucher_number::numeric) filter (
               where voucher_type = '记' and voucher_number ~ '^[0-9]+$'
           ), 0) as last_number
    from voucher
    where deleted_at is null
    group by ledger_id, period_id
), numbered as (
    select v.id, m.last_number + row_number() over (
        partition by v.ledger_id, v.period_id
        order by v.voucher_date, v.created_at, v.id
    ) as next_number
    from voucher v
    join maxima m using (ledger_id, period_id)
    where v.deleted_at is null
      and (v.voucher_type <> '记' or v.voucher_number !~ '^[0-9]+$')
)
update voucher v
set voucher_type = '记', voucher_number = n.next_number::text,
    version = v.version + 1, updated_at = now()
from numbered n
where v.id = n.id;
