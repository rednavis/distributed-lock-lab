# 10 — Delivery plan {#delivery}

> **Status:** baseline · **Owner:** maintainers · **Last reviewed:** 2026-09

How 63 implementation tasks get executed, in what order, and what "finished" means.

Requirements live in [01](01-requirements.md#br-fr); names live in the
[contract set](04-contracts.md#c-routing). **This file adds no requirement and no name.**

Three companion documents split the job between them, and confusing them wastes time:

| Document | Answers |
|---|---|
| This file | *Why* the work is ordered this way, and what a milestone must satisfy to be exited |
| [`12-parallelization-map.md`](12-parallelization-map.md) | *What can I start right now*, and what is genuinely blocked |
| [`../tasks/README.md`](../tasks/README.md) | *What is actually done* — the ledger, and the single source of truth for progress |

## 10.1 Execution model {#dp-execution}

The original design of this work assumed a single implementer working strictly sequentially. It is now
an open-source project, and the model has changed accordingly:

| Rule | Statement | Why |
|---|---|---|
| **Parallel where the graph allows** | Any task whose preconditions are merged may be claimed and worked concurrently with any other such task. The dependency graph, not the task numbering, is what constrains order | Contributors arrive with different interests, skills and availability. Serialising them wastes most of them |
| **The task id is an identifier, not a schedule** | `T-023` may legitimately land before `T-011`. Ids encode grouping and stable cross-references, not sequence | Renumbering would invalidate every cross-reference in the doc set |
| **Preconditions are hard** | A task whose preconditions are unmerged cannot be implemented — the files it edits do not exist. `status: ready` on an issue means a maintainer verified this | Starting a blocked task produces work that must be redone against state that does not exist yet |
| **One claim at a time** | Per contributor, until they have landed one task | Keeps the board honest about what is actually in flight |
| **Contract, not conversation** | No contributor may rename anything; contract silence is not permission ([04 §4.4](04-contracts.md#c-precedence)) | Nine modules compile against one vocabulary. Broken: green build, broken fence |
| **One task, one pull request** | Scope creep is the review killer. The specification's §7 names what is out of scope | A reviewer can hold one task in their head. They cannot hold three |

**What replaced sequencing as the coordination mechanism.** In the single-implementer model, strict
ordering substituted for coordination: if you always did the lowest-numbered unfinished task, two
pieces of work could never collide. With many contributors that no longer holds, so three other
mechanisms carry the load — **the contracts** pin every shared name so that independently written
modules fit together; **the preconditions** in each specification make the real dependency explicit;
and **the ledger** records what is done so that nobody duplicates it.

If those three are maintained, parallelism is safe. If they rot, it is not — which is why updating the
ledger is part of the [definition of done](../CONTRIBUTING.md#7-definition-of-done) rather than a
courtesy.

## 10.2 The eight milestones {#dp-milestones}

Exit criteria are commands or file states — true or false in one tree state, never judgements. The
contributor-facing version of this table, with entry points and parallelism notes, is
[`ROADMAP.md`](../ROADMAP.md).

| M | Title | Goal | Tasks | Exit criterion (objectively checkable) |
|---|---|---|---|---|
| **M0** | Foundations | Repo, build, module skeleton, CI | T-001…008 | `./gradlew build` and `spotlessCheck` green in a clean tree; all modules of [C5 `#ct5-modules`](contracts/C5-config-build-and-naming.md#ct5-modules) present; CI blocks merge on failure; `lock-api` has no third-party dependency ([C2 `#ct2-zero-dep`](contracts/C2-java-api.md#ct2-zero-dep)) |
| **M1** | PostgreSQL lock backend | Lock service with visible state: acquire/renew/release/inspect/revoke | T-010…017 | Testcontainers suite green for `store/pg`; FR-01…FR-08 each have a passing test; `SELECT * FROM lock_entry` shows a live grant while a lock is held; tokens strictly increase under 16 concurrent acquirers; all eight endpoints L1–L8 exist |
| **M2** | Protected resource + executor | `paydb`, ledger, rail-stub, rail-proxy, payout-executor, both fence points | T-020…027 | A local payout moves `PENDING → POSTED` with balanced ledger rows; the fenced `UPDATE` ([C1 `#ct1-fenced`](contracts/C1-database-schemas.md#ct1-fenced)) affects 0 rows for a stale token; `rail_high_water` refuses a stale token before the rail is touched (FR-17) |
| **M3** | etcd backend | Second `LockStore`; `ModRevision` captured at grant time as the token | T-030…034 | The *same* `payout-executor` passes the M2 suite with `lock.backend=etcd`, no code change (SC-02); a session-death test shows all its locks released (FR-04) |
| **M4** | Client SDK + correctness proof | Conservative lease deadline, the SIGSTOP experiment, simulation, linearizability | T-040…047 | SC-03 script produces both captured outcomes; SC-04 double rejection holds with either fence point disabled; a known-bad seed fails and the fix passes (SC-05); INV-01…INV-06 each have a check that fails when broken (SC-06) |
| **M5** | Cloud infrastructure | Terraform: project, GKE Autopilot, two Cloud SQL instances, etcd StatefulSet, workload identity | T-050…059 | Budget alert exists **before** the first apply (NFR-14); apply→destroy leaves zero billable resources (SC-07); `dlock-pg-lock` REGIONAL and `dlock-pg-pay` ZONAL; the etcd StatefulSet has a PodDisruptionBudget and zone `topologySpreadConstraints` |
| **M6** | Observability + SRE | Metrics, logs, traces, SLIs/SLOs, error-budget policy, alerts, runbooks, game day | T-060…069 | Managed Prometheus scrapes `http-metrics` and every metric in [C4 `#ct4-metrics`](contracts/C4-observability.md#ct4-metrics) is queryable; the cardinality check fails the build on an added key tag (SC-11); every alert links a runbook section (NFR-10/11); the game-day table records detection latency per alert (SC-09) |
| **M7** | Benchmark, comparison, publication | pg vs etcd measured; failover experiment; the write-up | T-070…075 | Results table with p50/p99 acquire per backend on one harness (NFR-03); regional primary-failover dip measured and logged with zero safety events (SC-08); one month of SLI data reported against the policy (SC-10); a reader can trace a token end to end (SC-12) |

Unassigned ids — T-009, T-018/019, T-028/029, T-035…039, T-048/049 — are **split capacity, not spare
scope.** See [10.7](#dp-overrun).

## 10.3 Dependency graph {#dp-graph}

The *reasons* behind the ordering. Knowing them is what lets you judge whether something is genuinely
blocked or merely later in the numbering.

```
  M0 foundations (T-001..008)
       |
       v                        SPI shaped by a real implementation
  M1 pg lock backend (T-010..017) -----------------------> M3 etcd (T-030..034)
       |  LockService + token                                     |
       v                                                          |
  M2 resource + executor (T-020..027)                             |
       |  two fence points, non-idempotent rail                   |
       v                                                          v
  M4 client SDK + correctness proof (T-040..047)
       |    ^--- T-042 <== FIRST DEMONSTRATION OF THE CENTRAL CLAIM
       v
  M5 cloud infrastructure (T-050..059)  [one-day cost window, 10.6]  ->  M6 observability + SRE
       (T-060..069)  ->  M7 benchmark + comparison + publication (T-070..075)
```

| Dependency | Because | If the order were changed |
|---|---|---|
| M1 → M3 | The PostgreSQL store comes first so that the SPI ([C2 `#ct2-spi`](contracts/C2-java-api.md#ct2-spi)) is shaped by a real implementation before a second one must fit it | An abstractly designed SPI leaks etcd concepts (leases, `ModRevision`) into the PostgreSQL path |
| M1 → M2 → M4 | The executor cannot claim a payout without `LockService` and a token; the SIGSTOP proof needs a real side effect (rail-stub) and a real fenced row to reject | Nothing to fence with, and the proof degrades into a unit test asserting a comparison operator |
| M3 → M4 | SC-02 (one executor, both backends) is asserted in M4; simulation and linearizability run against both stores | The dual-backend claim becomes untested prose |
| M2/M4 → M5 | Deploy only what already works locally: cloud debugging costs money per hour, local debugging costs none | The one-day cost window becomes a three-day one |
| M5 → M6 | `PodMonitoring`, the named-port trap ([C4 `#ct4-scrape`](contracts/C4-observability.md#ct4-scrape)), Managed Prometheus and log-based metrics exist only in a cluster | Alerts written against metrics nobody has ever seen arrive |
| C4 → M1…M4 | Telemetry names are contract *before* code, so instrumentation is inline, never retrofitted | A retrofit across eight modules, and a cardinality violation found after the dashboards |
| M3+M5+M6 → M7 | The comparison needs both backends, real infrastructure, and a measurement pipeline | An unmeasured opinion — the thing this project exists to avoid |

**Note what this graph does *not* say.** It constrains milestones, not every task within them. M2 and
M3 touch entirely different modules and can proceed simultaneously. All of M5's Terraform can be
authored and `terraform validate`d while M1 is still in progress. The offline half of M6 — runbooks,
alert definitions, SLO objects — needs no cluster and no Java.
[`12-parallelization-map.md`](12-parallelization-map.md) works this out task by task.

## 10.4 The critical path {#dp-critical}

**M0 → M1 → M2 → M4 through T-042** is roughly 28 tasks. M3, M5, M6 and M7 broaden the claim; they do
not create it.

**`T-042` is where the project first demonstrates its central claim — locally, on one laptop, with no
cloud account.** One script `SIGSTOP`s a holder past its lease, lets a second worker take the lock,
then resumes the first: with fencing off, two rail submissions and a double debit you can `SELECT`;
with fencing on, one submission plus a `fenced_out` event carrying the presented and highest tokens.

That is [SC-03](00-charter.md#ch-success) plus the visible half of SC-04, and **the first moment the
work is worth showing anyone.** Everything before it is scaffolding; everything after is evidence that
the claim survives a second backend, a real cluster and a measurement.

**Maintainers: prioritise review on the critical path.** A contributor waiting five days for review of
`T-011` is blocking six downstream tasks; one waiting on `T-068` is blocking nobody. Issues on the path
carry the `critical-path` label for exactly this reason.

## 10.5 Where new contributors should start {#dp-onramp}

Ranked by how quickly a new contributor can produce something reviewable:

| Lane | Needs | Available | Entry point |
|---|---|---|---|
| **Documentation** | Nothing but a text editor | **Now** | [`T-068`](../tasks/T-068-runbook.md), any [docs issue](../.github/ISSUE_TEMPLATE/docs.yml) |
| **Terraform authoring** | Terraform CLI. **No cloud account, no billing** | **Now** — `validate` only | [`T-050`](../tasks/T-050-tf-root.md)…[`T-054`](../tasks/T-054-tf-artifacts.md) |
| **Build and tooling** | JDK 25, Gradle | After `T-001` | [`T-002`](../tasks/T-002-version-catalog.md), [`T-003`](../tasks/T-003-convention-plugins.md) |
| **Self-contained services** | JDK 25, Docker | After M0 | [`T-023`](../tasks/T-023-rail-stub.md) — zero dependencies, deliberately simple |
| **Lock backends** | JDK 25, Docker, distributed-systems interest | After `T-010` | [`T-011`](../tasks/T-011-pg-tryacquire.md), [`T-030`](../tasks/T-030-etcd-tryacquire.md) |
| **Correctness harness** | JDK 25, appetite for simulation testing | After M2 | [`T-043`](../tasks/T-043-sim-test.md), [`T-044`](../tasks/T-044-linearizability.md) |
| **Cloud infrastructure** | A Google Cloud project **you are willing to delete** | After M4 | [`T-053`](../tasks/T-053-tf-gke.md) onward — **this bills** |

The documentation and Terraform lanes are genuinely useful and genuinely unblocked today. They are not
busywork offered to newcomers: the runbook is on the never-cut list
([00 §0.5](00-charter.md#ch-nongoals)), and every hour of Terraform authored offline is an hour not
spent paying for a cluster.

## 10.6 The one-day cloud cost window {#dp-cost}

**The cloud is rented by the day, so everything needing it happens on one day.** Anything authorable
offline — Terraform HCL, dashboards, alert policies, runbook prose, the game-day script — is written
beforehand, with `terraform validate`/`plan` only.

This is a **maintainer-coordinated event**, not something an individual contributor should start
casually. Read [`05-infrastructure.md`](05-infrastructure.md#gcp-cost) first.

| # | Tasks | Action | Note |
|---|---|---|---|
| 1 | T-050 | Project bootstrap, APIs, **budget + budget alert** | NFR-14: the alert precedes the first apply. It is the only control that survives your own mistakes |
| 2 | T-051 | Network: VPC, the subnet carrying the `pods`/`services` secondary ranges, Cloud NAT, private service access | Everything later attaches to these ranges |
| 3 | T-052 | Cloud SQL: `dlock-pg-lock` REGIONAL, `dlock-pg-pay` ZONAL | Start first among slow resources — regional provisioning is the longest wait (ASSUMPTION ≈10–20 min) |
| 4 | T-053…054 | GKE Autopilot cluster; Artifact Registry, then push the six images | Cluster creation runs in parallel with step 3 |
| 5 | T-055…057 | Deploy the five Spring workloads, then the etcd StatefulSet + PDB + spread constraints, then Workload Identity and secret projection | Order fixed by preconditions. Smoke test: one payout end to end in-cluster |
| 6 | T-060…063 | Centralise the meters, **verify the scrape**, structured JSON logs, log-based metrics | The named-port trap fails **silently**; verify in C4's order |
| 7 | T-064…069 | SLO objects, alert policies, the dashboard JSON, traces, then **game day firing every alert** | The runbook it exercises was written offline |
| 8 | T-070…072 | Benchmark both backends; kill the regional primary; record the dip | Destructive experiments come **after** observability is verified, or they produce no data |
| 9 | — | **Export everything**: metric CSVs, dashboard screenshots, log fixtures, game-day table | Evidence must outlive the infrastructure |
| 10 | T-059 | `terraform destroy` in T-059's pinned order, then its by-hand orphan sweep; confirm zero billable resources; **re-check billing the next morning** | Destroy is not proof; the billing report is |

**The failure mode this order prevents:** discovering at 22:00 that the scrape never worked, so the
benchmark ran unmeasured, so the whole day repeats at full cost. **Second:** destroying before
exporting, which converts a completed experiment into an anecdote.

Autopilot-induced leader elections are expected and *budgeted* (NFR-02) — count and report them; they
do not make it a failed day.

## 10.7 When a task turns out to be bigger than specified {#dp-overrun}

**Split, do not sprawl.**

| Situation | Do |
|---|---|
| Nearly done, scope was slightly under-estimated | Finish it. Note the under-estimate in the pull request so the specification can be corrected |
| Still discovering scope | **Stop adding code.** Bring the branch to a buildable state — `./gradlew build` green, no half-written file left behind |
| Then | Keep the original id for the finished part. Take the **next reserved id in that milestone's gap** (T-009, T-018/019, T-028/029, T-035…039, T-048/049) for the remainder, and write its specification *now*, while the context is in your head |
| Then | Mark the original ledger row `split`, pointing at the new id. Open an issue for the remainder. Land what you have |
| Gap exhausted | The milestone was mis-scoped. That is a maintainer problem — open an issue rather than appending `T-017b`, `T-017c` |

*Why the gaps exist:* renumbering 63 tasks to accommodate one split would invalidate every
cross-reference in this doc set. They are pre-paid insurance.

The anti-pattern to avoid is **unrecorded sprawl** — three tasks of half-work on a branch with no green
command and no way for anyone else to tell what is real.

## 10.8 Quality gates between milestones {#dp-gates}

A milestone is exited by passing its gate, not by finishing its last task. Gates are cheap; a skipped
one is paid for two milestones later. Each gate is verified by a maintainer and recorded in the
milestone's closing issue.

| Gate | Automated | Manual (≤5 min) |
|---|---|---|
| **G0** | `./gradlew build` and `spotlessCheck` green in a clean tree; CI blocks merge on failure | `lock-api`'s dependency list has no third-party entry |
| **G1** | PostgreSQL store suite; 16-thread concurrency test; Flyway migrate idempotent | Read a live grant with `psql`; eyeball token monotonicity once |
| **G2** | End-to-end payout, fenced-write, duplicate-submission tests | `rail.duplicate.attempted` and `lock.fenced.out` are **zero** in a healthy run ([C4 `#ct4-zero`](contracts/C4-observability.md#ct4-zero)) |
| **G3** | The full M2 suite passes twice, once per `lock.backend` value | Diff the two runs' logs: same events, different token source |
| **G4** | SC-03/04/05/06 checks; the bad simulation seed fails on purpose | Read the two captured fixtures as a stranger would |
| **G5** | `terraform plan` shows no drift after apply; destroy leaves nothing | The budget alert email actually arrived at the test threshold |
| **G6** | Cardinality check fails on a deliberately added key tag; every alert resolves to a runbook anchor | Per alert: actionable? novel? symptom-paged? |
| **G7** | The comparison table regenerates from the raw data files in the tree | The recommendation (etcd) is stated **with its cost**, not only its benefit |

Checked at every gate: **no contract drift.** A name introduced that is not in C1–C5 means either a
contract amendment row ([04 §4.5](04-contracts.md#c-changelog)) or a rename. Never neither.

## 10.9 What production would actually cost {#dp-v1}

This project is **V0** ([00 §0.2](00-charter.md#ch-versions)). The estimate below is not the plan above
— it answers a different and more senior question: *what would it take to run this for real?* It is
included because a design document that cannot price its own production version is incomplete, and
because it makes the [non-goals](00-charter.md#ch-nongoals) concrete rather than merely asserted.

**V1 team — 4.0 engineering FTE plus 0.6 supporting, for two to three quarters:**

| Role | FTE | Owns |
|---|---|---|
| Tech lead / staff engineer | 1.0 | Protocol correctness, the fencing contract, API compatibility, design review |
| Backend engineer (senior) | 1.0 | Service, sharding, gateway, admin and revocation tooling |
| Backend engineer (mid) | 1.0 | Client SDKs, integration with the first customer teams, migration tooling |
| SRE / infrastructure | 1.0 | Terraform, Kubernetes, rollout, monitoring, SLOs, load and chaos testing |
| Engineering manager · Product/TPM · Security review | 0.3 · 0.2 · 0.1 | Roadmap and unblocking · customer onboarding and comms · authN/Z, tenancy isolation, audit logging |

**The SRE is a full FTE, not an afterthought.** For an availability-critical infrastructure service the
operational work is not a tax on the build — it *is* half the build. That is the same claim this
project makes by importing V1's operational practices into a V0 prototype
([00 §0.2](00-charter.md#ch-versions)).

**V1 phase plan — six months, two-week sprints:**

| Phase | Weeks | Deliverable | Exit criterion |
|---|---|---|---|
| 0 Discovery | 1–3 | Requirements from candidate customers; buy-vs-build memo; API RFC | RFC approved; **a named first customer committed** |
| 1 Walking skeleton | 4–7 | etcd-backed service, acquire/release/renew, Java SDK, Terraform to dev | End-to-end lock in a dev cluster |
| 2 Correctness | 8–13 | Fencing end to end, sessions and heartbeats, conservative client expiry, simulation | Linearizability harness green under injected faults |
| 3 Hardening | 14–19 | Sharding, leader balancing, quotas, authN/Z, admin API, revocation | Load test at 3× projected peak; chaos suite green |
| 4 Operations | 20–23 | Dashboards, SLOs, alerts, runbooks, game day, on-call handbook | Game day executed; two engineers on-call qualified |
| 5 First customer | 24–26 | Shadow mode, then cutover for one workload | 30 days in production, zero safety events |

**Assumptions without which this estimate is worthless:** an existing Kubernetes/Terraform platform to
build on (add 6–8 weeks if not); one region (multi-region roughly doubles phases 3–5); Java-first
clients; one committed pilot customer. **If there is no committed first customer, the correct decision
is not to build** ([00 §0.3](00-charter.md#ch-customer)).

**Therefore not evidenced by this project:** sharding and leader balancing, authN/Z and multi-tenancy,
quotas and chargeback, a second-language SDK, migration off an incumbent Redis lock, 24/7 on-call,
multi-region. Citing this repository as evidence for any of them is the one reliable way to lose the
credibility it buys.

## 10.10 Definition of done — whole project {#dp-dod}

All of it true **in one tree state**, not spread across a history:

| # | Condition |
|---|---|
| D1 | SC-01…SC-12 ([00 §0.4](00-charter.md#ch-success)) each verified by a named command or an artifact on disk |
| D2 | **Every ledger row** in [`../tasks/README.md`](../tasks/README.md) `done`; nothing `in-progress` or `blocked`; every `split` resolved |
| D3 | FR-01…FR-30 and INV-01…INV-08 traceable to tests ([01 §1.10](01-requirements.md#br-trace)); each NFR either measured or explicitly marked unmeasured with the reason |
| D4 | Zero contract drift: no name in code absent from C1–C5, and [§4.5](04-contracts.md#c-changelog) reconciled |
| D5 | `./gradlew build` green in a clean tree with no cloud account (NFR-15) |
| D6 | Zero billable cloud resources; the cost window's billing report recorded as a number; the pg-vs-etcd comparison published with its raw data files in the tree, stating a recommendation **with its cost** |
| D7 | Error-budget policy written with named consequences plus one month of measured SLI data against it, Autopilot-induced elections counted as a budgeted expense |
| D8 | Game-day table complete: every alert fired, detection latency and time-to-runbook-step recorded |
| D9 | Runbooks, toil register and blameless-postmortem template exist, each used at least once; both kill switches documented as experiment-only, defaulting to enforcement on |
| D10 | Every number in the doc set is measured (with its command) or labelled ASSUMPTION. No production data, no real organisation names |
