# ADR-004 — The protected operation is a payout execution, not a balance update {#adr4}

**Status:** Accepted, 2026-08-21
**Deciders:** project architect
**Requirements touched:** FR-11, FR-12, FR-13, FR-15, FR-17, FR-18, INV-01, INV-02, INV-05
**Contracts touched:** [C1 §1.6](../contracts/C1-database-schemas.md#ct1-fenced), [C1 §1.5](../contracts/C1-database-schemas.md#ct1-paydb), [C2 §2.7](../contracts/C2-java-api.md#ct2-propagation), [C3 §3.5](../contracts/C3-http-surfaces.md#ct3-railproxy), [C4 §4.4](../contracts/C4-observability.md#ct4-zero)
**Related:** [ADR-002](ADR-002-fencing-token-source.md#adr2), ADR-007 (rail proxy)

## A4.1 Context {#adr4-context}

Every distributed-lock tutorial protects a bank balance. **That demo is a strawman, and it should be
named as one.** The canonical statement

```
UPDATE account SET balance_minor = balance_minor - :amount
 WHERE account_id = :id AND balance_minor >= :amount
```

is already atomic. PostgreSQL takes a row lock on `account_id`, serialises every concurrent writer
behind it, and the `>=` predicate makes overdraft impossible: the loser's `UPDATE` returns zero rows
and the application retries or fails. There is no interleaving in which two workers both succeed. A
distributed lock wrapped around that statement buys **nothing** — it adds a network hop, a lease, a
clock assumption, a second store that can be unavailable when PostgreSQL is not, and a brand-new
failure mode (the paused holder) that the bare `UPDATE` never had. It makes the system strictly worse.

Any competent reviewer asks, at the first diagram, *"why is etcd in this picture?"* For a balance update
the only honest answer is **"it should not be"** — and a project whose central artifact cannot survive
its first question is not worth building. Worse, the strawman teaches the wrong reflex:
readers conclude that locks are how you protect database rows, when a conditional write is.

A lock earns its place under one condition: **the critical section spans a side effect the database
transaction cannot roll back.** Once the section reaches outside the transaction boundary, `COMMIT`/
`ROLLBACK` stops being a correctness tool, at-most-once stops being free, and the resource itself must
be able to reject a writer that a lease already disowned. That is the situation worth studying, and it
is the only situation in which fencing tokens (INV-05, [ADR-002](ADR-002-fencing-token-source.md#adr2))
are more than ceremony.

## A4.2 Decision {#adr4-decision}

**The protected operation is a payout execution: claim a pending payout, submit it to an external
payment rail, then post the double-entry ledger rows and update the account balance.** The rail
submission is the irreversible side effect; the rail is **deliberately non-idempotent** — it accepts no
idempotency key and it will happily move money twice (FR-18).

| Phase | Store / system | Reversible? | Protection |
|---|---|---|---|
| Claim payout | `paydb` (`dlock-pg-pay`, ZONAL) | Yes — `ROLLBACK` | Lock grant + fenced `UPDATE` ([C1 §1.6](../contracts/C1-database-schemas.md#ct1-fenced)) |
| Submit to rail | third-party, via `rail-proxy` | **No** | Per-account high-water mark ([C3 §3.5](../contracts/C3-http-surfaces.md#ct3-railproxy)) |
| Post ledger + balance | `paydb` | Yes | Fenced `UPDATE`, same token ([C1 §1.6](../contracts/C1-database-schemas.md#ct1-fenced)) |

**Fencing therefore has two enforcement points, both in processes separate from the lock service**,
because the two protected resources have unequal capabilities:

1. **The `paydb` row** supports a conditional write, so the fence lives in a column and the guard is
   `UPDATE … WHERE fence < :token` — the resource rejects the stale writer itself.
2. **The third-party rail** supports nothing at all: no conditional submit, no compare-and-set, no
   idempotency key. So `rail-proxy` holds a **persisted highest-token-per-account high-water mark** and
   rejects a stale token **before the rail is ever called** (FR-17, ADR-007). Enforcement must be
   persisted, not in-memory, or a proxy restart forgets every fence it ever saw.

Neither point trusts the lock service, a clock, or a lease. This is the whole thesis of the project.

## A4.3 Consequences {#adr4-consequences}

| | |
|---|---|
| **Positive** | The demo survives its first question, and fencing has real stakes: money submitted twice to a rail with no reversal API is an operational incident, not a failed assertion. `lock.fenced.out` becomes a meaningful must-be-zero signal ([C4 §4.4](../contracts/C4-observability.md#ct4-zero)) rather than a curiosity. The two-point design teaches the transferable lesson — that a fence is enforced *at the resource*, and that different resources need different mechanisms. |
| **Negative** | The build is materially larger: two extra modules (`rail-proxy`, `rail-stub`), a payout state machine, a ledger schema, and a second Cloud SQL instance. More surface to keep correct, and the lock lesson competes for the reader's attention with payment-domain detail. |
| **What we accept** | `RAIL_AMBIGUOUS` becomes a first-class state, not an error branch: a submit that times out means we **do not know** whether the rail acted, and no lock, token, or transaction can tell us. Resolution requires reconciliation against the rail, and the payout stays unresolved until then. This is exactly where real payment systems hurt, so it is in scope on purpose — but it means the project must document an outcome the lock cannot fix. |

## A4.4 Alternatives considered {#adr4-alternatives}

| Alternative | Why it is attractive | Why rejected |
|---|---|---|
| **Bare balance withdraw** (the tutorial demo) | Simplest possible project; one table, one statement, no external system; fastest to build and to read | The "a single `UPDATE` would do this" critique **lands**, and it can then only be answered verbally — "imagine the operation were irreversible" — rather than structurally. The artifact would depend on a caveat the reader has to accept on trust. |
| **Two-account transfer with a double-entry ledger** | Genuinely better: earns the lock-ordering and deadlock lesson, and the ledger is realistic | Still arguably **one transaction**. A reviewer can correctly say both rows are in the same database, so `SERIALIZABLE` or an ordered pair of row locks suffices. The lock's justification stays weak, just less obviously so. Lock ordering is retained as an exercise instead. |
| **Make the operation idempotent and drop the lock** | For many production systems this is **the correct answer** — an idempotency key plus a conditional write beats mutual exclusion on availability, latency, and operability | Acknowledged as correct, and recorded as the **"Don't"** row of the build-versus-buy table so no reader mistakes this project for a recommendation. Rejected here only because it removes the entire subject of study: with an idempotent side effect there is nothing left to fence. |
| **Simulate irreversibility inside PostgreSQL** (e.g. an append-only "sent" table treated as un-rollbackable by convention) | Keeps the stack to one store; no proxy needed | The irreversibility would be a fiction maintained by discipline; a `ROLLBACK` still undoes it. An out-of-process rail makes the constraint real, which is the point. |

## A4.5 Revisit when {#adr4-revisit}

| Trigger | Threshold (ASSUMPTION unless measured) | Then |
|---|---|---|
| The rail gains real idempotency keys and exactly-once semantics | Any replacement rail whose contract guarantees a duplicate submit is a no-op | **Re-derive the design; do not inherit it.** The lock's justification weakens to near zero and the honest outcome may be to delete it. Open a superseding ADR rather than editing this one. |
| The rail gains a reversal / void API | Reversal is durable and available within the payout SLA (ASSUMPTION: minutes) | The side effect stops being irreversible; compensation may replace mutual exclusion. Re-open §A4.1. |
| `RAIL_AMBIGUOUS` dominates the failure profile | ASSUMPTION: more than ~1 in 100 submits under the M4 fault harness | Reconciliation, not locking, is the project's real lesson at that point — promote it from Appendix to a Part. |
| The ledger and balance move to separate services | Any split of `paydb` across two owners | The section then spans two non-transactional resources and needs a third fencing point; new ADR before the split ships. |
