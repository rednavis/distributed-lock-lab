# T-047 — payout-executor onto the SDK: delete the temporary lock client

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M4 (SDK and correctness proof) · **Estimate** 40 min (honest: the edit is small, but the T-027 suite has to go green again and Testcontainers startup dominates; if the session-lifecycle wiring and the re-run cannot both land, land the wiring and note the failing scenario)

**Preconditions**
- **T-025** — `payout-executor` drives the payout state machine through `TemporaryLockServerClient`, its own `@Deprecated` direct HTTP client over L4/L5/L6 plus session create/refresh. That class is the thing this task deletes.
- **T-040** — `lock-client` has `LockClient`, `LockClientSession`, the `Clock` seam, the heartbeat scheduler, conservative monotonic expiry, `checkStillHeld` and `onLockLost`.
- **T-041** — the bounded acquire loop with full jitter and same-session reentrancy exists, so the executor no longer has to own any retry policy.

**Goal** Make the SDK's only production caller real: replace the executor's direct HTTP calls with one `LockClient` session, register the loss listener, delete the temporary client, and prove the M2 invariants still hold.

## 1. Why this task exists

Until this task lands, `lock-client` ships with **zero production callers** — a library exercised only by its own tests, which is how an SDK acquires a plausible-looking API that nobody can actually hold a lock with. Two concrete consequences: the heartbeat and conservative-expiry arithmetic from T-040 is never subjected to a real critical section, and `lock.session.lost` / the `session_lost` log event can never fire in a deployed service, because nothing registers `onLockLost` (T-060 depends on that listener existing here). This is also the moment the executor gets *smaller*: backoff, jitter and reentrancy move out of it and into the SDK where they were specified.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| SDK surface: `openSession`, `acquire` returning `Optional<LockHandle>`, `checkStillHeld` (**throws**, no boolean), `onLockLost` | `docs/contracts/C2-java-api.md#ct2-sdk` |
| `LockHandle` shape; `LockOutcome` is the **`forceRevoke`** result record only — never an acquire result | `C2#ct2-records` |
| `LockLostException`, `FencedOutException`, `ContentionException` semantics; retry-safety verdicts | `C2#ct2-exceptions` |
| Token as an explicit parameter or `X-Fencing-Token` header — **never** a thread-local / MDC / `ScopedValue` | `C2#ct2-propagation` |
| L4 acquire and L6 release — release compares on `lock_key` **and** `fencing_token`, nothing else | `docs/contracts/C3-http-surfaces.md#ct3-lock` |
| What the executor may assume after a timeout; `RAIL_AMBIGUOUS` is terminal and submittable by no token | `C3#ct3-ambiguity` |
| Lease vs hop budgets, `lock.client.safety-margin`, `lock.default.ttl` | `C3#ct3-timeouts`, `docs/contracts/C5-config-build-and-naming.md#ct5-config` |
| Module deps: `payout-executor` → `lock-api` + `lock-client` only | `C5#ct5-modules` |
| `payout.execute` outcome tags unchanged; `lock.session.lost{backend}`; `session_lost` log fields | `docs/contracts/C4-observability.md#ct4-metrics`, `#ct4-logs` |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report the mismatch, quoting both.**

## 3. Deliverables

| Path | What |
|---|---|
| `payout-executor/.../client/TemporaryLockServerClient.java` | **Deleted.** Not deprecated further, not kept "for reference" — removed, with its tests |
| `payout-executor/.../PayoutExecutionService.java` | modify: take a `LockClientSession`; acquire via the SDK; drop the hand-rolled retry/renew code |
| `payout-executor/.../LockClientConfiguration.java` | new: one `LockClient` bean and one long-lived session per process; base URL, `ownerId`, TTL and safety margin from config; closed on shutdown |
| `payout-executor/.../SessionLossHandler.java` | new: registers `onLockLost`, marks the in-flight payout abandoned-for-this-worker, logs `session_lost`, exposes the hook T-060's `SessionLossMetrics` counts |
| `payout-executor/src/main/resources/application.yml` | modify: replace the ad-hoc lock-server settings with the SDK's pinned config keys |
| `payout-executor/src/test/java/…` | modify: retarget the unit tests at the SDK seam; delete assertions about the temporary client's HTTP shape. `docs/contracts/…`: **no edits** — report instead if something is unpinned |

## 4. Specification

**One session per process, not per payout.** The session is opened at startup and closed at shutdown; the heartbeat scheduler belongs to the `LockClient`, so the executor no longer renews anything itself. Delete the executor's renew timer outright rather than leaving it alongside the SDK's — two heartbeats on one lease is a race that hides expiry bugs.

