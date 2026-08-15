alter table fixed_asset_disposal
    drop constraint ck_fixed_asset_disposal_cancellation,
    add column cancelled_depreciation_voucher_id uuid,
    add column cancelled_transfer_voucher_id uuid,
    add column cancelled_settlement_voucher_id uuid,
    alter column transfer_voucher_id drop not null,
    alter column settlement_voucher_id drop not null,
    add constraint fk_fixed_asset_disposal_depreciation_voucher
        foreign key (ledger_id, depreciation_voucher_id) references voucher (ledger_id, id),
    add constraint fk_fixed_asset_disposal_transfer_voucher
        foreign key (ledger_id, transfer_voucher_id) references voucher (ledger_id, id),
    add constraint fk_fixed_asset_disposal_settlement_voucher
        foreign key (ledger_id, settlement_voucher_id) references voucher (ledger_id, id),
    add constraint ck_fixed_asset_disposal_cancellation
        check ((status = 'ACTIVE' and cancelled_at is null and cancelled_by is null and cancellation_reason is null
                and transfer_voucher_id is not null and settlement_voucher_id is not null
                and cancelled_depreciation_voucher_id is null and cancelled_transfer_voucher_id is null
                and cancelled_settlement_voucher_id is null)
            or (status = 'CANCELLED' and cancelled_at is not null and cancelled_by is not null
                and cancellation_reason is not null and depreciation_voucher_id is null
                and transfer_voucher_id is null and settlement_voucher_id is null
                and cancelled_transfer_voucher_id is not null and cancelled_settlement_voucher_id is not null));

alter table fixed_asset_depreciation_run
    drop constraint ck_fixed_asset_run_status,
    alter column voucher_id drop not null,
    add column cancelled_voucher_id uuid,
    add column cancelled_at timestamptz,
    add column cancelled_by uuid references app_user (id),
    add column cancellation_reason varchar(1000),
    add constraint ck_fixed_asset_run_status
        check (status in ('POSTED', 'SUPERSEDED', 'STALE', 'CANCELLED')),
    add constraint ck_fixed_asset_run_cancellation
        check ((status <> 'CANCELLED' and voucher_id is not null and cancelled_voucher_id is null
                and cancelled_at is null and cancelled_by is null and cancellation_reason is null)
            or (status = 'CANCELLED' and voucher_id is null and cancelled_voucher_id is not null
                and cancelled_at is not null and cancelled_by is not null and cancellation_reason is not null));

alter table fixed_asset_depreciation_line
    drop constraint ck_fixed_asset_line_status,
    add constraint ck_fixed_asset_line_status check (status in ('ACTIVE', 'SUPERSEDED', 'CANCELLED'));
