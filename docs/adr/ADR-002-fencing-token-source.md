# ADR-002 — Fencing token source: `ModRevision` captured at grant time {#adr2}

**Status:** Accepted, 2026-08-21
**Deciders:** project architect
**Requirements touched:** FR-02, FR-09, FR-10, FR-15, FR-16, FR-17, INV-04, INV-05, INV-07
**Contracts touched:** [C1 §1.7](../contracts/C1-database-schemas.md#ct1-seq), [C1 §1.3](../contracts/C1-database-schemas.md#ct1-acquire), [C1 §1.6](../contracts/C1-database-schemas.md#ct1-fenced), [C2 §2.2](../contracts/C2-java-api.md#ct2-lockservice), [C2 §2.7](../contracts/C2-java-api.md#ct2-propagation)

## A2.1 Context {#adr2-context}

The token is the only thing that survives the failure the lock cannot prevent: a holder that is paused,
partitioned or slow, believes it still holds the lease, and arrives at the resource *after* someone else
took over. Mutual exclusion (INV-06) is a claim about the lock service's own view; the token is what lets
the **resource** reject a stale writer without trusting anybody's clock (INV-05, INV-07).

So the token must be strictly increasing per key **including across delete-and-recreate of the lock**
(INV-04), comparable between the two fencing points — the `paydb` row and the rail proxy's per-account
high-water mark (FR-15, FR-17) — and bound to *one* grant forever. Two backends must produce tokens with
identical semantics (FR-09) even though their mechanics differ entirely.

## A2.2 Decision {#adr2-decision}

**The token is a `long` obtained once, at grant time, and thereafter treated as an immutable property of
the grant: etcd supplies the key's `ModRevision` from the successful acquire transaction's response;
PostgreSQL supplies `nextval('fencing_token_seq')` in the same statement that creates or steals the row.**

| Backend | Source | Captured |
|---|---|---|
| etcd | `ModRevision` of the lock key in the response to the winning CAS txn (FR-10) | In the acquire response, before `LockHandle` is constructed |
| PostgreSQL | `nextval('fencing_token_seq')`, one global non-transactional sequence | Inside the acquire statement ([C1 §1.3](../contracts/C1-database-schemas.md#ct1-acquire)) |

The value travels as an **explicit parameter or header**, never a thread-local
([C2 §2.7](../contracts/C2-java-api.md#ct2-propagation)), and `LockHandle` exposes it as a final field.
There is no API that re-reads, refreshes or recomputes a token for an existing grant. **Renewal extends
the lease and does not change the token** — a renewed grant is the same grant.

## A2.3 The trap {#adr2-trap}

**`ModRevision` is the revision of the last modification of the key — not an identifier of the grant.**
Every write to that key bumps it. So a "helpful" implementation that reads the token at *write* time —
`etcd.get(key).modRevision` just before submitting to the rail, or refreshing it on each heartbeat if
heartbeat touches the key — reintroduces precisely the race the token exists to close.

```
  W1 acquires k          -> ModRevision = 42, W1's grant token = 42
  W1 stalls (GC / partition, longer than the lease)
  lease expires; W2 steals k -> ModRevision = 57, W2's grant token = 57
  W2 submits to rail        -> high-water(account) := 57      [FR-17]
  W1 wakes up
     CORRECT   : W1 presents 42  -> 42 < 57 -> FencedOutException, no side effect  [INV-05]
     THE BUG   : W1 re-reads k, gets 57, presents 57 -> 57 >= 57 -> ACCEPTED
                 -> second submission to a non-idempotent rail. INV-02 violated.
```

**Why it is dangerous rather than merely wrong:** the buggy version passes every happy-path test, every
single-worker test, and every contention test in which the loser never wakes up. The token still looks
strictly increasing in the logs. It fails only in the interleaving that requires a *stalled survivor* —
which is exactly the interleaving the M4 harness manufactures at T-042.

Guards, in order of strength: (1) the token is a final field on `LockHandle`, so there is no setter to
call; (2) no read path in `lock-server` returns a token for a key the caller does not hold — `inspect`
is documented as never usable for a correctness decision (FR-07); (3) a negative-control test that
deliberately re-reads at write time and **must** produce a duplicate submission, proving the harness can
see the bug; (4) `lock.token` is a required attribute on every span
([C4 §4.8](../contracts/C4-observability.md#ct4-traces)), so a token that changes mid-grant is visible in
one trace.

## A2.4 Consequences {#adr2-consequences}

| | |
|---|---|
| **Positive** | Zero token machinery in etcd mode — nothing to allocate, lose or protect (FR-10). Both backends yield one comparable `long`, so the fenced `UPDATE` ([C1 §1.6](../contracts/C1-database-schemas.md#ct1-fenced)) and the rail high-water mark are backend-agnostic. A grant has exactly one token for its whole life, which makes "which grant did this write?" answerable from a log line. |
| **Negative** | The two backends' tokens are numerically incomparable (etcd revisions are cluster-global; sequence values are `lockdb`-global), so **switching backends requires fast-forwarding above every stored fence** — the same procedure as the restore hazard. Sequence values have gaps on rollback, and etcd revisions jump by unrelated cluster writes; a reader may misread either as lost grants. |
| **What we accept** | Bounded by: a documented backend-switch and restore procedure ([C1 §1.7](../contracts/C1-database-schemas.md#ct1-seq)) that fast-forwards past `max(account.fence, rail_high_water.highest_token)` before traffic resumes; `lock.fenced.out` as a must-be-zero counter ([C4 §4.4](../contracts/C4-observability.md#ct4-zero)) so any token regression pages; and both kill switches ([C5 §5.2](../contracts/C5-config-build-and-naming.md#ct5-killswitches)) existing only to demonstrate the corruption on purpose. |

## A2.5 Alternatives considered {#adr2-alternatives}

| Alternative | Why it is attractive | Why rejected |
|---|---|---|
| **PostgreSQL global sequence as the sole source, both backends** | One token semantics everywhere; trivially comparable; keeps etcd stateless w.r.t. tokens | Puts `lockdb` on the critical path of *every* etcd acquire — the etcd backend would inherit PostgreSQL's availability and latency, deleting the reason it exists (FR-09). Two consensus systems per grant, and a restore of `lockdb` rewinds tokens for a cluster that never used it. |
| **Per-row `version` column** instead of a global sequence | Cheaper; the obvious ORM instinct | Release deletes the `lock_entry` row, so the next acquire restarts at 1 and reissues tokens the resource has already seen — INV-04 gone. It also makes the per-*account* rail high-water mark (FR-17) unimplementable, because two keys' counters are incomparable. Argued in full at [C1 §1.7](../contracts/C1-database-schemas.md#ct1-seq). |
| **etcd lease ID** as the token | Already unique per session; free | Not monotonic — lease IDs are effectively arbitrary — so `fence < :token` is meaningless. Also session-scoped, not grant-scoped: all of a client's locks would share it. |
| **`CreateRevision`** instead of `ModRevision` | Immune to the §A2.3 trap by construction: it does not change on modification | Genuinely tempting, and the closest call here. Rejected because a lock key that is *stolen* in place (CAS over an expired entry) keeps its `CreateRevision`, so two consecutive holders can present the same token. `ModRevision`-at-grant-time is correct with the discipline; `CreateRevision` is subtly wrong regardless of discipline. |
| **Timestamp** (wall clock, or a hybrid logical clock) | Human-readable; sorts naturally | Wall clocks go backwards; NTP steps and VM migration break ordering, and the whole point of fencing is to stop trusting clocks. An HLC would need its own correctness argument for no gain. |
| **UUID per grant** | Unique, trivially generated client-side | Not ordered, so a resource cannot decide *stale* versus *current*. Sufficient for idempotency keys ([C3 §3.1](../contracts/C3-http-surfaces.md#ct3-conventions)); useless as a fence. |

## A2.6 Revisit when {#adr2-revisit}

| Trigger | Threshold (ASSUMPTION unless measured) | Then |
|---|---|---|
| Key-space sharding becomes real | More than one etcd cluster, or a forked `fencing_token_seq` | Tokens stop being globally comparable. Open an ADR for a shard-prefixed composite token *before* the second shard exists — retrofitting it means re-fencing every stored high-water mark |
| A fence must be compared across backends live | Any dual-run or migration in which both backends grant concurrently | Do not attempt: the fast-forward procedure assumes a clean cut-over. A new ADR must first define a shared token space |
| `lock.fenced.out` is non-zero with fencing enabled | Any single occurrence (NFR-06) | Incident, not a tuning exercise — check §A2.3 first, it is the most likely cause |
| etcd revision growth becomes a concern | Compaction interval and revision jump rate make token values unwieldy in logs | Cosmetic only; do **not** rebase revisions. Change the log rendering, never the token |
