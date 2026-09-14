# T-041 — lock-client: bounded acquire, full jitter, reentrancy

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M4 (SDK and correctness proof) · **Estimate** 30 min

**Preconditions**
- **T-040** — `lock-client` has `LockClient`, `LockClientSession`, the `Clock` seam, the heartbeat scheduler and monotonic conservative deadlines; L1/L2/L3/L5 transport exists and maps statuses to the `dev.lock.api` exceptions.
- **T-017** — `lock-server` serves L4 acquire and L6 release against `lockdb`, returning 409 `LOCK_CONTENDED` and 429 `CONTENTION_EXCEEDED`.

**Goal** Add the acquire path: a bounded, deadline-driven retry loop with **full jitter**, plus explicit same-session reentrancy semantics, so contended callers neither block forever nor synchronise into a herd.

## 1. Why this task exists

Plain exponential backoff gives every waiter the *same* delay, so N waiters that collided once collide again on every subsequent attempt — a thundering herd you built by hand, and each collision costs the lock service a wasted acquire round trip. Full jitter (uniform in `[0, backoff]`) spreads them across the window and decorrelates them permanently. The acquire loop is also the only place where "retry-safe" from the exception table is allowed to mean *retry* — the side effect is never retried.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| L4 acquire — path, `sessionId`/`ttlMillis`/`waitMillis` fields, `waitMillis` range 0…30_000 default 0, granted body incl. `fencingToken` | `docs/contracts/C3-http-surfaces.md#ct3-lock` |
| L6 release — compare-and-delete on `(lock_key, fencing_token)` and **nothing else**; `sessionId` is carried for attribution, never in the predicate; benign 409 `LOCK_LOST` | `C3#ct3-lock` |
| Error codes and their retry-safety verdicts (`LOCK_CONTENDED`, `CONTENTION_EXCEEDED`, `NOT_LEADER`, `VALIDATION_FAILED`) | `C3#ct3-errors` |
| `ContentionException` retry with jittered backoff (bounded, FR-28); `NotLeaderException` retry after a short delay; `FencedOutException` never | `docs/contracts/C2-java-api.md#ct2-exceptions` |
| SDK surface and `LockHandle` shape | `C2#ct2-sdk`, `C2#ct2-records` |
| **The acquire result is `Optional<LockHandle>`** — `Optional.empty()` *is* the not-granted case. `LockOutcome` is the **`forceRevoke`** result record and must not appear on any acquire path in any module; the L4 HTTP body is `LockGrant`. This task must not introduce a second name for a grant | `C2#ct2-records`, `C3#ct3-lock` |
| Token propagation: explicit parameter or header, **never** a thread-local / MDC / `ScopedValue` | `C2#ct2-propagation` |
| Per-hop timeouts and retry budgets; acquire wait must fit inside them | `C3#ct3-timeouts` |
| `lock.default.ttl`, `lock.client.safety-margin`, any new client key | `docs/contracts/C5-config-build-and-naming.md#ct5-config` |
| `lock.acquire` is **lock-server's** meter — one emitter per metric, so the SDK registers none of it; plus the cardinality budget (no per-key or per-payout tags) | `docs/contracts/C4-observability.md#ct4-metrics`, `#ct4-cardinality` |
| `lock_granted` and contention log events and required fields | `C4#ct4-logs` |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report the mismatch, quoting both.**

## 3. Deliverables

| Path | What |
|---|---|
| `lock-client/src/main/java/dev/lock/client/LockClientSession.java` | modify: add the bounded `acquire` entry points, the reentrancy registry, and release |
| `lock-client/src/main/java/dev/lock/client/AcquirePolicy.java` | new: immutable record — total budget, base backoff, max backoff, max attempts, per-attempt `waitMillis` |
| `lock-client/src/main/java/dev/lock/client/Backoff.java` | new: full-jitter generator taking a `java.util.Random` (seedable) and returning the next delay |
| `lock-client/src/main/java/dev/lock/client/ReentrantHold.java` | new (or a private nested type): key → handle + hold depth |
| `lock-client/src/test/java/dev/lock/client/…` | new: jitter distribution tests, budget-exhaustion tests, reentrancy tests, one Testcontainers contention test |
| `docs/contracts/…` | **no edits** — report instead if something is unpinned |

