# T-033 — Watch-based awaitRelease

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M3 · **Estimate** 30 min

**Preconditions** — T-030…T-032 done: the etcd `LockStore` is complete, grants carry a token captured at
grant time, and `tryInsert` already returns the observed response-header revision on an
etcd-package-internal type. `core`'s `acquire(key, maxWait)` currently satisfies its wait via bounded
jittered backoff for both backends.

**Goal** — Let the etcd backend replace poll-and-backoff with a watch that starts from the revision
observed by the *failed* acquire plus one, so a release that happens in the gap cannot be missed.

## 1. Why this task exists

Polling burns round trips and adds latency equal to half the backoff interval on every handoff — the
number the M7 benchmark will report. But the naive fix is worse than polling: if you watch from "now"
after a failed acquire, the delete that happened microseconds earlier is already in the past and the
waiter sleeps until `maxWait`. etcd's revision-addressed watch is precisely the tool that closes that
gap, and demonstrating it is one of the few places this project can show a consensus store doing something a
relational lock table cannot.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| `acquire(key, maxWait)` is the only method allowed to block, is bounded by `maxWait`, retries internally with jittered backoff, and is interruptible (interrupt must release any recorded grant) | [C2 `#ct2-lockservice`](../docs/contracts/C2-java-api.md#ct2-lockservice), [`#ct2-threading`](../docs/contracts/C2-java-api.md#ct2-threading) |
| The `LockStore` SPI method set is **closed** — `awaitRelease` is *not* on it, so it may not be added to `lock-api` | [C2 `#ct2-spi`](../docs/contracts/C2-java-api.md#ct2-spi), [C2 `#ct2-zero-dep`](../docs/contracts/C2-java-api.md#ct2-zero-dep) |
| `LockInfo.waiterCount` is the only place a waiter count is exposed, and it is advisory | [C2 `#ct2-records`](../docs/contracts/C2-java-api.md#ct2-records) |
| Wait/contention metric names and tag sets; no per-key or per-owner tags | [C4 `#ct4-metrics`](../docs/contracts/C4-observability.md#ct4-metrics), [`#ct4-cardinality`](../docs/contracts/C4-observability.md#ct4-cardinality) |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report.** Concretely:
adding `awaitRelease` to `dev.lock.api.LockStore` would be a C2 change requiring a §4.5 changelog row —
do not do it as part of this task.

## 3. Deliverables

| Path | What |
|---|---|
| `lock-server/src/main/java/dev/lock/server/store/ReleaseWaiter.java` | **Server-internal** optional capability interface (package `dev.lock.server.store`, *not* `lock-api`): one method that waits for a key to become free, given the revision observed by the failed attempt and a deadline, returning whether a release was observed |
| `lock-server/src/main/java/dev/lock/server/store/etcd/EtcdReleaseWaiter.java` | The jetcd `Watch` implementation, plus the per-key waiter counter that feeds `LockInfo.waiterCount` |
| `lock-server/src/main/java/dev/lock/server/store/etcd/EtcdLockStore.java` (modify) | Implements `ReleaseWaiter` by delegation; `read` now reports the live waiter count |
| `lock-server/src/main/java/dev/lock/server/core/...` (modify — the existing acquire/wait loop) | Uses the capability when the wired store implements it, otherwise keeps the existing backoff loop unchanged |
| `lock-server/src/test/java/dev/lock/server/store/etcd/EtcdWatchWaitTest.java` | Tests per §4, including the deliberate-gap race |

## 4. Specification

**The capability seam.** `core` must not know the word "etcd". Detect the capability once at wiring time
(constructor-injected `Optional<ReleaseWaiter>` or an `instanceof` check performed when the store bean is
resolved, not on every acquire) and branch there. The pg store gains nothing and implements nothing; its
behaviour must be byte-for-byte what T-017 already validated.

**The revision arithmetic — the point of the task.** A failed `tryInsert` returns the etcd response
header revision `R` it observed. The watch must be created with `withRevision(R + 1)`. etcd replays every
event with revision > `R`, so a delete that landed between the failed txn and the watch registration is
delivered on registration rather than lost. Starting from `R`, from 0, or from "no revision" is each
wrong in a different way: `R` re-delivers the event that caused the failure, 0 replays history from the
beginning of the key's life (and may be compacted away), and no-revision starts at *current* and drops
the gap event — which is exactly the race being closed, and which passes any test whose release is
delayed by a sleep.

**What ends the wait.** Return as soon as any of: a `DELETE` event for the key; an `EXPIRE`-equivalent
delete from lease revocation; the deadline; or thread interruption. A `PUT` for the key (a renew by the
current holder) is **not** a release and must not wake the waiter into a spin.

**After the wait.** The waiter returns a hint, never a grant. `core` re-runs the normal single-shot
acquire and may lose the race to another waiter — that is correct and expected under a thundering herd.
The overall `maxWait` bound and the jittered-backoff fallback stay in force: if the watch errors, is
cancelled, or hits a compacted revision (`ErrCompacted`), fall back to the polling path for the remainder
of the budget and count it.

**Resource discipline.** One watch per waiting call, closed in a `finally`; no watch outlives its
deadline. Never open a watch per key and cache it — the key space is unbounded and a leaked watcher on a
shared jetcd client eventually stalls the client's stream. Keep a per-key waiter count (increment on
entry, decrement in `finally`) purely so `read` can populate `LockInfo.waiterCount`; it is advisory.

**Telemetry.** Record wait duration and the outcome (`released`, `deadline`, `interrupted`, `fallback`)
using the C4-pinned metric with a closed tag set. Do not tag by key.

## 5. Acceptance criteria

1. `dev.lock.api` contains no `awaitRelease`/`ReleaseWaiter` reference: `grep -rn "ReleaseWaiter"
   lock-api/src` prints nothing.
2. Race test — the load-bearing one: hold the lock, capture the failed acquire's observed revision,
   **delete the key before the watch is created** (order the test so the delete provably precedes watch
   registration), then assert the wait returns "released" well inside the deadline. This test must fail
   if `withRevision(R + 1)` is replaced by a current-revision watch; state that in a comment.
3. Happy-path test: waiter blocked, holder releases after a delay, waiter returns within a small
   multiple of the release delay — and strictly faster than the configured backoff floor would allow.
4. Renew-does-not-wake test: holder renews twice while a waiter waits; assert the waiter is still waiting
   (no early return, no busy loop) then release and assert it returns.
5. Deadline test: nobody releases; the wait returns "not released" at the deadline, and total elapsed
   time is within `maxWait` plus a small tolerance.
6. Interruption test: interrupting the waiting thread returns/propagates promptly and leaves no grant
   behind (C2 `#ct2-threading`).
7. Fallback test: simulate a compacted or errored watch and assert the acquire still succeeds via the
   polling path within `maxWait`, with the `fallback` outcome recorded.
8. `LockInfo.waiterCount` is > 0 while a waiter waits and returns to 0 afterwards.
9. Every existing pg test from T-017 still passes untouched.

## 6. Verification

```
./gradlew :lock-server:spotlessCheck :lock-server:test
grep -rn "ReleaseWaiter\|Watch" lock-api/src ; grep -rn "etcd" lock-server/src/main/java/dev/lock/server/core
```
Expected: full `lock-server` suite green (etcd *and* pg); both greps print nothing.

## 7. Out of scope

Running the T-017 matrix against both stores and writing the divergence table (**T-034**); client-side
SDK waiting or the SDK deadline (M4); benchmark numbers for watch-vs-poll handoff latency (**M7**, which
will cite this task); etcd cluster sizing (**M5**).

## 8. Hazards

The trap is a green test suite that proves nothing: any test that sleeps before releasing will pass with
a current-revision watch, so criterion 2 must order the delete *before* registration. Second trap:
leaking watchers — jetcd watchers are not garbage-collected on scope exit; an unclosed watcher per failed
acquire is a slow-motion outage. Third: putting the capability check inside the acquire hot path, or
worse, importing an etcd type into `dev.lock.server.core`, which breaks the SPI seam C2 `#ct2-spi` exists
to protect.

## 9. On completion

Mark T-033 done in `tasks/README.md`. Record the measured handoff latency difference (watch vs backoff)
from criterion 3 as a note — M7 will want the local baseline.
