# T-040 — lock-client: session, heartbeat, conservative expiry

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M4 (SDK and correctness proof) · **Estimate** 30 min (tight but honest: no acquire loop here, that is T-041)

**Preconditions**
- **T-008** — M0 complete: Gradle 9.5 Kotlin DSL build, version catalog, the module skeleton incl. `lock-client`, Spotless + google-java-format, and a CI workflow authored but not executed ([ADR-011](../docs/adr/ADR-011-no-git-initialisation-yet.md)).
- **T-017** — M1 complete: `lock-server` with the PostgreSQL `LockStore` serves L1–L8 against a Testcontainers `lockdb`; `lock-api` types and the exception hierarchy exist and compile.
- **T-027** — M2 complete: `payment-resource`, `payout-executor`, `rail-proxy`, `rail-stub` run in the local compose stack. You inherit a repo where the executor obtains locks through direct HTTP calls; it stays that way until **T-047** migrates it onto this SDK.

**Goal** Implement the session half of the SDK — one session, one heartbeat scheduler, and a *monotonic, conservative* local expiry that declares the lock lost before the server does.

## 1. Why this task exists

Every caller gets lease arithmetic wrong in the same way: they trust the server's `expiresAt` wall-clock instant, compare it against their own clock, and hold a lock they no longer own across an NTP step or a GC pause. Writing the arithmetic once, in one module, is the only way the rest of the project can be honest about liveness. The lease is measured from the moment the request **left** this process, not from when the response arrived — the network delay on the response leg is time the server has already spent counting down.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| SDK members: `openSession`, `checkStillHeld`, `onLockLost` — names and signatures. `checkStillHeld` is **`void checkStillHeld(LockHandle handle) throws LockLostException`** — it returns normally or throws; **never a boolean** | `docs/contracts/C2-java-api.md#ct2-sdk` |
| `LockHandle` fields incl. `clientDeadlineNanos`; `LockInfo`. `LockOutcome` is the **`forceRevoke`** result record only — never an acquire or held-check result | `C2#ct2-records` |
| `LockLostException` is terminal and never retryable | `C2#ct2-exceptions` |
| Thread-safety and nullability of SDK types | `C2#ct2-threading` |
| L1 create session, L2 heartbeat, L3 delete session, L5 renew — paths, bodies, status codes (`SESSION_UNKNOWN` 404, `SESSION_EXPIRED` 410, `LOCK_LOST` 409, `NOT_LEADER` 503) | `docs/contracts/C3-http-surfaces.md#ct3-lock` |
| Per-hop timeouts and the lease-vs-timeout arithmetic | `C3#ct3-timeouts` |
| `lock.session.ttl`, `lock.client.safety-margin` (0.30) — keys, defaults, env-var spellings | `docs/contracts/C5-config-build-and-naming.md#ct5-config` |
| Log events `session_lost` / `lease_expired` and their required fields; any metric you emit | `docs/contracts/C4-observability.md#ct4-logs`, `#ct4-metrics` |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report the mismatch, quoting both. Do not implement either version.**

## 3. Deliverables

| Path | What |
|---|---|
| `lock-client/build.gradle.kts` | modify: add the HTTP client and Micrometer/SLF4J deps from the catalog; `lock-api` stays the only API dependency |
| `lock-client/src/main/java/dev/lock/client/LockClient.java` | new: entry point holding base URL, timeouts, the scheduler, `openSession(String ownerId)` |
| `lock-client/src/main/java/dev/lock/client/LockClientSession.java` | new: `AutoCloseable` session — handle registry, heartbeat state, `checkStillHeld`, `onLockLost` |
| `lock-client/src/main/java/dev/lock/client/LockClientConfig.java` | new: immutable config record — session TTL, safety margin, heartbeat period, per-request timeouts |
| `lock-client/src/main/java/dev/lock/client/Clock.java` | new: seam over `System.nanoTime()` so T-043 can drive it; production impl delegates |
| `lock-client/src/main/java/dev/lock/client/LockServerHttp.java` | new: thin L1/L2/L3/L5 transport, status→exception mapping |
| `lock-client/src/test/java/dev/lock/client/…` | new: unit tests on the fake clock plus one Testcontainers-backed session test |

## 4. Specification

**Deadline arithmetic.** For every grant and every successful renew: capture `sendNanos` from the `Clock` **before** the request is written, and on success set `clientDeadlineNanos = sendNanos + leaseMillis * (1 - safetyMargin)` converted to nanos, where `leaseMillis` is the TTL the **server granted** (it may clamp down), never the TTL requested. Never derive a deadline from `expiresAt`; that field is for logs and human display only. `checkStillHeld` compares `clock.nanoTime()` against the stored deadline with subtraction, never `<` on raw values, and **throws `LockLostException` when the deadline has passed rather than returning a verdict** (C2 §2.6 pins `void … throws`).

