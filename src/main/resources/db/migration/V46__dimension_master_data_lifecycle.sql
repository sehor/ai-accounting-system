alter table dimension_type
    add column updated_at timestamptz not null default now(),
    add column version bigint not null default 0;

alter table dimension_value
    add column updated_at timestamptz not null default now(),
    add column version bigint not null default 0;
