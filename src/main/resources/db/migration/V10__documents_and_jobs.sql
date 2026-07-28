create table document (
    id uuid primary key,
    ledger_id uuid not null references ledger (id),
    object_key varchar(200) not null,
    file_name varchar(255) not null,
    content_type varchar(100) not null,
    size_bytes bigint not null,
    sha256 varchar(64) not null,
    status varchar(32) not null default 'UPLOADED',
    duplicate_warning jsonb,
    created_at timestamptz not null default now(),
    created_by uuid not null references app_user (id),
    constraint uk_document_ledger_object_key unique (ledger_id, object_key),
    constraint ck_document_status check (status in ('UPLOADED', 'PROCESSING', 'EXTRACTED', 'FAILED'))
);

create index ix_document_ledger_sha256 on document (ledger_id, sha256);

create table document_extraction (
    id uuid primary key,
    ledger_id uuid not null references ledger (id),
    document_id uuid not null references document (id),
    provider varchar(64) not null,
    provider_version varchar(64) not null,
    structured_result jsonb not null,
    source_references jsonb,
    input_hash varchar(64) not null,
    output_hash varchar(64) not null,
    status varchar(32) not null default 'SUCCEEDED',
    created_at timestamptz not null default now(),
    constraint ck_document_extraction_status check (status in ('SUCCEEDED', 'FAILED'))
);

create table background_job (
    id uuid primary key,
    ledger_id uuid not null references ledger (id),
    job_type varchar(64) not null,
    aggregate_type varchar(64) not null,
    aggregate_id uuid not null,
    payload jsonb not null default '{}'::jsonb,
    status varchar(32) not null default 'QUEUED',
    attempts integer not null default 0,
    next_run_at timestamptz not null default now(),
    locked_at timestamptz,
    locked_by varchar(200),
    last_error_code varchar(64),
    last_error_message varchar(1000),
    created_at timestamptz not null default now(),
    constraint ck_background_job_status check (status in ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'RETRYING', 'NEEDS_HUMAN'))
);

create index ix_background_job_claim on background_job (status, next_run_at, created_at);
