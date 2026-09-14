# C3 - HTTP surfaces

Wire contract for all four services. Companion to [01 requirements](../01-requirements.md)
(FR/NFR ids) and [02 domain model](../02-domain-model.md). Tables are normative; prose explains only
the *why* and the *failure mode*.

## 3.1 Conventions {#ct3-conventions}

| Rule | Value | Why / failure mode if broken |
|---|---|---|
| Path prefix | `/v1` on every service except `rail-stub` (`POST /submit`) | The stub stands in for a third party whose URL shape is not ours to design. Versioning the stub would imply we can negotiate with it. |
| Content type | `application/json; charset=utf-8`, request and response, including errors | A caller that must parse a token-mismatch body cannot afford an HTML error page from a proxy. |
| `X-Owner-Id` | Required on all lock-server and payment-resource writes. Opaque worker identity, ≤64 chars. | Attribution (FR-30). Without it a fenced-out log line names no suspect. |
| `X-Fencing-Token` | Required on every protected write (`claim`, `post`, `rail/submissions`). Decimal `int64` > 0. | The token travels in the header, not the body, so an intermediary or gateway rule can reject an unsigned write without parsing the payload (FR-15). |
| `X-Idempotency-Key` | Required on `claim`, `post`, `rail/submissions`. Derived from `payout_id`, stable for the payout's whole life, **never regenerated on retry**. | A regenerated key makes a duplicate undetectable at the proxy - the exact defect this project exists to prevent. |
| Idempotency scope | Replay of the same key with the same body returns the original response and status. Same key, different body → `IDEMPOTENCY_CONFLICT` (422). | Idempotency is a *deduplication* aid, never a substitute for fencing: two live workers hold two different tokens and two different keys are not involved. |
| Clock fields | RFC 3339 UTC with milliseconds | Lease arithmetic client-side is monotonic (FR-11); wall clocks appear in responses for humans only. |
| Never for correctness | `GET /v1/locks/{key}` and `GET /v1/payouts/{id}` are advisory reads (FR-07) | Deciding "safe to submit" from a GET is a TOCTOU race; only the conditional write decides. |

