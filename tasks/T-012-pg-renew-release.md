# T-012 — PostgresLockStore renew, release, inspect

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M1 (Postgres lock backend) · **Estimate** 30 min

**Preconditions** — T-011 done: `PostgresLockStore` exists with `tryInsert` and `backendId()`
implemented against `LockSql.ACQUIRE`, wired on `lock.backend=pg`, with a passing acquire IT including
the takeover-token negative control. `extend`, `deleteIfOwner` and `read` still throw
`UnsupportedOperationException`.

**Goal** — Implement `extend`, `deleteIfOwner` and `read` from the statements pinned in C1
`#ct1-renew`, with an exception-translation layer that never lets a transient fault masquerade as a
lost lock.

## 1. Why this task exists

`extend` returning empty is the strongest statement the system can make: *you do not hold this lock,
now*. If a pool timeout, a serialization failure or a dropped connection is folded into that same empty
result, the caller aborts a healthy critical section (annoying) — and worse, the inverse mistake, a
retry loop that treats a genuinely lost lease as transient, puts two holders inside the critical
section. Release compares the key **and the token** for the mirror-image reason: a fenced-out worker
calling `close()` must not be able to hand the lock to a third party.

## 2. Contracts to obey

| What | Pinned at |
|---|---|
| The renew, release and heartbeat statements, verbatim | `docs/contracts/C1-database-schemas.md#ct1-renew` |
| Zero-rows → caller obligations table (renew vs release) | C1 `#ct1-renew` |
| Why release compares `lock_key` **and** `fencing_token` — **and nothing else**; `owner_id` and `session_id` are deliberately excluded because one global sequence makes the token unique per grant, so widening buys no safety and adds bind parameters the pinned Java signatures cannot supply | C1 `#ct1-renew` closing paragraph |
| `extend` / `deleteIfOwner` / `read` semantics; empty means LOST NOW | C2 `#ct2-spi` |
| `LockLostException` vs `ContentionException` — retryable vs terminal | C2 `#ct2-exceptions` |
| `read` is non-authoritative, may be a follower read | C2 `#ct2-spi` |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report.** The SPI and
the SQL line up exactly and take **no owner parameter**: `extend(String key, long fencingToken,
Duration ttl)` binds `:key`, `:token`, `:ttl_seconds`, and `deleteIfOwner(String key, long
fencingToken)` binds `:key`, `:token` (C2 `#ct2-spi`, C1 `#ct1-renew`). If you find yourself needing an
owner or session value to satisfy a statement, you have the wrong statement — re-copy it from C1 rather
than adding a parameter.

## 3. Deliverables

| Path | What |
|---|---|
| `lock-server/src/main/java/dev/lock/server/store/pg/LockSql.java` | Add `RENEW`, `RELEASE`, `READ` constants; renew/release copied verbatim from C1 `#ct1-renew` |
| `lock-server/src/main/java/dev/lock/server/store/pg/PostgresLockStore.java` | Implement `extend`, `deleteIfOwner`, `read`; only `revoke` and `reapExpired` may remain unimplemented |
| `lock-server/src/main/java/dev/lock/server/store/pg/PgExceptionTranslator.java` | SQLState → `lock-api` exception mapping, table in §4 |
| `lock-server/src/test/java/dev/lock/server/store/pg/PostgresLockStoreRenewReleaseIT.java` | Integration test, cases below |
| `lock-server/src/test/java/dev/lock/server/store/pg/PgExceptionTranslatorTest.java` | Pure unit test over synthesised `SQLException`s |
| `tasks/README.md` | Ledger row for T-012 marked done |

## 4. Specification

**`extend`** — one execution of `RENEW`. One row → `Optional.of` a handle carrying the *same* token and
the new `expires_at` from `RETURNING`. Zero rows → `Optional.empty()`, which the caller (the web layer,
a later task) turns into `LockLostException`. The store itself does not throw on empty. The predicate
keeps all three conditions — `lock_key`, `fencing_token`, and `expires_at > now()`; renewing a lapsed
row is forbidden even by one millisecond, because the row may already have been taken over.

**`deleteIfOwner`** — one execution of `RELEASE`; `true` when one row was deleted, `false` otherwise.
`false` is a success path for the caller, not an error, and must never trigger a retry or a widened
predicate. Do not add `expires_at` to the release predicate: releasing a lease that expired one
millisecond ago is still our own row and deleting it is correct.

