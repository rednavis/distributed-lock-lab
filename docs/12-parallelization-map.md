# 12 — Parallelization map {#parallel}

> **Status:** baseline · **Owner:** maintainers · **Last reviewed:** 2026-09
>
> **This document answers one question: what can I start right now?**
>
> [`10-delivery-plan.md`](10-delivery-plan.md) explains *why* the work is ordered this way.
> [`../tasks/README.md`](../tasks/README.md) records what is *actually done*. This file sits between
> them and says what is *available*.

## 12.1 How to read this {#pm-howto}

The 63 task ids look like a sequence. **They are not a schedule.** `T-023` can legitimately land before
`T-011`; the numbering encodes grouping and stable cross-references, nothing more.

What actually constrains order is each specification's **Preconditions** line. A task whose
preconditions are unmerged cannot be implemented, because the files it edits do not exist yet. A task
whose preconditions *are* merged can be claimed immediately, regardless of what else is in flight.

| Symbol | Meaning |
|---|---|
| **→** | Hard dependency. The left task must be **merged**, not merely claimed |
| **∥** | These tasks touch disjoint files and can proceed simultaneously |
| **◆** | Fan-out point: merging this unblocks several tasks at once. Prioritise review |
| **$** | Costs real money. Requires a cloud project you are willing to delete |

## 12.2 Available right now {#pm-now}

