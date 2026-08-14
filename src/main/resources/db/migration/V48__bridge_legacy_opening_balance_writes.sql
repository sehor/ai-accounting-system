-- Keep the pre-dimension-combination upsert contract valid during a rolling
-- deployment. PostgreSQL cannot infer V47's partial index for the old
-- ON CONFLICT column list, so retain the original full conflict target and
-- bridge legacy writes to an immutable combination before conflict detection.
drop index if exists uk_opening_balance_legacy_key;

update opening_balance balance
set dimension_key = combination.dimension_key
from dimension_combination combination
where balance.ledger_id = combination.ledger_id
  and balance.dimension_combination_id = combination.id
  and balance.dimension_key = ''
  and combination.canonical_key = 'v1;';

create unique index uk_opening_balance_legacy_key
    on opening_balance (ledger_id, period_id, account_id, currency, dimension_key);

create or replace function bridge_legacy_opening_balance_combination()
returns trigger
language plpgsql
as $$
declare
    canonical text;
    combination_id uuid;
    fingerprint varchar(32);
    candidate_md5 varchar(32);
begin
    if new.dimension_combination_id is not null then
        return new;
    end if;

    if coalesce(new.dimension_key, '') = '' then
        canonical := 'v1;';
    else
        canonical := 'legacy-v1;' || new.dimension_key;
    end if;
    candidate_md5 := md5('dimension-combination:' || new.ledger_id::text || ':' || canonical);

    insert into dimension_combination (id, ledger_id, kind, canonical_key, dimension_key)
    values ((
            substr(candidate_md5, 1, 8) || '-' || substr(candidate_md5, 9, 4) || '-' ||
            substr(candidate_md5, 13, 4) || '-' || substr(candidate_md5, 17, 4) || '-' ||
            substr(candidate_md5, 21, 12)
        )::uuid,
        new.ledger_id,
        case when canonical = 'v1;' then 'STRUCTURED' else 'LEGACY_UNMAPPED' end,
        canonical,
        md5(canonical))
    on conflict (ledger_id, canonical_key)
    do update set canonical_key = excluded.canonical_key
    returning id, dimension_key into combination_id, fingerprint;

    new.dimension_combination_id := combination_id;
    if canonical = 'v1;' then
        -- Empty legacy and structured writes must hit the same old conflict key.
        new.dimension_key := fingerprint;
    end if;
    return new;
end;
$$;

create trigger trg_bridge_legacy_opening_balance_combination
before insert or update of dimension_key, dimension_combination_id
on opening_balance
for each row
execute function bridge_legacy_opening_balance_combination();
