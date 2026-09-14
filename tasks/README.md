# Task board

**63 implementation specifications, `T-001` … `T-075`.** This file is the **ledger** — the single
source of truth for what is actually done.

| Document | Answers |
|---|---|
| **This file** | What is *done*, and what is *claimed* |
| [`../docs/12-parallelization-map.md`](../docs/12-parallelization-map.md) | What can I start *right now* |
| [`../ROADMAP.md`](../ROADMAP.md) | What each milestone must satisfy to be *exited* |
| [`../docs/10-delivery-plan.md`](../docs/10-delivery-plan.md) | *Why* the work is ordered this way |

---

## 1. How to use this board

**Task ids are identifiers, not a schedule.** `T-023` may legitimately land before `T-011`. What
constrains order is the **Blocked by** column — a task whose blockers are unmerged cannot be
implemented, because the files it edits do not exist yet.

1. Find a row whose **Blocked by** is `—` or fully merged, and whose Status is `Not started`.
2. Cross-check [`12-parallelization-map.md`](../docs/12-parallelization-map.md) — it groups these into
   lanes and flags the ones that unblock the most other work.
3. Open or find the GitHub issue, comment `/claim`, and get assigned.
4. Read the specification and **the contracts it cites**. Execute exactly that task.
5. Update your row here **in the same pull request**, and stop.

**One claim at a time** per contributor until you have landed one. Claims lapse after 14 days without a
draft pull request or a progress comment — no explanation owed, no fault implied.

Updating this file is part of every task's [definition of done](../CONTRIBUTING.md#7-definition-of-done).
A task whose row was never updated is indistinguishable from a task never done, and somebody will redo
it.

## 2. Status legend

| Status | Meaning |
|---|---|
| `Not started` | No work on disk. Default |
| `Claimed` | Assigned to a contributor. The Notes column names them and the date |
| `In review` | A pull request is open. Notes link it |
| `Done` | Every acceptance criterion verified by running the specification's §6 commands |
| `Deviated` | Done, but the specification was not followed exactly. Notes **must** state the deviation and why |
| `Blocked` | Cannot proceed. Notes **must** name what decision is needed and from whom |
| `Split` | Scope exceeded the specification. Notes point at the reserved id carrying the remainder |
| `Skipped` | Deliberately not done. Notes **must** name the forfeited claim — what the project can no longer demonstrate |

## 3. Ledger

**Progress: 0 of 63 done.**

### M0 — Foundations

Blocks every Java task in the repository. [`T-001`](T-001-monorepo-skeleton.md) is the single highest
priority in the project.

| Task | Title | Blocked by | Status | Notes |
|---|---|---|---|---|
| [T-001](T-001-monorepo-skeleton.md) | Monorepo skeleton and Gradle settings | — | Not started | **Fan-out: blocks all Java work** |
| [T-002](T-002-version-catalog.md) | The version catalog | T-001 | Not started | `good first issue` |
| [T-003](T-003-convention-plugins.md) | Convention plugins, Spotless, Google Java Style | T-002 | Not started | **Fan-out: unblocks T-004…T-008** |
| [T-004](T-004-lock-api-types.md) | `lock-api`: the contract types | T-003 | Not started | **Fan-out: six modules depend on this** |
| [T-005](T-005-compose-stack.md) | Local compose stack | T-003 | Not started | `good first issue` |
| [T-006](T-006-ci-build.md) | CI: the build workflow | T-003 | Not started | Extends the existing authored workflow |
| [T-007](T-007-ci-supporting.md) | CI: infra, container, CodeQL, Dependabot, templates | T-006 | Not started | Much of this already exists — reconcile rather than replace |
| [T-008](T-008-repo-front-matter.md) | Repo front matter and non-goals | T-003 | Not started | Reconcile with the published README |

### M1 — PostgreSQL lock backend

