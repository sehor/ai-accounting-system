alter table agent_tool_audit
    alter column result_hash drop not null;

alter table agent_tool_audit
    add column duration_ms bigint not null default 0;

alter table agent_tool_audit
    add constraint ck_agent_tool_audit_duration_ms check (duration_ms >= 0);
