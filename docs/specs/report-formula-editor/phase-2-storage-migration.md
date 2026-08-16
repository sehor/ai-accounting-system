# 阶段 2：存储、迁移与备份

## 前置

阶段 1 已通过；标准包公式均为 schemaVersion 1。

## 目标

建立当前发布版、唯一草稿、不可变历史版本和具体科目引用的持久化能力。

## 数据库

使用下一个可用 Flyway 版本（当前最高为 V60 时使用 V61）：

```sql
alter table report_formula_snapshot
  add column formula_kind varchar(32) not null default 'LEGACY',
  add column schema_version int not null default 0,
  add column published_version int not null default 1,
  add column updated_at timestamptz not null default now(),
  add column updated_by uuid references app_user(id);

create table report_formula_revision (
  id uuid primary key,
  formula_id uuid not null references report_formula_snapshot(id) on delete cascade,
  state varchar(16) not null check (state in ('DRAFT','PUBLISHED')),
  definition_json jsonb not null,
  base_published_version int not null,
  draft_version bigint,
  published_version int,
  source varchar(32) not null check (source in ('STANDARD','MIGRATION','USER','ROLLBACK')),
  rollback_of_version int,
  last_previewed_draft_version bigint,
  preview_has_warnings boolean not null default false,
  created_by uuid references app_user(id),
  updated_by uuid references app_user(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create unique index uk_formula_one_draft
  on report_formula_revision(formula_id) where state='DRAFT';
create unique index uk_formula_published_version
  on report_formula_revision(formula_id,published_version) where state='PUBLISHED';

create table report_formula_account_reference (
  revision_id uuid not null references report_formula_revision(id) on delete cascade,
  ledger_id uuid not null references ledger(id),
  account_id uuid not null references ledger_account(id),
  primary key (revision_id, account_id)
);
```

为 `state` 与 draft/published 版本列增加一致性 check。`account_id` 外键保持默认 RESTRICT，使被任一修订引用的科目不能硬删除。

## Repository

新增独立 `ReportFormulaRepository` 和 JDBC 实现，负责：

- 按 ledgerId+code 读取/锁定 snapshot。
- 创建、读取、乐观锁更新、删除唯一草稿。
- 插入不可变发布版本。
- 分页读取历史。
- 替换某修订的具体科目引用索引。

草稿更新 SQL 必须包含 `where id=? and draft_version=?`，成功后版本加一并清空上次试算状态。

## 老数据迁移

新增幂等启动迁移：

```java
if (snapshot.schemaVersion == 1 && version1Exists()) return;

canonical = ledger.standard == SME
    ? standardCatalog.formula(ledger.standard, snapshot.code)
    : convertLegacyCasCategories(snapshot.formulaJson);

validate(canonical);
updateSnapshotToSchema1(canonical);
upsertPublishedRevision(version=1, source=MIGRATION, canonical);
```

任一公式无法迁移时抛出异常，阻止应用 Ready；正式运行不保留 LEGACY 回退。

新建账套时同时插入 snapshot 和 `source=STANDARD` 的发布版本 1。

## 备份

将两个新表加入备份/恢复白名单，顺序为：snapshot → revision → account_reference。补充往返测试。

## 验收

- 老 SME/CAS 账套各有两个发布版本 1，重复启动不重复插入。
- 同一公式数据库层只能有一个草稿。
- 旧 draftVersion 更新返回冲突结果。
- 被公式修订引用的具体科目不能删除。
- 备份恢复后 snapshot、revision、reference 数量和 JSON 一致。

## 交接记录

记录迁移版本号、表结构、Repository 方法及迁移/备份测试结果。
