# T-032 — EtcdLockStore renew, release, inspect

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M3 · **Estimate** 30 min

**Preconditions** — T-030 and T-031 done: grants happen through the `CreateRevision == 0` txn with the
token captured at grant time, and every grant key is attached to a per-session lease with a live
keepalive. `extend`, `deleteIfOwner`, `read`, `revoke`, `reapExpired` are still stubbed.

**Goal** — Complete the etcd `LockStore` by implementing `extend`, `deleteIfOwner`, `read`, `revoke` and
`reapExpired`, each as a compare-on-`ModRevision` transaction rather than an unconditional operation.

## 1. Why this task exists

A lock store's dangerous operations are the *destructive* ones. An unconditional `Delete` on release is
the classic bug: a process that stalled past its expiry, lost the lock, and then finished its work
deletes a key that now belongs to a **different** holder — handing the lock to a third party while the
second still believes it holds it (INV-06). Comparing on the grant's `ModRevision` makes that
inexpressible, and it is the same predicate `fence < :token` gives us in Postgres.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| `extend` returns the **same** token with a later expiry, or empty meaning **LOST NOW** — never empty for a blip | [C2 `#ct2-spi`](../docs/contracts/C2-java-api.md#ct2-spi) |
| `deleteIfOwner(String key, long fencingToken)` is compare-and-delete on **key + `fencingToken` and nothing else** — there is no `sessionId` or `ownerId` parameter to compare, because the token (here the cluster-wide revision) already identifies one grant for all time; `false` only when the grant no longer matches | [C2 `#ct2-spi`](../docs/contracts/C2-java-api.md#ct2-spi) |
| `revoke` deletes **and** advances the token floor atomically, appending a revocation record; `LockOutcome` fields | [C2 `#ct2-spi`](../docs/contracts/C2-java-api.md#ct2-spi), [`#ct2-records`](../docs/contracts/C2-java-api.md#ct2-records) |
| `read` is explicitly non-authoritative, may be a follower read | [C2 `#ct2-spi`](../docs/contracts/C2-java-api.md#ct2-spi) |
| Transient-vs-terminal mapping (`ContentionException`, `NotLeaderException`, `LockLostException`) | [C2 `#ct2-exceptions`](../docs/contracts/C2-java-api.md#ct2-exceptions) |
| `lock.renew`, `lock.release`, `lock.revoke` metric names and tag sets; `lock_released` / `lock_revoked` log events | [C4 `#ct4-metrics`](../docs/contracts/C4-observability.md#ct4-metrics), [`#ct4-logs`](../docs/contracts/C4-observability.md#ct4-logs) |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report.**

## 3. Deliverables

| Path | What |
|---|---|
| `lock-server/src/main/java/dev/lock/server/store/etcd/EtcdLockStore.java` (modify) | Remove all `UnsupportedOperationException` stubs; implement the five remaining methods |
| `lock-server/src/main/java/dev/lock/server/store/etcd/EtcdGrantCodec.java` | Encode/decode of the grant value (`ownerId`, `sessionId`, `serverExpiry`, `tokenFloor`) — one place, so `read` and the txns cannot disagree |
| `lock-server/src/test/java/dev/lock/server/store/etcd/EtcdRenewReleaseTest.java` | The CAS tests, incl. the stale-releaser scenario |
| `lock-server/src/test/java/dev/lock/server/store/etcd/EtcdRevokeTest.java` | Force-revoke and token-floor tests |

## 4. Specification

| Method | Shape required |
|---|---|
| `extend` | `Txn If(ModRevision(key) == grantToken) Then(Put(key, sameValueWithNewExpiry, withLease(sessionLease), withIgnoreLease?)) Else(Get(key))`. **The put bumps `ModRevision`**, so the store must track the *current* revision of the grant separately from the immutable `fencingToken` it returns. Return the original token unchanged. |
| `deleteIfOwner` | `Txn If(ModRevision(key) == currentGrantRevision) Then(Delete(key)) Else(noop)`. `false` when `succeeded == false`. **The revision comparison is the entire condition** — do not add a decoded `sessionId` or `ownerId` conjunct: the signature supplies neither, and the revision already identifies one grant (mirrors the pg predicate `(lock_key, fencing_token)`, C1 `#ct1-renew`). |
| `read` | Plain `Get`, `withSerializable()` permitted (follower read); maps to `LockInfo` with `waiterCount` from the T-033 waiter registry if present, else 0. |
| `revoke` | `Txn` that deletes the key **and** puts a tombstone/floor record under a sibling `/dlock/<ns>/floor/<key>` key in the same transaction, carrying the highest token seen. `tryInsert` must consult that floor and never grant a token at or below it. |
| `reapExpired` | Returns 0 and is a documented no-op: leases own expiry. Do not emulate a reaper. |

**Revision bookkeeping — the subtle part.** After T-030 the invariant is: `fencingToken` = revision at
*grant*; `currentRevision` = revision after the most recent successful `extend`. Both are per-grant
in-JVM state. Comparisons in `extend`/`deleteIfOwner` use `currentRevision`; every value handed to a
caller or a resource uses `fencingToken`. Mixing them produces either a renew that always fails after
the first one, or a release predicate that matches a grant it should not.

**The stale-releaser scenario to encode as a test.** Session A grants; A's keepalive is stopped so the
lease expires and the key vanishes; session B acquires and gets a higher token; A then calls
`deleteIfOwner` with its own (now stale) revision. Required outcome: `false`, B's key still present, B's
token unchanged. This is the whole justification for the compare-and-delete.

**Token floor and etcd's revision counter.** etcd revisions are cluster-monotonic, so a `revoke` cannot
"advance a sequence" the way pg does; the floor key records the last token so that a *subsequent* grant's
revision, which is necessarily larger, is validated rather than assumed. If a grant's revision ever
compares below the floor, that is INV-04 violated — throw and log at incident grade rather than
returning a handle.

**Failure mapping.** `succeeded == false` ⇒ the documented empty/`false` result. gRPC
`UNAVAILABLE`/`DEADLINE_EXCEEDED` ⇒ `ContentionException`; no-leader ⇒ `NotLeaderException`. Never map a
transport failure to LOST.

## 5. Acceptance criteria

1. No `UnsupportedOperationException` remains in `EtcdLockStore` (grep).
2. Renew test: three consecutive `extend` calls succeed and all three return the *same* `fencingToken`
   as the original grant.
3. Renew-after-loss test: kill the lease out of band, then `extend` returns empty (not an exception).
4. Stale-releaser test exactly as §4 describes; asserts `false` **and** that B's key and token survive.
5. Wrong-token release test: a *different* session's live grant, released with the caller's own stale
   revision ⇒ `false`, key still present. There is no "correct revision, wrong session" case to test —
   the revision *is* the session's grant, which is why the predicate needs nothing else.
6. Revoke test: `LockOutcome.revoked == true`, `previousToken` matches the grant, `newTokenFloor` >
   `previousToken`, and the next `tryInsert` returns a token strictly greater than `newTokenFloor`.
7. `reapExpired` returns 0 and its no-op rationale is a class comment citing the lease.
8. Transport-failure mapping covered by at least one test (stop the container or use an unroutable
   endpoint) asserting `ContentionException`, **not** empty.

## 6. Verification

```
./gradlew :lock-server:spotlessCheck :lock-server:test --tests '*Etcd*Test*'
grep -rn "UnsupportedOperationException" lock-server/src/main/java/dev/lock/server/store/etcd
grep -rn "delete(" lock-server/src/main/java/dev/lock/server/store/etcd | grep -v "Txn\|txn"
```
Expected: tests green; both greps print nothing (any bare delete outside a txn is the bug this task
exists to prevent).

## 7. Out of scope

Watch/`awaitRelease` and `waiterCount` population (**T-033**); the parity matrix (**T-034**); HTTP
surface changes (already M1); anything touching `paydb` or the rail high-water mark (M2, already done).

## 8. Hazards

C2 `#ct2-spi` warns that conflating LOST-NOW with transient failure is "the most common bug in
home-grown lock clients" — criterion 8 is the guard. Second trap: `Put` inside `extend` *changes*
`ModRevision`, so a naive implementation that compares against `fencingToken` forever works once and
then always fails; keep `currentRevision` separate. Third: `withLease` omitted on the renew `Put`
detaches the key from the session lease, which quietly destroys the T-031 cascade — assert attachment
after a renew if time allows.

## 9. On completion

Mark T-032 done in `tasks/README.md`; note the floor-key design (`/dlock/<ns>/floor/<key>`) so T-034 and
the §7.9 comparison table can describe the divergence from `fencing_token_seq`.
