# T-020 — Flyway migration: paydb schema

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M2 — protected resource and payout executor · **Estimate** 25 min

**Preconditions** — T-001…T-008 (M0: multi-module Gradle 9.5 Kotlin DSL build, version catalog,
Spotless, CI) and T-010…T-017 (M1: the `lockdb` Flyway tree, the Testcontainers PostgreSQL 16 test
harness and the two-datasource configuration pattern already exist and are green). You inherit a repo
where `payment-resource` is an empty-but-wired Gradle module with no schema of its own.

**Goal** — Create the single baseline Flyway migration that defines all five `paydb` tables with every
CHECK constraint that encodes a project invariant, and prove each constraint rejects its violation.

## 1. Why this task exists

The fencing story is only demonstrable if the database refuses corrupt states on its own. Every CHECK
here is an invariant expressed where no application bug can bypass it: `ledger_leg_uq` is what makes a
double-post impossible, `rail_submission_attempt_uidx` is INV-02 in DDL form. Doing this before any
repository code means T-021 writes queries against a schema that already enforces the rules, rather
than defending them in Java. `paydb` is also a *separate database on a separate Cloud SQL instance*
from `lockdb` (ADR-003), which is why it gets its own migration tree, not a V2 in the existing one.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| The five tables, every column name, type, default and constraint name | `docs/contracts/C1-database-schemas.md#ct1-paydb` |
| Money is `BIGINT` minor units; `FLOAT`/`DOUBLE`/`REAL` forbidden | C1 `#ct1-paydb` |
| Which constraint encodes which invariant | C1 `#ct1-paydb` (constraint→invariant table) |
| Migration filename, location and the one-tree-per-database rule | C1 `#ct1-migrations` |
| `paydb` datasource / Flyway config keys | `docs/contracts/C5-config-build-and-naming.md#ct5-config` |
| Module and repo layout for `payment-resource` | C5 `#ct5-modules`, `#ct5-layout` |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report the mismatch,
quoting both.** Do not implement a reconciled third version. Contract silence is not permission to
invent a column (`docs/04-contracts.md#c-precedence`).

## 3. Deliverables

| Path | What it is |
|---|---|
| `payment-resource/src/main/resources/db/migration/paydb/V1__payments.sql` | The baseline migration. Exact directory per C1 `#ct1-migrations` — if it pins another location, use that. |
| `payment-resource/src/main/resources/application.yaml` | `paydb` datasource + Flyway locations, keys per C5 `#ct5-config`. Extend if T-010…017 already created it. |
| `payment-resource/src/test/java/…/PaydbMigrationIT.java` | Testcontainers PostgreSQL 16 integration test: migration applies clean, then one negative case per named constraint. Package per C5 `#ct5-naming`. |
| `payment-resource/build.gradle.kts` | Add Flyway, JDBC, PostgreSQL driver, Testcontainers via the version catalog only. |

## 4. Specification

`V1__payments.sql` contains, in this order (FK dependency order): `account`, `payout` plus its two
indexes, `ledger_entry`, `rail_submission` plus its partial unique index, `rail_high_water`. Copy every
identifier, type, default, `REFERENCES` clause and **constraint name** verbatim from C1 `#ct1-paydb`.
No `GRANT`, no seed rows, no triggers, no views, no `IF NOT EXISTS`.

Constraints that must appear by name (the test enumerates them): `account_currency_ck`,
`account_balance_ck`, `account_fence_ck`, `payout_idem_uq`, `payout_amount_ck`, `payout_attempts_ck`,
`payout_state_ck`, `payout_pending_ck`, `payout_claimed_ck`, `ledger_direction_ck`,
`ledger_amount_ck`, `ledger_fence_ck`, `ledger_leg_uq`, `rail_outcome_ck`, `rail_token_ck`,
`rail_resolved_ck`, `rail_hwm_token_ck`; indexes `payout_pending_idx`, `payout_account_idx`,
`rail_submission_attempt_uidx`.

