# T-011 — PostgresLockStore.tryAcquire

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M1 (Postgres lock backend) · **Estimate** 30 min

**Preconditions** — T-010 done: the `lockdb` migrations apply against a PostgreSQL 16 Testcontainer
and `lock-server` has JDBC, Flyway and Testcontainers wired. From M0 you also inherit `lock-api` with
`LockHandle`, `LockInfo`, `LockOutcome` and the exception hierarchy (C2 `#ct2-records`,
`#ct2-exceptions`). If `LockStore` / `SessionRegistry` are not yet on disk, declare them in this task
**exactly** as C2 `#ct2-spi` pins them — signatures only, no additions.

**Goal** — Implement `tryInsert` in `dev.lock.server.store.pg.PostgresLockStore` as the single atomic
`INSERT … ON CONFLICT` statement pinned in C1 `#ct1-acquire`, and prove takeover bumps the token.

## 1. Why this task exists

The entire M1 safety argument reduces to one SQL statement. It must grant in one round trip with no
read-then-write window, must take over only genuinely expired leases, must be re-entrant for the same
owner+session, and must mint a *strictly greater* token on every grant including takeovers. Anything
implemented above this statement — retries, `SELECT … FOR UPDATE`, an application-side token counter —
reintroduces the race the statement exists to remove.

## 2. Contracts to obey

| What | Pinned at |
|---|---|
| The acquire statement, verbatim, plus the rows→outcome table | `docs/contracts/C1-database-schemas.md#ct1-acquire` |
| One global sequence is the token source; gaps are legal, decreases are not | C1 `#ct1-seq` |
| `LockStore` signatures, `tryInsert` semantics | C2 `#ct2-spi` |
| `LockHandle` / `LockInfo` / `LockOutcome` shape. `tryInsert` returns **`Optional<LockHandle>`**; `LockOutcome` is the `forceRevoke` result record only and appears nowhere on the acquire path | C2 `#ct2-records` |
| `lock.backend=pg` selects this store at startup; `lock.default.ttl` = 30s | C5 `#ct5-config` |
| Package `dev.lock.server.store.pg`; Lombok limited to `@RequiredArgsConstructor`, `@Slf4j` | C5 `#ct5-modules`, `#ct5-catalog` |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report.** In
particular, do not "fix" the statement's `WHERE` clause; report a suspected defect instead.

## 3. Deliverables

| Path | What |
|---|---|
| `lock-api/src/main/java/dev/lock/api/LockStore.java` | The SPI interface, if not already present (zero third-party imports — C2 `#ct2-zero-dep`) |
| `lock-server/src/main/java/dev/lock/server/store/pg/LockSql.java` | Package-private constants holder; `ACQUIRE` copied character-for-character from C1 `#ct1-acquire` |
| `lock-server/src/main/java/dev/lock/server/store/pg/PostgresLockStore.java` | `LockStore` implementation; `tryInsert` and `backendId()` real, the rest throwing `UnsupportedOperationException` with a `// T-012` / `// T-016` marker |
| `lock-server/src/main/java/dev/lock/server/store/pg/PgStoreConfiguration.java` | Bean wiring, conditional on `lock.backend=pg` |
| `lock-server/src/test/java/dev/lock/server/store/pg/PostgresLockStoreAcquireIT.java` | Testcontainers integration test, six cases below |
| `tasks/README.md` | Ledger row for T-011 marked done |

## 4. Specification

**`LockSql.ACQUIRE`** — one Java text block holding the contract statement unchanged, named
parameters `:key`, `:owner_id`, `:session_id`, `:ttl_seconds` in the contract's spelling. Both
`nextval('fencing_token_seq')` occurrences must be present: the one in `VALUES` and the one in the
`DO UPDATE … SET`. Add a comment above the constant pointing at C1 `#ct1-acquire` Warning 1.

