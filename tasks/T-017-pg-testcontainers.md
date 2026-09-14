# T-017 — Testcontainers matrix for the Postgres backend

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M1 — Postgres lock backend · **Estimate** 30 minutes (tight; if the shared container
fixture and Flyway wiring take longer than 10 minutes, land the fixture plus scenarios 1–3 and note
4–6 as the immediate follow-up in the ledger)

**Preconditions** — T-010…T-013 (lockdb migrations, `PostgresLockStore`, pg `SessionRegistry`, core
`LockService`), T-014 (`reapExpired`), T-015 (`revoke`), T-016 (web layer; not exercised here). You
inherit a Postgres backend whose behaviour has only been asserted against mocks and hand-run `psql`.

**Goal** — Prove the six load-bearing store behaviours against a real PostgreSQL 16 container, so M1 can
be declared done on evidence rather than on inspection.

## 1. Why this task exists

Every interesting property of this backend lives in SQL semantics no mock reproduces: the unique
constraint that *is* mutual exclusion, `now()` evaluated server-side, the steal-if-expired predicate, and
compare-and-delete on `(lock_key, fencing_token)` and nothing else. Mocking `LockStore` proves only that the Java
compiles. This matrix is also the reference the etcd backend must match verbatim in T-030s — same
scenarios, same assertions, different store — which is what keeps both backends first-class rather than
one being the real one and the other a demo.

## 2. Contracts to obey

| What | Pinned at |
|---|---|
| Acquire = insert-or-steal-if-expired; the exact predicate and its clock | `docs/contracts/C1-database-schemas.md#ct1-acquire` |
| Renew/release statements; the "lost now" vs "transient" table | `docs/contracts/C1-database-schemas.md#ct1-renew` |
| `lock_entry` / `lock_session` / `lock_revocation` DDL, cascade, indexes | `docs/contracts/C1-database-schemas.md#ct1-lockdb` |
| Token strictly greater than any ever issued for the key, incl. revoked | `docs/contracts/C1-database-schemas.md#ct1-seq` |
| Flyway migration filenames and the two separate trees | `docs/contracts/C1-database-schemas.md#ct1-migrations` |
| SPI semantics: `extend` empty means LOST NOW, never a blip; `reapExpired` idempotent | `docs/contracts/C2-java-api.md#ct2-spi` |
| Exception semantics — `ContentionException` vs `LockLostException` | `docs/contracts/C2-java-api.md#ct2-exceptions` |
| PostgreSQL 16, Testcontainers, JUnit 5, awaitility versions from the catalog only | `docs/contracts/C5-config-build-and-naming.md#ct5-catalog` |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report, quoting both**
(`docs/04-contracts.md#c-precedence`). Notably scenario 4: assert the re-acquire behaviour **C1 §1.3
actually pins**; if C1 is silent on whether a same-session re-acquire returns the same token or a new
one, stop and request an amendment rather than encoding your guess as a test.

## 3. Deliverables

| Path | What |
|---|---|
| `lock-server/src/test/java/dev/lock/server/store/pg/PgTestSupport.java` | one shared, reused `PostgreSQLContainer` (PostgreSQL 16) + Flyway migrate of the lockdb tree + per-test truncation |
| `lock-server/src/test/java/dev/lock/server/store/pg/PostgresLockStoreIT.java` | scenarios 1–4 and 6 |
| `lock-server/src/test/java/dev/lock/server/store/pg/PgReleaseAfterLossIT.java` | scenario 5 |
| `lock-server/build.gradle.kts` | modify: Testcontainers/awaitility test deps from the catalog; an `integrationTest`-tagged JUnit include so `test` stays fast |
| `lock-server/src/test/resources/junit-platform.properties` | tag configuration; container reuse hint |

## 4. Specification

**Fixture.** One container per JVM, started once (static/singleton or `@Testcontainers` with reuse), never
one per test class. Migrations run through **Flyway against the real lockdb tree** — not a hand-written
DDL string, because a test schema that drifts from `V*.sql` hides the migration bug it exists to catch.
Isolation is by truncating `lock_session` and `lock_revocation` between tests (the cascade removes
`lock_entry`); do not restart the container. Time is controlled by short TTLs plus awaitility polling —
**never `Thread.sleep` on a wall-clock guess, and never a clock stubbed in Java**, since the predicate is
evaluated by the server's `now()`.

**The six scenarios.**

