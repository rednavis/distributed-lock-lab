# ADR-001 — etcd as the consensus store {#adr1}

**Status:** Accepted, 2026-08-21
**Deciders:** project architect
**Requirements touched:** FR-01, FR-02, FR-09, FR-10, NFR-01, NFR-03, NFR-05, INV-04, INV-06
**Contracts touched:** [C2 §2.5](../contracts/C2-java-api.md#ct2-spi), [C1 §1.7](../contracts/C1-database-schemas.md#ct1-seq), [C5 §5.3](../contracts/C5-config-build-and-naming.md#ct5-catalog)
**Related:** [00-charter §0.5](../00-charter.md#ch-nongoals) (custom Raft is a non-goal), [ADR-002](ADR-002-fencing-token-source.md)

## A1.1 Context {#adr1-context}

The project must grant a mutually exclusive, expiring lease and hand out a **strictly increasing per-key
token** (FR-01, FR-02, INV-04, INV-06). That needs a store with linearizable reads and compare-and-set,
plus a durable revision counter. Two of these are cheap to buy and one is expensive to build.

The temptation in this problem space is to reach for whatever key-value store is already deployed —
usually Redis — because acquire looks like `SET key NX PX`. That works right up to the first failover,
and then it does not, silently, at the exact moment the money moves.

The honest framing: **the interesting engineering here is not the consensus algorithm.**
It is the lease API, the conservative client-side deadline (FR-11…FR-13), and the two fencing points
(FR-15, FR-17). Those are the parts a real team gets wrong. Consensus is a commodity; buy it.

## A1.2 Decision {#adr1-decision}

**We build the lock API, the SPI seam and the client SDK exactly as designed — that is where the
correctness lives — and back them with etcd, because etcd already provides linearizable CAS, leases and
a monotonic revision counter that is a better fencing token than one we would implement.**

| Part | Position |
|---|---|
| Consensus substrate | **etcd 3.6**, 3-replica StatefulSet, recommended production choice |
| Token | The key's `ModRevision`, captured at grant time ([ADR-002](ADR-002-fencing-token-source.md), FR-10) |
| PostgreSQL backend | **Kept first-class, not a stepping stone** (FR-09). Taught first because its state is inspectable with `SELECT`; measured against etcd as a deliverable |
| The seam | One `LockStore` / `SessionRegistry` SPI ([C2 §2.5](../contracts/C2-java-api.md#ct2-spi)); both backends observably identical |
| Replacement bar | Only on **evidence**: sustained load past what sharded etcd absorbs, **or** a lock semantic etcd cannot express. Not on taste, not on benchmark folklore |

## A1.3 Consequences {#adr1-consequences}

| | |
|---|---|
| **Positive** | Linearizable CAS and lease expiry are the store's problem, not ours (FR-01, FR-03). `ModRevision` gives INV-04 for free — no counter to protect. A member loss is survivable and testable (NFR-05). The SPI forces the API to stay backend-agnostic, which is what makes the PostgreSQL-vs-etcd comparison meaningful at all. |
| **Negative** | A stateful workload to operate: quorum, disk latency sensitivity, leader elections, compaction, and defrag. On Autopilot, more elections than on Standard ([ADR-005](ADR-005-gke-autopilot-eviction-as-budgeted-unavailability.md)). Two backends is roughly 1.6× the store code and 2× the integration-test matrix. etcd state is opaque next to a `SELECT` — hence `etcdctl` in the runbook. |
| **What we accept** | Single-region, single-shard, no authN on the lock API (charter §0.5) — bounded by being written down as disqualifying for production. etcd writes are quorum writes, so the p99 acquire target (NFR-03, ≤ 50 ms) is a *measured* claim, not an assumed one; if it misses, the benchmark says so publicly. |

## A1.4 Alternatives considered {#adr1-alternatives}

| Alternative | Why it is attractive | Why rejected |
|---|---|---|
| **Single Redis** with `SET NX PX` | Sub-millisecond, already in every stack, five lines of client code | Asynchronous replication. A failover promotes a replica that never saw the winning `SET`, so a second client acquires the *same* lock and both hold a valid-looking lease. No token is derivable from `SET NX` either, so there is nothing for the resource to fence with. It fails exactly INV-06 and INV-04 — the project's entire premise. Charter risk R2 names "teams keep their own Redis locks" as the likeliest way this failure reaches production. |
| **Redlock** across N independent Redis nodes | Removes the single point of failure; a published algorithm with a real following | Its safety argument depends on bounded clock drift and bounded process pauses. A GC pause or a VM live-migration longer than the lease breaks it, and neither is bounded on GKE. It still yields no monotonic token, so it cannot feed the fence — and a lock without a fence is a performance hint. Rejected on the algorithm, not the operational cost. |
| **ZooKeeper** (ephemeral sequential znodes + `zxid`) | Genuinely correct: ZAB, ephemeral nodes, and `zxid` is a fine fencing token. The original Chubby-lineage answer | Correct but heavier for equal safety here: JVM ensemble, its own ops model and tooling, and a client library whose session semantics are a second thing to teach. etcd delivers the same guarantees with a leaner surface, a native lease API, and first-class Kubernetes-adjacent operations. This is a *cost* rejection, and it is the alternative a reasonable engineer would have picked. |
| **Custom Raft** implementation | Maximum learning about consensus; total control of the token | A different project with a different lesson (charter §0.5). An unproven Raft under a money path is negligent, and the token it produced would be strictly worse than `ModRevision`. Time spent here is time not spent on the SDK deadline and the fencing experiment, which are what the project is *for*. |
| **Managed global store** (Spanner, or a hosted lock service) | No ops; strong consistency out of the box | Hides the mechanism the project exists to expose, and pulls in multi-region semantics that are an explicit non-goal. Costed at an order of magnitude more for a torn-down-daily project (NFR-14). |
| **Do nothing** — advisory locks in `paydb` (`pg_advisory_lock`) | Zero new infrastructure; the resource DB is already there | Couples lock availability to resource availability, the precise coupling [ADR-003](ADR-003-two-databases-two-instances.md) exists to break, and session-scoped advisory locks give no lease expiry and no token. Retained only as a teaching contrast. |

## A1.5 Revisit when {#adr1-revisit}

| Trigger | Threshold (ASSUMPTION unless measured) | Then |
|---|---|---|
| Sustained write load past a sharded etcd | Sustained > ~10k lock mutations/s per shard **after** key-space sharding is real, with etcd disk-commit p99 dominating acquire latency | Open an ADR for a different substrate; bring the benchmark, not an opinion |
| A semantic etcd cannot express | A hard requirement for shared/exclusive modes, strict FIFO fairness, or cross-region quorum (all charter §0.5 non-goals today) | Re-open; note that ZooKeeper is the first re-evaluation, not Redis |
| etcd operational toil exceeds its value | Toil register shows etcd care > ~2 h/month sustained on the project's scale | Consider collapsing to the PostgreSQL backend for the project only, and say so in the publication |
| Autopilot elections blow the availability budget | NFR-02 budget consumed for two consecutive months | Charter D-05 decision: move the StatefulSet to a Standard node pool ([ADR-005](ADR-005-gke-autopilot-eviction-as-budgeted-unavailability.md)) |
| PostgreSQL backend measures *better* end-to-end | pg p99 acquire beats etcd p99 at the assumed load in the M7 benchmark | Do not flip the recommendation on latency alone — INV-04's restore hazard ([C1 §1.7](../contracts/C1-database-schemas.md#ct1-seq)) still favours etcd. Publish both numbers. |
