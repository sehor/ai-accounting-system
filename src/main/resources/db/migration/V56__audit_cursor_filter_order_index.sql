create index if not exists ix_audit_revision_ledger_type_aggregate_created_id
    on audit_revision (ledger_id, aggregate_type, aggregate_id, created_at desc, id desc);
