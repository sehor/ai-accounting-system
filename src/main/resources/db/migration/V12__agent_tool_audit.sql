create table agent_tool_audit (
    id uuid primary key,
    tool_name varchar(100) not null,
    ledger_id uuid,
    actor_id uuid not null,
    trace_id uuid not null,
    input_hash varchar(64) not null,
    result_hash varchar(64) not null,
    created_at timestamptz not null default now()
);

create index ix_agent_tool_audit_ledger_created on agent_tool_audit (ledger_id, created_at);
