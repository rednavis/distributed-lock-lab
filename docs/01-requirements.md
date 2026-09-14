# 01 — Business requirements

Scope authority: [charter 0.1](00-charter.md#ch-what). Domain model: [02](02-domain-model.md).
All parties are fictional; every number is a labelled assumption ([1.8](#br-assumptions)).

## 1.1 Business context {#br-context}

The PSP is a mid-size payment service provider. Among other things it moves money *out*: a merchant or
end customer is owed funds, a **payout** is created, and a settlement worker submits it to an
**external payment rail** operated by a third party. Once the rail accepts a submission, funds have
left. The PSP does not control the rail, cannot change its API, and cannot roll it back.

The business problem, in business language:

> **A settlement worker that runs twice costs real money.** A duplicate submission sends the same
> payout to the rail a second time. Recovering it means a manual recall request to the rail operator, a
> support conversation with the beneficiary, days of finance time, and — often — an unrecoverable loss
> plus a compliance-reportable reconciliation break. Prevention is orders of magnitude cheaper than
> recovery.

Why the obvious defences do not work here:

| Defence | Why it fails for this operation |
|---|---|
| Database transaction | The rail submission is outside it. The transaction can roll back the ledger; it cannot un-send money. |
| Idempotency key on the rail | The rail does not support one. **This is the whole reason a lock is justified** — see [charter 0.1](00-charter.md#ch-what). If the rail had one, the correct decision would be to use it and build nothing. |
| Conditional single-row update | Sufficient for a balance change, and used for exactly that. It cannot protect a multi-step critical section that includes an external call. |
| "Only run one worker" | A single worker is a single point of failure and does not survive a deploy, an eviction, or a stop-the-world pause. Duplicate execution arises from *retry and recovery*, not from misconfiguration. |

What a duplicate actually looks like in production, and the case this project reproduces on purpose: a
worker holds a lease, is frozen by a long garbage-collection pause or a VM migration, its lease
expires, a second worker legitimately takes over and submits, and then the first worker **thaws and
submits too**, believing no time has passed. The lock service behaved correctly throughout. Only the
resource can stop the second submission, and only if it is given a fencing token.

## 1.2 Stakeholders and actors {#br-actors}

| Actor | Type | Interest | What they can do |
|---|---|---|---|
| **Payouts team** | Internal team; **the committed first customer** | Every payout executes exactly once, with acceptable latency | Runs `payout-executor`; consumes the lock SDK |
| **Platform team** | Internal team; owner of the lock service (this project) | Correctness, availability, low operational toil | Builds and operates lock service, SDK, rail proxy |
| **Finance / reconciliation** | Internal, **the party harmed by a duplicate** | Ledger balances; every rail movement has a matching ledger record | Runs reconciliation; raises breaks; requests recalls |
| **Rail operator** | External third party | Well-formed, non-duplicated submissions | Accepts or rejects submissions; answers lookups; handles recalls slowly and manually |
| **Beneficiary** | External | Receives the correct amount once | Nothing; harmed silently by a duplicate |
| **On-call engineer** | Internal, Platform | Actionable alerts, working runbooks | Break-glass revoke, rollback, incident command of the technical investigation |
| **Compliance / audit** | Internal | Every state change and every break-glass action is attributable | Reads audit logs; requires retention |

Software actors treated as first-class in the use cases: `payout-executor` worker, `lock-server`,
`rail-proxy`, `rail-stub`, `payment-resource`, `reconciler` (a job inside `payment-resource`).

## 1.3 Scope {#br-scope}

**In scope.** Executing a pending payout under a distributed lock; two lock backends behind one
contract; monotonic fencing tokens; two independent enforcement points (Postgres row, rail proxy);
double-entry ledger and balance maintenance; a non-idempotent rail stub with an ambiguity mode;
operator break-glass revoke; reconciliation detection of mismatches; observability, SLOs, alerts,
runbooks; GCP dev deployment as code.

**Out of scope.** Payout *creation*, approval, sanctions screening, fraud scoring, FX, fees, partial
settlement, chargebacks/recalls beyond detection, multi-currency, batch/file rails, customer-facing
APIs, and everything in [charter 0.5](00-charter.md#ch-nongoals).

## 1.4 Use cases {#br-usecases}

Template: actor · precondition · main flow · alternates · failures · postcondition · the invariant it
must not break. `T` denotes the fencing token from the current grant.

### UC-01 Execute a pending payout {#br-uc01}

| | |
|---|---|
| **Actor** | `payout-executor` worker |
| **Precondition** | A payout exists in `PENDING`; its account has sufficient available funds; the lock service is reachable |
| **Main flow** | 1. Worker selects a candidate `PENDING` payout for account `A`. 2. Worker acquires lock `payout:{A}`, receiving token `T`. 3. Worker re-reads the payout inside the lease and confirms it is still `PENDING`. 4. Worker checks the local lease has not expired, then transitions `PENDING → CLAIMED` with a **fenced** update stamping `T`. 5. Worker submits to `rail-proxy` with `T`, the payout id and a stable client reference; state `→ RAIL_SUBMITTED`. 6. Proxy validates `T` against the account's highest seen token *and* that this payout has never been submitted, records the attempt, forwards to the rail. 7. Rail acknowledges; state `→ RAIL_ACKED`. 8. In one paydb transaction: post the double-entry ledger rows and update the balance, fenced on `T`; state `→ POSTED`. 9. Worker releases the lock. |
| **Alternates** | (a) Payout no longer `PENDING` at step 3 — another worker completed it; release and move on, no error. (b) Insufficient available funds — `→ FAILED`, reason recorded, no rail call. (c) Rail rejects (business decline) — `→ FAILED`, submission recorded as declined, no ledger movement. |
| **Failures** | Lock not acquired within the wait budget → skip this payout, retry later ([UC-02](#br-uc02)). Any fenced rejection → abort immediately, do not retry under this token. |
| **Postcondition** | Exactly one rail submission exists for the payout; ledger rows sum to zero; balance equals the ledger sum; lock released |
| **Must not break** | INV-01, INV-02, INV-03, INV-04 |

### UC-02 Two workers race for the same payout {#br-uc02}

| | |
|---|---|
| **Actor** | Two `payout-executor` workers, `W1` and `W2` |
| **Precondition** | Both have selected the same `PENDING` payout for account `A` (a normal outcome of polling, redelivery, or an autoscale event) |
| **Main flow** | 1. Both attempt `acquire("payout:{A}")`. 2. Exactly one — say `W1` — is granted with token `T1`. 3. `W2` receives *not acquired* and either waits within its budget or skips the payout with a jittered backoff. 4. `W1` executes [UC-01](#br-uc01). 5. `W2`, on a later poll, re-reads the payout, sees a terminal state, and does nothing. |
| **Alternates** | `W2` waits, `W1` releases, `W2` acquires with `T2 > T1`, re-reads state, finds `POSTED`, releases without acting. **The re-read after acquiring is mandatory** — holding the lock does not imply there is still work to do. |
| **Failures** | If `W2` acted without re-reading, it would submit a second payment. Prevented by the state check *and*, if that check is wrong, by the proxy's per-payout submission guard. |
| **Postcondition** | One submission, one set of ledger rows |
| **Must not break** | INV-01, INV-02 |

### UC-03 A worker pauses past its lease and returns {#br-uc03}

**This is the centrepiece experiment** ([charter SC-03](00-charter.md#ch-success)).

| | |
|---|---|
| **Actor** | `W1` (frozen), `W2` (takes over), `payment-resource`, `rail-proxy` |
| **Precondition** | `W1` holds `payout:{A}` with token `T1`; a pause longer than the lease is induced (`SIGSTOP`, simulating a stop-the-world GC or VM migration) |
| **Main flow** | 1. `W1` freezes mid-critical-section, after claiming and before submitting. 2. Its lease expires server-side; the lock service is behaving correctly. 3. `W2` acquires with `T2 > T1` and executes the payout to `POSTED`. 4. `W1` thaws with no knowledge that time passed and attempts its next step. 5. **Every mutation `W1` attempts is rejected:** the fenced Postgres update matches zero rows because the stored fence is now `T2`; the rail proxy refuses because `T1` is below the account's highest seen token. 6. `W1` observes a lock-lost condition, aborts, and logs a `fenced_out` event with the presented and highest tokens. |
| **Alternates** | If the client-side conservative expiry fires before the pause ends, `W1` aborts on its own without contacting anything. That is a *liveness* optimisation, not the safety mechanism, and it must not be relied on. |
| **Failures** | With fencing disabled (the negative control), `W1` succeeds: a second rail submission and a double debit. The run is captured and committed as the proof that mutual exclusion alone is insufficient. |
| **Postcondition** | Fencing on: one submission, ledger balanced, one `fenced_out` event, one alert. Fencing off: corrupted ledger, captured as evidence |
| **Must not break** | INV-01, INV-02, INV-03, INV-05 |

### UC-04 A worker dies mid-payout {#br-uc04}

| | |
|---|---|
| **Actor** | `W1` (killed), `W2`, `reconciler` |
| **Precondition** | `W1` holds the lock and has reached some intermediate state: `CLAIMED`, `RAIL_SUBMITTED`, or `RAIL_ACKED` |
| **Main flow** | 1. `W1`'s process terminates (`SIGKILL`, pod eviction, node failure). 2. Its session stops heartbeating; the lease expires; the lock becomes acquirable. 3. `W2` acquires with a higher token and re-reads the payout. 4. Recovery is **per state**: `CLAIMED` → safe to submit, because the proxy's submission record proves nothing was sent; `RAIL_SUBMITTED` with no recorded outcome → **`RAIL_AMBIGUOUS`, never resubmit** ([UC-05](#br-uc05)); `RAIL_ACKED` → the rail moved the money and only the ledger posting is missing, so `W2` completes the posting and reaches `POSTED`. |
| **Alternates** | `W1` died between the rail returning and the outcome being recorded. Indistinguishable from a timeout, therefore treated as ambiguous. |
| **Failures** | If recovery treated `RAIL_SUBMITTED` as retryable, this use case *is* the duplicate-payment bug, with no fencing violation involved. Correct recovery is a state-machine property, not a locking property — worth saying out loud. |
| **Postcondition** | The payout is in `POSTED`, `FAILED`, or `RAIL_AMBIGUOUS`; never in a state that invites a blind retry |
| **Must not break** | INV-02, INV-03 |

### UC-05 The rail times out with an ambiguous outcome {#br-uc05}

| | |
|---|---|
| **Actor** | `rail-proxy`, `rail-stub`, `reconciler`, finance |
| **Precondition** | A submission was forwarded to the rail and the call timed out or failed with an indeterminate error |
| **Main flow** | 1. The proxy has already durably recorded the submission attempt **before** forwarding, so an attempt is never lost. 2. On timeout it records the outcome as `TIMEOUT`. 3. The payout moves to `RAIL_AMBIGUOUS`. 4. **No worker may resubmit, at any time, under any token.** 5. The reconciler resolves it by a read-only lookup against the rail using the client reference: settled → `RAIL_ACKED`, then the ledger posting completes to `POSTED`; definitively absent → `FAILED`. 6. Unresolvable within the resolution SLA → escalate to finance; an operator may move it to `ABANDONED` with a reason. |
| **Alternates** | The lookup itself is unavailable — the payout stays `RAIL_AMBIGUOUS` and ages; the age of the oldest ambiguous payout is an alerting SLI. |
| **Failures** | Automatic retry on timeout is the single most expensive mistake available in this domain, and it is the default behaviour of most HTTP clients. Retries must be **off** for the submit call, explicitly and with a comment. |
| **Postcondition** | The payout is never resubmitted; it is resolved by evidence or escalated |
| **Must not break** | INV-02, INV-03 |

### UC-06 An operator force-revokes a stuck lock {#br-uc06}

| | |
|---|---|
| **Actor** | On-call engineer |
| **Precondition** | A lock on `payout:{A}` is held and not progressing — typically an unreachable holder whose session is somehow still alive, blocking every payout for that account |
| **Main flow** | 1. Operator inspects the lock: holder, token, age, waiter count. 2. Operator consults the runbook, which requires a recorded reason and identity. 3. Operator calls force-revoke. 4. The revoke **advances the token**, so the previous holder is fenced out at both enforcement points by construction. 5. An audit event and an alert are emitted. 6. The next acquirer takes over via [UC-04](#br-uc04) recovery. |
| **Alternates** | The holder is mid-rail-submission. **Revoking does not un-send money.** The runbook states this explicitly: revoke restores availability, it does not undo side effects, and the payout may land in `RAIL_AMBIGUOUS`. |
| **Failures** | A revoke that reuses or lowers the token would silently disable fencing for that key — the worst possible outcome. INV-04 forbids it and a test asserts it. |
| **Postcondition** | The lock is acquirable; the old holder can mutate nothing; an audit record exists |
| **Must not break** | INV-04, INV-05, INV-06 |

### UC-07 Reconciliation detects a mismatch {#br-uc07}

| | |
|---|---|
| **Actor** | `reconciler`, finance |
| **Precondition** | Scheduled run over a completed window |
| **Main flow** | 1. Compare, per payout: recorded submissions, rail outcomes, ledger rows, and payout state. 2. Assert the ledger balances globally and per payout. 3. Classify every discrepancy: duplicate submission, submission with no ledger, ledger with no submission, balance ≠ ledger sum, ambiguous beyond SLA. 4. Emit one structured event per class with counts and a bounded sample of ids. 5. A duplicate submission or a ledger imbalance **pages**; ageing ambiguity **tickets**. |
| **Alternates** | Zero discrepancies is the expected result and is still reported, so that a silently broken reconciler is distinguishable from a healthy system. |
| **Failures** | The reconciler must never *repair* automatically in the project. Automated repair of a money movement without human judgement is how a small break becomes a large one. |
| **Postcondition** | Every discrepancy is either resolved or an open item with an owner |
| **Must not break** | INV-03, and it is the detective control behind INV-01 and INV-02 |

### UC-08 The lock service is unavailable {#br-uc08}

| | |
|---|---|
| **Actor** | `payout-executor`, on-call |
| **Precondition** | Acquire calls fail or time out — backend down, leader election, network partition, or the etcd cluster is mid-election after an Autopilot eviction |
| **Main flow** | 1. Acquire fails after bounded retries with jittered backoff. 2. **The worker fails closed: it does not execute the payout.** 3. Payouts accumulate as `PENDING` and their age grows. 4. Workers keep retrying at a bounded rate; no thundering herd. 5. Availability and backlog-age SLIs degrade; alerting fires on the symptom (backlog age), not the cause. |
| **Alternates** | Holders already inside a lease continue until their conservative local expiry, then abort. A brief outage therefore costs throughput, not correctness. |
| **Failures** | Failing **open** — executing without a lock because the lock service is down — converts an availability incident into a money incident. It must be impossible to configure, not merely discouraged. |
| **Postcondition** | No payout executes without a valid grant; the backlog drains when the service returns |
| **Must not break** | INV-01, INV-07 |

## 1.5 Functional requirements {#br-fr}

Atomic and testable. "Verified by" is elaborated in [07](07-correctness-and-testing.md).

| # | Requirement | From |
|---|---|---|
| FR-01 | The lock service shall grant a named lock to at most one holder at a time. | UC-02 |
| FR-02 | Every grant shall carry a fencing token that is strictly increasing per key across the service's lifetime. | UC-03 |
| FR-03 | Grants shall be leases with a server-side expiry; an unrenewed lease shall become acquirable by others. | UC-04 |
| FR-04 | A client shall hold one session, kept alive by heartbeat, to which all its locks attach; session death shall release them. | UC-04 |
| FR-05 | Release shall be a compare-and-delete against the holder's own grant; releasing another holder's lock shall be inexpressible in the API. | UC-06 |
| FR-06 | Renew shall extend a lease only for a still-valid grant, and shall fail explicitly otherwise. | UC-03 |
| FR-07 | An inspect operation shall report holder, token, acquisition time, expiry and waiter count, and shall be documented as never usable for a correctness decision. | UC-06 |
| FR-08 | A force-revoke operation shall exist, shall require an operator identity and a reason, shall advance the token, and shall emit an audit event and an alert. | UC-06 |
| FR-09 | Two interchangeable lock backends shall be provided — PostgreSQL and etcd — selected by configuration, behind one contract with identical observable semantics. | charter 0.4 |
| FR-10 | The etcd backend shall use the key's `ModRevision` as the fencing token. | charter |
| FR-11 | The client SDK shall maintain a conservative local lease deadline computed from a monotonic clock and the send time of the heartbeat, discounted by a safety margin. | UC-03 |
| FR-12 | The SDK shall raise a lock-lost signal as soon as the local deadline passes, so application code can abort before acting. | UC-03 |
| FR-13 | The SDK shall expose an explicit "still held?" check that application code must call before any protected side effect, documented as a liveness aid and not a safety mechanism. | UC-03 |
| FR-14 | A worker shall re-read the payout's state after acquiring the lock and before acting. | UC-02 |
| FR-15 | Every mutation of a payout, ledger or balance shall be conditional on the presented token exceeding the stored fence, and shall stamp the new fence in the same statement. | UC-03 |
| FR-16 | A rejected fenced write shall be a first-class outcome: distinct error, structured log event with key, presented token and stored token, and a counter. | UC-03 |
| FR-17 | The rail proxy shall reject any submission whose token is not the highest yet seen for that account. | UC-03 |
| FR-18 | The rail proxy shall reject any second submission for a payout that already has a recorded attempt, regardless of token. | UC-04 |
| FR-19 | The rail proxy shall durably record a submission attempt **before** forwarding it to the rail. | UC-05 |
| FR-20 | The rail proxy shall record every outcome as acknowledged, declined, or unknown, and shall never retry a submit automatically. | UC-05 |
| FR-21 | The rail stub shall be non-idempotent by design and shall offer injectable timeout, decline, delay and ambiguity behaviours. | UC-05 |
| FR-22 | The payout state machine shall implement exactly the states and transitions in [02 §2.3](02-domain-model.md#dm-payout-fsm), rejecting any transition not listed. | UC-01 |
| FR-23 | A payout in the ambiguous state shall not be submittable by any worker under any token. | UC-05 |
| FR-24 | Recovery of an abandoned in-flight payout shall be decided by its state and the proxy's submission record, never by elapsed time alone. | UC-04 |
| FR-25 | Every money movement shall be recorded as balanced double-entry ledger rows, and the account balance shall equal the sum of its ledger rows. | UC-01 |
| FR-26 | A reconciliation job shall compare payout state, submissions, rail outcomes and ledger rows, and shall classify and report every discrepancy without repairing it. | UC-07 |
| FR-27 | A worker that cannot acquire a lock shall not execute the payout, and failing open shall not be configurable. | UC-08 |
| FR-28 | Acquire retries shall be bounded and jittered. | UC-08 |
| FR-29 | The fencing enforcement at the resource and at the proxy shall be independently disableable **for the experiment only**, defaulting to enabled, and the disabled state shall be logged loudly at startup. | UC-03 |
| FR-30 | Every state transition, submission and break-glass action shall be attributable in an audit log to an actor and a token. | UC-06 |

## 1.6 Non-functional requirements {#br-nfr}

Targets are project targets against the assumed workload in [1.8](#br-assumptions), not commitments.

| # | Category | Requirement and target | How verified |
|---|---|---|---|
| NFR-01 | Availability | Lock acquire success ratio ≥ 99.9% over a rolling 28 days, excluding legitimate "held by another" outcomes | SLI in [06](06-observability-and-slo.md); measured, reported against the error budget |
| NFR-02 | Availability | An etcd leader election costs ≤ 2 s of shard unavailability; a **budgeted** allowance of elections per month is declared and measured, not assumed to be zero | Autopilot eviction experiment; SLO report |
| NFR-03 | Latency | p50 acquire ≤ 10 ms, p99 ≤ 50 ms, same region, uncontended, etcd backend | Benchmark; the PostgreSQL backend is measured and expected to be worse, and the gap is a deliverable |
| NFR-04 | Latency | End-to-end payout execution p99 ≤ 2 s with the rail stub at its nominal latency | Load test |
| NFR-05 | Durability | No committed grant or fence value is lost across a backend primary failover | Regional Cloud SQL failover and etcd member-kill experiments |
| NFR-06 | Correctness | Zero safety events across the whole test corpus; a single fenced-out write is treated as an incident-grade signal | [07](07-correctness-and-testing.md); invariant checks |
| NFR-07 | Correctness | Every invariant in [1.7](#br-invariants) has an automated check that fails when the invariant is deliberately broken | Mutation-style negative controls |
| NFR-08 | Observability | Every state transition and every rejection emits a structured JSON event with a stable `event` name; alerts filter on the name, never the message text | [C4 §4.5](contracts/C4-observability.md#ct4-logs) |
| NFR-09 | Observability | **No metric is tagged with a lock key, payout id, account id, or token value.** Unbounded identifiers live in logs and traces only | CI cardinality check |
| NFR-10 | Observability | Every alert is actionable, novel, and linked to a runbook section; symptoms page, causes ticket | Alert review table; game day |
| NFR-11 | Operability | Every alert has a runbook section, and every runbook section has been executed at least once during a game day with detection latency recorded | Game-day results table |
| NFR-12 | Security | No real personal or payment data; synthetic fixtures only; no secret in source or in Terraform state committed anywhere | Review; the repo is not initialised yet ([charter 0.8](00-charter.md#ch-deferred)) |
| NFR-13 | Security | Break-glass actions are attributable and audited; least-privilege service accounts per workload | IAM inventory in [05](05-infrastructure.md) |
| NFR-14 | Cost | The dev environment costs under a stated daily cap, has a budget alert configured **before** the first apply, and destroys to zero billable resources | Billing export; teardown check |
| NFR-15 | Portability | The whole system runs locally with containers and no cloud account | `docker compose` path in [08](08-operations.md) |
| NFR-16 | Maintainability | One build, one version catalog, formatting and static analysis enforced in CI; `lock-api` has zero third-party dependencies | CI |

## 1.7 Correctness invariants {#br-invariants}

Stated separately because they are the project. Each holds **always**, under arbitrary process pauses,
crashes, message loss, reordering, duplication, and backend failover. Where each is enforced is
[02 §2.5](02-domain-model.md#dm-invariant-enforcement).

| # | Invariant | Formally | Violation looks like |
|---|---|---|---|
| **INV-01** | At most one worker may successfully mutate a given payout at a time | For any payout `p`, the set of successful mutations of `p` is totally ordered by non-decreasing fence, and every one of them was performed under the currently-highest token for `p`'s account | Two workers both advance the same payout; interleaved states |
| **INV-02** | At most one rail submission may ever succeed for a payout | For every payout `p`, `|{s : s is a submission for p with outcome ∈ {ACKED, DECLINED}}| ≤ 1`, over all time | Duplicate payment. The failure the project exists to prevent |
| **INV-03** | The ledger balances | For every transaction group `g`, `Σ signed_amount(g) = 0`; and for every account `a`, `balance(a) = Σ signed_amount(entries of a)` | Money invented or destroyed; a finance break |
| **INV-04** | Fencing tokens never repeat and never go backwards for a key | For a key `k`, tokens issued form a strictly increasing sequence, **including across restart, failover, restore from backup, and force-revoke** | A restored backend reissues token 41 after 90 was used; every downstream check silently stops protecting anything |
| **INV-05** | A resource never accepts a write below its stored fence | For every fenced row, `fence` is non-decreasing over time, and an update with `token ≤ fence` affects zero rows | The stale holder's write lands |
| **INV-06** | Mutual exclusion of grants | For a key `k` and any instant `t`, at most one grant is valid at `t` from the service's own view | The lock service itself is broken — distinct from INV-01, which survives even when this fails |
| **INV-07** | No side effect without a grant | Every rail submission and every fenced write is causally preceded by a grant whose token it presents | A code path bypasses the lock, or fails open |
| **INV-08** | Terminal states are terminal | `POSTED`, `FAILED` and `ABANDONED` have no outgoing transitions | A completed payout is re-executed by a "cleanup" job |

**INV-04 is the one people get wrong.** It is not a property of normal operation; it is a property of
*restore*. Restoring the lock backend from a backup taken before the current token was issued reissues
tokens that downstream resources have already seen and rejected — after which fencing is decoration.
The consequences for backup and restore procedure belong in [08](08-operations.md) and are called out
in the [risk register](09-risks.md).

## 1.8 Assumptions {#br-assumptions}

All invented for the project. None is measured production data.

| # | Assumption |
|---|---|
| A-01 | 2,000 payouts per hour at peak; 300 sustained. Small on purpose: the project is about correctness, not throughput. |
| A-02 | ~50,000 accounts; ~5,000 distinct live lock keys at peak. |
| A-03 | Payout critical section: 200–800 ms nominal, dominated by the rail call; tail to 30 s on rail slowness. |
| A-04 | Proposed lease TTL 15 s, heartbeat every ~5 s, client safety margin 30% — to be **measured**, not settled by this document ([charter D-04](00-charter.md#ch-deferred)). |
| A-05 | 12 executor pods at peak, autoscaled, subject to eviction at any time. |
| A-06 | Single currency (minor units, integer), single rail, no FX, no fees, no partial settlement. |
| A-07 | The rail: no idempotency key, no CAS, p50 300 ms, p99 3 s, timeout budget 10 s, ~0.5% ambiguous outcomes injected. |
| A-08 | The rail **does** offer a read-only lookup by client reference, eventually consistent within minutes. Without this, ambiguity would be unresolvable by software and every case would be a phone call. |
| A-09 | Funds sufficiency is *checked* at claim time and the ledger is posted **after** the rail acknowledges. A production system would reserve funds first; the project's ordering is deliberate because it makes the ambiguous case maximally instructive — money possibly moved, no ledger row yet ([02 §2.8](02-domain-model.md#dm-simplifications)). |
| A-10 | Reconciliation runs hourly over a closed window; ambiguity resolution SLA is 4 hours before escalation. |
| A-11 | One region (`europe-central2`); all components co-located; no cross-region traffic. |
| A-12 | Workers, lock service, proxy and rail stub all run in one GKE Autopilot cluster; the lock backends are managed Cloud SQL / an in-cluster etcd StatefulSet ([05](05-infrastructure.md)). |
| A-13 | No authentication between internal components in the project; network-level isolation only. **This is the assumption that disqualifies the project from production.** |
| A-14 | One first customer, one lock key pattern (`payout:{account_id}`), one protected resource type. |
| A-15 | The lock key is per **account**, not per payout: the protected thing is the account's payout stream — its balance row, its ledger and its submissions — so two payouts for one account are never in flight together. Per-payout keys would permit concurrent balance races and lose the ordering the rail expects. |

## 1.9 Deferred requirements {#br-deferred}

Captured so they are not rediscovered as ideas later. None is in scope now.

| # | Requirement | Trigger to reconsider |
|---|---|---|
| DR-01 | AuthN/AuthZ and per-tenant namespaces on the lock API | A second customer |
| DR-02 | Quotas, object-size limits, and chargeback | Any sign of the service being used as a general-purpose store |
| DR-03 | Key-space sharding across consensus groups | Measured acquire throughput within 3× of the single-shard ceiling |
| DR-04 | Shared/exclusive modes and reentrancy accounting | A customer whose critical section is genuinely read-mostly |
| DR-05 | FIFO fairness with starvation bounds | Observed starvation of a hot key |
| DR-06 | Multi-region and the semantics of a partitioned region | A regulatory or availability requirement, with the latency cost accepted in writing |
| DR-07 | A second-language SDK and a published gRPC contract | A non-JVM customer |
| DR-08 | Automatic resolution of ambiguous payouts, and recall workflow | The reconciler has a track record and finance signs off |
| DR-09 | Multi-currency, FX, fees, partial settlement, chargebacks | Never, in this project |
| DR-10 | Data retention, PII classification, and access review | Any non-synthetic data, which is prohibited here |

## 1.10 Traceability {#br-trace}

| Requirement group | Specified in |
|---|---|
| FR-01…FR-10 (lock semantics, backends, tokens) | [03 architecture](03-architecture.md#arch-tech), [C2 §2.2 API](contracts/C2-java-api.md#ct2-lockservice), [C1 §1.2 schema](contracts/C1-database-schemas.md#ct1-lockdb) |
| FR-11…FR-13 (SDK, sessions, conservative expiry) | [03](03-architecture.md), [C2 §2.6](contracts/C2-java-api.md#ct2-sdk) |
| FR-14…FR-16 (fenced writes at the resource) | [02 §2.5](02-domain-model.md#dm-invariant-enforcement), [C1 §1.6](contracts/C1-database-schemas.md#ct1-fenced) |
| FR-17…FR-21 (rail proxy and stub) | [02 §2.6](02-domain-model.md#dm-token-journey), [C3 §3.5](contracts/C3-http-surfaces.md#ct3-railproxy) |
| FR-22…FR-24 (payout state machine and recovery) | [02 §2.3](02-domain-model.md#dm-payout-fsm) |
| FR-25…FR-26 (ledger, reconciliation) | [02 §2.2](02-domain-model.md#dm-entities), [C1 §1.5](contracts/C1-database-schemas.md#ct1-paydb) |
| FR-27…FR-28 (fail closed, backoff) | [03](03-architecture.md), [C5 §5.1](contracts/C5-config-build-and-naming.md#ct5-config) |
| FR-29…FR-30 (experiment flags, audit) | [07](07-correctness-and-testing.md), [C4 §4.5](contracts/C4-observability.md#ct4-logs) |
| NFR-01…NFR-05 (availability, latency, durability) | [06](06-observability-and-slo.md), [05](05-infrastructure.md) |
| NFR-06…NFR-07 (correctness) | [07](07-correctness-and-testing.md) |
| NFR-08…NFR-11 (observability, operability) | [06](06-observability-and-slo.md), [08](08-operations.md) |
| NFR-12…NFR-13 (security) | [05](05-infrastructure.md), [09](09-risks.md) |
| NFR-14 (cost) | [05](05-infrastructure.md), [10](10-delivery-plan.md) |
| NFR-15…NFR-16 (portability, maintainability) | [03](03-architecture.md), [08](08-operations.md) |
| INV-01…INV-08 | [02 §2.5](02-domain-model.md#dm-invariant-enforcement) (enforcement) and [07](07-correctness-and-testing.md) (proof) |
| UC-01…UC-08 | [02 §2.3](02-domain-model.md#dm-payout-fsm), [07](07-correctness-and-testing.md), [08](08-operations.md) |
