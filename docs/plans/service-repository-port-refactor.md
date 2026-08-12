# Implementation Plan: Service and repository port refactor

Status: Completed on 2026-07-28.

## Overview

Implement ADR-001 without changing application behavior. Migration proceeds from
small, low-coupling modules to the transaction-heavy ledger and voucher modules.
Each slice keeps the application compilable and leaves PostgreSQL as the active
adapter.

## Dependency graph

```text
public service contracts
        |
        v
application implementations
        |
        v
repository contracts
        |
        v
PostgreSQL/JDBC adapters

identity ---> ledger ---> voucher ---> reporting
                    \         \
                     \         ---> documents/extraction/jobs
                      \                |
                       ----------------> agent/MCP
```

## Architecture decisions

- Public service names remain stable; implementations move to
  `internal.application.Default*Service`.
- Persistence contracts and models remain internal to their owning Modulith module.
- SQL and `JdbcTemplate` move to `internal.persistence.Jdbc*Repository`.
- Transaction annotations remain on application implementations.
- Existing integration tests are behavior contracts; one new architecture test
  guards the dependency direction.

## Task list

### Phase 1: Guardrails and small modules

#### Task 1: Add architecture regression guard

**Acceptance criteria:**

- Public application service types are required to be interfaces.
- Spring service and MCP adapter fields may not use `JdbcTemplate` or
  `JdbcOperations`.
- The test fails before the refactor and passes when all slices are complete.

**Verification:**

- `.\mvnw.cmd -Dtest=AccountingArchitectureTest test`

**Dependencies:** ADR-001

**Files:** one architecture test plus accepted ADR/plan.

#### Task 2: Migrate identity

**Acceptance criteria:**

- `IdentityService` is an interface.
- `DefaultIdentityService` contains transaction/business orchestration.
- `IdentityRepository` has a JDBC implementation and owns all identity SQL.

**Verification:**

- `.\mvnw.cmd -Dtest=CurrentUserResolverTest,Stage1IdentityLedgerSchemaTest test`

**Dependencies:** Task 1

#### Task 3: Migrate audit

**Acceptance criteria:**

- `AuditService` is an interface with an internal implementation.
- Audit query SQL exists only in `JdbcAuditRepository`.

**Verification:**

- Compile plus affected audit/controller tests.

**Dependencies:** Task 1

#### Task 4: Migrate jobs

**Acceptance criteria:**

- `JobService` is an interface with an internal implementation.
- Job state and query SQL exists only in `JdbcJobRepository`.
- Claim/update transaction semantics remain unchanged.

**Verification:**

- `.\mvnw.cmd -Dtest=Stage5DocumentTest test`

**Dependencies:** Task 1

### Checkpoint: small modules

- Application compiles.
- Identity and document/job tests pass.
- No public API signature has changed.

### Phase 2: Documents, reporting, and agent boundary

#### Task 5: Migrate document metadata and extraction persistence

**Acceptance criteria:**

- `DocumentService` and `ExtractionService` are interfaces.
- File streaming remains in the document application implementation.
- Document/extraction database access is behind module repository ports.

**Verification:**

- `.\mvnw.cmd -Dtest=Stage5DocumentTest test`

**Dependencies:** Tasks 2 and 4

#### Task 6: Migrate reporting

**Acceptance criteria:**

- `ReportingService` is an interface.
- Trial balance, statements, ledgers, formula snapshots, and finance-query SQL live
  in `JdbcReportingRepository`.
- Report calculations and DSL validation remain in `DefaultReportingService`.

**Verification:**

- `.\mvnw.cmd -Dtest=Stage4ReportingTest test`

**Dependencies:** Task 2

#### Task 7: Remove JDBC from MCP tools

**Acceptance criteria:**

- MCP tools inject public service interfaces.
- Agent audit writes use `AgentToolAuditRepository`.
- Existing MCP transaction and audit behavior remains atomic.

**Verification:**

- `.\mvnw.cmd -Dtest=FinanceMcpToolsTest test`

**Dependencies:** Tasks 2, 5, and 6

### Checkpoint: external adapters

- REST and MCP compile against service interfaces.
- No Controller or MCP tool depends on JDBC.
- Reporting and document/MCP tests pass.

### Phase 3: Core accounting aggregates

#### Task 8: Define and implement ledger persistence ports

**Acceptance criteria:**

- Ledger, membership, period, dimension, opening-balance, and initialization SQL is
  implemented behind ledger-owned repository contracts.
- Repository methods retain mandatory `ledgerId` scoping.
- Locking operations remain explicit in the contract.

**Verification:**

- Compile and ledger tests.

**Dependencies:** Task 2

#### Task 9: Migrate ledger application service

**Acceptance criteria:**

- `LedgerService` is a public interface.
- `DefaultLedgerService` has no JDBC dependency.
- Initialization, authorization, replacement, and concurrency behavior is unchanged.

**Verification:**

- `.\mvnw.cmd -Dtest=Stage1IdentityLedgerSchemaTest,Stage2LedgerInitializationTest,Stage2BaseDataTest,LedgerControllerTest test`

**Dependencies:** Task 8

#### Task 10: Define and implement voucher persistence ports

**Acceptance criteria:**

- Voucher, lines, approval, revision, idempotency, hard-delete, and reference-cleanup SQL is implemented
  behind voucher-owned repository contracts.
- Repository contracts expose typed records rather than SQL/JDBC types.
- The PostgreSQL uniqueness and optimistic-update semantics remain unchanged.

**Verification:**

- Compile and voucher tests.

**Dependencies:** Task 9

#### Task 11: Migrate voucher application service

**Acceptance criteria:**

- `VoucherService` is a public interface.
- `DefaultVoucherService` has no JDBC dependency.
- State transitions, snapshots, restore, direct posted-voucher mutation, pagination, and authorization
  behavior remain unchanged.

**Verification:**

- `.\mvnw.cmd -Dtest=Stage3VoucherTest,Stage7IsolationTest test`

**Dependencies:** Task 10

### Checkpoint: accounting core

- Ledger and voucher services depend only on repository/service interfaces.
- All accounting-core regression tests pass.
- No SQL remains in application-service implementations.

### Phase 4: Documentation and final verification

#### Task 12: Align architecture documentation and module metadata

**Acceptance criteria:**

- The original TDD no longer claims that service/repository interfaces are avoided.
- Package/module documentation describes public service and internal persistence
  boundaries.

**Verification:**

- Review documentation diff and run Modulith verification.

**Dependencies:** Tasks 1–11

#### Task 13: Final verification

**Acceptance criteria:**

- Architecture guard passes.
- Full Maven verification passes.
- `git diff --check` passes.
- Graphify output is current.

**Verification:**

- `.\mvnw.cmd verify`
- `git diff --check`
- `graphify update .`

**Dependencies:** Task 12

## Risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Business rules accidentally move into JDBC adapters | High | Keep calculations, validation, authorization, and state-machine decisions in application implementations |
| Transaction behavior changes during extraction | High | Retain service-level transactions and migrate one module at a time |
| Repository contracts leak PostgreSQL details | High | Use typed Java records/outcomes; prohibit SQL/JDBC/vendor types in ports |
| Ledger/voucher changes become too large to review | High | Add contracts first, then adapter, then switch service; verify at each checkpoint |
| Existing dirty bug-fix work is overwritten | High | Preserve current branch changes and avoid destructive Git operations |

## Scope intentionally excluded

- A second database driver or implementation.
- Rewriting or relocating existing Flyway migrations.
- Abstracting local file storage.
- Changing REST/MCP schemas or accounting behavior.
