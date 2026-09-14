# Roadmap

Eight milestones, 63 task specifications, one argument to prove.

This document is the **plan**. [`tasks/README.md`](tasks/README.md) is the **ledger** — what is actually
done. When they disagree, the ledger is right and this file needs updating.

Each milestone below maps to a GitHub milestone of the same name. Exit criteria are commands or file
states: true or false in one tree state, never a judgement call.

---

## Where the project is now

| | |
|---|---|
| **Current milestone** | **M0 — Foundations** |
| Tasks complete | 0 of 63 |
| Blocking everything | `T-001`, `T-002`, `T-003` — until the build exists, no Java task can start |
| Available in parallel right now | The documentation and Terraform-authoring lanes; see [`docs/12-parallelization-map.md`](docs/12-parallelization-map.md) |

## The three checkpoints that matter

Not every milestone is equally significant. Three are worth pausing at, and one of them is the reason
the project exists.

| Checkpoint | After | What becomes true |
|---|---|---|
| **First proof** | `T-017` | The PostgreSQL lock backend is *provable locally*. Mutual exclusion, expiry and monotonic tokens are demonstrated by tests rather than asserted by prose |
| **The claim** | **`T-042`** | **The fencing experiment runs and produces the two-run contrast.** This is the first point at which the project is worth showing anyone — it demonstrates the one thing a lock cannot do alone. If effort ever has to stop, stop here, not mid-M5 |
| **Operated** | `T-069` | A deployed, instrumented and *operated* service: SLOs, alerts, dashboards, traces, runbook, game-day record. Everything after this is measurement and writing |

**`T-042` is the critical path.** M0 → M1 → M2 → M4-through-T-042 is roughly 28 tasks. M3, M5, M6 and
M7 broaden the claim; they do not create it.

---

## M0 — Foundations

> Repository, build, module skeleton, CI. **Everything else is blocked on this.**

| | |
|---|---|
| Tasks | [`T-001`](tasks/T-001-monorepo-skeleton.md)…[`T-008`](tasks/T-008-repo-front-matter.md) |
| Parallelism | Low. `T-001` → `T-002` → `T-003` are strictly ordered; `T-004`…`T-008` can then fan out |
| Good first issues | [`T-002`](tasks/T-002-version-catalog.md) (version catalog), [`T-005`](tasks/T-005-compose-stack.md) (compose stack) |
| Needs cloud | No |

**Exit criteria**

