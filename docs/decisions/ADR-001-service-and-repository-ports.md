# ADR-001: Service contracts and database repository ports

## Status

Accepted

## Date

2026-07-28

## Objective

Refactor the modular monolith so HTTP, MCP, and cross-module callers depend on
application-service interfaces, while application services depend on repository
interfaces instead of `JdbcTemplate`.

The first implementation remains PostgreSQL/JDBC. A future database adapter must
be addable without changing controllers, MCP tools, application-service contracts,
or business rules.

## Assumptions

1. This change introduces both service interfaces and repository interfaces.
2. Existing REST/MCP inputs, outputs, error codes, ordering, transaction boundaries,
   schema, and Flyway history remain compatible.
3. PostgreSQL is the only adapter delivered in this change.
4. "Supports multiple databases" means the application layer is database-agnostic;
   it does not mean MySQL, SQL Server, or SQLite are certified now.
5. Database-specific SQL, locking, JSON handling, and exception translation belong
   to the PostgreSQL adapter.

## Decision

Use ports and adapters inside each existing Spring Modulith module.

```text
REST / MCP
    |
    v
public Service interface
    |
    v
internal application Service implementation  <-- transaction boundary
    |
    v
internal Repository interface                 <-- database-neutral contract
    |
    v
internal PostgreSQL/JDBC adapter               <-- SQL and JdbcTemplate
```

Service contracts keep the current public names, for example `VoucherService`.
Concrete classes use `DefaultVoucherService` and live under
`voucher.internal.application`.

Repository contracts are use-case-oriented rather than one generic CRUD interface
per table. They accept and return Java records and scalar values; they never expose
SQL, `ResultSet`, `JdbcTemplate`, PostgreSQL JSON types, or vendor exception types.

The following database ports are expected:

- `IdentityRepository`
- `LedgerRepository`
- `VoucherRepository`
- `ReportingRepository`
- `DocumentRepository`
- `JobRepository`
- `AuditRepository`
- `AgentToolAuditRepository`

Each port initially has one `Jdbc...Repository` implementation in the owning
module's `internal.persistence` package.

## Interface rules

- Controllers, MCP tools, and other modules inject service interfaces only.
- Application-service implementations inject repository interfaces only.
- Repository methods that access ledger-owned data require `ledgerId`.
- Repository contracts describe business intent, such as `lockLedger`,
  `replaceVoucherLines`, or `findTrialBalance`; they do not accept raw SQL.
- Transactions remain on application-service implementations so one use case may
  coordinate several repository calls atomically.
- Stable application errors remain owned by the application layer. Adapters
  translate vendor constraint failures into database-neutral repository outcomes
  or exceptions before they cross the port.
- No generic base repository, service locator, factory hierarchy, or one-interface-
  per-table scaffolding is introduced.

## Project structure

```text
src/main/java/com/example/accounting/<module>/
├── <Module>Service.java                    # public application interface
├── *Requests.java / *Responses.java        # existing public contracts
└── internal/
    ├── application/
    │   └── Default<Module>Service.java      # business rules + transactions
    ├── port/
    │   ├── <Module>Repository.java          # database-neutral persistence port
    │   └── RepositoryModels.java            # internal records when needed
    └── persistence/
        └── Jdbc<Module>Repository.java       # PostgreSQL/JDBC implementation
```

The `documents` module keeps local file I/O unchanged in this decision. A separate
object-storage port is out of scope because the requested variability is the
database.

## Code style

```java
public interface VoucherRepository {
    Optional<VoucherRecord> find(UUID ledgerId, UUID voucherId);

    boolean changeStatus(UUID ledgerId, UUID voucherId, String expected, String next, UUID actorId);
}

@Service
final class DefaultVoucherService implements VoucherService {
    private final VoucherRepository vouchers;

    DefaultVoucherService(VoucherRepository vouchers) {
        this.vouchers = vouchers;
    }
}

@Repository
final class JdbcVoucherRepository implements VoucherRepository {
    private final JdbcTemplate jdbc;
}
```

Prefer explicit module records and methods over generic maps or reflection-based
mapping. Preserve current method signatures unless a database-neutral result type
is required internally.

## Commands

```powershell
.\mvnw.cmd -Dtest=AccountingArchitectureTest test
.\mvnw.cmd -Dtest=<affected-module-tests> test
.\mvnw.cmd verify
graphify update .
```

Project command policy still requires running these through `rtk`.

## Testing strategy

1. Add an architecture test that fails while Spring services or MCP adapters hold a
   `JdbcTemplate`/`JdbcOperations` field.
2. Assert the public `*Service` types are interfaces.
3. Keep existing PostgreSQL integration tests as adapter contract and behavior
   regression tests.
4. Migrate one module at a time and run only its affected tests after each slice.
5. Run the full Maven verification once after all modules are migrated.
6. A future database adapter must pass the same repository contract tests and
   application behavior tests.

## Boundaries

### Always

- Preserve ledger isolation and parameter binding.
- Preserve transaction and locking semantics.
- Keep PostgreSQL-specific behavior inside JDBC adapter classes.
- Keep each migration slice compilable and behaviorally equivalent.

### Ask first

- Add a second database driver or dialect.
- Move or rewrite existing Flyway migrations.
- Change public REST/MCP contracts or error codes.
- Change transaction isolation or locking behavior.

### Never

- Commit database credentials.
- Expose `JdbcTemplate`, SQL strings, or vendor types through repository interfaces.
- Add empty interfaces that have no caller boundary.
- Claim support for a database that has no adapter and contract-test run.

## Success criteria

- All public application services are interfaces with one internal implementation.
- Controllers, MCP tools, and cross-module services inject those interfaces.
- No application-service implementation or MCP tool directly depends on
  `JdbcTemplate` or `JdbcOperations`.
- All SQL is located in `internal.persistence` adapters.
- Existing behavior, tests, migrations, and API contracts remain unchanged.
- Modulith verification and the full Maven verification pass.
- Adding another database requires new repository adapter implementations and
  vendor migrations/configuration, not edits to application services.

## Alternatives considered

### One `XxxService` interface plus `XxxServiceImpl`, but keep SQL in the implementation

Rejected because it changes class names without creating a database boundary.

### Spring Data generic repositories for every table

Rejected because current persistence operations are aggregate- and use-case-oriented,
and generic CRUD would leak storage structure into business services.

### One application-wide `DatabaseRepository`

Rejected because it would couple all modules and bypass Spring Modulith boundaries.

### Implement several databases now

Deferred until a concrete second database is selected. Repository contract tests
will define the acceptance gate for that adapter.

## Open questions

1. Is the target limited to application portability with PostgreSQL as the sole
   current adapter, as assumed?
2. Should local document storage also be abstracted in this refactor, or remain a
   separate future change?