With **zero tasks merged**, these lanes are genuinely open. They are not busywork offered to
newcomers — the runbook is on the never-cut list ([00 §0.5](00-charter.md#ch-nongoals)), and every hour
of Terraform authored offline is an hour not spent paying for a cluster.

| Task | Lane | Needs | Why it is unblocked |
|---|---|---|---|
| [`T-001`](../tasks/T-001-monorepo-skeleton.md) ◆ | Build | JDK 25, Gradle | **Blocks everything Java. Highest priority in the repository** |
| [`T-050`](../tasks/T-050-tf-root.md) | Terraform | Terraform CLI only | HCL is authored and `validate`d offline; nothing applies |
| [`T-051`](../tasks/T-051-tf-network.md) | Terraform | Terraform CLI only | Same — write and validate, no apply |
| [`T-052`](../tasks/T-052-tf-cloudsql.md) | Terraform | Terraform CLI only | Same |
| [`T-068`](../tasks/T-068-runbook.md) | Docs | A text editor | Runbook prose is authored against the alert definitions in doc 06, not against a running cluster |
| [`T-058`](../tasks/T-058-console-walkthrough.md) | Docs | A text editor | The click path is documented from doc 05's inventory |

> [!NOTE]
> **`T-001` is the bottleneck and should be reviewed the day it is opened.** Until the Gradle build
> exists, no Java task in any milestone can start. If you are a maintainer with limited review time,
> spend it here.

## 12.3 The lanes {#pm-lanes}

Five lanes that run largely independently of each other. A contributor can stay in one lane
indefinitely and never collide with another.

| Lane | Milestones | Depends on the Java build? | Costs money? |
|---|---|---|---|
| **Build and tooling** | M0 | Is the build | No |
| **Lock core** | M1, M3 | Yes | No |
| **Payments and rail** | M2 | Yes | No |
| **Infrastructure** | M5 | **No, for authoring** | Only from `T-053` |
| **Observability and docs** | M6, M7 | Partially | Only for verification |

### Lane A — Build and tooling (M0)

```
T-001 ◆ ──→ T-002 ──→ T-003 ◆ ──┬──→ T-004 ◆
                                 ├──→ T-005   ∥
                                 ├──→ T-006 ──→ T-007
                                 └──→ T-008
```

The only strictly serial stretch in the repository is `T-001 → T-002 → T-003`: the settings file must
exist before the version catalog, and the catalog before the convention plugins that consume it. After
`T-003`, five tasks fan out.

`T-004` is a second fan-out point — it creates `lock-api`, which six modules depend on.

### Lane B — Lock core (M1, then M3)

```
T-010 ◆ ──┬──→ T-011 ──→ T-012 ──┐
          ├──→ T-013            ├──→ T-016a ──→ T-016b ──→ T-017
          ├──→ T-014     ∥      │
          └──→ T-015            ┘

M3 (needs T-016b for the parity suite, but T-030..T-033 need only T-004):
T-030 ──→ T-031 ──→ T-032 ──→ T-033 ──→ T-034a ──→ T-034b
```

`T-010` (the schema migration) is the fan-out point: `T-011`, `T-013`, `T-014` and `T-015` all become
available at once, and four people can work them simultaneously.

**`T-030`…`T-033` only need `lock-api` from M0.** The etcd backend does **not** depend on the
PostgreSQL backend being finished — only the *parity suite* (`T-034`) does. If two contributors arrive
at once, one on each backend is an excellent split.

### Lane C — Payments and rail (M2)

```
T-020 ◆ ──┬──→ T-021 ──→ T-022 ──┬──→ T-025 ──→ T-026 ──→ T-027
          │                      │
T-023 ────┴──→ T-024 ────────────┘
   (needs only M0)
```

**`T-023` (`rail-stub`) needs nothing from M1 or M2.** It is a self-contained, zero-dependency service
whose entire job is to behave badly on purpose. It is the single best first Java contribution in the
repository and can be built the moment M0 lands.

M2 and M3 touch **entirely disjoint modules** and run fully in parallel.

### Lane D — SDK and the correctness proof (M4)

```
T-040 ──→ T-041 ◆ ──┬──→ T-042  ★ THE CENTRAL CLAIM
                    ├──→ T-043a ──→ T-043b
                    ├──→ T-044          ∥
                    ├──→ T-045
                    ├──→ T-046
                    └──→ T-047
```

After `T-041`, six tasks fan out. `T-042` is the one that matters — see
[10.4](10-delivery-plan.md#dp-critical).

### Lane E — Infrastructure (M5)

```
authoring (no cloud account, no billing):
T-050 ∥ T-051 ∥ T-052 ∥ T-054  ── all write-and-validate only

applying ($, strictly serial, one focused day):
T-050 $ ──→ T-051 $ ──→ T-052 $ ──→ T-053 $ ──→ T-054 $ ──→
T-055 $ ──→ T-056 $ ──→ T-057 $ ──→ T-058 ──→ T-059 $
```

**The authoring half and the applying half are different activities with different prerequisites.**
All the HCL can be written and `terraform validate`d by anyone, in parallel with every Java milestone,
at zero cost. Only the apply sequence is serial, expensive, and maintainer-coordinated.

> [!WARNING]
> **`T-053` onward bills real money.** Two Cloud SQL instances (one REGIONAL), GKE Autopilot pods, a
> 3-member etcd StatefulSet, Managed Prometheus ingestion. Read
> [`05-infrastructure.md`](05-infrastructure.md#gcp-cost) first. The budget alert is created in
> `T-050`, **before** the first apply — that ordering is deliberate, and `T-059` (teardown) runs the
> same day.

### Lane F — Observability, SRE and publication (M6, M7)

```
offline (no cluster):          verification ($, needs the M5 stack up):
T-060 ──→ T-061                T-061 verify, T-063, T-064, T-065,
T-062 ──→ T-063                T-066, T-067, T-069
T-064 ──→ T-065
T-066 (author)                 M7:
T-067                          T-070 ──→ T-071 $ ──→ T-072
T-068  ← available NOW         T-073 (needs T-042 captures)
                               T-074 ──→ T-075
```

Same split as Lane E: **runbooks, alert definitions and SLO objects are authored with no cluster
running**; only their verification needs the cloud. `T-068` (the runbook) is available today and is
pure prose.

`T-066` (the dashboard) is the exception — build it in the console and **export** it. Do not
hand-author the JSON.

## 12.4 Fan-out points, ranked {#pm-fanout}

Merging these unblocks the most work. Maintainers: review these first.

| Task | Unblocks | Lane |
|---|---|---|
| [`T-001`](../tasks/T-001-monorepo-skeleton.md) | **Every Java task in the repository** | Build |
| [`T-003`](../tasks/T-003-convention-plugins.md) | `T-004`…`T-008` (5 tasks) | Build |
| [`T-004`](../tasks/T-004-lock-api-types.md) | All of M1, M2 and M3 — six modules depend on `lock-api` | Build |
| [`T-010`](../tasks/T-010-lockdb-migration.md) | `T-011`, `T-013`, `T-014`, `T-015` (4 tasks) | Lock core |
| [`T-020`](../tasks/T-020-paydb-migration.md) | `T-021`, and with `T-023` also `T-024` | Payments |
| [`T-041`](../tasks/T-041-sdk-acquire.md) | `T-042`…`T-047` (6 tasks, including the central claim) | SDK |
| [`T-016b`](../tasks/T-016-lock-server-rest.md) | `T-017`, `T-025`, `T-041`, `T-034` | Lock core |

## 12.5 Suggested splits by team size {#pm-teams}

| Contributors | Split |
|---|---|
| **1** | Follow the critical path: M0 → M1 → M2 → `T-042`. Ignore the lanes entirely |
| **2** | One on Lane B (lock core), one on Lane C (payments and rail) after M0. They converge at `T-027` |
| **3** | Add Lane E: one person authoring all of M5's Terraform offline, in parallel, from day one |
| **4–5** | Split Lane B by backend — one on PostgreSQL (`T-011`…`T-017`), one on etcd (`T-030`…`T-033`). They converge at `T-034` |
| **6+** | Add Lane F's offline half (runbooks, alert definitions) and the M7 write-ups. Beyond this, review capacity becomes the binding constraint, not contributor capacity |

**Review capacity is the real ceiling.** With one maintainer and a five-working-day review commitment
([`GOVERNANCE.md`](../GOVERNANCE.md#review)), roughly five to eight open pull requests is the
sustainable steady state. Past that, the queue is the bottleneck and the answer is more maintainers
([`GOVERNANCE.md` §4](../GOVERNANCE.md#4-becoming-a-maintainer)), not more contributors.

## 12.6 Collision risks {#pm-collisions}

Places where two contributors working legitimately in parallel can still conflict. None is a reason to
serialise; each is a reason to say so in your issue.

| Risk | Who collides | Mitigation |
|---|---|---|
| **The version catalog** | Anyone adding a dependency | `gradle/libs.versions.toml` is a single file every module touches. Expect rebases; keep catalog edits to their own commit so they rebase cleanly |
| **`tasks/README.md`** | **Everyone** — updating the ledger is in the definition of done | Edit only your own row. A conflict here is a trivial rebase, but it will happen on nearly every PR |
| **`lock-api`** | M1, M2 and M3 contributors simultaneously | Changes need **two approvals** and are wire-compatibility changes. If your task needs a new type there, say so in the issue early so others can plan around it |
| **Contract amendments** | Any contributor who finds a contract wrong | Amendments block dependent tasks by design. Open the [contract-change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) immediately rather than at the end of your work |
| **Shared test fixtures** | M2 and M4 | Fixtures live inside the owning module. If you need someone else's, that is a signal the fixture belongs in a shared test source set — raise it, do not copy it |
| **The cloud project** | Two people applying Terraform at once | **Never do this.** The apply sequence is serial and maintainer-coordinated. State locking will save you; the bill will not |

## 12.7 Keeping this document true {#pm-maintenance}

This map is derived from the **Preconditions** line of each task specification. When a specification's
preconditions change, this file is wrong until somebody fixes it.

- Changing a precondition requires updating this map **in the same pull request**.
- The `status: ready` label on an issue is the machine-readable version of this document. If they
  disagree, this document is the one to trust and the label is the bug.
- A contributor who finds this map wrong should [report it](../.github/ISSUE_TEMPLATE/docs.yml) — a
  wrong dependency graph costs somebody a wasted afternoon, which is exactly the failure this document
  exists to prevent.
