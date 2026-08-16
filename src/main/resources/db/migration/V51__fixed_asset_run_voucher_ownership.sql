alter table fixed_asset_depreciation_run
    drop constraint ck_fixed_asset_run_cancellation;

alter table fixed_asset_depreciation_run
    rename column cancelled_voucher_id to historical_voucher_id;

update fixed_asset_depreciation_run
set historical_voucher_id = coalesce(historical_voucher_id, voucher_id),
    voucher_id = null
where status = 'SUPERSEDED';

alter table fixed_asset_depreciation_run
    add constraint ck_fixed_asset_run_voucher_ownership
        check ((status in ('POSTED', 'STALE') and voucher_id is not null and historical_voucher_id is null
                and cancelled_at is null and cancelled_by is null and cancellation_reason is null)
            or (status = 'SUPERSEDED' and voucher_id is null and historical_voucher_id is not null
                and cancelled_at is null and cancelled_by is null and cancellation_reason is null)
            or (status = 'CANCELLED' and voucher_id is null and historical_voucher_id is not null
                and cancelled_at is not null and cancelled_by is not null and cancellation_reason is not null));