**`read`** — a single-key point-in-time `SELECT` returning `Optional<LockInfo>` with the columns C2
`#ct2-records` pins; expired-but-unreaped rows are still returned as data (the reaper is a liveness
mechanism, not the source of truth). Javadoc must state it is non-authoritative and must not be used
to decide whether to proceed.

**`PgExceptionTranslator`** — a static function from `SQLException` to a `lock-api` exception:

| Condition | Maps to | Rationale |
|---|---|---|
| SQLState `40001` serialization failure, `40P01` deadlock | `ContentionException` (retryable) | transient, the lock may still be ours |
| SQLState class `08` connection failures, `57014` query cancelled, JDBC/pool timeout | `ContentionException` | outcome unknown, never "lost" |
| Constraint violation `23503` / `23514` (bad session FK, failed CHECK) | programming error — rethrow unchanged, do not wrap | a bug, not contention |
| Anything else | rethrow unchanged | silent swallowing is worse than a stack trace |

No path in this class may produce `LockLostException`; only a zero-row result may mean lost.

**Tests** — `PostgresLockStoreRenewReleaseIT`: renew of a live lock extends expiry and preserves the
token; renew with the wrong token returns empty; renew
after the lease lapsed returns empty **even when no other holder exists**; renew after another session
took the key over returns empty (the takeover minted a new token, which is exactly why no session
column is needed to detect it); release with the matching `(key, token)` pair returns true and the row is gone;
release with a stale token returns false and the current holder's row is untouched (assert its token
and `expires_at` are unchanged); release of an unknown key returns false; `read` of a held key returns
the current token and of an unknown key returns empty. `PgExceptionTranslatorTest`: one case per table
row, asserting type, and asserting no input yields `LockLostException`.

## 5. Acceptance criteria

1. `RENEW` and `RELEASE` in `LockSql` are textually identical to C1 `#ct1-renew` (parameter names included).
2. `grep -rn 'LockLostException' lock-server/src/main/java/dev/lock/server/store/pg/` returns no hit in `PgExceptionTranslator`.
3. No retry loop, no `while`, no backoff anywhere in `PostgresLockStore`.
4. The stale-release test asserts the surviving row's token **and** expiry are unchanged.
5. A test proves renew fails on a lapsed lease with no competing holder present.
6. Only `revoke` and `reapExpired` remain `UnsupportedOperationException`; a grep confirms the count is 2.
7. `./gradlew :lock-server:build spotlessCheck` is green.

## 6. Verification

```
./gradlew :lock-server:test --tests '*PostgresLockStoreRenewReleaseIT' --tests '*PgExceptionTranslatorTest'
grep -c 'UnsupportedOperationException' lock-server/src/main/java/dev/lock/server/store/pg/PostgresLockStore.java   # expect 2
./gradlew :lock-server:spotlessCheck
```

Expected: both test classes green; the grep prints 2.

## 7. Out of scope

Session lifecycle and the heartbeat statement — T-013 owns it even though it sits in C1 `#ct1-renew`.
`revoke` and the `lock_revocation` insert, and `reapExpired`: later M1 tasks. Turning empty into
`LockLostException` on the wire, and the `lock.session.lost` metric / `lease_expired` log event (C4
`#ct4-metrics`, `#ct4-logs`): the web-layer task and M6.

## 8. Hazards

The named trap is conflation: C2 `#ct2-spi` calls it "the most common bug in home-grown lock clients".
A driver-level `SQLException` caught and turned into `Optional.empty()` is exactly that bug and it will
pass every happy-path test. Second trap: adding `expires_at > now()` to release, which strands rows
whose lease lapsed a moment before `close()`. Third: **widening either predicate back out to
`owner_id` or `session_id`.** The token alone *is* the predicate — it comes from a single global
sequence (C1 `#ct1-seq`), so one value identifies one grant for all time and the owner and session are
implied by the row. Adding a column buys no safety, and it cannot even be built: `extend(String key,
long fencingToken, Duration ttl)` and `deleteIfOwner(String key, long fencingToken)` have no parameter
to bind it from, so the "fix" ends as an invented signature that breaks C2 `#ct2-spi` and the etcd
backend that must match it. Despite its name, `deleteIfOwner` proves ownership by the token.

## 9. On completion

Mark the T-012 row done in `tasks/README.md`. Record any SQLState you encountered that the §4 table
does not classify.
