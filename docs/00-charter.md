# 00 — Project charter

> **Status:** baseline · **Owner:** architecture · **Last reviewed:** 2026-09
>
> Every other document in this repository cites this file for scope. If you are reading the doc set for
> the first time, start here, then [01](01-requirements.md) and [02](02-domain-model.md).

## 0.1 What this project is, and what it is not {#ch-what}

The product of this repository is not a lock service. The product is **evidence.**

A small, complete, readable system that (a) implements a correctness-grade distributed lock over two
independent backends, (b) **proves by experiment** that mutual exclusion alone is insufficient and that
fencing tokens are what actually protect the resource, and (c) is operated on real infrastructure with
SLOs, alerts, runbooks and a game day, so that the operational half is demonstrated rather than
asserted.

It is a **reference implementation and a teaching artifact. It is not production-ready and must never
be deployed in front of real money.** Concretely, what is missing for production: no authentication or
authorisation on the lock API, no multi-tenancy, no quotas, single region, no key-space sharding, no
capacity headroom analysis beyond a single benchmark, no data-retention or PII handling story, no
24/7 rotation. Those absences are deliberate, catalogued in [0.5](#ch-nongoals), and restated in
[`SECURITY.md`](../SECURITY.md) where somebody evaluating the code for real use will actually see them.

**The domain is fictional.** The organisation is a mid-size payment service provider, referred to
throughout as *the PSP*; it is deliberately unnamed, as are all counterparties. Every quantity in this
document set is an **explicitly labelled assumption** invented for the project. Nothing here is derived
from, or describes, any real organisation, customer, partner, employer, or production system.

**Why a payout executor and not a balance withdrawal.** The textbook demonstration protects a bank
balance with a lock, and it is wrong on its own terms: a single-row
`UPDATE accounts SET balance = balance - :amt WHERE id = :id AND balance >= :amt` is already atomic and
already safe in PostgreSQL, so it needs no distributed lock at all, and building one around it teaches
a reflex that will cost somebody real money later.

The lock here is warranted for exactly one reason: **the critical section spans a side effect the
database transaction cannot roll back** — a submission to an external payment rail that is deliberately
non-idempotent. That is the only shape for which a distributed lock is the right answer, and saying so
plainly is part of the deliverable. The first question to ask of any lock proposal is whether the
resource can be made idempotent instead; check that every time, before reaching for this.

## 0.2 V0 / V1 / V2, and which one this is {#ch-versions}

"Build a distributed lock service" is not one project. It is three, differing by an order of magnitude
in cost.

| | **V0 — Prototype** | **V1 — Production, one region** | **V2 — Platform** |
|---|---|---|---|
| Goal | Prove the model, learn | One team's correctness locks in production | Company-wide, multi-tenant, multi-region |
| Substrate | etcd or PostgreSQL | etcd, sharded | Custom Raft or managed global store |
| API + SDK | Java only, thin | Java + one more language, hardened | 4+ languages, generated clients |
| Fencing | Demonstrated | Enforced end to end, one resource type | All resource types, proxy for legacy |
| Availability target | none | 99.9% | 99.99% |
| On-call | none | Business hours, best effort | 24/7 with an error budget |
| Multi-tenancy | no | Namespaces | Quotas, isolation, chargeback |
| Effort | 1 engineer, 4–6 weeks | 3–4 engineers, 4–6 months | 6–8 engineers, 12–18 months |

**This project builds V0** — prototype grade, with no availability commitment to anyone.

**With two deliberate exceptions**, because they are the point of the exercise rather than a step
towards production:

1. **Fencing is enforced end to end, not merely demonstrated.** Two independent enforcement points
   exist in two separate processes ([architecture 3.3](03-architecture.md#arch-boundaries)), and the
   experiment that proves it is a graded deliverable ([0.4](#ch-success)).
2. **V1's operational practices are imported wholesale.** SLIs, SLOs, an error-budget policy with
   written consequences, symptom paging, runbooks, a game day with measured detection latency, a
   blameless postmortem template, and a toil register. Operating the thing *is* half of what this
   repository is for, so the operational surface is V1-shaped even though the service is V0-shaped. The
   availability *number* is a target measured against a synthetic workload, not a commitment to anyone.

Everything else V1 would add — sharding, authentication and authorisation, quotas, migration tooling, a
second-language SDK — is out of scope ([0.5](#ch-nongoals)).

## 0.3 The named first customer {#ch-customer}

There is a rule in platform engineering worth more than most estimates: **if there is no committed
first customer, the correct decision is not to build.** A lock service with no caller is a platform
with no premise, and "every team just keeps using their own Redis lock" is the most likely way this
class of project dies.

So this design has one, and only one:

| | |
|---|---|
| First customer | **the Payouts team** of the fictional PSP (`payout-executor`) |
| What they need | Exactly-once submission of a pending payout to an external, non-idempotent rail |
| Why they cannot self-serve | The rail has no idempotency key and no compare-and-set; the side effect is outside any transaction they control |
| Commitment modelled | One workload, one lock key pattern (`payout:{account_id}`), one resource type |
| Owner of the lock service | The Platform team |

**The single-customer constraint is load-bearing, not an accident.** It authorises every simplification
in [0.5](#ch-nongoals): one key pattern means no quota story; one caller means no authentication story;
one resource type means fencing has to work in exactly two places instead of everywhere. When a second
customer appears, this charter is invalid and V1 scoping starts.

## 0.4 Success criteria {#ch-success}

Each is true or false at a given commit; none is a sentiment. Anything not on this list is not a
success criterion.

| # | Criterion |
|---|---|
| SC-01 | `./gradlew build` from a clean clone builds every module and runs the full test suite with no network access beyond the dependency cache and Testcontainers images. |
| SC-02 | The same `payout-executor`, unchanged, runs against **both** lock backends (M1 PostgreSQL, M3 etcd) selected by one configuration key, and the correctness suite passes against both. |
| SC-03 | **The `SIGSTOP` experiment produces two captured outcomes from one script:** with fencing disabled, a *corrupted ledger* — two rail submissions and a double debit for one payout, visible as two log events and a failing ledger-balance assertion; with fencing enabled, a *rejected write* — one successful submission plus a `fenced_out` log event carrying the presented and highest tokens. Both runs are captured as structured logs and committed as fixtures. |
| SC-04 | The stale worker is rejected **twice, independently**: by the PostgreSQL resource (`WHERE fence < :token` affects zero rows) and by the `rail-proxy` (stale token → refusal before the rail is touched). Disabling either one still leaves the other rejecting. |
| SC-05 | A deterministic-simulation test fails on a known-bad seed and passes on the fixed implementation, and a linearizability check over the lock history is green under injected message loss, reorder and pause. |
| SC-06 | Every correctness invariant INV-01…INV-06 ([01 §1.7](01-requirements.md#br-invariants)) has at least one automated check that fails when the invariant is deliberately broken. |
| SC-07 | The cloud environment stands up from `terraform apply` in a dedicated project and tears down to zero billable resources with `terraform destroy`, with a budget alert configured before the first apply. |
| SC-08 | Killing the **regional** lock Cloud SQL instance's primary produces a measured, logged acquire-availability dip and a recovery, without any safety event; the pay instance is untouched, which is why the experiment is interpretable. |
| SC-09 | A game day fires **every** alert policy on purpose; for each, detection latency and time-to-runbook-step are recorded in a results table. |
| SC-10 | The error-budget policy is written with named consequences, and one month of measured SLI data is reported against it — including the etcd leader elections that GKE Autopilot eviction causes, counted as a budgeted expense rather than an anomaly. |
| SC-11 | No metric in the system is tagged with a lock key, a payout id, an account id, or a token value; a cardinality check enforces this in CI. |
| SC-12 | A reader can trace one fencing token from SDK to PostgreSQL row to rail proxy using only this doc set and the code, without asking the author a question. |

SC-12 is the one that most directly serves an open-source contributor, and it is the hardest to
self-assess. If you read this doc set and could *not* trace the token end to end, that is a
[documentation bug](../.github/ISSUE_TEMPLATE/docs.yml) worth reporting — the author cannot see their
own blind spot.

## 0.5 Non-goals {#ch-nongoals}

Written down because *not writing them down* is how a teaching project becomes an unfinished platform.

| Out of scope | Why | Discussed in |
|---|---|---|
| Custom Raft implementation | The backends already provide consensus; writing Raft is a different project with a different lesson. etcd's `ModRevision` is a better token than one we would implement. | [ADR-001](adr/ADR-001-etcd-as-the-consensus-store.md), [arch 3.11](03-architecture.md#arch-tech) |
| Multi-region / global locking | Cross-region quorum latency is ruinous and it obscures the single lesson this project exists to teach. Single region, documented as a constraint. | [infra](05-infrastructure.md), [risks](09-risks.md) |
| Shared / exclusive (read-write) modes | Extra state in the state machine, no new insight about fencing. | [contracts](04-contracts.md) |
| Strict FIFO fairness | Barging locks with jittered backoff demonstrate the mechanics; a fairness queue adds a starvation-versus-throughput analysis that is a separate topic. | [arch](03-architecture.md) |
| Multi-tenancy, namespaces, quotas | An operations concern, not a correctness one, and there is exactly one customer ([0.3](#ch-customer)). | [risks](09-risks.md) |
| Admin UI | An operator CLI plus `psql`/`etcdctl` covers break-glass. A UI is the first thing to cut. | [ops](08-operations.md) |
| AuthN/AuthZ on the lock API | One in-cluster caller; adding it would double the surface without touching the lesson. **This alone disqualifies the project from production.** | [`SECURITY.md`](../SECURITY.md), [risks](09-risks.md) |
| Key-space sharding | One shard is arithmetically sufficient for the assumed load; sharding is designed on paper only. | [arch 3.8](03-architecture.md#arch-scale) |
| Real payment rails, real money, real PII | Fictional domain; the rail is a stub. | [0.1](#ch-what) |

Never cut, at any schedule pressure: **fencing tokens**, **conservative client-side expiry in the
SDK**, **the correctness harness**, **the runbook**. Without the first the project has no premise;
without the rest it is a claim rather than a system.

Several non-goals have a **documented trigger condition** that would make them correct — sharding, for
instance, becomes correct when write load approaches one Raft group's measured ceiling, and the design
is currently three orders of magnitude below it ([arch 3.8](03-architecture.md#arch-scale)). Proposing
one of these is legitimate; open a discussion and expect to be pointed at the trigger first.

## 0.6 What this project demonstrates {#ch-demonstrates}

The claims this repository is built to support, and where each is exercised. A claim not on this list
is not one the project makes.

| Claim | Where it is exercised | What would falsify it |
|---|---|---|
| **Mutual exclusion alone does not protect a resource** | [`T-042`](../tasks/T-042-fencing-demo.md), [correctness 7.x](07-correctness-and-testing.md) | The fencing-off run failing to corrupt anything |
| **Fencing tokens must be enforced by the resource, not the lock service** | [arch 3.3](03-architecture.md#arch-boundaries), fence points (a) and (c) | Either enforcement point living inside `lock-server` and still catching the bug |
| A lock is warranted only when the critical section spans a non-rollback-able side effect | [0.1](#ch-what), [ADR-004](adr/ADR-004-payout-executor-as-the-protected-operation.md) | Showing the same protection with an idempotent resource and no lock |
| Client-side expiry is a **liveness** device and never a safety one | [arch 3.5](03-architecture.md#arch-sdk), [ADR-006](adr/ADR-006-conservative-client-side-expiry.md) | A safety property that depends on the client's clock |
| Two substrates, one SPI, honestly compared | [M3](10-delivery-plan.md), [`T-072`](../tasks/T-072-comparison-table.md) | A comparison without reproducible numbers |
| etcd's tokens are monotonic **by construction**; PostgreSQL's are monotonic **by procedure** | [ADR-002](adr/ADR-002-fencing-token-source.md), [arch 3.6](03-architecture.md#arch-failures) | A restore procedure that makes the PostgreSQL sequence safe without operator discipline |
| Operating a service is half of building it | [observability](06-observability-and-slo.md), [ops](08-operations.md), game day | SLOs and runbooks that were never exercised |

## 0.7 How to read this doc set {#ch-map}

Read 00 → 01 → 02 first: they are the problem. Everything after is a solution and cites them. The
index with reading paths for different purposes is [`docs/README.md`](README.md).

| File | Purpose | Read it when |
|---|---|---|
| [`00-charter.md`](00-charter.md) | Scope, version framing, success criteria, non-goals (this file) | First, always |
| [`01-requirements.md`](01-requirements.md) | Use cases, FR/NFR, **the correctness invariants** | Before proposing any change |
| [`02-domain-model.md`](02-domain-model.md) | Entities, the payout and lock state machines, where each invariant is enforced | Before touching payout or lock state |
| [`03-architecture.md`](03-architecture.md) | Modules, boundaries, backends, failure catalogue | Before adding a module or dependency |
| [`04-contracts.md`](04-contracts.md) | Index to every pinned identifier; precedence rules | While implementing anything |
| [`05-infrastructure.md`](05-infrastructure.md) | Cloud project, Terraform and Kubernetes inventory, cost | **Before `terraform apply`** |
| [`06-observability-and-slo.md`](06-observability-and-slo.md) | SLIs, SLOs, error-budget policy, alerts, cardinality rules | Before adding a metric or alert |
| [`07-correctness-and-testing.md`](07-correctness-and-testing.md) | Test pyramid, the `SIGSTOP` experiment, simulation, linearizability | Before writing a test |
| [`08-operations.md`](08-operations.md) | Runbooks, break-glass, game day, postmortem template, toil register | During a game day, or when something is broken |
| [`09-risks.md`](09-risks.md) | Risks with probability, impact, mitigation, owner | At every milestone boundary |
| [`10-delivery-plan.md`](10-delivery-plan.md) | Milestones, sequencing, quality gates, definition of done | When planning what to do next |
| [`11-glossary.md`](11-glossary.md) | Every term of art used in this repository | Whenever a word is doing more work than it looks like |
| [`12-parallelization-map.md`](12-parallelization-map.md) | The dependency graph: what can be worked on simultaneously | **When looking for something to start now** |
| [`adr/`](adr/) | One decision per file: context, options, decision, consequences | Before relitigating a decision |
| [`contracts/`](contracts/) | C1 schemas · C2 Java API · C3 HTTP · C4 observability · C5 config/build/naming | Constantly, while implementing |
| [`../CONTRIBUTING.md`](../CONTRIBUTING.md) | How to find, claim and land work | Before your first contribution |
| [`../AGENTS.md`](../AGENTS.md) | Additional obligations for AI contributors | Before generating anything |
| [`../tasks/`](../tasks/) | 63 implementation specifications, each traceable to a requirement | When picking up work |

## 0.8 Open questions {#ch-deferred}

| # | Item | Position today |
|---|---|---|
| D-03 | gRPC surface alongside HTTP | HTTP/JSON only for now; the shape is designed so that a gRPC service definition is additive. Revisit at M3 |
| D-04 | Lease TTL and heartbeat defaults | Proposed defaults in [C5 §5.1](contracts/C5-config-build-and-naming.md#ct5-config); to be **measured** in the benchmark, not guessed once |
| D-05 | Whether the etcd StatefulSet stays on Autopilot or moves to Standard | Stay on Autopilot and **measure** election frequency against the error budget; revisit only if the measurement exceeds the budgeted allowance |
| D-06 | Ambiguous-rail resolution automation | Manual and reconciler-assisted resolution first ([02 §2.3](02-domain-model.md#dm-payout-fsm)); automatic resolution only once the reconciler is trusted |
| D-07 | Cost of a standing cloud environment | Assumed torn down same-day; a standing environment needs a re-costing |

**Resolved since the baseline:**

| # | Item | Resolution |
|---|---|---|
| D-01 | Version control and publication | **Resolved.** The repository is under git with trunk-based development and is published publicly — see [ADR-012](adr/ADR-012-git-and-public-publication.md), which supersedes [ADR-011](adr/ADR-011-no-git-initialisation-yet.md) |
| D-02 | Licence and public-repo hygiene files | **Resolved.** Apache-2.0 with DCO sign-off; the full community health set is in place — see [ADR-014](adr/ADR-014-apache-2-and-dco.md) |
