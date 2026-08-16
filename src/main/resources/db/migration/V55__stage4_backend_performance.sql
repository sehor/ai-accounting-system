create index if not exists ix_audit_revision_ledger_created_id
    on audit_revision (ledger_id, created_at desc, id desc);

create index if not exists ix_balance_projection_event_created_id
    on balance_projection_event (created_at, id);