**`tryInsert(key, ownerId, sessionId, ttl)`** — executes `ACQUIRE` once, in a single statement with no
surrounding explicit transaction beyond the implicit one, binding `ttl` as whole seconds (reject a TTL
below one second or above the configured maximum with `IllegalArgumentException`). Map the result by
C1's table: **one row → `Optional.of(LockHandle …)`** built from the `RETURNING` columns; **zero rows →
`Optional.empty()`**, meaning contended, not an error. No loop, no retry, no second query to discover
who holds it. Timestamps come from the `RETURNING` values — never from `Instant.now()` in Java, because
the lease clock is the database clock (C1 `#ct1-acquire` Warning 2). `backendId()` returns `"pg"`.

**Wiring** — `PgStoreConfiguration` exposes the store on `lock.backend=pg`, using the `lock-server`
primary `DataSource`. Named-parameter binding via Spring's JDBC support is fine; an ORM is not.

**`PostgresLockStoreAcquireIT`** — one container and one Flyway run for the class; each test inserts
the `lock_session` rows it needs. Cases, asserted in prose: (1) acquiring a free key returns a handle
whose token is > 0 and whose `expires_at` is later than `acquired_at`; (2) a second, different
session on the held key returns empty and leaves the stored token unchanged; (3) the same
owner+session re-acquiring returns a handle with a **strictly greater** token and an extended expiry;
(4) after the row's `expires_at` has passed, a different session takes it over and receives a token
strictly greater than the dead holder's — this is the negative control for C1 Warning 1 and is
mandatory; (5) tokens across all grants in the class are strictly increasing when read in insertion
order, with gaps tolerated; (6) two threads racing the same free key produce exactly one non-empty
result. Force expiry by acquiring with a one-second TTL and waiting past it, or by updating
`expires_at` directly in the fixture — never by mutating `fencing_token`.

## 5. Acceptance criteria

1. `grep -c "nextval('fencing_token_seq')" lock-server/src/main/java/dev/lock/server/store/pg/LockSql.java` returns 2.
2. `grep -rn 'FOR UPDATE\|Instant.now()' lock-server/src/main/java/dev/lock/server/store/pg/` returns nothing.
3. `tryInsert` contains no retry loop and issues exactly one JDBC statement per call.
4. All six IT cases exist as separate `@Test` methods and pass; case (4) is named so a reader can find it (e.g. `takeoverOfExpiredLeaseMintsStrictlyGreaterToken`).
5. Deleting the `nextval` from the `DO UPDATE … SET` list makes case (4) fail — verify by temporary local edit, then revert.
6. `LockStore` in `lock-api` imports nothing outside `java.*`.
7. `./gradlew :lock-server:spotlessCheck build` is green.

## 6. Verification

```
./gradlew :lock-server:test --tests '*PostgresLockStoreAcquireIT'
./gradlew :lock-api:dependencies --configuration runtimeClasspath   # expect no third-party entries
./gradlew :lock-server:spotlessCheck
```

Expected: all six tests green; step 5 above shows exactly one failing test before revert.

## 7. Out of scope

`extend`, `deleteIfOwner`, `read` (T-012); sessions and heartbeat (T-013); `revoke` and `reapExpired`
(later M1 tasks); the HTTP surface and `lock.acquire` metrics/log events (later M1 / M6 — C3
`#ct3-lock`, C4 `#ct4-metrics`); the etcd store (M3); fencing enforcement (M2).

## 8. Hazards

The signature silent bug: with the `DO UPDATE` `nextval` missing, the lock still excludes, acquire
still returns a handle, and every test except the takeover case passes (C1 `#ct1-acquire` Warning 1).
Second trap: treating zero rows as an exception — empty means contended and is a normal outcome, while
a SQLState-40001 style failure is a `ContentionException` (that mapping is T-012's; here, let the
`SQLException` propagate rather than inventing a translation). Third: computing expiry in Java, which
imports client clock skew into the lease.

## 9. On completion

Mark the T-011 row done in `tasks/README.md`. Note whether `LockStore` already existed or was declared
here, and record the result of the criterion-5 mutation check.