**Heartbeat.** One single-threaded scheduled executor per `LockClient`, daemon threads, named `lock-hb-*`. Period = `min(sessionTtl, shortest live lease) * (1 - margin) / 3` (ASSUMPTION: divisor 3, so two consecutive losses can be tolerated before the server expires the session). Each tick sends L2 for the session and L5 for each live handle whose remaining time is under two periods. On L2 success, extend the session deadline from that tick's send time. On 404/410, or on the local session deadline passing, the session enters `LOST`: every handle is marked lost, `onLockLost` listeners fire once per handle on the scheduler thread, `session_lost` is logged. `NOT_LEADER` (503) and I/O errors do **not** immediately lose the session — they are retried on the next tick; only the deadline decides.

**Failure semantics.** After a handle is lost, any SDK call taking that handle throws `LockLostException` — the *first* call after the deadline passes, with no server round trip required. `close()` sends L3 best-effort, shuts the scheduler down, and never throws on transport failure. Listener exceptions are caught and logged; one bad listener must not kill the heartbeat thread. Registration order is preserved; a listener registered after loss fires immediately.

**Threading.** `LockClientSession` is safe for concurrent use by application threads; handle state lives in a concurrent map keyed by lock key; deadlines are `volatile` longs. No lock is held while an HTTP call is in flight.

## 5. Acceptance criteria

1. `./gradlew :lock-client:build` passes with Spotless clean; `lock-client` still declares no dependency on `lock-server`.
2. A unit test with a fake `Clock` proves `clientDeadlineNanos` equals `sendNanos + 0.7 * leaseMillis` for margin 0.30 — and that a 400 ms response-leg delay does **not** move the deadline later.
3. A unit test where the server clamps a requested 30 s TTL to 10 s shows the deadline computed from 10 s.
4. A test advancing the fake clock past the deadline with the heartbeat stalled shows `checkStillHeld` **throwing `LockLostException`**, `onLockLost` fired exactly once, and the next handle-taking call throwing too. A test also asserts the method's declared return type is `void`, so no boolean creeps back in.
5. A test returning 503 `NOT_LEADER` on one tick and 200 on the next shows the session survives and no listener fires.
6. A Testcontainers test against a real `lock-server` holds a lock for 3× its TTL with heartbeats running and never loses it; killing the heartbeat scheduler makes it lost within `ttl * (1 - margin)` + one period.
7. No `Thread.sleep` in production sources under `lock-client/src/main`; no wall-clock (`System.currentTimeMillis`, `Instant.now`) use in any expiry decision.

## 6. Verification

```
./gradlew :lock-client:build :lock-client:test
grep -rn "currentTimeMillis\|Instant.now" lock-client/src/main/java   # expect: no hits in deadline code paths
grep -rn "Thread.sleep" lock-client/src/main/java                     # expect: no hits
./gradlew :lock-client:test --tests '*SessionExpiry*' --info           # expect: the seven-scenario suite green
```

Expected observable result: the test report at `lock-client/build/reports/tests/test/index.html` lists the clamp, response-delay, stalled-heartbeat, and `NOT_LEADER` cases as passing.

## 7. Out of scope

Bounded acquire, backoff, jitter and reentrancy — **T-041**. Migrating `payout-executor` off its direct HTTP calls — **T-047**. The SIGSTOP experiment — **T-042**. The simulation harness — **T-043** (you only provide the `Clock` seam it needs). Any change to `lock-server` behaviour: if the server is wrong, report it, do not compensate in the client.

## 8. Hazards

- **Measuring the lease from response arrival** is the whole bug this task exists to prevent; it looks correct in every test where the network is fast.
- `checkStillHeld` returning normally is *not* permission to perform a side effect — C2 §2.6 says so explicitly; the fencing token is the safety mechanism. Do not add a comment or API that implies otherwise.
- **Do not give `checkStillHeld` a boolean return.** C2 §2.6 pins `void … throws LockLostException` precisely because a boolean invites `if (checkStillHeld(h))` with no `else` — a silently skipped payout instead of a loud abort. A convenience boolean *alongside* it is the same defect with an extra name.
- Renew must **not** change the fencing token (`C3#ct3-lock` L5). If your code re-reads a token after renew, you have introduced a second token per grant.
- A `ScheduledExecutorService` whose task throws silently stops rescheduling — wrap every tick body.

## 9. On completion

Mark the T-040 row done in `tasks/README.md` with the local timestamp. Note any deviation (especially the heartbeat divisor if you changed it) in that row, and open a contract-amendment note in `docs/04-contracts.md#c-changelog` only if a contract had to move.