| Task | Title | Blocked by | Status | Notes |
|---|---|---|---|---|
| [T-010](T-010-lockdb-migration.md) | Flyway migration: lockdb schema | T-004 | Not started | **Fan-out: unblocks T-011, T-013, T-014, T-015.** `good first issue` |
| [T-011](T-011-pg-tryacquire.md) | `PostgresLockStore.tryAcquire` | T-010 | Not started | `critical-path`, `safety` |
| [T-012](T-012-pg-renew-release.md) | `PostgresLockStore` renew, release, inspect | T-011 | Not started | `critical-path` |
| [T-013](T-013-session-registry.md) | SessionRegistry and heartbeat persistence | T-010 | Not started | ∥ with T-011 |
| [T-014](T-014-expiry-sweeper.md) | ExpirySweeper and the expiry signal | T-010 | Not started | ∥ with T-011 |
| [T-015](T-015-force-revoke.md) | forceRevoke and the revocation audit trail | T-010 | Not started | ∥ with T-011 |
| [T-016a](T-016-lock-server-rest.md) | lock-server HTTP: conventions, error envelope, L1–L3 | T-012, T-013 | Not started | Part A. Header binding, validation, exception→code mapper |
| [T-016b](T-016-lock-server-rest.md) | lock-server HTTP: L4–L8 (acquire, renew, release, revoke, info) | T-016a | Not started | **Mandatory, not optional.** T-017, T-025 and T-041 all need L4–L8 |
| [T-017](T-017-pg-testcontainers.md) | Testcontainers matrix for the Postgres backend | T-016b | Not started | **Checkpoint: the backend becomes provable locally** |

### M2 — Protected resource and executor

Runs **fully in parallel with M3** — different modules entirely.

| Task | Title | Blocked by | Status | Notes |
|---|---|---|---|---|
| [T-020](T-020-paydb-migration.md) | Flyway migration: paydb schema | T-004 | Not started | **Fan-out** |
| [T-021](T-021-fenced-repositories.md) | payment-resource: account and ledger repositories | T-020 | Not started | `safety` — fence point (a) |
| [T-022](T-022-payment-resource-rest.md) | payment-resource HTTP surface, fenced-out signal | T-021 | Not started | `safety` |
| [T-023](T-023-rail-stub.md) | rail-stub: the non-idempotent external rail | T-004 | Not started | **Best first Java task. Zero dependencies, needs nothing from M1** |
| [T-024](T-024-rail-proxy.md) | rail-proxy: the fencing gate | T-020, T-023 | Not started | `safety` — fence point (c) |
| [T-025](T-025-payout-executor.md) | payout-executor: the state machine | T-022, T-024, T-016b | Not started | `critical-path` |
| [T-026](T-026-kill-switches.md) | The two fencing kill switches | T-025 | Not started | `safety` — read C5 §5.2 before touching |
| [T-027](T-027-m2-integration.md) | Integration test: no duplicate submission | T-026 | Not started | M2 exit gate |

### M3 — etcd backend

`T-030`…`T-033` need only `lock-api`. **The etcd backend does not wait for the PostgreSQL backend** —
only the parity suite does.

| Task | Title | Blocked by | Status | Notes |
|---|---|---|---|---|
| [T-030](T-030-etcd-tryacquire.md) | `EtcdLockStore.tryAcquire` | T-004 | Not started | `safety` — `ModRevision` captured **at grant time** |
| [T-031](T-031-etcd-lease-session.md) | etcd lease per session and keepAlive | T-030 | Not started | |
| [T-032](T-032-etcd-renew-release.md) | `EtcdLockStore` renew, release, inspect | T-031 | Not started | |
| [T-033](T-033-etcd-watch.md) | Watch-based `awaitRelease` | T-032 | Not started | |
| [T-034a](T-034-backend-parity.md) | Backend parity suite over both stores | T-033, T-017 | Not started | Part A, criteria 1–5 |
| [T-034b](T-034-backend-parity.md) | Backend parity: executor on etcd, divergence table | T-034a, T-027 | Not started | **M3's exit criterion. Not optional** |

### M4 — Client SDK and the correctness proof