| # | Scenario | The assertion in prose |
|---|---|---|
| 1 | Uncontended acquire | `tryInsert` on a free key returns a handle; exactly one `lock_entry` row exists; the token is positive; `read` reports held with the same token and owner. |
| 2 | Contended acquire | With a live grant held by session A, `tryInsert` by session B returns **empty**, the row still names A with A's token, and the row count is still one. The unique constraint, not application logic, produced this. |
| 3 | Takeover of an expired lock | A holds a grant with a very short TTL. Await expiry. B's `tryInsert` **succeeds without any sweeper having run**, and B's token is **strictly greater** than A's. Then assert A's `extend` returns empty and A's `deleteIfOwner` returns false — the old holder can neither renew nor release the new holder's grant. |
| 4 | Idempotent re-acquire | The same session re-acquires the key it already holds. Assert exactly the behaviour C1 §1.3 pins (see §2), the row count stays one, and no token is skipped in a way that violates monotonicity. Whatever the pinned answer, the observable rule is: no second row, no lost mutual exclusion. |
| 5 | Release after loss | A's grant is taken over (reuse scenario 3's setup), then A calls `deleteIfOwner` with its stale token: returns **false**, and B's row is **untouched** — same token, same owner, same expiry. This is the compare-and-delete guarantee; an unconditional release would free B's lock. |
| 6 | Renew after expiry | A's lease expires and nobody takes over. A's `extend` returns **empty**, meaning LOST NOW, and must not be reported as retryable. Assert the empty result and that a `ContentionException` is *not* thrown — the whole point of `#ct2-spi`'s `extend` row is that these two are never conflated. |

Additionally: one assertion that `reapExpired` run twice over the same expired row returns a positive
count then `0` (idempotence, `#ct2-spi`), and one that a revoked key's next token exceeds the revoked
token (closing T-015 with container evidence).

**Naming and speed.** Test method names read as the behaviour (`expiredLeaseIsStolenWithHigherToken`),
not `test3`. Total suite target under 60 seconds on a warm container; if a scenario needs more than a
2-second TTL to be deterministic, the design is wrong, not the timeout.

## 5. Acceptance criteria

1. `PgTestSupport` starts exactly one PostgreSQL 16 container per JVM run and applies the production Flyway lockdb migrations; no test contains inline `CREATE TABLE`.
2. All six scenarios exist as named tests, plus the `reapExpired` idempotence and post-revocation token tests.
3. Scenario 3 passes with the sweeper disabled or absent — takeover does not depend on `reapExpired`.
4. Scenario 5 asserts B's row is byte-for-byte unchanged (token, owner, expiry) after A's release attempt.
5. Scenario 6 asserts an empty `extend` result and asserts no exception is thrown.
6. No `Thread.sleep` in the suite (`grep -R "Thread.sleep" lock-server/src/test` returns nothing); waiting uses awaitility.
7. Versions come only from the version catalog; no literal version string appears in `lock-server/build.gradle.kts`.
8. The suite is tagged so `./gradlew :lock-server:test` and the tagged integration run are separately invocable, and both are green.

## 6. Verification

- `docker info` succeeds (Testcontainers prerequisite).
- `./gradlew :lock-server:test` — unit slices only, green, no container started (visible in the log).
- `./gradlew :lock-server:integrationTest` (or the equivalent tagged task this task defines) — green; the log shows one container start and the Flyway `V1__`/`V2__` migrations applied.
- `./gradlew :lock-server:integrationTest --tests '*PostgresLockStoreIT*' -i` — the six scenario names appear individually.
- Time the run: `time ./gradlew :lock-server:integrationTest` → under 60 s warm.
- Re-run immediately; identical result (no cross-test leakage from surviving rows).

## 7. Out of scope

HTTP-level tests of L1–L8 (T-016 covers the web slice; end-to-end lives in the harness at M4), the etcd
mirror of this matrix (T-030s — but keep these tests structured so the mirror can share scenario names),
multi-JVM/multi-replica concurrency and the fencing experiment (T-042), chaos and eviction testing (M6),
and performance measurement (M7).

## 8. Hazards

- **Testing against a hand-rolled schema** rather than Flyway is the trap that makes a migration defect
  invisible until deploy (`#ct1-migrations` pins two separate trees — use the lockdb one, not paydb).
- **Sleep-based expiry tests** are flaky on CI and hide the fact that expiry is decided by the server's
  clock (`#ct1-acquire`, and C1's "one `now()` clock" note).
- **Conflating empty `extend` with a transient failure** is called out in `#ct2-spi` as the most common
  bug in home-grown lock clients; scenario 6 exists specifically to pin the distinction.
- A test that asserts a *specific* token value rather than ordering will break the moment the global
  sequence is shared — assert `>`, never `==` on tokens across grants.
- Do **not** run `git` (ADR-011).

## 9. On completion

Mark T-017 done in `tasks/README.md` and declare M1 complete or name what remains. Record the C1 §1.3
reading used for scenario 4, the warm suite runtime, and any scenario deferred.
