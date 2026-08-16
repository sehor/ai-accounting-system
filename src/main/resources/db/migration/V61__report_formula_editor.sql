-- Report formula editor: snapshot columns, revisions, concrete account references.
alter table report_formula_snapshot
    add column formula_kind varchar(32) not null default 'LEGACY',
    add column schema_version int not null default 0,
    add column published_version int not null default 1,
    add column updated_at timestamptz not null default now(),
    add column updated_by uuid references app_user (id);

create table report_formula_revision (
    id uuid primary key,
    formula_id uuid not null references report_formula_snapshot (id) on delete cascade,
    state varchar(16) not null check (state in ('DRAFT', 'PUBLISHED')),
    definition_json jsonb not null,
    base_published_version int not null,
    draft_version bigint,
    published_version int,
    source varchar(32) not null check (source in ('STANDARD', 'MIGRATION', 'USER', 'ROLLBACK')),
    rollback_of_version int,
    last_previewed_draft_version bigint,
    preview_has_warnings boolean not null default false,
    created_by uuid references app_user (id),
    updated_by uuid references app_user (id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create unique index uk_formula_one_draft
    on report_formula_revision (formula_id) where state = 'DRAFT';
create unique index uk_formula_published_version
    on report_formula_revision (formula_id, published_version) where state = 'PUBLISHED';

-- A draft carries a draft_version and never a published version; a published
-- revision is the reverse.
alter table report_formula_revision
    add constraint chk_report_formula_revision_state_versions check (
        (state = 'DRAFT' and draft_version is not null and published_version is null)
        or (state = 'PUBLISHED' and published_version is not null and draft_version is null)
    );

create table report_formula_account_reference (
    revision_id uuid not null references report_formula_revision (id) on delete cascade,
    ledger_id uuid not null references ledger (id),
    account_id uuid not null references ledger_account (id),
    primary key (revision_id, account_id)
);