| Task | Title | Blocked by | Status | Notes |
|---|---|---|---|---|
| [T-040](T-040-sdk-session.md) | lock-client: session, heartbeat, conservative expiry | T-016b | Not started | `critical-path` |
| [T-041](T-041-sdk-acquire.md) | lock-client: bounded acquire, full jitter, reentrancy | T-040 | Not started | **Fan-out: unblocks T-042…T-047** |
| [T-042](T-042-fencing-demo.md) | **THE fencing experiment: SIGSTOP** | T-041, T-027 | Not started | ★ **The central claim.** `critical-path`, `safety` |
| [T-043a](T-043-sim-test.md) | Deterministic simulation: world, seeds, INV-01/04/05/06 | T-041 | Not started | Part A |
| [T-043b](T-043-sim-test.md) | Deterministic simulation: resource model, INV-02/03, shrinking | T-043a | Not started | **Without this, INV-02/03 are never asserted (SC-06)** |
| [T-044](T-044-linearizability.md) | Linearizability history recorder and export | T-041 | Not started | Recorder only; does **not** carry T-043b's scope |
| [T-045](T-045-load-generator.md) | Load generator and the violation detector | T-041 | Not started | ∥ |
| [T-046](T-046-fault-matrix.md) | Fault-injection matrix runner | T-041 | Not started | ∥ |
| [T-047](T-047-executor-sdk-migration.md) | payout-executor onto the SDK | T-041, T-025 | Not started | Gives `lock-client` its only production caller |

### M5 — Cloud infrastructure

