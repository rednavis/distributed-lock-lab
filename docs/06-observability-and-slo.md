# 06 — Observability, SLOs and the error-budget policy {#obs}

Identifiers are **contract, not choice**: metric names, tags, log events, spans, scrape wiring and health semantics are pinned in
[C4](contracts/C4-observability.md#ct4-scope). This file owns what C4 defers ([C4 §4.11](contracts/C4-observability.md#ct4-nonspec)): **targets,
windows, histogram buckets, burn-rate thresholds, the alert catalogue, the dashboard, the sampling rate, and the error-budget policy**. Every number
is a labelled **ASSUMPTION** for a fictional mid-size payment service provider, never production data. Built in **M6 (T-060…T-069)**.

## 6.1 Three signals and the one rule {#obs-signals}

**Alert on metrics · diagnose with logs · explain with traces.** Not stylistic — it follows from cardinality. Metrics are cheap per point, expensive
per series, so they carry only enumerable labels ([C4 §4.3](contracts/C4-observability.md#ct4-cardinality)): enough to say *something is wrong*,
never *which payout*. Logs carry identifiers ([C4 §4.5](contracts/C4-observability.md#ct4-logs)) and answer *which one*; traces carry causal order
across four processes and answer *why, in what sequence*. Inverting this — alerting on log text, tagging a metric with an account id — is the most
expensive mistake in practical observability, and it is expensive in both directions.

### 6.1.1 Metric type vs monitored resource — why a correct query returns nothing {#obs-empty}

A Cloud Monitoring time series is identified by **two** things, and a query needs both right:

| Part | Example (Managed Service for Prometheus) | Failure if wrong |
|---|---|---|
| **Metric type** | `prometheus.googleapis.com/lock_acquire_seconds/histogram` | Empty result. The Micrometer name `lock.acquire` is *not* the queryable string ([C4 §4.2](contracts/C4-observability.md#ct4-metrics)) |
| **Monitored resource** | `prometheus_target{project_id, location, cluster, namespace, job, instance}` | Empty result. Querying `k8s_container` for a Prometheus-ingested series matches nothing, forever |

**The failure mode that matters:** a wrong metric or resource type is **not an error** — it is an empty series, and on a `< threshold` condition
empty looks exactly like health, so the alert meant to wake you is silently disarmed. Three countermeasures, all required: copy exported names from
[C4 §4.2](contracts/C4-observability.md#ct4-metrics), never retype; run the three-step verification in [C4
§4.9](contracts/C4-observability.md#ct4-scrape) after every deploy; pair every ratio alert with an **absent-data** alert on the same series (§6.7).

## 6.2 Why Managed Service for Prometheus, not self-hosted {#obs-mspfp}

| Criterion | Self-hosted (+Thanos) | Managed Service for Prometheus |
|---|---|---|
| Who is paged when monitoring dies | You, without monitoring | Google — decisive |
| Toil | StatefulSet, retention, WAL disks, sharding, upgrades | None — decisive |
| Cost shape | Fixed VM+disk, always on | Per-sample, ~zero after teardown (NFR-14) |
| SLO objects, burn-rate alerts | Hand-built recording rules | First-class `ServiceLevelObjective` API objects |

**Recommendation: Managed Service for Prometheus.** *Why:* a project whose thesis is SRE practice must spend its budget exercising the stack, not
operating it — and monitoring that shares a failure domain with the monitored system is worthless during exactly the incident it exists for.
**Portability makes that safe:** services expose plain OpenMetrics at `/actuator/prometheus`, scrape config is a `PodMonitoring` CRD, and every
condition is PromQL over the same series names. Nothing depends on Google except the SLO objects and notification channels, so migrating means
running a Prometheus and re-authoring ~10 objects — a day, not a rewrite. **Lock-in is proportional to the layer you accept it at**; here it is only
at the top.

## 6.3 SLI catalogue {#obs-slis}

An SLI is a **ratio of good events to valid events**, plus a **measurement point** and a **window**. If you cannot state all three, you have a
graph, not an SLI. Window is **rolling 28 days** throughout (matching NFR-01) — rolling, not calendar, so a bad Monday cannot be laundered by a
month boundary.

| # | SLI | Good events | Valid events | Measurement point | Window |
|---|---|---|---|---|---|
| S1 | **Acquire availability** | `lock_acquire_seconds_count{outcome="granted"}` | `…{outcome=~"granted\|error"}` | lock-server, server-side | 28 d |
| S2 | **Acquire latency** | granted acquires in buckets ≤ **0.05 s** | all granted acquires | lock-server histogram | 28 d |
| S3 | **Session survival** | sessions closed explicitly = sessions opened − `lock_session_lost_total` | sessions opened | lock-client (see below) | 28 d |
| S4 | **Leader-election rate** | *(count SLI — no ratio)* `etcd_server_leader_changes_seen_total` increments | — | etcd `/metrics`, second `PodMonitoring` | 28 d |
| S5 | **Time to leader** | elections whose first post-election successful acquire lands ≤ **2 s** | induced elections in a kill experiment | harness, not continuous | per experiment |
| S6 | **Payout execution success** | `payout_execute_seconds_count{outcome="posted"}` | `…{outcome=~"posted\|failed\|abandoned\|ambiguous"}` | payout-executor | 28 d |
| S7 | **Claim-to-posted latency** | posted attempts in buckets ≤ **2 s** | all posted attempts | payout-executor histogram | 28 d |
| S8 | **Backlog freshness** | minutes in which `payout_backlog_age_seconds` < **300** | all minutes | payout-executor gauge, time-sliced | 28 d |

Definitional decisions, each with a reason:

- **S1 excludes `outcome="contended"` from *valid* entirely.** A lock legitimately held by someone else is a **correct answer**. Counting it as bad
  turns the SLO into a contention metric that breaches under load while the service is perfectly healthy (NFR-01 agrees). **S2** excludes contended
  and error too: contention latency is a business fact, error latency is fast-fail noise that *flatters* the percentile.
- **S6 counts only `posted` as good** — deliberately pessimistic. `payout.execute` alone cannot separate a clean rail decline (a correct answer)
  from an internal failure; that needs a join with `rail_submission_total{outcome="rejected"}`. Rather than encode a join into an SLO, the target
  absorbs the normal decline rate and the split is a dashboard tile (§6.8). **`ambiguous` is bad**: money may have moved and nobody knows, which is
  the worst state this system has.
- **S8 converts a gauge into a time-sliced ratio.** Gauges have no events; "minutes in a good state" is the standard bridge and makes backlog
  comparable to the request-ratio SLOs in one budget.

**Histogram buckets are part of the SLO** — a latency threshold that is **not a bucket boundary** is interpolated, i.e. invented.
Explicit boundaries (ASSUMPTION, T-060): `lock.acquire` → `0.001 0.0025 0.005 0.01 0.025 0.05 0.1 0.25 0.5 1 2 5`; `payout.execute` → `0.05 0.1 0.25
0.5 1 2 5 10 30`. **0.05 s and 2 s must exist exactly**, because S2/S7 count requests in buckets rather than read a published percentile. Publish
percentiles for dashboards only — never aggregate a client-side percentile across pods; it is arithmetically meaningless.

**The asymmetry in S3 that will mislead you** — `lock_session_lost_total` is emitted by the **client**, so a client that is killed, frozen
or partitioned **cannot report its own loss**, precisely the interesting case. The server-side twin is `lock_lease_expired_total`. Alert on the
server signal, diagnose with the client one, and read a growing divergence between them as clients dying silently.

### 6.3.1 A correctness invariant is not an SLO {#obs-not-slo}

`lock_fenced_out_total` and `rail_duplicate_attempted_total` ([C4 §4.4](contracts/C4-observability.md#ct4-zero)) are **not SLIs and never get
targets**. A 100 % target has an error budget of zero, and a budget of zero is not a budget — it is a wish that gives operators no decision rule.
The semantics differ in kind: an SLO says *this much failure is acceptable*, and **there is no acceptable number of duplicate payments** (INV-02) or
accepted stale writes (INV-05). Healthy value is exactly **zero**, so they are:

| Treated as | Consequence |
|---|---|
| Threshold `> 0`, no duration window | The one legitimate single-data-point alert — there is no noise floor to average out ([C4 §4.4](contracts/C4-observability.md#ct4-zero)) |
| Release gate | Any occurrence in CI, load test or game day blocks publication (NFR-06, M7) |
| Incident, not a metric regression | Closed on *why a zombie existed*, never on "the safety net held" |

## 6.4 SLO table {#obs-slos}

Window 28 days = **40,320 minutes**. Budget minutes = `40320 × (1 − target)`, read as *minutes of total unavailability*; the request-count
equivalent assumes a nominal **20 acquires/s** (ASSUMPTION).

| SLI | Target | Window | Error budget (explicit arithmetic) | Rationale |
|---|---|---|---|---|
| S1 acquire availability | **99.9 %** | 28 d | `40320 × 0.001` = **40.3 min** ≈ 48,400 failed acquires | NFR-01. Three nines is what a 3-node etcd on **Autopilot** can actually hold (§6.5); claiming four would be a lie with a straight face |
| S2 acquire latency ≤ 50 ms | **99 %** | 28 d | `40320 × 0.01` = **403 min**-equivalent ≈ 484,000 slow acquires | NFR-03 p99 ≤ 50 ms restated as a ratio. Loose because the pg backend is expected to be worse and the gap is a deliverable |
| S3 session survival | **99.9 %** of sessions | 28 d | 1 loss per 1,000 sessions | Every loss is a possibly-aborted payout; tighter than this measures GC pauses in the *client*, not the service |
| S4 leader elections | **≤ 60 per 28 d** per cluster | 28 d | `60 × 2 s` = 120 s = **2.0 min** = **5 %** of the S1 budget | A *sub-budget* carved out of S1 (NFR-02). Budgeted, not assumed zero |
| S5 time to leader | **p99 ≤ 2 s** | per experiment | none — pass/fail | NFR-02. Bounds the per-election cost that S4 multiplies |
| S6 payout execution success | **99.5 %** | 28 d | `40320 × 0.005` = **201.6 min**-equivalent | Deliberately looser: it includes the injected-fault rail stub and counts declines as bad (§6.3) |
| S7 claim-to-posted ≤ 2 s | **99 %** | 28 d | `40320 × 0.01` = **403 min**-equivalent | NFR-04 |
| S8 backlog age < 300 s | **99 %** of minutes | 28 d | **403 bad minutes** | A backlog is the only SLI a user would actually feel; 5 min of aging is invisible, an hour is a complaint |

## 6.5 The error-budget policy {#obs-policy}

A policy not written down before the breach is not a policy, it is a negotiation held while everyone is angry. Roles are **roles**, filled by one
person here: **service owner** (accountable for the budget), **on-call** (the incident), **reviewer** (countersigns exceptions).

| Budget consumed | Automatic consequence | Who decides |
|---|---|---|
| **25 %** | Notice only. Reliability items enter the next iteration's backlog with a named owner. No freeze. | on-call notes it |
| **50 %** | **Half the iteration's capacity** shifts to reliability work. New feature flags ship dark, not enabled. A written cause summary is required. | service owner |
| **75 %** | **Feature freeze** on the lock service and executor: only reliability, rollback, and toil-reduction changes merge. Progressive rollouts pause at the current stage. | service owner, recorded |
| **100 %** (exhausted) | **Hard change freeze** except reliability fixes and security patches. Every subsequent breach is a **blameless postmortem** with action items that have owners and dates. The next iteration is planned reliability-first. | service owner + reviewer |
| **Any correctness counter > 0** | Independent of budget: freeze, incident, postmortem (§6.3.1) | on-call, immediately |

**Exceptions** are grantable and must be: (1) requested in writing, naming change and reason; (2) **time-boxed with an explicit expiry** — an
exception without one is a policy amendment; (3) countersigned by the reviewer; (4) recorded in the ADR log. "Business needs it" is a reason; "the
freeze is inconvenient" is not.

### 6.5.1 The arithmetic that turns an SLO into an engineering constraint {#obs-arithmetic}

```
99.99 % / 30 d : 43,200 min x 0.0001  =   4.32 min =   259 s  budget
99.9  % / 28 d : 40,320 min x 0.001   =  40.32 min = 2,419 s  budget   <- this project (S1)
one etcd leader election             =  1-2 s of one shard being unavailable  (NFR-02)
  at four nines : 259 s / 2 s        =   130 elections  (150 at 1.75 s) exhausts EVERYTHING
  at three nines: 2419 s / 2 s       = 1,210 elections  (2,419 at 1 s)
```

**So an election is a budgeted expense, and any change that increases elections spends availability.** That is the justification for the **GKE
Autopilot** decision: Autopilot bin-packs and evicts with no node-placement control, so a 3-replica etcd StatefulSet **will** see more elections
than on Standard. At **99.99 %** that is disqualifying — 130 evictions is an ordinary Autopilot month and eats the entire budget. At the **99.9 %**
this project commits to, the same behaviour costs a few percent: affordable **and measurable**. Mitigations (PodDisruptionBudget,
`topologySpreadConstraints` across zones, safe-to-evict annotation) reduce the rate; the S4 sub-budget of 60/28 d is the **falsifiable claim** and
T-069 measures the residual against it. If it returns 300, the honest options are raise the budget, move to Standard node pools, or lower the SLO —
**not** stop counting.

## 6.6 The signals nobody remembers, and what each predicts {#obs-leading}

Trailing indicators tell you that you failed; these tell you that you are about to.

| Signal | Source | Predicts | Failure mode it catches first |
|---|---|---|---|
| **Lock hold-time distribution** (`lock.held_ms` span attr, [C4 §4.8](contracts/C4-observability.md#ct4-traces)) | `lock.release` spans | Cascading contention and lease expiry | A long tail means someone holds the lock **across a network call** — the design error that makes every other symptom worse |
| **Waiter-queue depth per key** (`lock_waiters_current` in aggregate; the *key* comes from logs) | lock-server | Hot-key collapse | One account monopolising a shard while overall throughput still looks fine |
| **Lease-renewal failure rate by client** (`lock_session_lost_total` / `lock_lease_expired_total` divergence) | client + server | The next fenced-out write | Finds the GC-pausing or CPU-throttled service **before** it produces a zombie holder |
| **Token gap growth** (`lock.token` deltas per key, from traces) | traces / audit log | Retry storms and revocation loops | Tokens jumping by hundreds means acquire-release churn: a client retrying blindly, or a sweeper fighting a holder |

None of these gets an SLO: they are **diagnostic and ticketing** signals, and giving them targets would manufacture pages for conditions that are
not yet failures.

## 6.7 Alert catalogue {#obs-alerts}

**Page on symptoms, ticket on causes.** A symptom is something a caller or a payout can feel; a cause is a mechanism that usually produces one.
Causes get tickets because the mapping is probabilistic — a cause that has not produced a symptom may never — and paging on causes is how fatigue
starts and how the one real page gets ignored. **Every page must pass three tests; one that fails any is deleted, not tuned:** **(1) Actionable** —
a human can do something now, distinct from what automation already did. **(2) Novel** — it is not a restatement of another alert firing on the same
cause in the same minute. **(3) Runbook-linked** — it names a runbook section that has been executed at least once (NFR-10/11).

| Alert | Symptom / cause | Condition, in precise words | Sev | Page/Ticket | Why that severity | Runbook |
|---|---|---|---|---|---|---|
| **Fenced-out write** | Symptom (correctness) | `lock_fenced_out_total` increases by ≥ 1 over 1 min, any `resource`, while `payment.fencing.enabled=true` — no `for:` clause | **S1** | **Page** | Zero is the healthy value; one increment is a completed safety-net catch and evidence a zombie holder reached a writer ([C4 §4.4](contracts/C4-observability.md#ct4-zero)) | [08 #rb-fenced-out](08-operations.md#rb-fenced-out) |
| **Duplicate rail submission** | Symptom (correctness) | `rail_duplicate_attempted_total` increases by ≥ 1 over 1 min | **S1** | **Page** | The failure the project exists to prevent (INV-02) came within one guard of happening | [08 #rb-rail-duplicate](08-operations.md#rb-rail-duplicate) |
| **Token regression** | Symptom (correctness) | Any token observed at or below one already seen for the same key — a `fenced_out` event whose stored `fence` exceeds a *later*-issued token, or a per-account `max(fence)` that decreases; no `for:` clause. The token cannot be a metric label ([C4 §4.3](contracts/C4-observability.md#ct4-cardinality)), so this one condition is evaluated over the log payload and the harness monotonicity check | **S1** | **Page, highest severity** | INV-04 is breached and fencing is decoration: every `fence <` check still passes while protecting nothing. Unbounded and silent — the only failure class here worse than a duplicate payment | [08 #rb-token-regression](08-operations.md#rb-token-regression) |
| **Fast budget burn** | Symptom | S1 burn rate ≥ **13.4×** over **1 h** (= 2 % of the 28-day budget in one hour) *and* ≥ 13.4× over the trailing 5 min | **S1** | **Page** | Callers are being refused now; at this rate the 28-day budget is gone in ~2 days | [08 #rb-acquire-burn](08-operations.md#rb-acquire-burn) |
| **Slow budget burn** | Symptom | S1 burn rate ≥ **5.6×** over **6 h** (= 5 % of the 28-day budget) *and* ≥ 5.6× over the trailing 30 min | S2 | Page, business hours | Real and will breach, but nothing is on fire; waking someone buys nothing | [08 #rb-acquire-burn](08-operations.md#rb-acquire-burn) |
| **Payout-success fast burn** | Symptom | S6 burn rate ≥ **13.4×** over **1 h** *and* ≥ 13.4× over the trailing 5 min | **S1** | **Page** | The business SLI, not the lock's: payouts are failing or abandoning **now**, and S6's budget is separate from S1's — a healthy lock with a failing payout path must still page | [08 #rb-payout-burn](08-operations.md#rb-payout-burn) |
| **Backlog age** | Symptom | `payout_backlog_age_seconds` > 300 for 10 min | S2 | **Page** | The only symptom an end customer would notice unaided; means work is stuck, not slow | [08 #rb-backlog](08-operations.md#rb-backlog) |
| **Lease expiry rate** | Leading indicator | `rate(lock_lease_expired_total[10m])` > 3× the trailing 7-day median for 15 min | S3 | Ticket | Predicts fenced-out writes; nothing is broken yet | [08 #rb-lease-expiry](08-operations.md#rb-lease-expiry) |
| **etcd `wal_fsync` p99** | Cause | `etcd_disk_wal_fsync_duration_seconds` p99 > 100 ms for 10 min | S3 | Ticket | Predicts election storms. Cause-based, therefore never a page | [08 #rb-etcd-disk](08-operations.md#rb-etcd-disk) |
| **etcd leader changes** | Cause | `increase(etcd_server_leader_changes_seen_total[1h])` ≥ 3, **or** the 28-day total exceeds the S4 sub-budget of 60 | S3 | Ticket | Each election costs 1–2 s; this is budget consumption, which is a planning problem, not a 3 a.m. problem (§6.5.1) | [08 #rb-etcd-quorum](08-operations.md#rb-etcd-quorum) |
| **Cloud SQL connection saturation** | Cause | `cloudsql.googleapis.com/database/postgresql/num_backends` ÷ `max_connections` > 0.8 for 5 min on `dlock-pg-lock` | S3 | Ticket | Exhaustion turns into an S1 acquire-availability page on its own; the ticket is the chance to fix it first | [08 #rb-pg-connections](08-operations.md#rb-pg-connections) |
| **Metrics absent** | Cause (meta) | No data point for `lock_acquire_seconds_count` for 10 min while the Deployment reports ≥ 1 ready replica | S2 | **Page** | Without this, every ratio alert above is silently disarmed (§6.1.1) — the alert that guards the alerts | [08 #rb-target-down](08-operations.md#rb-target-down) |

**Where 13.4× and 5.6× come from, and why not 14.4×:** the famous 14.4 is *not* a constant. `14.4 = 0.02 × (720 h / 1 h)` assumes a **30-day**
window. This project's window is **28 days** (§6.4), so the exact equivalents are `0.02 × (672 h / 1 h)` = **13.4×/1 h** and `0.05 × (672 h / 6 h)` =
**5.6×/6 h** — the pairs configured in the table above. Using 14.4 on a 28-day window mis-targets by ~7 %, so the borrowed constant is wrong here.
Multi-window (long *and* short, the short one at the same multiplier) is mandatory on **both** burn alerts — 1 h + 5 min for fast, 6 h + 30 min for
slow: the short window is what makes the alert reset instead of latching for an hour.

## 6.8 Dashboard specification {#obs-dashboard}

Six tiles **in 3 a.m. reading order** — *is it broken → is it us → what changed → where exactly* — so an on-call reading top-left to bottom-right
cannot take the questions out of order.

| # | Tile | Shows | Answers |
|---|---|---|---|
| 1 | **Two correctness counters** | `lock_fenced_out_total`, `rail_duplicate_attempted_total`, 24 h, big and green at zero | "Is this a *money* incident?" — asked first because it changes everything else |
| 2 | **S1/S6 SLO + budget remaining** | Current 28-day attainment, minutes left, burn-rate sparkline | "Are we out of budget, and how fast are we spending?" |
| 3 | **Acquire outcome mix** | `lock_acquire_seconds_count` rate stacked by `outcome`, split by `backend` | "Errors, or just contention?" — the single most common misdiagnosis |
| 4 | **Acquire latency** | p50/p99 from buckets, `pg` vs `etcd` side by side | "Slow, or refusing?" |
| 5 | **Payout journey funnel** | `payout_execute` by outcome, `rail_submission` by outcome, `payout_backlog_age_seconds` | "Is the business impacted, and is it the rail or us?" |
| 6 | **Backend health** | etcd leader changes + `wal_fsync` p99; Cloud SQL connections + replication lag | "What changed underneath?" |

Tiles 7–8 (optional): lock hold-time distribution; lease-renewal failure divergence (§6.6).

**The JSON belongs in the repo** (`observability/dashboards/`, applied by Terraform, T-063): a dashboard edited in the console at 3 a.m. is **lost
work**; in-repo it is **reviewable** (adding a tile is a diff), **restorable** (a dashboard is a disaster artifact), and **versioned alongside the
metric names it queries**, so a C4 contract change and its dashboard update land in one commit.

## 6.9 Tracing {#obs-traces}

**What no metric can answer:** *for this one payout, in what order did four processes act, and which token did each see?* Metrics are aggregates by
construction; a fencing incident is a statement about **one** token's history. Filtering traces on `lock.token` yields the complete life of one
grant — issued, accepted by one writer, rejected at another — which is why [C4 §4.8](contracts/C4-observability.md#ct4-traces) requires `lock.token`
on **every** span. Its absence turns one incident into four unrelated traces and a lost afternoon.

| Sampling (ASSUMPTION, T-067) | Value | Why |
|---|---|---|
| Steady state | **1 %**, **parent-based** head sampling | Cost; 1 % is ample for latency shape. Parent-based is mandatory across all four services, or a trace is kept in one process and dropped in the next — broken traces are worse than none |
| Error paths | **100 %** retained: any span with error status, or a trace containing `fenced_out`, `rail_ambiguous`, `duplicate_rail_submission_attempted` | Rare-and-critical is exactly the population uniform sampling destroys |
| Benchmark / game day | **100 %**, time-boxed. No tail sampling anywhere — it needs a buffering collector, not worth the moving part here | Runs whose whole purpose is measurement |

## 6.10 Verification: an alert that has never fired is a hypothesis {#obs-verify}

An untested alert is a **belief about a query you have never seen return true**. Every alert in §6.7 is fired on purpose during the **game day
(T-068)** using levers already in the system: the two kill switches ([C5 §5.2](contracts/C5-config-build-and-naming.md#ct5-killswitches)), the
rail-stub fault injectors (FR-21), `SIGSTOP` on a holder to manufacture a zombie, an etcd member kill, and Cloud SQL connection exhaustion. Record
per alert (NFR-11):

| Column | Why it is the number that matters |
|---|---|
| **Injection** t0, **fire** t1 | `t1 − t0` = **detection latency**. Correct logic with 20 minutes of latency is not protection |
| **Notification arrival** t2 | `t2 − t1` catches broken channels — the most common silent failure in alerting |
| **Runbook executed? correct? sufficient?** | An alert whose runbook does not resolve it fails test (1) |
| **Recovery / auto-resolve time** | A latching alert that never clears trains people to ignore it |
| **Verdict** | Keep · retune · **delete**. Deleting is a success |

Detection-latency **targets** (ASSUMPTION): correctness counters ≤ 90 s; fast burn ≤ 5 min; symptom pages ≤ 5 min; cause tickets ≤ 30 min. Also
exercised here: the blameless-postmortem template on one game-day finding, and the toil register updated with anything done by hand twice.

## 6.11 What this deliberately does not do {#obs-nonspec}

| Not done | Why |
|---|---|
| Per-key or per-account SLOs | Would require unbounded metric labels ([C4 §4.3](contracts/C4-observability.md#ct4-cardinality)). Per-key questions are answered from logs and traces |
| Client-side / synthetic-prober SLIs as the primary measurement | Server-side S1 cannot see requests that never arrived — an honest limitation, stated rather than hidden. A prober is listed as future work, not pretended to exist |
| Anomaly detection or ML-based alerting | Nothing here has enough history for a baseline, and an unexplainable page is an unactionable page |
| On-call rotation, paging schedules, notification routing | One person; the *policy* is the deliverable, not a rota ([C4 §4.11](contracts/C4-observability.md#ct4-nonspec)) |
| Log retention/sinks/exclusion filters; multi-region SLOs | Terraform's job (`deploy/terraform/`, M5); single region `europe-central2` by charter |
| A composite "user journey" SLO spanning S1 and S6 | Multiplying two SLOs produces a number nobody can act on; keep the layers separate so an alert names the owner |
