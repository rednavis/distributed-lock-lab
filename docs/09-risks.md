# 09 — Risk register {#risk}

Scope: the risks to *this* project and to the V1 service it prototypes. Every number below is an
**ASSUMPTION** for a fictional mid-size payment service provider — none is production data.

## 9.1 How to read it {#r-howto}

Two scores and a name. Probability is the chance the risk fires **within the delivery window**
(assumed 12 weeks for the project; assumed two quarters for V1). Impact is what it costs **if it fires**,
measured against the charter's success criteria and the invariants in
[01 §1.7](01-requirements.md) — INV-01…INV-08.

| Score | Probability | Impact |
|---|---|---|
| **Low** | < 20% | Absorbed by the error budget or a sprint of rework |
| **Med** | 20–60% | A milestone slips, or an SLO is missed for a month |
| **High** | > 60% | A charter success criterion is not met |
| **Critical** | — (impact only) | Money moves twice, or the ledger disagrees with the rail |

Three rules make this register more than decoration.

1. **A named owner role, never a team.** "Platform owns it" means nobody owns it. Roles here:
   **Tech lead** (correctness and design), **SRE** (production behaviour, cost, alerts),
   **EM/TPM** (scope, schedule, adoption, people), **Author** (lab-only risks — one person, no
   delegation available, which is itself risk R-11).