> [!WARNING]
> **`T-053` onward bills real money.** `T-050`…`T-052` and `T-054` are write-and-validate only and cost
> nothing — those can be done today, by anyone, with no cloud account. Read
> [`05-infrastructure.md`](../docs/05-infrastructure.md#gcp-cost) before applying anything.

| Task | Title | Blocked by | Status | Notes |
|---|---|---|---|---|
| [T-050](T-050-tf-root.md) | Terraform root, dev environment and the budget alert | — | Not started | **Authorable now.** Owns the only budget — must exist before the first apply |
| [T-051](T-051-tf-network.md) | Terraform module: network | — | Not started | **Authorable now**, write + validate |
| [T-052](T-052-tf-cloudsql.md) | Terraform module: cloudsql, two instances | — | Not started | **Authorable now**, write + validate |
| [T-053](T-053-tf-gke.md) | Terraform module: GKE Autopilot | T-051, M4 | Not started | `needs:cloud` — **first real apply, the meter starts here** |
| [T-054](T-054-tf-artifacts.md) | Terraform module: Artifact Registry, image build | T-053 | Not started | `needs:cloud` |
| [T-055](T-055-k8s-services.md) | Kubernetes manifests: the six services | T-054 | Not started | `needs:cloud` |
| [T-056](T-056-k8s-etcd.md) | Kubernetes: etcd StatefulSet on Autopilot | T-055 | Not started | `needs:cloud` — PDB + zone spread constraints |
| [T-057](T-057-wi-secrets.md) | Workload Identity, secrets, private connectivity | T-056 | Not started | `needs:cloud` — paydb pods stay unready until this lands |
| [T-058](T-058-console-walkthrough.md) | Console walkthrough: the click path | T-057 | Not started | Prose; **drafting is possible now** |
| [T-059](T-059-teardown.md) | Teardown, `deletion_protection`, orphan verification | T-058 | Not started | `needs:cloud` — **run the same day as T-050…T-058** |

### M6 — Observability and SRE

The **offline half** — runbooks, alert definitions, SLO objects — needs no cluster and no Java.

| Task | Title | Blocked by | Status | Notes |
|---|---|---|---|---|
| [T-060](T-060-metrics.md) | LockMetrics instrumentation | T-027 | Not started | |
| [T-061](T-061-podmonitoring.md) | PodMonitoring and metric-arrival verification | T-060, T-055 | Not started | `needs:cloud` — **the named-port trap fails silently** |
| [T-062](T-062-structured-logs.md) | Structured JSON logging | T-027 | Not started | ∥ with T-060 |
| [T-063](T-063-log-based-metrics.md) | Log-based metrics in Terraform | T-062 | Not started | Authorable offline |
| [T-064](T-064-slo-objects.md) | SLO objects in Terraform | T-061 | Not started | Authorable offline |
| [T-065](T-065-alert-policies.md) | Alert policies in Terraform | T-064 | Not started | **Twelve policies** — split a/b is expected |
| [T-066](T-066-dashboard.md) | The dashboard, committed as JSON | T-064 | Not started | Build in console and **export** — do not hand-author |
| [T-067](T-067-tracing.md) | OpenTelemetry tracing | T-027 | Not started | ∥ |
| [T-068](T-068-runbook.md) | The runbook, one entry per alert | — | Not started | **Authorable now. Pure prose.** `good first issue` |
| [T-069](T-069-game-day.md) | Game day: fire every alert on purpose | T-065, T-068 | Not started | **Checkpoint: the service is now *operated*** |

### M7 — Benchmark and publication

| Task | Title | Blocked by | Status | Notes |
|---|---|---|---|---|
| [T-070](T-070-benchmark-runner.md) | Benchmark protocol runner, environment capture | T-034b | Not started | Every number needs its command, environment and date |
| [T-071](T-071-failover-experiments.md) | Failover experiments, both backends | T-070, T-061 | Not started | `needs:cloud` — destructive, run **after** observability works |
| [T-072](T-072-comparison-table.md) | Fill the backend comparison table | T-071 | Not started | Must regenerate from committed raw data |
| [T-073](T-073-fencing-writeup.md) | The fencing experiment write-up | T-042 | Not started | Needs T-042's captures on disk |
| [T-074](T-074-readme-final.md) | README final pass and design condensation | T-072, T-073 | Not started | |
| [T-075](T-075-publication-checklist.md) | Publication checklist | T-074 | Not started | |

## 4. Reserved ids

`T-009` · `T-018` · `T-019` · `T-028` · `T-029` · `T-035`…`T-039` · `T-048` · `T-049`

**Split capacity, not spare scope.** When a task exceeds its specification, the remainder takes the next
reserved id in that milestone's gap. Never `T-017b`. They exist so that one split does not require
renumbering 63 tasks and invalidating every cross-reference in the doc set.
See [`CONTRIBUTING.md` §9](../CONTRIBUTING.md#9-when-a-task-turns-out-to-be-bigger-than-it-looked).

## 5. Three checkpoints worth pausing at

- **After `T-017`** — the PostgreSQL lock backend is *provable locally*. Mutual exclusion, expiry and
  monotonic tokens are demonstrated by tests, not asserted by prose. A good moment to re-read the ADRs
  before layering the payout path on top.
- **After [`T-042`](T-042-fencing-demo.md)** — the fencing experiment runs and produces the two-run
  contrast. **This is the first point at which the project is worth showing anyone.** If effort has to
  stop, stop here, not mid-M5.
- **After `T-069`** — a deployed, instrumented and *operated* service: SLOs, alerts, dashboards, traces,
  runbook, game-day record. Everything after this is measurement and writing.

## 6. Pointers

| Need | File |
|---|---|
| How to claim, branch, and land work | [`../CONTRIBUTING.md`](../CONTRIBUTING.md) |
| Additional rules for AI contributors | [`../AGENTS.md`](../AGENTS.md) |
| What can be started right now | [`../docs/12-parallelization-map.md`](../docs/12-parallelization-map.md) |
| Authoritative names and precedence | [`../docs/04-contracts.md`](../docs/04-contracts.md) and [`../docs/contracts/`](../docs/contracts/) |
| Why the tasks are grouped this way | [`../docs/10-delivery-plan.md`](../docs/10-delivery-plan.md) |
| Decisions not to relitigate | [`../docs/adr/`](../docs/adr/) |

**If a specification and a contract disagree, the contract wins** — open a
[contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) rather than reconciling them in
code.
