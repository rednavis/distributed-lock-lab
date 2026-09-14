# 11 — Glossary {#glossary}

> **Status:** baseline · **Owner:** maintainers · **Last reviewed:** 2026-09

Terms of art as this repository uses them. Several look like ordinary words and are doing precise
work; those are the ones that cause the expensive misunderstandings.

Names pinned by a contract are marked **(pinned)** and may not be spelled differently anywhere in the
codebase — see [04 §4.4](04-contracts.md#c-precedence).

---

## The central concepts

**Fencing token** **(pinned: `fence`)**
A strictly increasing integer minted by the lock service at **grant time** and presented by the holder
with every write to a protected resource. The resource compares it against the highest token it has
already accepted and rejects anything not strictly greater. **This — not mutual exclusion — is what
actually protects the resource.** A lock says "you may proceed"; a fence says "you are still the most
recent one who was told that".

> The column is `fence`, never `fencing_token`. A plausible synonym compiles, passes its own module's
> tests, and fails at every integration point three milestones later.

**Fence point**
A place where a fencing token is checked. This project has exactly two, and both live in processes the
lock service does not control — that separation is the load-bearing design decision
([03 §3.3](03-architecture.md#arch-boundaries)).

| | Resource | Mechanism |
|---|---|---|
| **(a)** | PostgreSQL row in `paydb` | conditional `UPDATE … WHERE fence < :token` |
| **(c)** | The external rail | `rail-proxy` compares against a **persisted** `rail_high_water` |

**Stale holder**
A process that still believes it holds a lock whose lease has already expired — typically after a
stop-the-world GC pause, a throttled container, or a `SIGSTOP`. **The lock service is not wrong when
this happens**; it expired the lease on schedule and granted it to somebody else. The stale holder is
the antagonist of the entire project.

**Lease**
A grant with an expiry. The lock service's clock is the only authority on whether a lease is live; a
client's opinion is a local optimisation ([06 conservative expiry](adr/ADR-006-conservative-client-side-expiry.md)).

**Conservative client-side expiry**
The SDK's local deadline, computed on a **monotonic** clock from the acquire's *send* time and reduced
by a safety margin, so the client gives up before the server does. It is a **liveness** device and
never a safety one — a paused process's clock arithmetic is paused too.

**Safety versus liveness**
The distinction this project is most insistent about.

| | Guarantees | Example here |
|---|---|---|
| **Safety** | Something bad never happens | The fencing token: a stale write is *rejected* |
| **Liveness** | Something good eventually happens | `checkStillHeld`, `onLockLost`: a stale holder *stops early* |

Safety without liveness is a correct system that pages you at 03:00. Liveness without safety is a
system that corrupts data quietly. Confusing them is the classic error in this domain.

## Lock service

**`lock-server`** **(pinned)** — the lease authority, and the **only** minter of fencing tokens.

**`lock-api`** **(pinned)** — the Java and wire contract. **Zero third-party dependencies**, enforced in
CI: six modules depend on it, and a framework in here would entangle the contract with that framework's
lifecycle.

**`lock-client`** **(pinned)** — the SDK. Conservative deadline, heartbeat loop, `checkStillHeld`,
`onLockLost`.

**`checkStillHeld`** **(pinned)** — called before every side effect. It **throws** rather than returning
a boolean, which is why it is not named `isStillHeld`: the check raises, it does not merely answer. It
*narrows* the window between believing you hold the lock and acting on it. **It cannot close it** — the
lease can lapse in the nanoseconds after it returns normally. That residual window is exactly what
fencing covers.

**Session** **(pinned: `lock_session`)** — a client's registration with the lock service, kept alive by
heartbeats. When it dies, every lock it holds is released.

**Barging** — a contended acquire that retries with jittered exponential backoff rather than queueing.
Strict FIFO fairness is a [non-goal](00-charter.md#ch-nongoals).

**Force-revoke** **(pinned: `lock_revocation`)** — break-glass clearing of a stuck lock. It **advances
the token**, so the revoked holder is fenced out. Requires an operator identity and a reason, and is
audited. Not routine automation.

**`ModRevision`** — etcd's per-key revision of the winning compare-and-swap, captured **at grant time**
and used as the token. Monotonic **by construction**, which is why etcd is the production
recommendation ([ADR-002](adr/ADR-002-fencing-token-source.md)).

**`fencing_token_seq`** **(pinned)** — the PostgreSQL backend's single global sequence. Monotonic **by
procedure**: a restore from backup can rewind it, and only operator discipline prevents that. The
distinction between *by construction* and *by procedure* is the whole backend comparison.

## The protected domain

All fictional. A mid-size payment service provider, deliberately unnamed.

**PSP** — the fictional payment service provider. Never a real company.

**`payout-executor`** **(pinned)** — the worker. The only module composing the whole critical section:
claim → lock → re-read → submit → post.

**`payment-resource`** **(pinned)** — owns `paydb`. **Fence point (a).** Deliberately knows nothing
about the existence of a lock service.

**The rail** — the external payment system. **Deliberately non-idempotent**: no idempotency key, no
compare-and-set, and it cannot be modified. This is not a strawman — it is the only shape for which a
distributed lock is the right answer.

**`rail-stub`** **(pinned)** — a fake rail that behaves badly on purpose, with injectable latency,
declines, timeouts and duplicate acknowledgements. It is the environment, not the system;
`rail-stub` misbehaving is never a bug.

**`rail-proxy`** **(pinned)** — **fence point (c)**. Records intent **before** forwarding, and holds the
`rail_high_water` mark. It **never retries** a submission.

**`rail_high_water`** **(pinned)** — highest token accepted per account, **persisted** rather than
in-memory so that a proxy restart cannot forget it and re-admit a stale writer
([ADR-007](adr/ADR-007-rail-fencing-proxy-for-a-non-cas-resource.md)).

**Ambiguous outcome** **(pinned: `RAIL_AMBIGUOUS`, `outcome='TIMEOUT'`)** — the rail timed out and we do
not know whether the money moved. The payout is frozen and resolved by reconciliation. **Retrying would
be a second non-idempotent submission** — a duplicate payment.

**Double-entry ledger** **(pinned: `ledger_entry`)** — immutable rows, balance derived. Makes
"balance equals the sum of the ledger" an assertable invariant instead of a hope.

## Operations

**SLI / SLO / error budget** — what is measured, the target, and the permitted shortfall. The budget is
**spendable**, and the [error-budget policy](06-observability-and-slo.md) names the consequences of
spending it.

**Symptom paging versus cause ticketing** — a human is paged for what the *customer* experiences
(`payout.backlog.age.seconds`); causes (acquire error rate) raise tickets. An alert that is not
actionable at 03:00 should not page.

**Kill switch** **(pinned: `payment.fencing.enabled`, `rail.proxy.fencing.enabled`)** — the two
configuration flags that disable fencing enforcement. They exist **only** to demonstrate corruption
inside a named experiment. Both default to safe and alarm while set.

> Turning one off to silence a `lock.fenced.out` alert is removing the smoke detector because the
> kitchen is on fire. A fenced-out write is not a malfunction — it is the system reporting that a stale
> writer was stopped.

**Must-be-zero counter** **(pinned: `lock.fenced.out`, `rail.duplicate.attempted`)** — non-zero in a
healthy run means a real safety event, not noise.

**Cardinality** — the number of distinct time series a metric produces. **No metric here is tagged with
a lock key, payout id, account id or token**; those live in structured logs only. CI fails the build on
a violation (SC-11).

**The named-port trap** — a `PodMonitoring` scrape that is configured correctly in every visible respect
and silently collects nothing. The canonical example of why "the code calls the meter" is not evidence
([C4 §4.9](contracts/C4-observability.md#ct4-scrape)).

**Game day** — deliberately firing **every** alert to measure detection latency and time-to-runbook-step.
An alert never fired is an alert never tested.

**Toil** — manual, repetitive, automatable operational work that scales with load. Tracked in a
register so that it is visible rather than absorbed.

## Correctness engineering

**Deterministic simulation (DST)** — running the system against a simulated world with a controlled
clock and scheduler, so that a failure is reproducible from a **seed**. The seed is the artifact; a
counterexample beats a pass, because a pass only means "no violation in the schedules we happened to
explore".

**Linearizability** — every operation appears to take effect instantaneously at some point between its
invocation and its response. The checker consumes a recorded history and looks for a valid
serialisation.

**Fail closed** **(FR-27)** — when the lock service is unreachable or its answer uncertain: **no grant,
no side effect.** Deliberately **not configurable**, so that no future incident-driven "just let it
through" flag can exist. A delayed payout is a support ticket; a duplicated payout is money gone.

**Negative control** — a deliberately broken run proving the detector works. The fencing-disabled run of
`T-042` is the project's central one: without it, "fencing works" is unfalsifiable.

## Project process

**Contract** — [C1–C5](04-contracts.md). Authoritative. **The contract wins** over any task
specification, and amendment is a two-approval breaking change.

**Ledger** — [`../tasks/README.md`](../tasks/README.md). The single source of truth for what is done.
Updating it is part of the definition of done.

**Precondition** — what must be **merged** before a task can start. The real dependency, as against the
task numbering, which is [only an identifier](12-parallelization-map.md#pm-howto).

**Fan-out point** — a task whose merge unblocks several others. Review these first.

**Deviation** — any difference between what a specification said and what was built. Recorded with
what, why, blast radius, and contract impact. An unrecorded deviation is indistinguishable from a bug
for everyone who comes after.

**Split** — dividing an over-large task, with the remainder taking the next **reserved id** in that
milestone's gap (`T-009`, `T-018`, …). Never `T-017b`.

**ASSUMPTION** — an invented figure. Every number in this repository is one unless it names the command
that measured it.
