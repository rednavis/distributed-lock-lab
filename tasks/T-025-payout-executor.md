# T-025 — payout-executor: the state machine

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M2 · **Estimate** 30 min (the ambiguity branch is the expensive part; if the loop/scheduler and the state machine cannot both land, ship the state machine and defer the polling loop to T-027's fixture)
**Preconditions** — T-010..T-017 (Postgres lock backend live behind `POST /v1/locks/{key}/acquire`); T-020..T-023 (paydb, `payment-resource` P1/P2/P3, `rail-stub`); **T-024** (`rail-proxy` enforcing the high-water fence). You inherit three protected surfaces and no caller: nothing yet composes claim → lock → submit → post. **`lock-client` is an empty module at this point** — it gains `LockClient`, the session and the acquire loop only in T-040/T-041 — so this task talks to `lock-server` over HTTP directly, through a client it owns and that T-047 later deletes.
**Goal** — Implement the single module that drives one payout through the whole critical section in a fixed order, and that treats an ambiguous rail outcome as terminal.

## 1. Why this task exists

The executor is the only module that sees the entire critical section, so it is the only place the ordering rules can be read end to end (`#ct5-modules`). It is also where the lock earns its existence: a balance update alone needs no distributed lock, but a rail submission that no transaction can roll back does (ADR-004). And it is where the tempting bug lives — on a timeout, the intuitive move is to retry.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| Session + acquire/renew/release calls, `LOCK_CONTENDED`, `LOCK_LOST`, token unchanged on renew | `docs/contracts/C3-http-surfaces.md#ct3-lock` |
| `LockService` / `LockHandle` usage — acquire yields **`Optional<LockHandle>`**, and `Optional.empty()` is the contended case; the executor never touches `LockOutcome`, which is the `forceRevoke` result record only; `LockLostException`, `FencedOutException`, `ContentionException` semantics | `docs/contracts/C2-java-api.md#ct2-lockservice`, `#ct2-records`, `#ct2-exceptions` |
| Token travels as an explicit parameter or `X-Fencing-Token` header — **never** a thread-local | `docs/contracts/C2-java-api.md#ct2-propagation` |
| P1 list, P2 claim (`expectedState`), P3 post (`railOutcome=ACKED`) and their codes | `docs/contracts/C3-http-surfaces.md#ct3-pay` |
| `POST /v1/rail/submissions` and its six outcomes | `docs/contracts/C3-http-surfaces.md#ct3-railproxy` |
| What the executor may and may not assume after a timeout; quarantine survives lease, session and pod | `docs/contracts/C3-http-surfaces.md#ct3-ambiguity` |
| `payout.state` enum values; a GET never decides safety | `docs/contracts/C1-database-schemas.md#ct1-paydb`, `C3#ct3-conventions` |
| Lease vs hop budgets, `lock.client.safety-margin` | `docs/contracts/C3-http-surfaces.md#ct3-timeouts`, `C5#ct5-config` |
| `payout.execute{outcome=posted|failed|abandoned|ambiguous}`; logs `rail_ambiguous`, `fenced_out` | `docs/contracts/C4-observability.md#ct4-metrics`, `#ct4-logs` |
| Module name, package `dev.lock.payments.executor`, deps `lock-api` + `lock-client` only | `docs/contracts/C5-config-build-and-naming.md#ct5-modules` |

**Precedence:** if this spec and a contract disagree, the **CONTRACT wins** — stop and report, quoting both. Do not implement either version.

## 3. Deliverables

| Path | What |
|---|---|
| `payout-executor/build.gradle.kts` | Boot app; `lock-api`, `lock-client` (declared per `#ct5-modules`, but still an empty module in M2 — nothing is imported from it yet), HTTP client, Micrometer; **no** dependency on `payment-resource` or `rail-proxy` |
| `.../client/TemporaryLockServerClient.java` | **New and deliberately TEMPORARY.** The minimum direct HTTP client over the L-surfaces this task needs: L4 acquire, L5 renew, L6 release, session create/refresh. Maps 409/429/503 to the `dev.lock.api` exceptions; sends nothing implicitly. No backoff loop beyond one fixed retry, no reentrancy, no heartbeat scheduler, no session-loss listener — those are SDK concerns (T-040/T-041). Mark the class `@Deprecated` with a Javadoc line naming **T-047** as its removal task |
| `payout-executor/src/main/java/dev/lock/payments/executor/PayoutExecutorApplication.java` | Entry point |
| `.../PayoutExecutionService.java` | The state machine; one method per transition, no hidden retries |
| `.../ExecutionOutcome.java` | Sealed result type: posted, failed, abandoned, ambiguous, contended, fenced |
| `.../client/PaymentResourceClient.java`, `.../client/RailProxyClient.java` | Typed clients; every call sends the token header; error codes map to the C2 exceptions |
| `.../PayoutPollingLoop.java` | Fixed-delay poll of P1 for `PENDING`, bounded concurrency, one payout per worker thread |
| `.../ExecutorMetrics.java` | `payout.execute` timer with the four pinned outcome tags |
| `payout-executor/src/main/resources/application.yml` | Lock-server, payment-resource and rail-proxy base URLs; `ownerId` from env; poll interval; safety margin |

## 4. Specification

**Fixed order, no shortcuts.** Create/refresh a session → acquire `payout:{accountId}` → capture the `fencingToken` from the grant → claim (P2, `expectedState=PENDING`) → submit via the proxy → post (P3) → release. The token from the grant is threaded through *every* subsequent call unchanged; there is no re-read of the token from anywhere else, and no GET is consulted to decide whether submitting is safe.

**Lease discipline.** Before submitting, check remaining lease against the proxy hop budget plus `lock.client.safety-margin`. If the remaining lease is smaller than the worst-case submit, do **not** submit: release and let another worker claim with a fresh token. Heartbeating continues on the client's own thread; a `LockLostException` mid-flight does not authorise a compensating write.

**Outcome branches.**

| Branch | Transition | Executor behaviour |
|---|---|---|
| Acquire contended | none | Skip the payout, no state change, no metric outcome beyond a debug log |
| Claim `PAYOUT_NOT_CLAIMABLE` | none | Another worker owns it; release and move on |
| Claim/post `FENCED_OUT` | payout left as-is | Terminal for this worker: release, count `payout.execute{outcome=abandoned}`. **Never re-acquire and retry the same payout in the same pass** |
| Proxy `FENCED_OUT` | `CLAIMED` retained | Same as above; the fence worked, this worker is a zombie |
| Proxy `DUPLICATE_SUBMISSION` | none | Terminal; a claim/lease boundary defect, not a rail problem. Log loudly |
| Proxy 422 `REJECTED` | `FAILED` | Business rejection; `payout.execute{outcome=failed}`; no rail retry |
| Proxy 503 `RAIL_UNAVAILABLE` | stays `CLAIMED` | Nothing was presented, so a later pass may retry from claim |
| Proxy 409 `RAIL_AMBIGUOUS` | `RAIL_AMBIGUOUS` | **Terminal, permanently.** Emit `rail_ambiguous`, `payout.execute{outcome=ambiguous}`, release the lock, stop |
| Ack | `RAIL_ACKED` → post → `POSTED` | `payout.execute{outcome=posted}` |

**The ambiguity rule, stated once so the implementer cannot miss it.** A timeout is the absence of information about a side effect that may already have moved money. The executor may assume exactly one attempt was presented; it may **not** assume nothing happened, nor that a newer token makes a second submit safe. `RAIL_AMBIGUOUS` is submittable by no worker under any token — the polling loop's P1 query must therefore never select it, and no code path may transition out of it. Resolution is out-of-band reconciliation (FR-26).

**Release always.** Release in a finally-equivalent path, on every branch including ambiguity — holding a lease over a quarantined payout blocks the account for nothing.

## 5. Acceptance criteria

1. `./gradlew :payout-executor:build` passes, Spotless clean.
2. Happy path: a `PENDING` payout ends `POSTED` with two `ledger_entry` rows and one `ACKED` `rail_submission`.
3. With `rail.stub.latency-ms` forcing a timeout, the payout ends `RAIL_AMBIGUOUS`, exactly one `rail_submission` row exists with `outcome='TIMEOUT'`, and the lock is released.
4. Re-running the loop after (3) leaves the payout `RAIL_AMBIGUOUS` and adds **no** `rail_submission` row.
5. `rail.duplicate.attempted` is 0 across (2)–(4).
6. Contention: two executor instances against one account produce exactly one `POSTED` payout per payout id and zero fenced writes at `payment-resource`.
7. `grep -rn "ThreadLocal" payout-executor/src/main/java` returns nothing (`#ct2-propagation`).
8. Every outbound call in both clients sends `X-Fencing-Token`; a test asserts a missing token is a programming error, not a warning.
9. `payout_execute_seconds_count` appears with each of the four pinned outcome tag values exercised by tests.

## 6. Verification

```
./gradlew :payout-executor:build :payout-executor:test
./gradlew :payout-executor:bootRun    # lock-server, payment-resource, rail-proxy, rail-stub up
psql "$PAYDB_URL" -c "select payout_id, state, attempt_count from payout order by created_at"
psql "$PAYDB_URL" -c "select payout_id, outcome, presented_token from rail_submission"
psql "$PAYDB_URL" -c "select account_id, balance_minor, fence from account"
curl -s localhost:8084/actuator/prometheus | grep -E 'payout_execute_seconds_count|rail_duplicate'
```
Expected: `POSTED` for the healthy payout, `RAIL_AMBIGUOUS` frozen after repeated passes, `rail_duplicate_attempted_total 0.0`.

## 7. Out of scope

The kill switches (T-026); the integration/invariant test (T-027); the etcd backend (M3); the client SDK hardening and the local fencing experiment (T-040..T-046); replacing the temporary lock client with the SDK (**T-047**); the reconciler that resolves `RAIL_AMBIGUOUS` (later milestone); GKE deployment (M5).

## 8. Hazards

Retrying after `RAIL_AMBIGUOUS` — the single defect this repository exists to demonstrate (`#ct3-ambiguity`). Re-acquiring the lock after `FENCED_OUT` and retrying with the fresh token: the fresh token *would* pass the fence, which is why the ban is at the executor. Deciding safety from `GET /v1/payouts/{id}` — a TOCTOU race (`#ct3-conventions`). Submitting with a lease that cannot cover the hop budget, which manufactures the zombie writer yourself. Passing the token implicitly through a thread-local, which silently breaks the moment work crosses an executor pool.

**The temporary lock client is throwaway on purpose — do not grow it.** It exists only because `lock-client` is empty until M4, and **T-047 deletes it** and moves the executor onto `LockClient` plus a real session. Every hour spent hardening it (backoff policies, jitter, reentrancy, a heartbeat thread, a loss listener) is an hour spent building T-040/T-041 in the wrong module, and it will be thrown away. Keep it at the minimum the state machine needs, and route anything more ambitious to the SDK tasks. The corollary: until T-047 lands there is **no session-loss listener** in the executor, so `lock.session.lost` / `session_lost` cannot fire from here — do not fake it with a local timer (T-060 depends on the real listener).

## 9. On completion

Mark the T-025 row done in `tasks/README.md`; note any deviation and any branch you could not exercise.
