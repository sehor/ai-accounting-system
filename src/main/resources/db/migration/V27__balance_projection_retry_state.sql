alter table balance_projection_state
    add column attempts integer not null default 0,
    add column next_attempt_at timestamptz not null default now();

alter table balance_projection_state
    add constraint ck_balance_projection_state_attempts check (attempts >= 0);

create index ix_balance_projection_state_retry
    on balance_projection_state (status, next_attempt_at, last_applied_event_id, last_enqueued_event_id);
