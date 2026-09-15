# 07 — Correctness and test strategy {#test}

Scope: how every claim in [01 §1.7](01-requirements.md#br-invariants) (INV-01…INV-08) is
verified, and what "verified" is allowed to mean. Names come from the contract set — metrics
[C4 §4.2](contracts/C4-observability.md#ct4-metrics), error codes
[C3 §3.2](contracts/C3-http-surfaces.md#ct3-errors), kill switches
[C5](contracts/C5-config-build-and-naming.md#ct5-killswitches). This file specifies; the implementer writes code.

## 7.1 Why the usual pyramid is the wrong shape here {#test-pyramid}

The standard pyramid assumes bugs live inside functions. Here they live **between** processes, in the
*schedule*: which message arrived first, how long a thread was frozen, whose clock drifted. A
95%-unit-covered lock service can still lose money, because no unit test expresses "client A was
paused for 7 s and came back believing it still held the lease." So the shape inverts. ASSUMPTION —
target effort split, not measured: unit **15%** (classic: 80%), integration **30%**, fault injection
**25%**, deterministic simulation plus linearizability **30%**. **Opinion:** unit tests here buy build
speed, not correctness — never let coverage stand in for proof.

| Layer | Verifies | Tooling | Cannot catch |
|---|---|---|---|
| Unit | Lease arithmetic, safety margin, FSM transition guards ([02 §2.3](02-domain-model.md#dm-payout-fsm)), token comparison, error mapping | JUnit 5 | Anything concurrent |
| Integration | Real acquire/renew/release against real PostgreSQL 16 and real etcd 3.6; the fenced `UPDATE` ([C1](contracts/C1-database-schemas.md#ct1-fenced)); the four HTTP surfaces | Testcontainers | Adversarial schedules |
| Fault injection | Behaviour under pause, partition, failover, clock skew (§7.6) | scripted, then game day | Rare interleavings you did not think of |
| Deterministic simulation | Interleavings you did not think of, reproducibly (§7.4) | in-repo harness | Real-clock and real-IO effects |
| Linearizability check | Whether the observed history was ever explainable by a single-threaded lock (§7.5) | Knossos or Porcupine | Nothing — but it is slow and offline |

## 7.2 The invariant to get right {#test-invariant}

This is the most important idea in the file. The tempting assertion is **"at most one client believes it holds the lock."** It is wrong, and it fails
on a *correct* system. During a pause, client A is frozen holding a valid-looking handle while its lease
expires and B legitimately acquires; for that interval two processes genuinely believe they hold the
lock, and no engineering removes the interval — a frozen process cannot be informed of anything. A test
asserting single belief goes red on a perfectly behaving system, and the usual reaction (loosen it, add
a sleep) destroys the only test that mattered. The correct assertion is:

> **At most one client can successfully *mutate the resource*.**

Belief is unbounded; *effect* is what the token bounds. Checkably:

| Assert this | Do not assert this | Enforced by |
|---|---|---|
| Successful mutations of a payout are totally ordered by non-decreasing fence, each under the highest token (INV-01) | Only one holder exists at a time | `UPDATE … WHERE fence < :token` — [C1 §1.6](contracts/C1-database-schemas.md#ct1-fenced) |
| At most one submission per payout ever reaches `ACKED`/`DECLINED` (INV-02) | The stale client never *tries* | Persisted high-water mark — [C3 §3.5](contracts/C3-http-surfaces.md#ct3-railproxy) |
| Tokens strictly increase per key, across restart, failover, restore, force-revoke (INV-04) | Tokens are contiguous | [C1 §1.7](contracts/C1-database-schemas.md#ct1-seq); etcd `ModRevision` |
| A write with `token ≤ fence` affects zero rows (INV-05) | The stale write never arrives | same fenced `UPDATE` |
| Mutual exclusion of *grants*, from the service's own view (INV-06) | Mutual exclusion of *beliefs* | lock backend |

INV-06 is the lock service's own contract, tested against the service; INV-01/02/05 are the *system's* and survive even when INV-06 fails. Keeping them separate is what makes §7.3 intelligible.

## 7.3 The SIGSTOP experiment {#test-fencing}

The centrepiece deliverable, runnable locally from **T-042**. **Preconditions:** local compose stack
(lockdb, paydb, lock-server, payment-resource, rail-proxy,
rail-stub, one executor per client). One `account` with a known synthetic balance, one `payout` in
`PENDING` (ASSUMPTION amount 100 minor units). Short lease TTL (ASSUMPTION 5 s) so the window is
observable; rail-stub delay well under the TTL. **Steps:**

| # | Action | Purpose |
|---|---|---|
| 1 | Start executor **A** on the payout with a work duration (ASSUMPTION 20 s) longer than the TTL | A will overrun its lease |
| 2 | Record A's granted token `tA` from the `lock_granted` event ([C4](contracts/C4-observability.md#ct4-logs)) | the artifact under test |
| 3 | `kill -STOP <A_pid>` about 1 s in — this *is* a stop-the-world pause | freeze mid-critical-section |
| 4 | Wait past TTL + server grace | A's lease expires while frozen |
| 5 | Start executor **B** on the same payout; it acquires, token `tB > tA`, and completes | legitimate new holder |
| 6 | `kill -CONT <A_pid>` | A resumes with no idea time passed |
| 7 | Read the account balance, `ledger_entry` rows, `rail_submission` rows, and the rail stub's received-request log | the verdict |

**Run 1 — fencing disabled** (`payment.fencing.enabled=false`, `rail.proxy.fencing.enabled=false`,
[C5](contracts/C5-config-build-and-naming.md#ct5-killswitches)). Expected, sketched:

```
B: lock_granted token=tB | rail submit p-1 -> stub receives #1 | ledger+balance under tB
A: (no new grant)        | rail submit p-1 -> stub receives #2  <-- DUPLICATE PAYMENT
A: ledger+balance applied under stale tA                        <-- CORRUPTED LEDGER
```

Two rail submissions for one payout against a deliberately non-idempotent rail, a second set of ledger
rows, a balance no longer equal to the sum of its entries (INV-02 and INV-03 violated). **The lock
service did nothing wrong in this run** — it granted one lease at a time and expired A's exactly on
schedule. That is why this bug class survives review: every component is correct, the composition is not.

**Run 2 — fencing enabled** (defaults). Expected rejection at **both** points, independently:

```
rail-proxy   409 FENCED_OUT   presented=tA highest=tB   (or DUPLICATE_SUBMISSION, per FR-18)
resource     0 rows affected -> 409 FENCED_OUT          presented=tA stored=tB
metrics      lock.fenced.out +1        rail.duplicate.attempted +1 (if the proxy path is exercised)
rail stub    exactly ONE received request for clientRef=p-1
```

**Capture** (the published evidence, committed as fixtures): both runs' `fenced_out` / `rail_ambiguous` events, the rail stub's
request count, before/after balance and ledger rows, the two counters from
[C4 §4.4](contracts/C4-observability.md#ct4-zero), the trace showing `lock.token` differing across spans.

**A real pass versus a pass for the wrong reason.** Run 2 means nothing unless run 1 *failed*:

| Looks like a pass | Actually | How to rule it out |
|---|---|---|
| No duplicate in run 1 | A never reached the rail — it aborted on its own liveness check, or the pause was too short/long | Assert run 1 produces exactly 2 rail requests. A failing run 1 is a **required** step. |
| Fenced in run 2 | Rejection came from the payout FSM (`PAYOUT_NOT_CLAIMABLE`) or an idempotency key, not the fence | Assert the error code is `FENCED_OUT`/`DUPLICATE_SUBMISSION` *and* `lock.fenced.out` incremented |
| Both points rejected | Only one is actually wired; the other never saw the request | Run with each switch off in turn — each point must reject on its own |
| Green in CI | Timing luck | Repeat N times (ASSUMPTION 20) and require identical outcomes |

## 7.4 Deterministic simulation — specification {#test-dst}

A single-threaded harness driving the lock core over a simulated network and clock. **Specified here;
implemented at T-043/T-044.** Not chaos — chaos finds bugs you cannot reproduce; DST finds bugs you re-run
byte-for-byte from a seed.

| Simulated | Knobs (all seed-driven) |
|---|---|
| Message reorder | probability, max displacement in the queue |
| Message drop | probability, per-link |
| Message duplication | probability, duplicate count |
| Message delay | distribution, per-link; tail-heavy option |
| Process pause | victim, start step, duration in simulated ms (the SIGSTOP analogue) |
| Per-node clock skew | offset and drift rate per node; monotonic and wall clocks skewed independently |
| Node restart | with and without state loss (must not break INV-04) |

**Rules.** Time advances only when the harness advances it — no `Thread.sleep`, no wall clock. Every run prints
its seed first line and on failure; a failing seed becomes a permanent regression test committed by number.
After **every** step the invariant checker runs over the whole simulated world: INV-01, INV-04, INV-05, INV-06
always; INV-02/INV-03 whenever the resource model is in play. Failure prints the minimal step trace, then a
shrink pass narrows the schedule. **Seed set:** 1…N nightly (ASSUMPTION N = 10 000), a small fixed set on every
push (§7.10), plus every historical failing seed forever. *Why:* a heisenbug reproduced from a seed is fixable;
the same bug found by chaos is just a flaky test.

## 7.5 Linearizability checking {#test-linearizability}

A recorded **history** is a flat list of invocation/response events per client:
```
{ "process":3, "op":"acquire", "key":"payout:p-1", "type":"invoke", "t_ns":1012 }
{ "process":3, "op":"acquire", "key":"payout:p-1", "type":"ok", "token":91, "t_ns":4880 }
{ "process":7, "op":"acquire", "key":"payout:p-1", "type":"fail", "code":"LOCK_CONTENDED" }
{ "process":3, "op":"release", "key":"payout:p-1", "type":"info" }   // timeout: outcome TIMEOUT
```

Three event types matter: `ok`, `fail` (definitely did not happen), `info` (**unknown** — a timeout is never
either). Mis-recording an `info` as a `fail` is the commonest way a linearizability run produces a
meaningless verdict. Export newline-delimited JSON per run, one file per key, seed and config in a header
record. **Use an existing checker** — Knossos (Clojure/Jepsen) or Porcupine (Go) — against a trivial
single-holder-with-monotonic-token model; writing your own means debugging your own.

**Why the counterexample beats the pass.** A pass means "no violation in the schedules you happened to
explore" — incomplete search, your workload. A counterexample is a concrete, minimal, reproducible
schedule money would have flowed through. Save every one. The ambition: **checked, plus a published
counterexample from a deliberately broken build**, proving the harness can detect a violation at all.

## 7.6 Fault-injection matrix {#test-faults}

| Fault | Injection method | Expected behaviour | Observed |
|---|---|---|---|
| Client crash | `kill -9` on the executor | Lease expires; session GC releases locks (FR-04); payout recoverable by state + submission record, never by elapsed time (FR-24) | |
| Client pause | `kill -STOP` / `-CONT` (§7.3) | Stale write fenced at both points; `lock.fenced.out` > 0; exactly one rail request | |
| Network partition | Drop rules between lock-server and its backend, and between executor and lock-server | Minority/degraded side refuses to grant (`NOT_LEADER`); never fails open (FR-27) | |
| Slow network | `netem delay` (ASSUMPTION 200 ms) on the backend link | Latency rises, SLO burn visible; zero safety events | |
| etcd leader kill | Delete the leader pod in the `dlock-etcd` StatefulSet | ≤ 2 s shard unavailability (NFR-02); tokens keep increasing; election counted against the budgeted allowance | |
| Cloud SQL primary failover | `gcloud sql instances failover dlock-pg-lock` (regional) | Acquire fails cleanly for the failover window (tens of seconds, ASSUMPTION); no committed grant or fence lost (NFR-05, INV-04) | |
| Clock jump forward | `libfaketime` on one node | Premature expiry; **no double grant**; no token reuse | |
| Clock jump backward | `libfaketime` on one node | Delayed expiry; **no double grant**; local deadline still monotonic (FR-11) | |
| Disk full on lockdb | Fill the volume | Writes fail with a clean error; no partial grant; no token regression | |
| Duplicate request | Harness replays the last acquire/renew | Same grant and same token returned; no second token burned | |
| Rail timeout | Rail stub timeout injection (FR-21) | `RAIL_AMBIGUOUS`; attempt record already durable (FR-19); payout not submittable by anyone (FR-23); **no automatic retry** (FR-20) | |
| Rail duplicate ack | Stub acks twice for one submission | Second ack reconciles to the same `rail_submission`; ledger written once; INV-03 holds | |

`Observed` is deliberately blank — filling it in is the work (M6, T-060…069, plus the game day), and
**the gap between expected and observed is the write-up.** One honestly explained divergence beats a table
of ticks — a perfect table usually means the faults were too gentle. Record detection latency (injected →
alert fired) for every row with an alert; that number, not the tick, is the SRE deliverable.

## 7.7 Load profiles {#test-load}

All numbers ASSUMPTIONS for the project workload in [01 §1.8](01-requirements.md#br-assumptions).

| Profile | Clients | Keys | Hold | Duration | What it reveals |
|---|---|---|---|---|---|
| Uncontended | 10 | 10 000 | 50 ms | 10 min | Baseline acquire latency; the NFR-03 p50/p99 numbers |
| Lightly contended | 50 | 500 | 100 ms | 10 min | Retry/backoff behaviour; whether jitter actually spreads (FR-28) |
| Hot key | 200 | 1 | 100 ms | 5 min | Queueing, fairness/starvation, `CONTENTION_EXCEEDED` shape, waiter accounting |
| Long hold | 20 | 20 | 30 s | 15 min | Renewal correctness, heartbeat loss, session TTL interaction |
| Churn | 100 | 1 000 | 10 ms | 10 min | Session create/destroy cost, GC pressure, backend write amplification |

**The detector that outranks every latency number.** Every profile runs a shared critical-section violation
counter: each acquire writes owner+token into a shared cell and verifies exclusivity of *effect* over the
hold (§7.2 semantics — token-ordered mutation, not belief). Its value must be **exactly zero**, as must
`lock.fenced.out` and `rail.duplicate.attempted`
([C4 §4.4](contracts/C4-observability.md#ct4-zero)). Non-zero: stop tuning and find the bug.

## 7.8 Benchmark protocol {#test-benchmark}

| Element | Rule | Why |
|---|---|---|
| Warm-up | First 2 min discarded (JIT, pools, page cache, autoscaler settling) | ASSUMPTION; cold numbers are a different system |
| Run length | ≥ 10 min steady state per profile | Short runs hide GC and checkpoint tails |
| Repeats | 3 runs; report **median with min–max spread**, never a single best | One run is an anecdote |
| Record | p50/p90/p99/p99.9 and max acquire latency; throughput; error rate by code; both zero-counters; backend CPU/IO; election/failover count | Tail is the product |
| Environment capture | Java build, image digests, Boot/etcd/pg versions from the version catalog ([C5](contracts/C5-config-build-and-naming.md#ct5-catalog)), GKE Autopilot node class, region `europe-central2`, instance tiers, harness location, concurrent load | An uncaptured environment makes the number unquotable |
| Mid-run fault | One run per backend with a leader kill / failover at minute 5 | Steady-state numbers are the easy half |
| Reporting | Never quote a mean; never quote a number whose run had a non-zero violation counter | |

## 7.9 Backend comparison — the table to fill in {#test-comparison}

Both backends are first-class; **etcd is the recommended production choice**. An M7 deliverable, not a formality; all cells blank until measured.

| Dimension | PostgreSQL `lockdb` | etcd 3.6 |
|---|---|---|
| p50 / p99 acquire, uncontended, in-region | | |
| p99 acquire, hot key | | |
| Throughput ceiling before error rate rises | | |
| Failover / election unavailability window | | |
| Token source | `fencing_token_seq` ([C1](contracts/C1-database-schemas.md#ct1-seq)) | `ModRevision` captured **at grant time** |
| Safety events across the corpus | must be 0 | must be 0 |
| Operational cost and toil | | |
| Recommendation | | |

**Be suspicious of a flattering result.** Calibration priors — ASSUMPTIONS from published behaviour, not
measurements: Cloud SQL primary failover **tens of seconds**; etcd leader election **sub-second**;
uncontended acquire on either **single-digit ms** in-region. A sub-second Cloud SQL failover means you timed
the proxy's reconnect, not the failover; a 30 ms uncontended acquire means the harness is out of region or a
pool is empty. Postgres beating etcd on p99 under contention is plausible at this scale and worth saying out
loud. **A non-zero violation count invalidates the whole table** — do not publish with a footnote; a lock
that occasionally permits two effective holders has no performance story at all.

## 7.10 What CI runs, and why {#test-ci}

| Trigger | Runs | Budget |
|---|---|---|
| Every push | Spotless + static analysis, unit tests, Testcontainers integration on **both** backends, the fixed DST seed set, **the §7.3 fencing experiment in both switch states** | ASSUMPTION ≤ 15 min |
| Every PR to master | The above, plus one linearizability run on a short history, plus the hot-key profile at reduced scale | ASSUMPTION ≤ 30 min |
| Nightly | Full DST seed sweep, all five load profiles, the mid-run-fault benchmark run | untimed |
| Weekly / release | Fault matrix rows that need cloud resources; game day on the schedule in [06](06-observability-and-slo.md) | manual |

**Why the fencing experiment belongs in CI:** a correctness claim verified once by a human is a *claim*;
re-verified on every push it is a *system*. The fence is the code nobody touches for six months and then
refactors, and it fails **silently** — a broken fence looks healthy right up to the duplicate payment. CI
must also assert the **disabled** run still corrupts: if the negative control stops failing, the experiment
has decayed into a tautology guarding nothing.

## 7.11 Definition of done for a correctness claim {#test-dod}

A claim is done when **all** of these hold; anything less is written as "believed, unverified."

1. It maps to a numbered invariant (INV-01…08) or requirement (FR-nn / NFR-nn).
2. An automated check asserts it, in the every-push set unless it needs cloud resources.
3. **The check has been observed to fail** against a deliberately broken build (the mutation test of NFR-07)
   — an assertion never seen red is decoration.
4. It is stated in terms of *effect*, not belief (§7.2), and holds on **both** backends.
5. It survives at least one relevant fault from §7.6.
6. Its evidence is an artifact — log events, counter values, a seed, a history file — not a README sentence.
7. The residual risk is written down: what this check still would not catch.
