# ADR-005 — GKE Autopilot: evictions accepted as budgeted unavailability {#adr5}

**Status:** Accepted, 2026-08-21
**Deciders:** project architect
**Requirements touched:** NFR-01, NFR-06, NFR-11
**Contracts touched:** [C4 §4.4](../contracts/C4-observability.md#ct4-zero), [C5 §5.6](../contracts/C5-config-build-and-naming.md#ct5-naming)

## A5.1 Context {#adr5-context}

The project runs in project `dlock-lab`, region `europe-central2`, on **GKE Autopilot**. Autopilot removes
node management entirely — no node pools, no machine-type sizing, no upgrade choreography — which is
exactly right for a project whose subject is *distributed locking*, not capacity planning. It also removes
a whole class of "why is my pod Pending" distraction that has nothing to teach here.

The cost is real and specific. Autopilot bin-packs, consolidates workloads onto fewer nodes, and evicts
pods to do so, and it gives **no node-placement control**. A 3-replica `etcd` StatefulSet therefore sees
materially more disruption than the same StatefulSet on GKE Standard with dedicated node pools. Every
disruption of the etcd leader costs a **leader election**, and an election is a window in which the lock
service cannot grant. For a genuine production lock service you would want replicas explicitly pinned
across failure domains and then left alone; this is not that.

Note what is *not* at risk: correctness. Fencing is enforced outside the lock service — the `paydb`
fenced `UPDATE` and the rail proxy's per-account high-water mark — so an election is an
**availability** event, never a safety one. That is what makes it budgetable in the first place.

## A5.2 Decision {#adr5-decision}

**Keep Autopilot. Apply three mitigations, then measure the residual election rate rather than assume
it is zero.**

| Mitigation | Purpose | Limit of what it buys |
|---|---|---|
| `PodDisruptionBudget` over the etcd StatefulSet | Caps *voluntary* disruption to one replica at a time | Honoured by drains and consolidation; ignored by node failure and by forced eviction |
| `topologySpreadConstraints` across zones | Spreads the three replicas over three zones so one zone's churn cannot take quorum | Best-effort under Autopilot; there is no node-affinity escape hatch |
| `cluster-autoscaler.kubernetes.io/safe-to-evict: "false"` | Tells consolidation to leave these pods alone | An annotation, not a guarantee — Autopilot may still act |

Each mitigation reduces the rate. None of them makes it zero, and the ADR does not pretend otherwise.

## A5.3 The reframe: an election is a line item, not a defect {#adr5-reframe}

| Quantity | Value | Basis |
|---|---|---|
| Unavailability per etcd leader election, affected shard | ~1–2 s | **ASSUMPTION** (election timeout plus client reconnect); to be measured |
| Availability target | **99.9 % over 28 d** (SLI S1) | **ASSUMPTION** — project target, not a customer commitment; pinned by [docs/06 §6.4](../06-observability-and-slo.md#obs-slos), which rejects 99.99 % as undeliverable on Autopilot |
| Error budget at that target | **~40.3 min = 2,419 s** | Arithmetic on the 28-day window: 40,320 min × 0.001 ([docs/06 §6.5.1](../06-observability-and-slo.md#obs-arithmetic)) |
| Elections that would consume the *entire* budget | **~1,210 per 28 d** (~2,400 at 1 s) | **ASSUMPTION**, derived from the two rows above |
| Elections actually budgeted for | **≤ 60 per 28 d** = 120 s = **5 %** of the S1 budget | SLI S4, the falsifiable sub-budget T-069 measures against |

That table is the whole point of this ADR. Elections are a **budgeted expense**: at three nines the
budget would absorb on the order of a thousand of them per 28-day window before it was gone, and the
project spends only the S4 slice of that — 60 elections, 5 % — deliberately. Any change that makes elections more
frequent — a tighter lease, a chattier heartbeat, a busier cluster, an extra rolling restart in CI — is
a change that **spends availability**, and must be argued for in those terms.

This converts what looks like an operational detail ("Autopilot evicts pods, annoying") into a
**resource-allocation decision** ("Autopilot's eviction rate consumes N % of this month's budget; is the
node-management saving worth N %?"). It is the same reasoning the error-budget policy in
[docs/06-observability-and-slo.md](../06-observability-and-slo.md) applies to every other source of
unavailability, applied to the platform itself rather than exempting it.

And the artifact is better this way. A project that *avoids* the problem by pinning replicas produces a
config file. A project that measures the platform's contribution to its own error budget produces a number,
a method, and a defensible build-versus-buy argument.

## A5.4 Consequences {#adr5-consequences}

| | |
|---|---|
| **Positive** | No node pools, no machine-type sizing, no upgrade windows — cluster operation is close to free in attention terms, and none of the removed work is on the subject being studied. Autopilot's per-pod billing suits a project that idles. The forced measurement yields a genuine artifact: elections-per-month against budget. |
| **Negative** | A real, quantified availability cost that a Standard cluster with pinned replicas would not pay. No node-placement control, so zone spread is best-effort and one bad consolidation can hit two replicas. A dependency on the PDB actually being honoured — which is an assumption about Autopilot's behaviour, not a contract, and must be **verified by observation**, not assumed. |
| **What we accept** | Bounded by: the three §A5.2 mitigations; an added experiment (§A5.5) that records the election rate over the project window and compares it to the budget; and the fact that correctness is fenced elsewhere, so the failure mode is degraded availability with `lock.fenced.out` still pinned at zero ([C4 §4.4](../contracts/C4-observability.md#ct4-zero)). |

## A5.5 The extra experiment this decision buys {#adr5-experiment}

Record, over the whole project window: etcd leader-election count, wall-clock duration of each election, and
the coincident Kubernetes eviction/preemption events. Report elections/month and the resulting budget
burn as a percentage. Two comparisons make the result mean something: elections during an *idle* window
(pure Autopilot churn) versus during a load run (churn plus the project's own restarts).

## A5.6 Alternatives considered {#adr5-alternatives}

| Alternative | Why it is attractive | Why rejected |
|---|---|---|
| **GKE Standard, dedicated node pool, pod anti-affinity** | Real placement control; near-zero involuntary eviction; what you would actually run in production | Reintroduces node pools, machine-type sizing, upgrade windows and autoscaler tuning — operational surface that is **not the subject being studied**, and that has historically eaten the time budget of labs like this one. Named as the correct production answer instead, in §A5.7 |
| **Single-replica etcd** | No elections at all; simplest possible deployment | Removes consensus, which is the thing being studied. A one-node etcd is a database with a `raft` package linked in. It would make the availability number look excellent and mean nothing |
| **A managed etcd service** | Someone else owns quorum and upgrades | **GCP offers no managed etcd.** That absence is not an inconvenience, it is *data* — it is a large part of why a lock service is a build decision at all, and it belongs in the build-versus-buy section rather than being worked around |
| **Cloud Run for the stateless services, etcd elsewhere** | Cheaper, scales to zero, no cluster to operate for `lock-server` / rail proxy | No StatefulSet, no stable network identity, no attached volumes — so **etcd still has to live somewhere**, and that somewhere is a cluster. Splits the deployment story in two to avoid a problem it does not actually remove |
| **Tune lease/heartbeat to mask elections** | Shorter unavailability per event | Spends correctness margin to buy availability, in the one place the project is least willing to trade: lease length is a fencing-safety parameter, not a performance knob |

## A5.7 Revisit when {#adr5-revisit}

| Trigger | Threshold (ASSUMPTION unless measured) | Then |
|---|---|---|
| Measured elections consume a material fraction of the budget | ≥ 25 % of the 28-day S1 budget attributable to platform-driven evictions | Re-open the Standard-versus-Autopilot trade with the measured number in hand — that is the whole purpose of §A5.5 |
| The PDB is demonstrably not honoured | Any observed simultaneous eviction of two etcd replicas | Autopilot's behaviour differs from the assumption this ADR rests on; escalate to GKE Standard, and record the observation as the reason |
| The project is promoted toward anything production-shaped | Any traffic that is not the project's own harness | This ADR expires. Production wants explicit failure-domain placement and replicas left alone; Autopilot's convenience was priced against a project's expectations, not a payment service's |
| Election duration proves materially worse than assumed | Measured p99 election window > 5 s | The §A5.3 arithmetic changes shape: the budget-exhausting election count drops from ~1,210 to ~480 per 28 d, and the S4 sub-budget of 60 costs **12 %** of S1 rather than 5 % — the trade tightens sharply |