- `./gradlew build` and `./gradlew spotlessCheck` green in a clean clone
- Every module in [C5 §5.4](docs/contracts/C5-config-build-and-naming.md#ct5-modules) present
- `lock-api` has **zero** third-party dependencies, verified by `./gradlew :lock-api:dependencies`
- CI runs on pull requests and blocks merge on failure

## M1 — PostgreSQL lock backend

> A lock service whose state you can read with `SELECT`. Taught first for exactly that reason.

| | |
|---|---|
| Tasks | [`T-010`](tasks/T-010-lockdb-migration.md)…[`T-017`](tasks/T-017-pg-testcontainers.md) |
| Parallelism | Moderate. `T-010` gates the rest; `T-013`/`T-014`/`T-015` can then run concurrently |
| Good first issues | [`T-010`](tasks/T-010-lockdb-migration.md) (the schema migration) |
| Needs cloud | No |

**Exit criteria**

- Testcontainers suite green for `store.pg`
- `SELECT * FROM lock_entry` shows a live grant while a lock is held
- Tokens strictly increase under 16 concurrent acquirers
- All eight HTTP endpoints L1–L8 exist ([C3](docs/contracts/C3-http-surfaces.md#ct3-lock))
- Takeover of an expired lease mints a **strictly greater** token — the negative control

## M2 — Protected resource and executor

> `paydb`, the double-entry ledger, the non-idempotent rail, and **both fencing enforcement points.**

| | |
|---|---|
| Tasks | [`T-020`](tasks/T-020-paydb-migration.md)…[`T-027`](tasks/T-027-m2-integration.md) |
| Parallelism | **High.** `rail-stub` and `rail-proxy` are independent of `payment-resource` |
| Good first issues | [`T-023`](tasks/T-023-rail-stub.md) (the deliberately-bad rail — self-contained, zero dependencies) |
| Needs cloud | No |

**Exit criteria**

- A local payout moves `PENDING → POSTED` with balanced double-entry ledger rows
- The fenced `UPDATE` affects **0 rows** for a stale token
- `rail_high_water` refuses a stale token **before the rail is touched**
- Both kill switches exist, default to safe, and log at `WARN` when disabled

## M3 — etcd backend

> The second backend, with `ModRevision` captured at grant time as the token.

| | |
|---|---|
| Tasks | [`T-030`](tasks/T-030-etcd-tryacquire.md)…[`T-034`](tasks/T-034-backend-parity.md) |
| Parallelism | Moderate, and **fully parallel with M2** — different modules entirely |
| Needs cloud | No (etcd runs locally) |

**Exit criteria**

- The *same* `payout-executor`, unchanged, passes the M2 suite with `lock.backend=etcd`
- A session-death test shows all of that session's locks released
- One parity suite passes against both backends
- The divergence table records where the two backends legitimately differ

## M4 — Client SDK and the correctness proof

> **The milestone the project exists for.**

| | |
|---|---|
| Tasks | [`T-040`](tasks/T-040-sdk-session.md)…[`T-047`](tasks/T-047-executor-sdk-migration.md) |
| Parallelism | Moderate. `T-043`…`T-046` are independent of each other once the SDK lands |
| Needs cloud | No |

**Exit criteria**

- **`T-042` produces both captured outcomes from one script**: fencing off → a corrupted ledger and two
  rail submissions; fencing on → one submission plus a `fenced_out` event carrying the presented and
  highest tokens. Both committed as fixtures
- The stale worker is rejected **twice, independently** — disabling either enforcement point still
  leaves the other rejecting
- A deterministic-simulation test fails on a known-bad seed and passes on the fixed implementation
- Every correctness invariant has a check that fails when the invariant is deliberately broken

## M5 — Cloud infrastructure

> Terraform, GKE Autopilot, two Cloud SQL instances, the etcd StatefulSet.

| | |
|---|---|
| Tasks | [`T-050`](tasks/T-050-tf-root.md)…[`T-059`](tasks/T-059-teardown.md) |
| Parallelism | **High for authoring, serialised for applying.** All HCL can be written and `terraform validate`d in parallel with M1–M4 |
| Needs cloud | **Yes — `T-053` onward bills real money** |

> [!WARNING]
> **Read [`docs/05-infrastructure.md`](docs/05-infrastructure.md#gcp-cost) before starting.** M5 and M6
> accrue real charges: two Cloud SQL instances (one REGIONAL), GKE Autopilot pods, a 3-member etcd
> StatefulSet, Managed Prometheus ingestion. Batch M5 and M6 into **one focused day and tear down the
> same day.** The budget alert is created in `T-050`, before the first apply — that ordering is
> deliberate. Use a dedicated project: deleting the project is the only teardown guaranteed complete.

**Exit criteria**

- A budget alert exists **before** the first `terraform apply`
- `apply` → serve traffic → `destroy` leaves **zero billable resources**, verified against the billing
  report the following morning
- `dlock-pg-lock` REGIONAL and `dlock-pg-pay` ZONAL, on separate instances
- The etcd StatefulSet has a PodDisruptionBudget and zone `topologySpreadConstraints`

## M6 — Observability and SRE

> Metrics, SLOs, an error-budget policy with named consequences, and a game day.

| | |
|---|---|
| Tasks | [`T-060`](tasks/T-060-metrics.md)…[`T-069`](tasks/T-069-game-day.md) |
| Parallelism | **High for the offline half.** Runbooks, alert definitions and SLO objects are authored with no cluster running; only their verification needs the cloud |
| Good first issues | [`T-068`](tasks/T-068-runbook.md) (the runbook — prose, no cluster needed) |
| Needs cloud | Partially — authoring no, verification yes |

**Exit criteria**

- Every metric in [C4 §4.2](docs/contracts/C4-observability.md#ct4-metrics) is queryable in Managed
  Prometheus, with the named-port scrape verified **by observation**
- The cardinality check fails the build when a key, payout id, account id or token is added as a metric
  tag
- Every alert links to a runbook section
- **The game day fires every alert policy on purpose**, and records detection latency and
  time-to-runbook-step for each

## M7 — Benchmark and publication

> The measured comparison, the failover experiment, the write-up.

| | |
|---|---|
| Tasks | [`T-070`](tasks/T-070-benchmark-runner.md)…[`T-075`](tasks/T-075-publication-checklist.md) |
| Parallelism | Low — depends on nearly everything else |
| Needs cloud | Yes, for `T-071` |

**Exit criteria**

- p50/p99 acquire latency per backend, on one harness, with the command and environment recorded
- Killing the **regional** lock instance's primary produces a measured, logged availability dip and a
  recovery **with zero safety events**
- The comparison table regenerates from raw data files committed to the tree
- The recommendation (etcd) is stated **with its cost**, not only its benefit
- A reader can trace one fencing token from SDK to Postgres row to rail proxy using only the docs and
  the code

---

## What is deliberately not on this roadmap

Adding any of these changes the project into a different one. Each is on the
[non-goals list](docs/00-charter.md#ch-nongoals) with its reasoning:

custom Raft implementation · multi-region or global locking · shared/exclusive lock modes · strict FIFO
fairness · multi-tenancy, namespaces, quotas · an admin UI · authentication and authorisation on the
lock API · key-space sharding · real payment rails.

Proposing one of these is legitimate — open a discussion and expect to be pointed at the reasoning
first. Several have a documented trigger condition that would make them correct; sharding, for
instance, becomes correct when write load approaches one Raft group's measured ceiling, and the project
is currently three orders of magnitude below it.

## Reserved task ids

`T-009`, `T-018`, `T-019`, `T-028`, `T-029`, `T-035`…`T-039`, `T-048`, `T-049` are **split capacity**,
not spare scope. When a task turns out to be bigger than its specification, the remainder takes the next
reserved id in that milestone's gap. They exist so that one split does not require renumbering 63 tasks
and invalidating every cross-reference in the doc set. See
[`CONTRIBUTING.md` §9](CONTRIBUTING.md#9-when-a-task-turns-out-to-be-bigger-than-it-looked).
