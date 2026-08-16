create index if not exists ix_audit_revision_ledger_type_created_id
    on audit_revision (ledger_id, aggregate_type, created_at desc, id desc);

create index if not exists ix_audit_revision_ledger_aggregate_created_id
    on audit_revision (ledger_id, aggregate_id, created_at desc, id desc);