**Acquire.** `session.acquire(key, ttl, policy)` returns `Optional<LockHandle>`; empty means the bounded loop exhausted its budget (the *contended* acquire result), which the executor treats exactly as T-025's "acquire contended" branch: skip the payout, no state change, no retry here. `ContentionException` must not surface as a stack trace in the loop's logs — the SDK owns contention.

**Fencing token.** The token comes from the returned handle and is threaded, unchanged, through claim (P2), submit and post (P3). No re-read from anywhere, no second acquire mid-payout, and no GET consulted to decide whether submitting is safe.

**Lease discipline, restated at the new seam.** Before submitting, call `checkStillHeld` — it **throws** `LockLostException` rather than returning a boolean, so the check is a guard clause, not an `if`. Then compare remaining lease against the proxy hop budget plus `lock.client.safety-margin`; if it does not cover the worst-case submit, release and let another worker claim with a fresh token.

**Loss listener.** `onLockLost` runs on the SDK scheduler thread and must not block: it flips a per-payout volatile flag, logs `session_lost`, and returns. It performs **no** compensating write and **no** database call — a lost lease is not evidence about the rail. All eight of T-025's outcome branches keep their existing semantics; `RAIL_AMBIGUOUS` stays terminal.

**Release.** Release on every branch through the handle's `close()`; the SDK's L6 compare-and-delete on `(lock_key, fencing_token)` makes a stale release benign, so a lost handle's release is logged at debug, not treated as an error.

## 5. Acceptance criteria

1. `./gradlew :payout-executor:build` green, Spotless clean.
2. `grep -rn "TemporaryLockServerClient" payout-executor lock-client harness` returns **nothing** — the class and every reference are gone.
3. `grep -rn "locks/.*/acquire\|/renew\|/release" payout-executor/src/main/java` returns nothing: no module but `lock-client` speaks the lock HTTP surface.
4. `grep -rn "ThreadLocal\|ScopedValue\|MDC" payout-executor/src/main/java` finds no token propagation.
5. The **T-027** suite passes unchanged, twice in a row, from a clean state — all four scenarios, including the two-executor contention case.
6. `rail_duplicate_attempted_total` is 0 after S1–S3 and 1 after S4, exactly as before the migration.
7. A test that stalls the heartbeat past the conservative deadline shows `onLockLost` fired once, one `session_lost` log line with its required fields, and the in-flight payout ending abandoned — with **no** ledger or rail write after the loss.
8. `lock_session_lost_total{backend="postgres"}` is observable from the executor's `/actuator/prometheus` after (7) — the meter T-060 formalises now has a source.
9. Exactly one `LockClient` and one session exist per process (asserted on the bean context), and both close cleanly on shutdown with no non-daemon thread left behind.

## 6. Verification

```
./gradlew :payout-executor:build :payout-executor:test
./gradlew :harness:test --tests '*M2EndToEndIT*'      # T-027, unchanged
grep -rn "TemporaryLockServerClient" .                # expect: no hits
curl -s localhost:8084/actuator/prometheus | grep -E 'lock_session_lost_total|payout_execute_seconds_count'
psql "$PAYDB_URL" -c "select payout_id, outcome, presented_token from rail_submission"
```

Expected observable result: the M2 report is green with no fixture edits, `lock_session_lost_total` appears with the `backend` tag, and `rail_submission` still holds at most one `ACKED`/`TIMEOUT` row per payout.

## 7. Out of scope

Any change to `lock-client` behaviour — if the SDK is wrong, report it and stop; do not compensate in the executor (that is how T-025's temporary client was born). Changing T-027's assertions to make the migration pass. The SIGSTOP experiment (T-042), the simulation harness (T-043). The `SessionLossMetrics` class itself (T-060) — you provide the listener, it provides the meter. The etcd backend path (M3 already covers parity).

## 8. Hazards

- **Silently keeping the old client as a fallback** ("use the SDK unless it throws"). Two lock clients in one process means two views of who holds the lease; the fence then depends on which one wrote last. Delete it.
- Leaving the executor's own renew timer running next to the SDK heartbeat — the lease never appears to expire in testing, so the conservative-expiry margin is never validated.
- Doing work inside `onLockLost`. It runs on the scheduler thread; a database call there stalls every other handle's heartbeat and can *cause* the loss it is reporting.
- Treating an empty `Optional` from `acquire` as an error branch. It is the ordinary contended outcome and must not change payout state or emit a failure metric.
- Re-acquiring after `FencedOutException` because the SDK "makes it easy now". The fresh token would pass the fence; the ban from T-025 §8 is unchanged and still lives at the executor.

## 9. On completion

Mark the T-047 row done in `tasks/README.md`, recording that `TemporaryLockServerClient` is deleted and noting the `AcquirePolicy` values the executor ships (they must match the four ASSUMPTIONs from T-041). Note any T-027 scenario you could not re-run.