**Error envelope.** Every non-2xx carries exactly this shape; fields beyond `code`/`message` are
per-code and listed in [3.2](#ct3-errors).

| Field | Type | Notes |
|---|---|---|
| `code` | string enum | Stable machine identifier. Alerts filter on this, never on `message` (NFR-08). |
| `message` | string | Human text. Not stable, not parseable. |
| `retrySafe` | boolean | Server's own verdict, mirroring [3.2](#ct3-errors). Belt-and-braces for clients that guess. |
| `retryAfterMillis` | int, optional | Present on `LOCK_CONTENDED`, `CONTENTION_EXCEEDED`, `NOT_LEADER`, `RAIL_UNAVAILABLE`. |
| `details` | object, optional | Per-code diagnostic fields (presented/highest token, resource id, state). |
| `traceId` | string | OpenTelemetry trace id, for log correlation. |

## 3.2 Error-code catalogue {#ct3-errors}

"Retry-safe" means: *may the identical request be re-sent by the same caller with the same token
without risking a duplicate side effect or a lost-mutual-exclusion event?* It is not "was the error
transient". Implementers conflate the two; the second column is where duplicate payments come from.

| Code | HTTP | Meaning | Retry-safe? |
|---|---|---|---|
| `LOCK_CONTENDED` | 409 | Lock currently held by another owner; lease not yet expired. | **Yes** - bounded, jittered retry (FR-28). Nothing happened. |
| `CONTENTION_EXCEEDED` | 429 | Caller's own retry budget for this key is exhausted, or the waiter queue is over its cap. | **Yes, but only after `retryAfterMillis`.** Immediate retry is what caused the throttle. |
| `NOT_LEADER` | 503 | This lock-server replica is not the etcd leader / lost its Cloud SQL primary. | **Yes** - no state was touched; retry against the redirect target. |
| `LOCK_LOST` | 409 | The grant this call names has expired or was revoked; the caller is no longer the holder. | **No.** Retrying with the dead token can only be fenced out. Re-acquire, re-read state (FR-14), get a *new* token. |
| `SESSION_UNKNOWN` | 404 | No such `sessionId` - never existed or was garbage-collected. | **No.** Open a new session; all locks attached to the old one are gone (FR-04). |
| `SESSION_EXPIRED` | 410 | Session existed, heartbeat lapsed, locks released. | **No.** Same remedy as above. Distinguished from 404 so an operator can tell "wrong id" from "we were too slow". |
| `INVALID_TOKEN` | 400 | `X-Fencing-Token` absent, non-numeric, ≤0, or overflowing `int64`. | **No** - deterministic client defect. Never coerce a malformed token to 0; 0 would compare *below* every stored fence and look like a benign no-op. |
| `FENCED_OUT` | 409 | Presented token did not exceed the stored fence / high-water mark. A newer holder exists. | **Never.** Incident-grade (NFR-06). The caller must abort the whole critical section, not retry, not re-acquire and continue. |
| `PAYOUT_NOT_CLAIMABLE` | 409 | Payout is not in a state this transition allows (already `POSTED`, `FAILED`, `ABANDONED`, or `RAIL_AMBIGUOUS`) (FR-22, FR-23). | **No.** State is terminal or quarantined; retry changes nothing. |
| `DUPLICATE_SUBMISSION` | 409 | The proxy already holds a submission record for this payout, whatever the token (FR-18). | **No.** Pages. Resolve from the existing record. |
| `INSUFFICIENT_FUNDS` | 422 | Balance would go negative. Payout → `FAILED`, no rail call. | **No.** |
| `IDEMPOTENCY_CONFLICT` | 422 | Same `X-Idempotency-Key`, materially different body. | **No.** |
| `RAIL_UNAVAILABLE` | 503 | Proxy could not reach the rail **and no attempt record was written**, so nothing was presented. | **Yes** - the only genuinely retry-safe rail failure, precisely because the proxy proved it never forwarded. |
| `RAIL_AMBIGUOUS` | 409 | An attempt was forwarded and the outcome is unknown (timeout, connection reset, malformed ack). | **Never.** See [3.7](#ct3-ambiguity). The payout moves to `RAIL_AMBIGUOUS` and leaves the automated path. |
| `VALIDATION_FAILED` | 400 | Schema/constraint violation. `details.fields[]`. | **No.** |

## 3.3 Lock-server surface {#ct3-lock}

Backend-agnostic (FR-09): identical status codes and bodies for `lock.backend=pg` and `etcd`. Under
etcd the token is the key's `ModRevision` (FR-10); under PostgreSQL it is `NEXTVAL` of
`fencing_token_seq`. Callers must not infer density or meaning from token values - only ordering.

| # | Method + path | Request (headers / body fields) | Response 2xx | Codes |
|---|---|---|---|---|
| L1 | `POST /v1/sessions` | `ownerId` string ≤64 (or `X-Owner-Id`); `ttlMillis` int, 1_000…60_000, default `lock.session.ttl` | 201: `sessionId` uuid, `ownerId`, `ttlMillis` granted (may be clamped down), `expiresAt` | 400 `VALIDATION_FAILED`, 503 `NOT_LEADER` |
| L2 | `POST /v1/sessions/{sessionId}/heartbeat` | path `sessionId` uuid; `X-Owner-Id` | 200: `sessionId`, `expiresAt`, `serverTime`, `heldLockCount` int | 404 `SESSION_UNKNOWN`, 410 `SESSION_EXPIRED`, 503 `NOT_LEADER` |
| L3 | `DELETE /v1/sessions/{sessionId}` | path `sessionId`; `X-Owner-Id` | 204, no body. Releases every attached lock. | 404 `SESSION_UNKNOWN`, 503 `NOT_LEADER` |
| L4 | `POST /v1/locks/{key}/acquire` | path `key` ≤128, `[a-zA-Z0-9:._-]+` (e.g. `payout:{accountId}`); `sessionId` uuid required; `ttlMillis` int, default `lock.default.ttl`; `waitMillis` int 0…30_000, default 0 (no server-side blocking beyond this) | 200: `LockGrant` — `key`, `ownerId`, `sessionId`, `fencingToken` int64, `acquiredAt`, `expiresAt`. (A 200 *is* the granted case; contention is the 409 below. `LockOutcome` is **not** this body — that name is the `forceRevoke` result record only, [C2 §2.3](C2-java-api.md#ct2-records).) | 409 `LOCK_CONTENDED` (+`details.holderOwnerId`, `details.expiresAt`), 429 `CONTENTION_EXCEEDED`, 404/410 session codes, 503 `NOT_LEADER` |
| L5 | `POST /v1/locks/{key}/renew` | path `key`; `sessionId`; `X-Fencing-Token` of the grant being renewed; `ttlMillis` optional | 200: same body as L4. **Token is unchanged** - renew extends, never re-issues. | 409 `LOCK_LOST` (+`details.storedToken`), 400 `INVALID_TOKEN`, 410 `SESSION_EXPIRED` |
| L6 | `DELETE /v1/locks/{key}` | path `key`; `sessionId`; `X-Fencing-Token` | 204. Compare-and-delete on `(lock_key, fencing_token)` and nothing else - the token comes from one global sequence, so it already identifies the grant; `sessionId` is carried for attribution and session bookkeeping, never as part of the predicate ([C1 §1.4](C1-database-schemas.md#ct1-renew)). Releasing someone else's grant is inexpressible (FR-05). | 409 `LOCK_LOST` (already expired or taken over; benign, log only), 400 `INVALID_TOKEN` |
| L7 | `GET /v1/locks/{key}` | path `key`. No auth-bearing headers required. | 200 `LockInfo`: `key`, `held` bool, `ownerId?`, `fencingToken?`, `acquiredAt?`, `expiresAt?`, `waiterCount` int. 404 if never seen. | - |
| L8 | `POST /v1/locks/{key}/revoke` | path `key`; `operator` string required; `reason` string ≥8 chars required; `expectedToken` int64 optional (CAS guard) | 200: `revokedToken`, `newTokenFloor` (spelled as in the `LockOutcome` record, [C2 §2.3](C2-java-api.md#ct2-records); the token is **advanced**, so the evicted holder is fenced out at the resource, not merely forgotten here), `revocationId` | 404 (not held), 409 `FENCED_OUT` if `expectedToken` stale, 400 `VALIDATION_FAILED` |

**Failure mode L7 guards against:** an operator or a worker reading `held=false` and concluding it is
safe to act. The lease may expire between response and action; only the fenced write is authoritative.
**Failure mode L8 guards against:** revoke that merely deletes the row. Without advancing the token,
the old holder's in-flight write still exceeds the resource fence and lands.

## 3.4 Payment-resource surface {#ct3-pay}

Runs in a process separate from the lock server, against `paydb`. It trusts no lock state - only the
token it is handed and the fence it has stored (FR-15). This is enforcement point **(a)**.

| # | Method + path | Request | Response 2xx | Codes |
|---|---|---|---|---|
| P1 | `GET /v1/payouts?state=PENDING` | query `state` enum (one of the eight `payout.state` values), `accountId` optional, `limit` int 1…500 default 100, `cursor` opaque | 200: `items[]` of `payoutId`, `accountId`, `amountMinor` int64, `currency` ISO-4217, `state`, `attemptCount`, `createdAt`; `nextCursor?` | 400 `VALIDATION_FAILED` |
| P2 | `POST /v1/payouts/{payoutId}/claim` | `X-Fencing-Token`, `X-Owner-Id`, `X-Idempotency-Key`; body `expectedState` = `PENDING` (or `CLAIMED` for recovery re-claim) | 200: `payoutId`, `state`=`CLAIMED`, `claimToken`, `accountFence` (post-update), `attemptCount`, `idempotencyKey` | 409 `FENCED_OUT`, 409 `PAYOUT_NOT_CLAIMABLE`, 422 `INSUFFICIENT_FUNDS`, 400 `INVALID_TOKEN` |
| P3 | `POST /v1/payouts/{payoutId}/post` | headers as P2; body `railReference` string required, `submissionId` uuid required, `railOutcome` = `ACKED` | 200: `payoutId`, `state`=`POSTED`, `entries[]` (2 balanced rows: `entryId`, `direction` DEBIT/CREDIT, `amountMinor`, `fence`), `balanceMinor` after | 409 `FENCED_OUT`, 409 `PAYOUT_NOT_CLAIMABLE` (not in `RAIL_ACKED`), 400 `INVALID_TOKEN` |
| P4 | `GET /v1/payouts/{payoutId}` | path only | 200: full payout + `submissions[]` summary. Advisory. | 404 |
| P5 | `GET /v1/accounts/{accountId}` | path only | 200: `accountId`, `currency`, `balanceMinor`, `fence`, `updatedAt`, `ledgerSumMinor` (recomputed, must equal `balanceMinor` per FR-25) | 404 |

**The 409 `FENCED_OUT` body is a contract, not a courtesy.** Every conditional write is
`UPDATE … SET fence = :presented WHERE … AND fence < :presented`; zero rows affected means a newer
holder exists. The response must carry enough to alert on without a database query:

| `details` field | Type | Why it must be there |
|---|---|---|
| `presentedToken` | int64 | What the loser sent. |
| `highestToken` | int64 | The stored fence that beat it - the gap size tells you how long the loser was asleep. |
| `resourceType` | enum `account` / `ledger` / `rail` | Identical value set to the `resource` tag on `lock.fenced.out` and to the `resource` field of the `fenced_out` log event. **[C4 §4.2](C4-observability.md#ct4-metrics) is the source of truth for these three values**; this body follows it. A `payout` value does not exist - a fenced payout write is `resource=account` (the balance row) or `resource=rail` (the high-water mark). |
| `resourceId` | string | `accountId` or `payoutId`. Logged, **never** used as a metric tag (NFR-09). |
| `ownerId` | string | The evicted worker, for the `fenced_out` log event. |

Response emits log `fenced_out` and counter `lock.fenced.out{resource=…}`. A single occurrence is
incident-grade: it means two workers believed they held one lock. The reason the body carries both
tokens is that the alternative - a bare 409 - forces the on-call engineer to reconstruct ordering from
two databases at 03:00.

## 3.5 Rail-proxy surface {#ct3-railproxy}

Enforcement point **(c)**, in a third process. It is the only component permitted to call the rail.

| Method + path | Request | Response 2xx | Codes |
|---|---|---|---|
| `POST /v1/rail/submissions` | `X-Fencing-Token` required, `X-Owner-Id`, `X-Idempotency-Key` (= payout's stable key); body `payoutId` uuid, `accountId` string, `amountMinor` int64 >0, `currency` ISO-4217, `beneficiaryRef` opaque synthetic | 200: `submissionId`, `payoutId`, `presentedToken`, `outcome` = `ACKED`, `railReference`, `submittedAt`, `resolvedAt` | 409 `FENCED_OUT` (`outcome=FENCED`), 409 `DUPLICATE_SUBMISSION`, 409 `RAIL_AMBIGUOUS` (`outcome=TIMEOUT`), 422 `REJECTED`→`VALIDATION_FAILED`, 503 `RAIL_UNAVAILABLE`, 400 `INVALID_TOKEN` |

Admission order, and it is not negotiable:

| Step | Check | On failure |
|---|---|---|
| 1 | `presentedToken > rail_high_water.highest_token` for `accountId`, advanced in the **same** transaction | 409 `FENCED_OUT`, `rail_submission.outcome = FENCED`, **no rail call ever made** |
| 2 | No existing `rail_submission` row for `payoutId` (FR-18) | 409 `DUPLICATE_SUBMISSION`, counter `rail.duplicate.attempted`, log `duplicate_rail_submission_attempted`, pages |
| 3 | Commit the intent row (`outcome` unresolved) **before** forwarding (FR-19) | If the commit fails, 503 `RAIL_UNAVAILABLE` - safe, because nothing was presented |
| 4 | Forward once. Record the outcome. **Never auto-retry** (FR-20) | Timeout → `TIMEOUT` + 409 `RAIL_AMBIGUOUS` |

**Why the high-water mark must be strongly consistent and durable, and why in-memory is not enough.**
The mark is the last line of defence for a side effect nothing can roll back, so it must survive
exactly the events that produce stale writers. A `HashMap` in the proxy fails three ways: a **restart**
(crash, rollout, Autopilot eviction) forgets the mark entirely and the next request from a
long-paused holder with an old token is admitted as if it were the first - re-admitting a stale writer
is the whole failure; **two replicas** each keep their own map, so a stale writer merely needs to land
on the other pod; and a **read replica or async cache** can serve a mark older than the token that
already advanced it, which is the same bug with extra steps. `rail_high_water` therefore lives in
`paydb` (Cloud SQL `dlock-pg-pay`), is read and advanced inside one serialisable-safe conditional
`UPDATE`, and the intent row commits before any network call. Correctness here costs one synchronous
write per submission; that is the price of the guarantee.

## 3.6 Rail-stub surface {#ct3-railstub}

| Method + path | Request | Response | Notes |
|---|---|---|---|
| `POST /submit` | `clientRef` string, `amountMinor` int64, `currency`. **No token header. No idempotency header.** | 200 `{ railReference, acceptedAt }`; 402 declined; connection held open then dropped (injected timeout); occasionally two distinct `railReference` values for the same `clientRef` | Every call is a fresh transfer |

| Config key | Effect | What it simulates |
|---|---|---|
| `rail.stub.latency-ms` | Fixed delay before responding | A rail slow enough that the executor's lease expires mid-flight - the origin of every stale writer in this project |
| `rail.stub.failure-rate` | Fraction returning 5xx / dropping the connection | Ambiguous outcomes at a tunable rate |
| `rail.stub.duplicate-ack-rate` | Fraction where a repeat `clientRef` is accepted again with a *new* reference | Proof that the rail will happily pay twice; the stub is **deliberately non-idempotent** (FR-21) |

The stub knows nothing about locks, tokens, sessions, or fences - that is the point. It stands in for a
third party you cannot modify, cannot instrument, and cannot ask to add a dedupe key. Every safety
property of this system must hold with the stub treated as a hostile, memoryless black box. A project that
"fixed" duplicate payments by making the stub idempotent would have proved nothing.

## 3.7 The ambiguous-outcome contract {#ct3-ambiguity}

A timeout is not a failure. It is the **absence of information** about a side effect that may already
have moved money.

| Question | Answer |
|---|---|
| What a timeout means | The proxy committed an intent row and forwarded exactly one request. The rail may have processed it, may not, and may still be processing it. |
| What the executor **may** assume | That exactly one attempt was presented for this payout (guaranteed by steps 1–3 in [3.5](#ct3-railproxy)), and that the payout is now `RAIL_AMBIGUOUS`. |
| What the executor **may not** assume | That nothing happened. That the money did not move. That releasing the lock or acquiring a newer token makes a second submit safe. |
| What the executor must do | Emit `rail_ambiguous`, `rail.submission{outcome=timeout}`, `payout.execute{outcome=ambiguous}`; release the lock; stop. Resolution is out-of-band - the reconciler (FR-26) classifies, a human or a rail statement resolves. |
| Why the payout is quarantined | `RAIL_AMBIGUOUS` is submittable by **no** worker under **any** token (FR-23). Not "the current holder"; nobody. The quarantine outlives the lease, the session, and the pod. |

**"Submit again to be safe" is the bug this project exists to prevent.** It is the intuitive move, it
feels conservative, and it is exactly backwards: with a non-idempotent rail, a retry after an unknown
outcome converts a *possible* single payment into a *probable* double payment, and the second one is
irreversible and unfenceable - no `WHERE fence <` clause can un-pay a beneficiary. Ambiguity is
resolved by *reading* the rail's record, never by writing to it again. This is also why the token
ordering must be *strict*: `>` and not `>=`. An equal-token retry after a timeout would be admitted.

## 3.8 Timeouts and retry budgets per hop {#ct3-timeouts}

Budgets shrink inward: no hop may wait longer than its caller, or the caller times out first and
manufactures an ambiguity that the inner hop was about to resolve.

| Hop | Connect / read timeout | Retries | Budget rationale |
|---|---|---|---|
| executor → lock-server acquire | 500 ms / 2 s | up to 5, jittered exponential 50 ms→1 s, then `CONTENTION_EXCEEDED` | Bounded and jittered (FR-28). Fail closed - `payment.fencing.enabled` and fail-open are not configurable together (FR-27). |
| executor → lock-server heartbeat | 300 ms / 1 s | 2 within one third of `lock.session.ttl` | Must fail fast: a slow heartbeat is indistinguishable from a lost lease, and the client's local deadline (TTL × (1 − `lock.client.safety-margin` = 0.30)) fires first by design. |
| executor → lock-server renew / release | 500 ms / 2 s | 1 (renew), 0 (release; expiry is the fallback) | Retrying release is pointless - the lease reaps it. |
| executor → payment-resource claim / post | 500 ms / 3 s | **0** on any 409; 1 retry only on 503 or a connect failure | A conditional fenced write is safe to retry *mechanically*, but a 409 means the world moved on; retrying is how a fenced-out worker turns one alert into a storm. |
| executor → rail-proxy submit | 1 s / **rail read timeout + 2 s** | **0. Ever.** | The only hop with a hard zero. Any retry re-enters step 2 and is answered `DUPLICATE_SUBMISSION` - correct but page-worthy, so the executor must not generate it. |
| rail-proxy → rail-stub | 1 s / 8 s (< executor's read timeout) | **0** (FR-20) | The proxy must observe the outcome before its caller gives up; otherwise both sides record ambiguity for one attempt. |
| any → `GET` advisory reads | 300 ms / 1 s | 2 | Advisory; a failed read never blocks a correctness decision. |
| reconciler → both databases | 1 s / 30 s | 3 | Detective control, off the payout path; long reads are acceptable, repairs are not attempted. |

Related: [3.2](#ct3-errors) for per-code retry-safety, `payout.backlog.age.seconds` for the symptom a
too-tight budget produces (work starved, not lost).