2. **A leading indicator, not a lagging one.** "Duplicate payment occurred" is a lagging indicator;
   by the time it reads true the risk has already cost money. A leading indicator is something you
   can watch *before* the harm — `lock.fenced.out` moving off zero
   ([C4 §4.4](contracts/C4-observability.md#ct4-zero)) is leading; a reconciliation break is lagging.
3. **Mitigation reduces probability; contingency limits impact.** A row with only a mitigation is a
   row where you have decided to be surprised.

*Why it matters:* a risk register is what distinguishes a design that was reasoned about from one that
was narrated, and it is the first thing a reviewer checks. *Failure mode:* a register written once at
kickoff and never re-scored —
review it at each milestone boundary (M0…M7) and record score changes rather than editing silently.

## 9.2 The register {#r-register}

| ID | Risk | P | I | Leading indicator | Mitigation | Contingency | Owner |
|---|---|---|---|---|---|---|---|
| **R-01** | **Fencing not enforceable at some resource**, so the correctness goal is unmet (FR-15, INV-05) | Med | **High** | A resource inventory row with no `fence` column and no proxy in front of it | Audit every candidate resource in M0 before any code; a fence column where the write is ours ([C1 §1.6](contracts/C1-database-schemas.md#ct1-fenced)), a persisted high-water proxy where it is not ([C3 §3.5](contracts/C3-http-surfaces.md#ct3-railproxy)) | **Descope that resource to efficiency-grade explicitly and in writing.** An unfenceable resource keeps the lock as an optimisation and loses the correctness claim — say so rather than implying otherwise | Tech lead |
| **R-02** | **Ambiguous rail outcome produces an unresolvable duplicate** — a submit times out, the payout state is `RAIL_AMBIGUOUS`, and someone retries (FR-20, FR-23, INV-02) | **High** | **Critical** | `rail.submission{outcome="timeout"}` rate rising; any `rail.duplicate.attempted` increment ([C4 §4.4](contracts/C4-observability.md#ct4-zero)) | Record the attempt **before** forwarding (FR-19); never auto-retry a submit (FR-20); the payout is not submittable under any token once ambiguous (FR-23); the proxy rejects a second attempt per payout regardless of token (FR-18) | Manual reconciliation path (FR-26) with a documented operator decision and an audit row (FR-30); a compensating ledger entry, never a silent balance edit | Tech lead |
| **R-03** | **Adoption failure — teams keep their own locks** (Redis `SET NX`, an advisory lock, a cron singleton) | **High** | **High** | Pilot team asks for "just the token generator"; a second locking mechanism appears in a design doc | Named pilot before the build starts; the SDK must be *better* than what they have — conservative expiry and the still-held check for free (FR-11…FR-13); publish the measured pg-vs-etcd comparison so the choice is evidence, not advocacy | Ship as a library-plus-etcd pattern rather than a service; a pattern that is adopted beats a service that is not | EM/TPM |
| **R-04** | **Split-brain incident** — two holders both believe they hold the lock (INV-06) | Low | **Critical** | Two distinct `lock_granted` events for one key with overlapping validity; election count above the budgeted allowance (NFR-02) | Consensus backend recommended for production (etcd, `ModRevision` as token, FR-10); fencing as the second line that makes split-brain *survivable* rather than merely unlikely; linearizability harness in CI (NFR-07) | Fencing turns the incident into a rejected write plus an alert instead of a duplicate payment — this is exactly why R-04's impact is Critical but its *realised* cost is not | Tech lead |
| **R-05** | **Latency regression in a caller's hot path** — acquire is now on the critical path of a payout (NFR-03, NFR-04) | Med | **High** | p99 acquire drifting toward the 50 ms objective; the acquire share of payout p99 rising | Same-region placement (`europe-central2`); p99 in the published contract; load test at 3× assumed peak; hold the cardinality budget so the metrics survive the load ([C4 §4.3](contracts/C4-observability.md#ct4-cardinality)) | Documented "efficiency-grade, local lock" escape for latency-critical callers who do not need INV-02 | SRE |
| **R-06** | **Scope creep into a general-purpose key-value store** — see [9.3](#r-emphasis) | **High** | Med | The first request to store a JSON blob "since it is already consistent"; value-size or key-count growth uncorrelated with lock traffic | Non-goals written into the charter ([00 §0.5](00-charter.md#ch-nongoals)); **enforced in code, not policy** — value-size cap, key-count cap, one key pattern | Hard-reject and point at the non-goals table; offer the right store instead | EM/TPM |
| **R-07** | **GCP cost overrun** on the dev environment (NFR-14) | Med | Low | Daily spend crossing 50% of the stated cap two days running; a regional instance left running overnight | Budget alert configured **before** the first `terraform apply`; `dlock-pg-pay` deliberately zonal; a teardown command in the runbook and an assumed nightly stop | Destroy the environment; the whole stack is reproducible from Terraform, so a rebuild costs minutes, not data | SRE |
| **R-08** | **Autopilot eviction causes an election storm** on the 3-replica `dlock-etcd` StatefulSet (NFR-02) | Med | Med | Elections per day above the budgeted allowance; eviction events clustering during Autopilot bin-packing | PodDisruptionBudget, `topologySpreadConstraints` across zones, `safe-to-evict=false`; then **measure** the residual against the error budget | Move the StatefulSet to a Standard node pool — a decision deferred deliberately (charter D-05) until there is data, not a hunch | SRE |
| **R-09** | **Key-person dependency** — one person holds the consensus/fencing reasoning | **High** | **High** | A design question that only one person can answer; a second reviewer who approves without comment | Design docs are the deliverable, not a byproduct; pair on the state machine; **two people on-call qualified before launch** (NFR-11) | Freeze feature work and pay down the documentation debt before the next milestone | EM |
| **R-10** | **Abandoned half-built repo** — worse than no repo, because it advertises the gap | Med | **High** | A milestone boundary passed with the progress ledger unchanged for a month | Small, independently completable tasks with explicit preconditions; parallel lanes so no single contributor is the bottleneck; T-042 makes the fencing experiment runnable **locally** long before the cloud work | **Publish at the last green milestone boundary** with an honest "M0–Mn complete, Mn+1 specified not built" status line. A truthful partial is a credible artifact; a silent stall is not | Author |
| **R-11** | **Inaccurate published claim** — a benchmark or a correctness statement that does not hold | Med | **High** | A number in prose with no command that reproduces it | Every number carries its command, environment and date; assumptions labelled as assumptions; the pg-vs-etcd comparison published with its methodology and its limits | Correct in place with a dated note; never quietly delete | Author |
| **R-12** | **Overclaiming** — the project cited as production-ready, by us or by a reader | Med | **Critical** (credibility, and someone else's incident) | A reader asking how to deploy it in front of real money | Prototype status stated in the first sentence of the README, in `SECURITY.md`, and in the charter; the missing authN/Z, single region, stub rail and synthetic load volunteered **before** being asked | Correct publicly and promptly. A claim a reader disproves discredits the parts that were true | Maintainers |

## 9.3 The one that deserves emphasis: R-06 {#r-emphasis}

**Scope creep is the failure that actually happened to Chubby.** Teams used it as a general-purpose
store because it was the most reliable thing available, and the load profile it was never designed
for became its main operational problem. Not an outage, not a design flaw — an adoption pattern.

The mechanism is worth stating plainly, because it will repeat here. A lock service is, structurally,
a small strongly-consistent key-value store with a great availability record. Every engineer who
meets one thinks of a config value they would like to keep somewhere that consistent. The first such
request is reasonable and small. The hundredth is your load profile.

**Writing non-goals down and enforcing them with quotas is cheap; retrofitting them after adoption is
not.** Cheap now: a value-size cap, a key-count cap, one documented key pattern, and a non-goals
table an owner can point at ([00 §0.5](00-charter.md#ch-nongoals)). Expensive later: negotiating a
migration with teams whose production path now depends on the behaviour you never intended to offer.

*Why the register scores impact only Med:* creep does not corrupt anything. It slowly converts a
correctness service into a storage service with a lock-shaped API, and the cost lands on whoever is
on call in year two. That is why the owner is the **EM/TPM** and not the tech lead — the control is a
scope decision, and it is enforced in code precisely so it does not depend on the owner remembering.

## 9.4 The cut list under schedule pressure {#r-cuts}

In order. Weeks are ASSUMPTIONS for the V1 service, not the project.

| # | Cut | Ship instead | Saves |
|---|---|---|---|
| 1 | Multi-region / global locking | Single region, documented as a constraint | 6–8 weeks |
| 2 | Shared / exclusive (read-write) modes | Exclusive only, added later behind the same API | 3 weeks |
| 3 | Strict FIFO fairness queue | Barging locks with bounded jittered backoff (FR-28) | 2 weeks |
| 4 | Additional language SDKs | Java SDK first; publish the wire contract ([C3](contracts/C3-http-surfaces.md#ct3-lock)) for everyone else | 4 weeks |
| 5 | Admin UI | Operator CLI plus `psql` / `etcdctl` break-glass | 2 weeks |
| 6 | The PostgreSQL backend as a *shipping* option | Keep it as the teaching backend and the measured comparison; ship etcd | 2 weeks |

Cut 6 is last on purpose and is the one to argue about: dropping it saves the least and costs the
most, because the pg-vs-etcd measurement is what turns "etcd is the correctness answer" from an
opinion into a result. In the project it is **not cut at all** — it is milestone M1 and the earliest
thing that works.

## 9.5 What is never cut {#r-never-cut}

Say these unprompted, before anyone asks what you would drop.

| Never cut | Why | What its absence would mean |
|---|---|---|
| **Fencing tokens** (FR-02, FR-15, FR-17) | Without them this is a mutual-exclusion *hint*, not a correctness lock | The project loses its premise; every argument in the design collapses to "usually works" |
| **Conservative client-side expiry in the SDK** (FR-11, FR-12) | Half the correctness lives on the client: a holder must doubt its own lease before the server does | A paused process wakes up believing it still holds a lease that expired two minutes ago |
| **The linearizability / correctness harness** (NFR-06, NFR-07) | A lock service without one is a claim rather than a system | No way to distinguish "correct" from "not yet observed to be wrong" |
| **The runbook and on-call qualification** (NFR-10, NFR-11) | Launching what nobody can operate is how you get an incident with no owner | The first real page becomes a research project at 03:00 |

Note the shape: two are correctness, two are operability, and none is a feature. Features are
negotiable; the premise and the ability to run it are not.

## 9.6 Lab-specific risks {#r-lab}

R-07, R-10, R-11 and R-12 above are the project's own, restated here as the four things to watch weekly.

| Risk | The concrete control |
|---|---|
| **Overspend** | Budget alert before the first apply (NFR-14); `dlock-pg-lock` regional only while the failover experiment runs, then destroyed; nightly teardown; the local `docker compose` path (NFR-15) is the default and cloud is the exception |
| **Scope creep** | The 63-task plan is the scope. A good idea that is not a task becomes an issue on the "later" list, not an unplanned pull request |
| **Publishing something inaccurate** | No number without its reproducing command, its environment and its date; every assumption labelled; a dated correction note when something turns out wrong |
| **Overclaiming** | Lead with "this is a prototype built to demonstrate a failure mode." Then volunteer, unasked: no authentication or authorisation on the lock API, single region, a **stub** rail, synthetic load. Naming the limits first is what makes the rest credible — a reader who has to extract a limitation stops believing the parts that were right |

The honest framing to rehearse: *"It demonstrates fencing end to end against a deliberately
non-idempotent rail, on two backends, with measurements. It is not production — it has no authN, one
region, and a stubbed rail."* Both halves, in that order, every time.
