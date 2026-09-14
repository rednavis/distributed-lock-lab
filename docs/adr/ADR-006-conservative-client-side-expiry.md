# ADR-006 — Conservative client-side lease expiry {#adr6}

| Field | Value |
|---|---|
| **Status** | **Accepted**, 2026-08-21 |
| **Decider** | Project owner / architect |
| **Scope** | `lock-client` only; no server behaviour changes |
| **Contracts** | [C5 §5.1](../contracts/C5-config-build-and-naming.md#ct5-config) (`lock.client.safety-margin` = `0.30`, `lock.default.ttl` = `30s`, `lock.session.ttl` = `15s`) · [C2 §2.6](../contracts/C2-java-api.md#ct2-sdk) · [C3 §3.8](../contracts/C3-http-surfaces.md#ct3-timeouts) |
| **Requirements** | [FR-11](../01-requirements.md#br-fr) · [UC-03](../01-requirements.md#br-uc03) · [INV-07](../01-requirements.md#br-invariants) · [A-04](../01-requirements.md#br-assumptions) |

## 6.1 Context {#adr6-context}

A lease is a *server-side* statement: the lock service will consider the grant valid until its own
deadline. The client cannot observe that deadline. It can observe only when it sent a heartbeat and
when a reply came back, and between those two instants sit network transit, server queueing, GC pauses,
and — on GKE Autopilot — CPU throttling and eviction. Every one of those inflates the client's belief
about how much lease it has left.

Three specific traps:

| Trap | Why it bites |
|---|---|
| Measuring the lease from **reply arrival** | The server's clock started at *receive*, which is earlier. A 2 s stalled reply gives the client 2 s of lease it does not own. |
| Using a **wall clock** | NTP slew and step corrections, VM live-migration pauses and container suspension all move `System.currentTimeMillis()` non-monotonically. A backwards step extends a lease silently. |
| Treating the lease as the **safety** mechanism | It is not. The two fence checks are ([02 §2.5](../02-domain-model.md#dm-invariant-enforcement)). A lease is a *liveness* device. |

The critical section here ends in an irreversible external side effect ([UC-05](../01-requirements.md#br-uc05)),
so the interesting question is not "am I still the holder?" but "do I have enough lease left to finish
the next irreversible step?"

## 6.2 Decision {#adr6-decision}

| # | Rule |
|---|---|
| D1 | The client computes its own deadline: `deadline = t_send + lease × (1 − margin)`, `margin` = `lock.client.safety-margin`, default **0.30** (ASSUMPTION, to be measured — [A-04](../01-requirements.md#br-assumptions)). |
| D2 | `t_send` is captured on a **monotonic** clock immediately before the heartbeat/renew request is written to the wire — never on reply, never from a wall clock, never from a server-supplied absolute timestamp. |
| D3 | `void checkStillHeld(LockHandle handle) throws LockLostException` ([C2 §2.6](../contracts/C2-java-api.md#ct2-sdk) owns the signature; the method **returns normally or throws** — an `is`-prefixed boolean name would be wrong twice over, because the call raises `LockLostException` rather than merely answering, and because a boolean invites a caller to test it and carry on) is **by default** a local, non-blocking comparison against that deadline, so the common path makes no network call and cannot itself consume the budget it is reporting on. C2 §2.6 permits it to **optionally confirm with the server** (FR-13); when that option is enabled the call blocks for one hop and its cost counts against D4's remaining-lease arithmetic like any other hop. Either way it is a **liveness narrowing, never a safety guarantee** — the lease can lapse in the nanoseconds after it returns normally. |
| D4 | **Do not start what you cannot finish.** Before any irreversible step the client requires `remaining > worst_case_cost_of_step`, where the cost is the hop's read timeout from [C3 §3.8](../contracts/C3-http-surfaces.md#ct3-timeouts) (rail submit = rail read timeout + 2 s). Insufficient remaining lease means *abandon before acting*, not *act and hope*. |
| D5 | On heartbeat failure the client **shrinks** its deadline (it never extends on a failed exchange) and stops the critical section at the deadline whether or not the server agrees. |
| D6 | Abandonment is cheap and always allowed; the payout returns to `PENDING`/`CLAIMED` and a later worker retries under a higher token. Correctness never depends on the client being right about time. |

**The safety / liveness split — the load-bearing sentence.** Safety is enforced by the two monotonic
fences ([C1 §1.6](../contracts/C1-database-schemas.md#ct1-fenced) and
[C3 §3.5](../contracts/C3-http-surfaces.md#ct3-railproxy)) and holds even if the client's clock is
nonsense. The margin buys **quiet**: it keeps a well-behaved client from routinely reaching those
fences. Therefore: raising the margin costs *throughput only*; lowering it never buys correctness, it
only converts silent self-restraint into loud `FENCED_OUT` events on counters whose healthy value is
exactly zero ([C4 §4.4](../contracts/C4-observability.md#ct4-zero)).

## 6.3 Consequences {#adr6-consequences}

**Positive**

- The client is wrong in the safe direction by construction; every clock error shortens the usable window.
- `lock.fenced.out` and `rail.duplicate.attempted` stay at zero in healthy operation, so they remain usable as alerts rather than as background noise.
- [UC-03](../01-requirements.md#br-uc03) (worker pauses past its lease and returns) becomes a *demonstration* rather than an accident: to fire it, the harness must disable the client check, and the fences still hold.

**Negative**

- ~30% of every lease is deliberately unusable, so effective throughput per holder drops and long payouts need a renew they would otherwise not need.
- A slow-but-alive worker abandons work it could have completed; `payout.backlog.age.seconds` rises under load and can be mistaken for a stuck queue.
- Two tunables (`lock.session.ttl`, `margin`) now interact; setting the margin so large that `lease × (1 − margin)` is shorter than one rail call makes progress impossible while every component reports healthy.

**What we accept**

- The margin is a guess until [M7](../00-charter.md#ch-map) measures heartbeat RTT distribution; the number in the contract is an ASSUMPTION, not production data.
- Wasted lease is the price of a quiet fence. We would rather pay throughput than debug a duplicate payment.

## 6.4 Alternatives considered {#adr6-alternatives}

| Alternative | Why rejected |
|---|---|
| Deadline from **reply arrival** time | Systematically optimistic by one RTT plus server latency — exactly the interval in which a stale writer is created. The bug is invisible on a fast LAN and fatal under the conditions that matter. |
| Server returns an **absolute expiry** the client trusts | Requires synchronised wall clocks between processes. Clock skew is the assumption distributed locking exists to avoid; importing it into the client is a regression. |
| **No client-side expiry**; rely purely on the fences | Correct but operationally useless: every overrun becomes a `FENCED_OUT` or a `DUPLICATE_SUBMISSION`, destroying two must-be-zero signals and paging on normal slowness. |
| **Adaptive margin** from observed RTT (e.g. p99 × k) | Better in principle, and a plausible V2. Rejected for now: it makes the benchmark non-reproducible and hides the arithmetic the project exists to teach. Deferred, not dismissed. |
| **Longer TTL** so the margin stops mattering | The TTL is also the recovery latency after a hard worker death ([UC-04](../01-requirements.md#br-uc04)); lengthening it trades a throughput problem for a stuck-payout problem. |
| Thread-local "current token + deadline" ambient context | Forbidden by [C2 §2.7](../contracts/C2-java-api.md#ct2-propagation): an ambient deadline survives into async work that no longer holds the lock. |

## 6.5 Revisit when {#adr6-revisit}

| Trigger | Action |
|---|---|
| Measured p99 heartbeat RTT exceeds one third of `lease × margin` | Raise the margin or shorten the heartbeat period; the current margin no longer covers observed jitter. |
| The M7 benchmark shows the margin is the dominant throughput limiter | Consider the adaptive margin (V2), not a smaller fixed one. |
| `lock.session.ttl` or `lock.default.ttl` changes | Recheck `lease × (1 − margin) > ` worst-case rail hop; a TTL cut can make D4 unsatisfiable. |
| Any `lock.fenced.out > 0` traced to a client that believed it held the lease | This ADR is being violated in code; fix the client, do not widen the fence. |
| A move off Autopilot removes CPU-throttling pauses | The margin may be reducible — but only against measurement, never against intuition. |