## 4. Specification

**Bounded acquire.** One public method taking key, requested TTL, and an `AcquirePolicy`, returning **`Optional<LockHandle>`**; a convenience overload uses defaults. An empty `Optional` *is* the not-granted case — do **not** invent a wrapper result type for it, and in particular do not return `LockOutcome`, which C2 §2.3 reserves for `forceRevoke`. The loop is governed by a **deadline computed once** from the `Clock` at entry (`start + policy.totalBudget`), not by attempt count alone — attempt count is a secondary cap. On `LOCK_CONTENDED` (409) or `CONTENTION_EXCEEDED` (429): sleep the jittered delay via the `Clock` seam and retry, provided the remaining budget exceeds the delay plus one estimated round trip; otherwise return `Optional.empty()`. On 503 `NOT_LEADER`: retry with a short fixed floor delay, still inside the budget. On `VALIDATION_FAILED` (400) or any 4xx not in the retry set: return/throw immediately, no retry. Never retry after a `FencedOutException` from any surface.

**If a not-granted *reason* is genuinely needed** — to distinguish "held by someone else the whole time" from "budget exhausted while the server kept returning `NOT_LEADER`" — do **not** widen the return type. Prefer `Optional<LockHandle>` plus a debug log line and the attempt count on the `lock.acquire` span (see Telemetry). Only if a caller must *branch* on the reason may you add a **local, SDK-private** `AcquireResult` record in `dev.lock.client` (`Optional<LockHandle> handle`, `NotGrantedReason reason`, `int attempts`, `Duration elapsed`) exposed on a *separate, explicitly named* method such as `acquireDetailed`, leaving the primary `acquire` returning `Optional<LockHandle>`. Record the choice in §9. `AcquireResult` must **not** be added to `lock-api`, must not be named `LockOutcome`, and must not be returned from the primary method — a second grant-shaped type in the shared contract is the exact collision C2 §2.3 closed.

**Full jitter.** `delay = random.nextLong(0, min(maxBackoff, base * 2^attempt) + 1)`, i.e. uniform in `[0, cap]`, where the cap grows exponentially and is clamped. ASSUMPTIONS to record in the class doc: base 50 ms, cap 2 s, total budget 10 s, max attempts 12. The `Random` is injectable so a seed makes a run reproducible for T-043. Forbidden alternatives, and say why in one comment line each: fixed delay, plain exponential without jitter, "equal jitter" (`cap/2 + rand(cap/2)`) — the last still leaves a synchronised floor.

**Never blocking on the server.** `waitMillis` per attempt stays small (ASSUMPTION 0 for the project default) so waiting is client-side and observable; a large server-side wait hides contention from metrics and burns a server thread. If a policy asks for `waitMillis` above the contract maximum, reject it at construction.

**Reentrancy.** Same session + same key + already held and not lost ⇒ increment hold depth and return the **same handle with the same fencing token**; do not call L4 again. Release decrements; only depth zero sends L6. A re-acquire of a key whose handle is **lost** is not reentrancy: it must throw `LockLostException` rather than silently issuing a new token behind the caller's back, because the caller's in-flight side effect still carries the old token. Reentrancy is per-session, never per-thread — document that a second thread in the same session asking for the same key is *not* granted a nested hold and must be rejected as contended by design (ASSUMPTION for the project; note it in the class doc).

