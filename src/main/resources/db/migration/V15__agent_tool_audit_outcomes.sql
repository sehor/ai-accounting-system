alter table agent_tool_audit
    alter column trace_id type varchar(128) using trace_id::text,
    add column outcome varchar(16) not null default 'SUCCESS',
    add column error_code varchar(100),
    add constraint ck_agent_tool_audit_outcome check (outcome in ('SUCCESS', 'FAILURE'));
