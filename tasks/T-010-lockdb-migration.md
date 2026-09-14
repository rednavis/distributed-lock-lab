# T-010 — Flyway migration: lockdb schema

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M1 (Postgres lock backend) · **Estimate** 25 min

**Preconditions** — T-001…T-008 (M0) complete. You inherit a Gradle 9.5 Kotlin-DSL monorepo with
`build-logic`, a version catalog, Spotless/google-java-format, GitHub Actions CI, and the module
skeletons from C5 `#ct5-modules`, including an empty-but-building `lock-server`.

**Goal** — Create the `lockdb` schema in `lock-server` as Flyway migrations that reproduce C1
`#ct1-lockdb` object-for-object, and prove it applies against PostgreSQL 16 in a Testcontainer.

## 1. Why this task exists

Every M1 guarantee is a database constraint, not application logic: mutual exclusion is the
`lock_entry` primary key, session-death release is `ON DELETE CASCADE`, and token monotonicity is one
global sequence. Getting the DDL exactly right is therefore the cheapest correctness work in the whole
project, and getting it subtly wrong (a missing index, a nullable `session_id`, a `DEFAULT nextval`) is
invisible until the fencing experiment at T-042. Migrations live inside the owning module so the two
databases can never share a migration tree (C5 `#ct5-layout`).

## 2. Contracts to obey

| What | Pinned at |
|---|---|
| Sequence, three tables, all columns/constraints, both indexes | `docs/contracts/C1-database-schemas.md#ct1-lockdb` |
| Why one global sequence rather than a per-row version | C1 `#ct1-seq` |
| Migration filenames, migration directory, history table | C1 `#ct1-migrations` |
| `lockdb` is owned by `lock-server` only, user `lockapp` | C5 `#ct5-config`, C1 `#ct1-scope` |
| `flyway-core` 11.8.0, `postgresql` 42.7.5, Testcontainers BOM — from the catalog only | C5 `#ct5-catalog` |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report.** Note one
known disagreement: the work order called this file `V1__locks.sql`; C1 `#ct1-migrations` pins
imperative names and lists `V1__create_lock_session_and_lock_entry.sql` and
`V2__create_lock_revocation.sql`. **Follow the contract**; record the deviation in §9.

## 3. Deliverables

| Path | What |
|---|---|
| `lock-server/src/main/resources/db/migration/lockdb/V1__create_lock_session_and_lock_entry.sql` | The sequence, `lock_session`, `lock_entry`, both indexes |
| `lock-server/src/main/resources/db/migration/lockdb/V2__create_lock_revocation.sql` | `lock_revocation` only |
| `lock-server/build.gradle.kts` | Add `spring-boot-jdbc`, `postgresql`, `flyway-core`, Testcontainers (test) via catalog aliases |
| `lock-server/src/main/resources/application.yaml` | Flyway locations = the lockdb tree only; `baseline-on-migrate` false; datasource from env per C5 `#ct5-env` |
| `lock-server/src/test/java/dev/lock/server/store/pg/LockdbMigrationIT.java` | Testcontainers PostgreSQL 16 integration test |
| `tasks/README.md` | Ledger row for T-010 marked done |

## 4. Specification

**V1** — create the sequence first, then `lock_session`, then `lock_entry` (the FK needs its parent),
then the two indexes. Copy every column name, type, nullability, default, and named `CHECK`
constraint from C1 `#ct1-lockdb` verbatim, including the constraint *names* — later tasks assert on
them. `lock_entry.session_id` is `NOT NULL` and references `lock_session (session_id)` with
`ON DELETE CASCADE`; no `ON UPDATE` clause. `fencing_token` gets **no column default** — the acquire
statement calls `nextval` explicitly in both branches (T-011), and a default would not fire on the
`DO UPDATE` path.

**V2** — `lock_revocation` exactly as pinned: `BIGSERIAL` primary key, append-only, no FK to
`lock_entry` (the row it records is gone by then), no index beyond the primary key.

**Configuration** — one Flyway location, the `lockdb` directory; history table `flyway_schema_history`
in that database. Do not enable `clean`. Do not add a second location; `payment-resource` owns `paydb`
migrations and mixing them is the failure C5 `#ct5-layout` calls out.

**LockdbMigrationIT** — start a PostgreSQL 16 container, run Flyway against it programmatically, then
assert, in prose terms: two rows in `flyway_schema_history` with success = true; the sequence and all
three tables exist in `information_schema`/`pg_class`; `lock_entry_expiry_idx` and
`lock_entry_session_idx` exist by name; `lock_entry.session_id` is `NOT NULL`; inserting a
`lock_entry` whose `session_id` has no parent row fails; deleting a `lock_session` row removes its
`lock_entry` children; two successive `nextval('fencing_token_seq')` calls are strictly increasing;
`INSERT` of a `lock_entry` with `fencing_token = 0` violates `lock_entry_token_ck`; a second Flyway
run is a no-op (idempotent). Use JDBC directly — no Spring context, no repositories.

## 5. Acceptance criteria

1. Both migration files exist at the paths in §3 with the contract-pinned filenames.
2. Every object, column, type, default and named constraint in C1 `#ct1-lockdb` appears; a
   diff of names between the contract block and V1+V2 is empty.
3. No `DEFAULT nextval` on `fencing_token`; `grep -n 'DEFAULT nextval' lock-server/src/main/resources/db/migration/lockdb/` returns nothing.
4. `grep -rn 'clock_timestamp' lock-server/src/main/resources/db/migration/` returns nothing.
5. `LockdbMigrationIT` covers all nine assertions in §4 and passes.
6. `application.yaml` lists exactly one Flyway location and it contains `lockdb`.
7. `spotlessCheck` passes; no new dependency string is written outside the version catalog.

## 6. Verification

```
./gradlew :lock-server:test --tests '*LockdbMigrationIT' --info
./gradlew :lock-server:spotlessCheck
grep -cE 'CREATE (SEQUENCE|TABLE|INDEX)' lock-server/src/main/resources/db/migration/lockdb/*.sql   # expect 5 then 1
```

Expected: the test task is green, container logs show `Successfully applied 2 migrations`, and a
second in-test migrate reports `Schema … is up to date`.

## 7. Out of scope

The acquire/renew/release statements and `PostgresLockStore` (T-011, T-012); `SessionRegistry`
behaviour (T-013); `paydb` (M2); Cloud SQL instances and the `lockapp` grant (M5); the reaper (M1
later); any metric or log event (C4, M6).

## 8. Hazards

Sequence-before-table ordering, and FK-before-parent, both fail the migration hard — that is the easy
class. The dangerous class is silent: omitting `lock_entry_session_idx` leaves cascade deletes doing
sequential scans that only hurt under load, and a `DEFAULT nextval` looks harmless while disabling the
takeover token bump described in C1 `#ct1-acquire` Warning 1 (the project's signature silent bug). Also do
not "improve" `lock_revocation` with a FK; C1 `#ct1-lockdb` states nothing reads it on the hot path.

## 9. On completion

Mark the T-010 row done in `tasks/README.md`. Record the filename deviation from §2 (spec said
`V1__locks.sql`, contract naming used) and anything the contract left silent that you had to stop on.