**Telemetry.** **Do not register `lock.acquire` here.** C4 §4.2 pins one emitter per meter and `lock.acquire` is **lock-server's** (timed on L4 in T-016): a second copy from the SDK changes the `service` label and silently doubles S1's denominator with client-side retries that the server already counted, so the availability ratio stops meaning what §6.3 says it means. The SDK's own meter is `lock.session.lost{backend}` (T-060); the per-attempt count belongs on the `lock.acquire` **span** and in the debug log line — never as a metric tag, and never as the key.

## 5. Acceptance criteria

1. `./gradlew :lock-client:build` green, Spotless clean.
2. A statistical test drawing ≥10_000 delays at attempt 5 shows a spread consistent with uniform `[0, cap]`: minimum below 5 % of cap, maximum above 95 % of cap, mean within 10 % of `cap/2`.
3. A test with a fixed seed produces a byte-identical delay sequence across two runs.
4. A test where L4 always returns 409 shows the loop stops at or before the budget, makes at most `maxAttempts` calls, and returns an **empty `Optional`** — no exception, no unbounded loop. The test asserts the declared return type is `Optional<LockHandle>`, and `grep -rn 'LockOutcome' lock-client/src` returns no hit.
5. A test where L4 returns 503 twice then 200 shows a grant and a token equal to the server's.
6. A reentrancy test shows two nested acquires produce one L4 call, identical `fencingToken`, and exactly one L6 on the outer release.
7. A test shows re-acquiring a lost key throws `LockLostException` and issues no L4 call.
8. Ten concurrent sessions contending for one key against a real `lock-server` all terminate; exactly one holds at any instant per the L7 advisory read; total L4 call count is bounded (record it in the test output).
9. `grep` finds no `ThreadLocal`, no MDC, and no `ScopedValue` carrying a fencing token in `lock-client/src/main`.

## 6. Verification

```
./gradlew :lock-client:build :lock-client:test
./gradlew :lock-client:test --tests '*Backoff*' --tests '*Reentran*' --tests '*Contention*'
grep -rn "ThreadLocal\|ScopedValue\|MDC" lock-client/src/main/java     # expect: no token propagation hits
grep -rn "LockOutcome" lock-client/src                                 # expect: no hits at all — acquire returns Optional<LockHandle>
grep -rn "nextLong\|nextDouble" lock-client/src/main/java/dev/lock/client/Backoff.java
```

Expected observable result: the contention test prints the observed L4 attempt total and the jitter test prints min/mean/max of the sampled delays; both appear in `lock-client/build/reports/tests/test/index.html`.

## 7. Out of scope

Heartbeat/expiry (T-040, do not change its arithmetic). The SIGSTOP experiment (T-042). The seeded simulation schedule (T-043) — you only make `Random` and `Clock` injectable. Server-side fair queueing or waiter ordering: `lock-server` owns `waiterCount`; the client must not attempt fairness.

## 8. Hazards

- **Retrying the side effect instead of the acquire.** C2 §2.4 is explicit: a `FencedOutException` from the rail proxy means another worker may own the payout; a retry there is the duplicate payment INV-02 forbids. Keep the retry loop strictly around L4.
- Sleeping on `Thread.sleep` directly makes T-043 impossible — every wait goes through the `Clock` seam.
- `base * 2^attempt` overflows `long` past attempt ~62 and silently goes negative; clamp before multiplying, not after.
- A shared `java.util.Random` across threads serialises on its seed CAS; use per-call `ThreadLocalRandom` in production *and* keep the injectable seeded instance for tests — do not conflate the two.

## 9. On completion

Mark the T-041 row done in `tasks/README.md`, recording the four ASSUMPTION values you shipped (base, cap, budget, max attempts) so T-042 and T-070 quote the same numbers. Also record whether the §4 not-granted-reason escape hatch was used — i.e. whether a local `AcquireResult` and an `acquireDetailed` method exist, or whether `Optional<LockHandle>` alone sufficed (the preferred answer) — because T-047 codes against whichever shape shipped. Note any deviation in that row.
