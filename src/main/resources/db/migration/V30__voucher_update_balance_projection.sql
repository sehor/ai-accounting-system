alter table balance_projection_event
    drop constraint uk_balance_projection_event_version;

alter table balance_projection_event
    add constraint uk_balance_projection_event_version
        unique (aggregate_type, aggregate_id, aggregate_version, event_type, period_id);

alter table balance_projection_event
    drop constraint ck_balance_projection_event_type;

alter table balance_projection_event
    add constraint ck_balance_projection_event_type
        check (event_type in ('POST', 'UNPOST', 'UPDATE', 'OPENING_CONFIRM'));
