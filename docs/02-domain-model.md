# 02 — Domain model

Defines the entities, the two state machines, and — in [2.5](#dm-invariant-enforcement) — where each
invariant from [BRD 1.7](01-requirements.md#br-invariants) is actually enforced. **No DDL
here**; the physical schema, column types and constraints are specified in
[C1 database schemas](contracts/C1-database-schemas.md#ct1-paydb). This file is the meaning; that file is the definition.

## 2.1 Entity overview {#dm-entities-overview}

Two databases, three trust boundaries, one token crossing all of them.

```
                    process boundary                     process boundary
                           |                                     |
  payout-executor          |    payment-resource (paydb)          |   rail-proxy (proxydb)
  ------------------       |    ------------------------          |   --------------------
  holds lock handle        |                                      |
  carries token T  --------+--> account  1 ---- N  payout         |     account_high_water
                           |      | fence (authoritative)   |     |       account_id -> max token
                           |      |                         |     |
                           |      N                         N     |     rail_submission
                           |   ledger_entry <--- group ---   |    |       payout_id  (UNIQUE)
                           |      (double entry, sums to 0)  |    |       fence_token, client_ref
                           |                                 |    |       outcome, rail_ref
        \-------- token T --+---------------------------------+----+--> validated again here
                           |                                      |
                           |                                      +--> rail-stub (external, non-idempotent)
                           |
  lockdb / etcd  (lock-server, a DIFFERENT database entirely)
  ---------------------------------------------------------
  lock_session  1 ---- N  lock_entry
     session_id            key, owner, token, expiry
```

Read the picture for its **absences**. The lock service knows nothing about payouts, accounts, or
money; the payment resource knows nothing about the lock service; the rail proxy knows about neither.
The only thing they share is an integer. That is the entire architectural claim of the project: *the
enforcement of mutual exclusion lives in processes that do not trust, and cannot see, the process that
granted it.* If enforcement lived inside the lock service, the lock service crashing or being restored
from a backup would silently switch protection off, and nobody would notice
([INV-04](01-requirements.md#br-invariants)).

## 2.2 Entities {#dm-entities}

### account {#dm-account}

**Purpose.** The party funds are paid out from, and — crucially — **the unit of locking and the carrier
of the authoritative fence**. The lock key is `payout:{account_id}` ([A-15](01-requirements.md#br-assumptions)).

**Fields, by meaning.** A stable synthetic identifier (never a natural key such as an IBAN). A balance
in integer minor units, single currency, which is a *cached projection* of the ledger and never a
source of truth. A **fence**: the highest fencing token ever accepted for this account, non-decreasing,
initialised to zero, and the single value every fenced write is conditioned on (`fence < :token`).
Bookkeeping timestamps and an optimistic version for ordinary application concurrency. Created out of
band by fixture data, never deleted; the fence must survive every restore.

**Why one fence per account and not one per payout.** The lock key is the account, so the token
sequence is per account; a fence stored per payout could be advanced by a stale holder acting on a
*different* payout of the same account, which is a real hole. Payouts carry a token stamp too, but it
is **diagnostic only** — it answers "who last touched this?" in a postmortem and is never a guard.

### payout {#dm-payout}

**Purpose.** One instruction to move a fixed amount out of one account to one beneficiary, exactly
once. The aggregate the state machine in [2.3](#dm-payout-fsm) governs.

**Fields, by meaning.** Identifier; owning account; amount in minor units and currency; an opaque
beneficiary reference (synthetic, no personal data — [NFR-12](01-requirements.md#br-nfr)); the
**state**; a **client reference** that is stable for the payout's whole life and is what the rail sees
and what ambiguity resolution looks up — it is derived from the payout id and never regenerated on
retry, because a regenerated reference makes a duplicate undetectable; the identity of the current or
last claiming worker; the diagnostic token stamp; a failure reason; and timestamps for created,
claimed, submitted, and settled. Immutable identity; created `PENDING`; terminal at `POSTED`, `FAILED`
or `ABANDONED`; never deleted (finance evidence).

**One constraint is easy to lose because it is nowhere in the schema:** *at most one non-terminal payout
per account may be in flight.* It is enforced by the lock, which is precisely why the lock exists. The
reconciler asserts it after the fact as a detective control.

### ledger_entry {#dm-ledger}

**Purpose.** The immutable double-entry record of a money movement. The only source of truth about
money.

**Fields, by meaning.** Identifier; a **transaction group** shared by all entries of one movement;
account; a signed amount in minor units where the sign carries direction; the counterparty leg's
account (a clearing account for the rail); the payout that caused it; the token under which it was
posted; and a creation timestamp. Append-only and immutable — never updated, never deleted; a
correction is a new compensating group, not an edit. Two entries per payout here: debit the customer
account, credit the payout-clearing account.

**Constraint.** Every transaction group sums to zero, and each account's balance equals the sum of its
entries. Both asserted continuously, not only at reconciliation
([INV-03](01-requirements.md#br-invariants)).

### rail_submission {#dm-submission}

**Purpose.** The rail proxy's durable record of *intent and outcome*. It exists because the rail cannot
answer "did I already send this?" synchronously and reliably, so the proxy must be able to answer it
instead. **It lives in the proxy's own store, not in paydb** — sharing a database with the payment
resource would let one failure remove both the record and the resource it protects.

**Fields, by meaning.** Identifier; **payout id, unique** — this single constraint is the mechanical
enforcement of "at most one submission ever"; account id; the fencing token presented at submission
time; the client reference sent to the rail; the outcome — `NULL` while the attempt is still unresolved,
then exactly one of `ACKED` / `REJECTED` / `TIMEOUT` / `FENCED`
([C1 §1.5](contracts/C1-database-schemas.md#ct1-paydb)); the rail's own reference when it gives one; a
decline or error code; created and resolved timestamps. Written **before** the rail is contacted
([FR-19](01-requirements.md#br-fr)), then updated exactly once with the outcome, then immutable;
never deleted. An unresolved (`NULL`) row and a `TIMEOUT` row are both "we may have moved money"; the
difference is only whether we managed to record the ending.

### account_high_water {#dm-highwater}

**Purpose.** The proxy's independent copy of "the highest token ever seen for this account". This is the
second enforcement point, and the reason the design survives a resource that cannot be modified at all.

**Fields.** Account id; highest token; last-updated timestamp. Advanced conditionally and monotonically,
in the same transaction that writes the submission intent.

**Why it must be a separate value from `account.fence`.** They are maintained by different processes
against different stores and they are *allowed to disagree transiently*. Two independent monotonic
guards are strictly stronger than one shared one, and if they were the same value the "unmodifiable
third-party resource" lesson would be lost.

### lock_entry {#dm-lockentry}

**Purpose.** One held lock. In the PostgreSQL backend a row in `lockdb`; in the etcd backend a key
whose `ModRevision` *is* the token, so the entity is largely implicit.

**Fields, by meaning.** The key; the owner identity; the **fencing token**, monotonic per key; the
owning session; a server-side expiry instant; acquisition time; and a waiter count for introspection
only — introspection is never a correctness input ([FR-07](01-requirements.md#br-fr)), because
by the time a caller reads it, it may be false. Keyed by the lock key; exists only while held (or as a
tombstone carrying the last token, depending on backend — **the token sequence must outlive the entry**,
which is the part that matters).

### lock_session {#dm-locksession}

**Purpose.** One client-level liveness relationship. Locks attach to it so that a dead client releases
everything in one action, and so that renewal cost is per client rather than per lock.

**Fields.** Session id; owner identity; TTL; last-heartbeat instant; state. In etcd this is a lease.
Created on client start, kept alive by heartbeat, ended by explicit close or by expiry. Its death is
the *only* mass-release mechanism.

## 2.3 The payout state machine {#dm-payout-fsm}

`T` is the token of the acting holder's grant. "Fenced" means the transition is performed only if the
conditional fence advance succeeds; a fenced-out attempt causes **no** transition.

| State | Event | Next state | Side effect | Who may trigger |
|---|---|---|---|---|
| PENDING | Claim, fenced with `T`, funds sufficient | CLAIMED | Account fence advanced to `T`; claimer and time recorded | Worker holding `payout:{account}` |
| PENDING | Funds insufficient | FAILED | Reason recorded; **no rail call** | Worker holding the lock |
| PENDING | Operator cancel with reason | ABANDONED | Audit event | Operator only |
| CLAIMED | Recovery re-claim, fenced with `T' > T` | CLAIMED | Fence advanced; claimer replaced. Idempotent re-entry after a crash | Later holder |
| CLAIMED | Proxy accepts the submission | RAIL_SUBMITTED | Proxy writes the intent record **before** forwarding; high-water advanced | Worker holding the lock, presenting `T` |
| CLAIMED | Proxy rejects: stale token | *(no change)* | `fenced_out` event; worker aborts; payout recovered later by a valid holder | — |
| CLAIMED | Proxy rejects: submission already exists for this payout | *(no change)* | Duplicate-attempt event; **pages**; the payout is resolved from the existing record instead | — |
| RAIL_SUBMITTED | Rail acknowledges | RAIL_ACKED | Outcome + rail reference recorded. **Money has left** | Worker holding the lock |
| RAIL_SUBMITTED | Rail declines (business rejection) | FAILED | Outcome recorded; no ledger movement | Worker holding the lock |
| RAIL_SUBMITTED | Timeout, connection failure, or 5xx | **RAIL_AMBIGUOUS** | Outcome recorded as `TIMEOUT`; **no retry, ever** | Worker holding the lock |
| RAIL_SUBMITTED | A later holder finds no recorded outcome | **RAIL_AMBIGUOUS** | Same. Crash after forwarding is indistinguishable from a timeout | Later holder |
| RAIL_ACKED | Ledger posted and balance updated, fenced with `T` | POSTED | Two balanced ledger rows; balance updated; lock released | Worker holding the lock |
| RAIL_ACKED | Posting fenced out or the worker dies | *(no change)* | The money moved but the ledger has no row yet. A later holder completes the posting. **This is why `RAIL_ACKED` is a distinct state** | — |
| RAIL_AMBIGUOUS | Lookup by client reference finds a settled movement | RAIL_ACKED | Outcome corrected to acknowledged; posting then proceeds normally | Reconciler |
| RAIL_AMBIGUOUS | Lookup definitively finds nothing, after the rail's own settlement window | FAILED | Outcome corrected to declined/absent; no ledger movement | Reconciler |
| RAIL_AMBIGUOUS | Resolution SLA exceeded, operator decision recorded | ABANDONED | Audit event; handed to finance | Operator only |
| POSTED / FAILED / ABANDONED | anything | *(no change)* | Rejected transition is logged as a defect signal | — |

**The ambiguous state is the honest part of this model, and the reason it exists is worth stating
plainly.** Every other state answers "what happened?". `RAIL_AMBIGUOUS` answers "we do not know, and no
amount of local reasoning will tell us." Three rules follow, and violating any of them is how real
payment systems lose money:

1. **Ambiguity is reached, never skipped.** A timeout is not a failure. Treating "no response" as "did
   not happen" is the single most expensive default in this domain, and it is the default behaviour of
   almost every HTTP client and every retrying message consumer.
2. **Nothing may resubmit an ambiguous payout** — not a worker, not an operator, not a "cleanup" job,
   regardless of token or elapsed time. The proxy's unique constraint on payout id makes this true even
   if some component tries.
3. **Resolution requires external evidence** — a lookup against the rail
   ([A-08](01-requirements.md#br-assumptions)) or a human. Time is not evidence.

The mirror image is `RAIL_ACKED`: money has definitively left and the local record is incomplete. That
state is *safe to retry* forever, because completing the ledger posting has no external side effect. The
model deliberately separates "unsafe to retry" from "safe to retry" into two states rather than one
boolean, because a boolean would eventually be read by the wrong branch.

## 2.4 The lock and session lifecycles {#dm-lock-fsm}

**Lock (server-side view).**

| State | Event | Next state | Side effect | Who |
|---|---|---|---|---|
| FREE | Acquire | HELD | New token minted, strictly greater than any previously issued for the key; expiry set | Any client |
| HELD | Acquire by the same owner and session | HELD | **Idempotent:** the *same* token is returned, not a new one. A duplicated network retry must not mint a token | Current holder |
| HELD | Acquire by another owner | HELD | Not-acquired result, or a queued waiter; **no token minted** | Other client |
| HELD | Renew with the valid handle | HELD | Expiry extended; token unchanged | Current holder |
| HELD | Release, compare-and-delete on the handle | FREE | Entry removed; token sequence retained | Current holder only |
| HELD | Lease expiry decided by the backend | FREE | Expiry is a **replicated decision**, never a replica-local timer read, or replicas diverge | Backend |
| HELD | Session death | FREE (for all its locks) | One action releases every lock of the session | Backend |
| HELD | Force-revoke with operator identity and reason | FREE | **Token advanced**, audit event, alert. Advancing is what fences the deposed holder | Operator |
| any | Backend restore from backup | *(hazard)* | Must not reissue a token already used — the INV-04 hazard, addressed in [08 operations](08-operations.md) | — |

**Session (server-side view):** `ACTIVE` → `SUSPECT` (heartbeat missed, still inside the grace window)
→ `EXPIRED` (all locks released) or back to `ACTIVE`; `CLOSED` on clean shutdown. After a backend leader
election, every session is treated as `ACTIVE` for one full timeout — a **grace period**, without which
a failover mass-evicts every client's locks at once.

**Session (client-side view), and the asymmetry that matters:** `HEALTHY` while the local monotonic
deadline is in the future → `LOST` the instant it passes. The client's deadline is computed from the
time the heartbeat was **sent**, discounted by a safety margin, and it is deliberately *earlier* than
the server's expiry. The two views are different on purpose:

| | Server view | Client view |
|---|---|---|
| Clock | Backend's, replicated decision | Local monotonic only, never wall clock |
| Bias | Generous — holds the lease as long as it legitimately can | **Conservative** — gives up early |
| Used for | Deciding who may acquire next | Deciding whether to keep working |
| Failure if wrong | A lock is held slightly too long | Work continues after the lease is gone — which fencing then catches |

The client check narrows the dangerous window; it does not close it. Between "still held?" and the write
the lease can still expire. The check is **liveness**; the token is **safety**. Being precise about
which mechanism provides which property is the point of the whole exercise.

## 2.5 Where each invariant is enforced {#dm-invariant-enforcement}

The most important table in this document set. "Primary" is the mechanism that makes the invariant hold;
"detective" catches a primary that has been broken or removed.

| Invariant | Primary enforcement | Which process | Detective control | If the primary is removed |
|---|---|---|---|---|
| **INV-01** at most one worker mutates a payout | Conditional fence advance on the account row (`fence < :token`) in the same transaction as, and before, every mutation; the row lock it takes also serialises within the database | `payment-resource` (paydb) | Reconciler asserts one non-terminal payout per account and consistent claim history | Two workers interleave states on one payout; the ledger may still balance, so this is silent |
| **INV-02** at most one rail submission ever succeeds | **Unique constraint on payout id** in the proxy's submission table, plus writing intent before forwarding | `rail-proxy` (proxydb) | Reconciler counts submissions per payout against rail lookups | Duplicate payment — the loss the project exists to prevent |
| **INV-03** the ledger balances | Both legs written in one transaction; entries immutable; balance updated from the same transaction | `payment-resource` | Continuous per-group and per-account sum assertions, hourly reconciliation | Money appears or disappears; discovered by finance, days later |
| **INV-04** tokens never repeat or go backwards | etcd: `ModRevision` is cluster-monotonic by construction. PostgreSQL: a sequence that is never reset, plus the restore procedure that forbids rewinding it | `lock-server` + backend, and the **operational** restore procedure | An alert on any observed token regression at either enforcement point | Every downstream `fence <` check still runs and still passes — fencing becomes decoration, undetectably |
| **INV-05** no write below the stored fence | The conditional predicate itself, plus monotonic high-water advance at the proxy | `payment-resource` **and** `rail-proxy`, independently | `fenced_out` counter and log event, alerted on any occurrence | The stale holder's write lands; this is the [UC-03](01-requirements.md#br-uc03) negative control |
| **INV-06** mutual exclusion of grants | Consensus in the backend: etcd's linearizable compare-and-swap, or a single conditional insert in PostgreSQL | `lock-server` + backend | Linearizability check over recorded lock history; deterministic simulation | Two live holders — which INV-01/02/05 still contain, and that containment is the design's whole point |
| **INV-07** no side effect without a grant | Every mutating path requires a token parameter that only a grant produces; fail-closed on acquire failure, not configurable | `payout-executor` (structure), enforced at both guards | Submissions with no matching grant flagged in reconciliation; audit log | A code path bypasses the lock entirely — the failure no lock service can defend against |
| **INV-08** terminal states are terminal | The transition table is total and explicit; unlisted transitions are rejected and logged | `payment-resource` | Rejected-transition counter, alerted on non-zero | A cleanup or replay job re-executes a completed payout |

Two conclusions to read off the table. First, **no invariant is enforced by the lock service alone** —
INV-06 is the only one it owns, and INV-01/02/05 are specifically designed to survive INV-06 being
violated. Second, **the enforcement points are in different processes with different stores**, so no
single failure, restore, or misconfiguration switches protection off everywhere.

## 2.6 The fencing token's journey {#dm-token-journey}

| # | Step | Where | What could go wrong, and what prevents it |
|---|---|---|---|
| 1 | **Minted.** The backend produces a value strictly greater than any it has issued for the key: etcd's `ModRevision` of the successful compare-and-swap, or a never-reset PostgreSQL sequence | lock backend | Reusing a value after restart or restore ⇒ INV-04 gone. Prevented by construction in etcd; by procedure in PostgreSQL, which is why etcd is the recommendation |
| 2 | **Returned** on the grant handle, as a required field alongside the client's own monotonic deadline | `lock-server` → `lock-client` | Making it optional invites callers to ignore it; the type makes it unavoidable |
| 3 | **Carried** through application code as an explicit parameter or request field | `payout-executor` | **Never** in a thread-local or ambient context: it is lost the first time work moves to another thread pool, and it is lost *silently* |
| 4 | **Presented** to the payment resource on every mutation, over a process boundary | → `payment-resource` | A path that mutates without it is INV-07; the mutating API takes the token as a required argument so such a path does not compile |
| 5 | **Validated independently** by the resource: advance `account.fence` only if `fence < :token`, in the same transaction, before anything else | `payment-resource` | Reading the fence and then writing in a second statement reintroduces the race the token exists to remove. One conditional statement, or nothing |
| 6 | **Presented** to the rail proxy with the payout id and client reference | → `rail-proxy` | — |
| 7 | **Validated independently again**, against a high-water value the resource cannot see, plus the per-payout uniqueness check, before the rail is contacted | `rail-proxy` | This is the only guard available when the resource cannot be modified at all — the general answer for a third-party API with no CAS |
| 8 | **Recorded** on the submission record and on the ledger rows | both stores | Makes a postmortem answer "under which grant did this happen?" without guessing |
| 9 | **Rejection is a first-class outcome:** a distinct error, a structured `fenced_out` event carrying key, presented token and stored token and owner, a counter, and an alert on any occurrence | both guards | A rejection swallowed as a generic error loses the only direct evidence that a safety net fired |

Steps 5 and 7 are the design. Everything else is plumbing that exists to get an integer to those two
places without dropping it.

## 2.7 Ubiquitous language {#dm-glossary}

| Term | Meaning in this project |
|---|---|
| **Payout** | One instruction to move a fixed amount out of one account, exactly once |
| **Rail** | The external, third-party payment network. Non-idempotent, unmodifiable, slow, occasionally ambiguous |
| **Rail proxy** | Our process in front of the rail; the second, independent fencing enforcement point |
| **Submission** | One forwarding of a payout to the rail. At most one may ever succeed per payout |
| **Ambiguous outcome** | A submission whose result is unknown and unknowable locally. Never retried; resolved by external evidence |
| **Client reference** | The stable identifier we send to the rail; the key by which ambiguity is later looked up. Never regenerated |
| **Fence** | The highest token a guard has ever accepted. Non-decreasing, per lock key |
| **Fencing token** | A strictly increasing integer issued with a grant, validated by each guard to reject a stale holder |
| **Fenced out** | A mutation rejected because its token is not above the stored fence. Evidence of a safety net firing |
| **Grant / handle** | The answer to a successful acquire: key, owner, token, session, server expiry, local deadline |
| **Lease** | A grant with an expiry, so a dead holder cannot block the key forever |
| **Session** | A client's liveness relationship with the lock service; locks attach to it and die with it |
| **Conservative local expiry** | The client's own earlier deadline, from a monotonic clock and the heartbeat *send* time, discounted by a safety margin |
| **Grace period** | The window after a backend leader election during which all sessions are assumed alive |
| **Force-revoke** | Operator break-glass that frees a key **and advances the token**; audited and alerted |
| **Guard** | A process that independently validates a token before permitting an effect. There are two |
| **Transaction group** | The set of ledger entries of one movement; sums to zero |
| **Terminal state** | `POSTED`, `FAILED`, `ABANDONED`. No outgoing transitions, ever |
| **Safety event** | Any observation implying two holders acted, or a stale write landed. Target: zero; one occurrence is incident-grade |

## 2.8 Deliberate simplifications {#dm-simplifications}

Each is a real gap. Naming the cost is the difference between a simplification and an omission.

| Simplification | Production reality | What the gap would cost |
|---|---|---|
| **Single currency, integer minor units, no FX** | Multi-currency ledgers need a currency on every entry, per-currency balancing, and FX rate capture at posting time with its own audit trail | Balance assertions become per-currency; a rate applied at the wrong instant is a permanent, silent loss. Roughly a project of its own |
| **No fees** | Fees split the movement into several legs with different counterparties and tax treatment | The two-row ledger becomes four to six rows; INV-03 holds but the group grows and fee reversal on decline is a new state path |
| **No partial settlement** | Rails settle partially, or settle a different amount than requested | `RAIL_ACKED` would need a settled amount distinct from the requested amount, and the ledger would post what actually moved. Every reconciliation rule changes |
| **No chargebacks, recalls, or reversals** | Money comes back weeks later, referencing the original movement | A whole second state machine and compensating groups. Ambiguity resolution ([2.3](#dm-payout-fsm)) is where recalls would attach |
| **Ledger posted after the rail acknowledges, funds only *checked* at claim time** ([A-09](01-requirements.md#br-assumptions)) | Production reserves funds first: a pending debit at claim time, converted or released at settlement | Concurrent payouts on one account could each pass the funds check and overdraw — which the per-account lock happens to prevent here, but *only* because of the lock. That is a fragile reason to be correct, and it is why production reserves. Kept deliberately: it makes ambiguity maximally instructive — money possibly moved, no ledger row at all |
| **One rail, one submission per payout** | Multiple rails, routing, retry on an *alternate* rail | Retry-on-another-rail needs proof the first did not settle, i.e. ambiguity resolution as a blocking dependency |
| **Synthetic beneficiary references, no personal data** | Names, account identifiers, sanctions screening, retention and access review | Data classification, encryption, retention, and DSR handling; also removes the option of publishing the repository |
| **No payout creation, approval, or fraud scoring** | Approval workflow and risk decisioning upstream | Another lock use case, and one where the correct answer is often idempotency rather than locking |
| **Balance as a cached projection with no rebuild job** | Continuous or scheduled rebuild from the ledger, with drift alerting | Drift between balance and ledger would be found late; here the reconciler asserts equality but does not repair it |
| **Per-account locking, not per-payout** ([A-15](01-requirements.md#br-assumptions)) | Some systems shard per beneficiary or per rail session | Coarser locking limits per-account throughput to one payout at a time. Acceptable at [A-01](01-requirements.md#br-assumptions) volumes; a hot account would be a contention hotspot and a fairness question this project explicitly does not answer |