`PaydbMigrationIT` starts a PostgreSQL 16 container, runs Flyway against it, then asserts, in prose
terms: the `flyway_schema_history` row for version 1 is `success`; all five tables and all three
indexes exist (query `pg_indexes`); and for each constraint above, an `INSERT`/`UPDATE` that violates
it fails with an SQL state of class 23 and an error message naming that constraint. Cases needing
thought: a `PENDING` payout carrying `claimed_by` violates `payout_pending_ck`; a `POSTED` payout with
null `claimed_by` violates `payout_claimed_ck`; a second `('DEBIT', same payout, same account)` leg
violates `ledger_leg_uq`; a second non-`FENCED` `rail_submission` for one payout violates
`rail_submission_attempt_uidx` while a second row with `outcome='FENCED'` is **accepted**; an
`outcome` set with `resolved_at` still null violates `rail_resolved_ck`.

Assertions are described here in prose deliberately — the implementing session writes the test code.

## 5. Acceptance criteria

1. `V1__payments.sql` exists at the path C1 `#ct1-migrations` pins and creates exactly five tables.
2. Every one of the 17 constraint names and 3 index names listed in §4 appears in the file.
3. The file contains no occurrence of `FLOAT`, `DOUBLE`, `REAL` or `NUMERIC`, and no money column of
   any type other than `BIGINT`.
4. `PaydbMigrationIT` has one negative case per named constraint and each asserts on the constraint
   name, not merely "an exception was thrown".
5. The `FENCED`-is-exempt case for `rail_submission_attempt_uidx` is asserted positively.
6. Flyway `locations` in `application.yaml` point at the `paydb` tree only — no reference to the
   `lockdb` tree, and no shared history table.
7. `./gradlew :payment-resource:check` passes with Spotless clean.

## 6. Verification

- `./gradlew :payment-resource:test --tests '*PaydbMigrationIT'` → green.
- `grep -cE '^CREATE TABLE' payment-resource/src/main/resources/db/migration/paydb/V1__payments.sql` → `5`.
- `grep -nE 'FLOAT|DOUBLE|REAL|NUMERIC' …/V1__payments.sql` → no output.
- `grep -c '_ck\|_uq\|_uidx\|_idx' …/V1__payments.sql` → at least 20.
- `./gradlew :payment-resource:spotlessCheck` → up-to-date.

## 7. Out of scope

Repositories and the fenced `UPDATE` statements (T-021 — do not put the statements in this file as
comments either). The HTTP surface (T-022). The rail stub (T-023). The `lockdb` schema (M1, done).
Cloud SQL provisioning and any `GRANT`/IAM database user (M5, T-050s). Reconciliation queries (M4).

## 8. Hazards

- **Flyway checksums are immutable.** Once V1 has run anywhere shared, editing it breaks validation.
  Get it right now; a correction after M5 costs a V2.
- `rail_submission_attempt_uidx` uses `IS DISTINCT FROM 'FENCED'`, not `<> 'FENCED'` — with `<>` a NULL
  (unresolved) outcome escapes the index and INV-02 silently stops being enforced.
- `rail_resolved_ck` compares two boolean NULL-tests for equality; do not "simplify" it to
  `outcome IS NULL AND resolved_at IS NULL`.
- Do **not** add a `fence` column to `payout`; the payout is fenced via `claim_token` (C1 `#ct1-fenced`
  statement (c)). Do not add `fencing_token` as a synonym for `fence` (`docs/04-contracts.md#c-precedence`).
- `rail_high_water` lives in `paydb` but is written only by `rail-proxy` (C1 `#ct1-paydb`). It is
  created here; nothing in `payment-resource` may ever write it.

## 9. On completion

Mark the T-020 row done in `tasks/README.md`. Note any deviation from C1 as a candidate §4.5 contract
change-log entry — do not amend the contract yourself.
