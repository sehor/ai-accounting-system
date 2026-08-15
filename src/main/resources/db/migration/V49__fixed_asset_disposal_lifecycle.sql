alter table fixed_asset_disposal
    add column status varchar(32) not null default 'ACTIVE',
    add column cancelled_at timestamptz,
    add column cancelled_by uuid references app_user (id),
    add column cancellation_reason varchar(1000);

alter table fixed_asset_disposal
    add constraint ck_fixed_asset_disposal_status
        check (status in ('ACTIVE', 'CANCELLED')),
    add constraint ck_fixed_asset_disposal_cancellation
        check ((status = 'ACTIVE' and cancelled_at is null and cancelled_by is null and cancellation_reason is null)
            or (status = 'CANCELLED' and cancelled_at is not null and cancelled_by is not null
                and cancellation_reason is not null));

alter table fixed_asset_disposal
    drop constraint uk_fixed_asset_disposal_asset;

create unique index uk_fixed_asset_disposal_active_asset
    on fixed_asset_disposal (ledger_id, asset_id)
    where status = 'ACTIVE';

alter table fixed_asset_change
    rename column effective_period_id to change_period_id;
