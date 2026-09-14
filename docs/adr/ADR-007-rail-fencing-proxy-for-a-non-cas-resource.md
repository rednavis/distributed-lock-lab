# ADR-007 — A fencing proxy in front of a resource with no CAS {#adr7}

| Field | Value |
|---|---|
| **Status** | **Accepted**, 2026-08-21 |
| **Decider** | Project owner / architect |
| **Scope** | `rail-proxy`, `rail-stub`, `paydb` table `rail_high_water` |
| **Contracts** | [C3 §3.5](../contracts/C3-http-surfaces.md#ct3-railproxy) (admission order, and why in-memory fails) · [C3 §3.7](../contracts/C3-http-surfaces.md#ct3-ambiguity) · [C1 §1.5](../contracts/C1-database-schemas.md#ct1-paydb) · [C5 §5.2](../contracts/C5-config-build-and-naming.md#ct5-killswitches) · [C4 §4.4](../contracts/C4-observability.md#ct4-zero) |
| **Requirements** | [FR-18](../01-requirements.md#br-fr), [FR-19](../01-requirements.md#br-fr), [FR-20](../01-requirements.md#br-fr) · [UC-05](../01-requirements.md#br-uc05) · [INV-02](../01-requirements.md#br-invariants), [INV-05](../01-requirements.md#br-invariants), [INV-07](../01-requirements.md#br-invariants) |

## 7.1 Context {#adr7-context}

Fencing works by making the protected resource reject anything below the highest token it has seen. That
requires the resource to hold state and compare. Our PostgreSQL resource can:
`UPDATE … WHERE fence < :token` ([C1 §1.6](../contracts/C1-database-schemas.md#ct1-fenced)).

The external payment rail cannot. It is a fictional third party that:

| Property | Consequence for us |
|---|---|
| Has no compare-and-set, no conditional accept | Nothing on their side can reject a stale writer. |
| Is **not idempotent** — two submissions move money twice | A retry is not a neutral act. |
| Cannot be modified, ever, on any timeline | "Ask them to add an idempotency key" is not an available design. |
| Answers ambiguously on timeout | We may not even know whether the first attempt landed. |

This is the general case in payments, and it is the case the project exists to answer: **when the resource
cannot be fenced, fence the only thing you control — the path to it.** Interposing a proxy converts an
unfenceable third party into a fenceable local resource, at the cost of one component and one synchronous
write. The proxy is deliberately a *separate process from the lock service* — enforcement point **(c)**
in [02 §2.6](../02-domain-model.md#dm-token-journey) — because a guard that lives inside the lock service
proves nothing about a resource that has never heard of the lock service.

## 7.2 Decision {#adr7-decision}

| # | Rule |
|---|---|
| D1 | **All rail traffic goes through `rail-proxy`.** No other module may hold rail credentials or a rail client. One chokepoint, or the guard is decorative. |
| D2 | The proxy keeps a **persisted, per-account high-water mark** in `paydb` (table `rail_high_water`, pinned in [C1 §1.5](../contracts/C1-database-schemas.md#ct1-paydb)), advanced monotonically and conditionally in the **same transaction** that records the submission intent. |
| D3 | Admission order is fixed and not negotiable: **(1)** token strictly greater than the stored mark, else `409 FENCED_OUT` and **no rail call is ever made**; **(2)** no existing submission for this `payoutId`, else `409 DUPLICATE_SUBMISSION`; **(3)** commit the intent row *before* forwarding; **(4)** forward **once**. Full table in [C3 §3.5](../contracts/C3-http-surfaces.md#ct3-railproxy). |
| D4 | **Never auto-retry the rail** (FR-20). A timeout resolves to `TIMEOUT` / `409 RAIL_AMBIGUOUS` and stops; resolution is a human or reconciliation decision ([C3 §3.7](../contracts/C3-http-surfaces.md#ct3-ambiguity)). |
| D5 | The mark is **per account**, matching the lock key, not per payout. Per-payout marks would never fence anything: a stale writer always carries a fresh payout id. |
| D6 | The mark lives in **`paydb`**, the protected resource's own database — not in lockdb, not in etcd. A guard that needs the lock backend to be reachable fails exactly when the lock backend has failed over, which is when stale writers exist. |
| D7 | `rail.proxy.fencing.enabled` exists **only** to demonstrate corruption ([C5 §5.2](../contracts/C5-config-build-and-naming.md#ct5-killswitches)); it is never off outside a harness scenario. |

**Why in-memory is not merely weaker but wrong.** A `ConcurrentHashMap<accountId, token>` inside the proxy
fails three ways, and each is the exact failure fencing is for ([C3 §3.5](../contracts/C3-http-surfaces.md#ct3-railproxy)):

```
  (i) RESTART        crash / rollout / Autopilot eviction
                       map is empty  ->  paused holder with token 41 looks like the first ever caller
                       -> ADMITTED  -> second payment for the same payout
 (ii) TWO REPLICAS   pod A saw 90, pod B saw nothing
                       stale writer only has to be load-balanced to pod B
                       -> ADMITTED  (and the more replicas, the better its odds)
(iii) STALE READ     read replica / async cache returns 41 after 90 was written
                       -> ADMITTED  -- same bug, one extra hop of deniability
```

The mark must survive **exactly the events that create stale writers**, and process restart is the most
common of them. Durable, strongly-consistent, read-and-advanced in one conditional write, or it is not a
guard. Cost: one synchronous `paydb` write per submission. That is the price of the guarantee, and it is
cheap next to the alternative.

## 7.3 Consequences {#adr7-consequences}

**Positive**

- Gives an unmodifiable, non-idempotent third party a fence, with **no cooperation from the third party**.
- Two independent monotonic guards (`payout`/`account` fence in the resource, `rail_high_water` in the proxy) maintained by different processes against different state — strictly stronger than one shared counter ([02 §2.2](../02-domain-model.md#dm-highwater)).
- Intent-before-forward means an ambiguous outcome is always *recorded*, so reconciliation has something to reconcile ([UC-07](../01-requirements.md#br-uc07)).
- `rail.duplicate.attempted` becomes a real must-be-zero alert: any occurrence is a bug in the caller, not noise.

**Negative**

- A new component on the money path: one more hop of latency, one more thing to deploy, one more single point of failure (mitigated by replicas, which is precisely why the mark cannot be in memory).
- A synchronous write before every external call — the proxy is now coupled to `paydb` availability. If `paydb` is down, submissions correctly stop.
- Per-account marks serialise unrelated payouts for the same account behind one monotonic value; a *lower*-token in-flight submission for a different payout of the same account is rejected. Correct, occasionally surprising.
- The proxy must be trusted with rail credentials, concentrating a secret.

**What we accept**

- Ambiguity is never resolved automatically. A `TIMEOUT` submission stays unresolved until a human or the reconciler closes it; we accept slower resolution to avoid a second payment.
- The proxy cannot prevent a duplicate the *rail itself* creates internally. Out of scope, stated openly ([00 §0.5](../00-charter.md#ch-nongoals)).

## 7.4 Alternatives considered {#adr7-alternatives}

| Alternative | Why rejected |
|---|---|
| Idempotency key alone, no token check | Stops *retries of the same request*; does nothing about a stale holder submitting a **different** payout it should no longer be working on. Necessary, not sufficient — we keep it as check (2), not as the fence. |
| Ask the rail to add conditional accept | Not available by construction, and in reality never available on the timeline that matters. |
| In-memory high-water map (with sticky routing) | See D2/§7.2: restart amnesia, per-replica divergence, stale reads. Sticky routing turns a correctness property into a load-balancer configuration — the worst place to keep an invariant. |
| Fence inside the executor (check the token before calling) | The stale executor is the untrusted party. A guard the attacker runs is not a guard ([INV-07](../01-requirements.md#br-invariants)). |
| Rely on the lock service to gate submissions (ask "am I still holder?") | A network round trip that is stale the instant it returns, and it fails when the lock service does. Fencing exists precisely so this question need not be asked. |
| Keep the mark in etcd next to the locks | Couples the guard to the backend whose failover produces stale writers, and would let the etcd/pg comparison change correctness. `paydb` keeps the guard independent. |
| Queue submissions and dedupe downstream | Moves the decision after the irreversible act. There is no "after" for money. |

## 7.5 Revisit when {#adr7-revisit}

| Trigger | Action |
|---|---|
| `rail.duplicate.attempted > 0` | Page. It is caller misbehaviour (usually a retry that FR-20/[C3 §3.8](../contracts/C3-http-surfaces.md#ct3-timeouts) forbids), not a proxy tuning issue. |
| A rail with genuine idempotency or conditional accept appears | Keep the proxy (intent record, ambiguity ledger) but re-evaluate whether the high-water check can be relaxed to a assertion. Do not delete it before measuring. |
| The synchronous `paydb` write becomes the submission-latency bottleneck | Optimise the write (single conditional `UPDATE`, no read-then-write), never remove or cache it. |
| Proxy replica count grows, or a multi-region proxy is proposed | Re-verify the mark is still advanced in one strongly-consistent transaction; cross-region async replication silently reintroduces failure (iii). |
| The rail gains a second endpoint / a second rail is added | Decide whether the mark is per-account or per-(account, rail) **before** shipping; a shared mark across rails rejects legitimate traffic. |
