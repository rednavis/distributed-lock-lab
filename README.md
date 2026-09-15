# distributed-lock-lab

**A distributed lock is not what makes your critical section safe. This repository proves it, by
experiment, in about ninety seconds.**

[![docs](https://github.com/rednavis/distributed-lock-lab/actions/workflows/docs.yml/badge.svg)](https://github.com/rednavis/distributed-lock-lab/actions/workflows/docs.yml)
[![pages](https://github.com/rednavis/distributed-lock-lab/actions/workflows/pages.yml/badge.svg)](https://github.com/rednavis/distributed-lock-lab/actions/workflows/pages.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Status](https://img.shields.io/badge/status-specification--complete%2C%20pre--implementation-orange.svg)](#project-status)
[![Contributions](https://img.shields.io/badge/contributions-welcome-brightgreen.svg)](CONTRIBUTING.md)

📖 **[Read the documentation site →](https://rednavis.github.io/distributed-lock-lab/)** — the full
doc set with search and working cross-references.

---

## The experiment

Two workers race to execute the same payout.

Worker **A** acquires the lock, then stalls — a stop-the-world GC pause, a throttled container, a
`SIGSTOP`. It stalls long enough that its lease expires. The lock service does exactly the right
thing: it expires the lease on schedule and grants the lock to worker **B**. Worker **B** submits the
payout to an external payment rail and posts the ledger entries.

Then worker **A** wakes up. It still believes it holds the lock. It submits **the same payout again**.

Run the scenario with fencing disabled and you get a duplicate submission to a non-idempotent payment
rail and a ledger that no longer balances. Run it with fencing enabled — **changing nothing else, same
lock service, same backend, same test** — and the stale worker is rejected at two independent
enforcement points before it can touch anything.

```console
$ ./gradlew :harness:runScenario --args='sigstop --fencing=off'
  rail.submissions=2   ledger.balanced=false   ← money sent twice
$ ./gradlew :harness:runScenario --args='sigstop --fencing=on'
  rail.submissions=1   ledger.balanced=true    ← stale writer rejected twice
```

The lock service behaved **correctly in both runs.** It granted one lease at a time and expired the
first one on schedule. That is precisely what makes this class of bug so easy to miss in code review,
and it is the entire argument of this project.

→ The experiment is task [`T-042`](tasks/T-042-fencing-demo.md). The mechanism is
[`docs/03-architecture.md`](docs/03-architecture.md#arch-flows), flow C.

## Why this repository exists

Search for "distributed lock tutorial" and you will find the same example every time: a bank balance,
protected by a lock.

```sql
UPDATE account SET balance_minor = balance_minor - :amount
 WHERE account_id = :id AND balance_minor >= :amount;
```

That statement is already atomic. It is already serialised by PostgreSQL on a single row. It needs no
lock service at all, and wrapping one around it teaches a reflex that will cost somebody real money
later.

A distributed lock earns its place only when the critical section spans **a side effect no database
transaction can roll back.** Here that side effect is a submission to a deliberately non-idempotent
external payment rail — the one shape for which a distributed lock is genuinely the right answer.

Because the two protected resources have different capabilities, fencing is enforced **twice**, in two
processes that the lock service does not control:

| Resource | Enforcement mechanism | Contract |
|---|---|---|
| PostgreSQL row | conditional `UPDATE … WHERE fence < :token` | [C1](docs/contracts/C1-database-schemas.md#ct1-fenced) |
| External rail (cannot be modified) | proxy holding a **persisted** highest-token-per-account high-water mark | [C3](docs/contracts/C3-http-surfaces.md#ct3-railproxy) |

The high-water mark is persisted rather than in-memory, so that a proxy restart cannot forget it and
re-admit a stale writer — see [ADR-007](docs/adr/ADR-007-rail-fencing-proxy-for-a-non-cas-resource.md).

Neither enforcement point lives inside the lock service. That is the load-bearing design decision of
the whole project: if the `fence < :token` check ran inside `lock-server`, the service would be
validating its own grants, and the bug being demonstrated — *the server was right, the holder was
wrong, and the resource believed the holder* — could not occur at all.

## Project status

> **This repository currently contains specifications, not code.**
>
> Approximately 100 documents: requirements, architecture, five authoritative contracts, fourteen
> decision records, SRE artifacts, and **63 implementation task specifications** written to be picked
> up independently by contributors. Nothing here compiles yet, because nothing here is code.
>
> **That is the invitation.** The design work is finished and reviewable; the implementation is open.

| | |
|---|---|
| Phase | Specification complete → implementation open |
| Milestone in progress | **M0 — Foundations** ([roadmap](ROADMAP.md)) |
| Tasks ready to claim | see the [task board](tasks/README.md) and [`good first issue`](../../issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22) |
| Language / build | Java 25, Gradle 9.5 Kotlin DSL, Spring Boot 4.1 |
| Backends | PostgreSQL 16 and etcd 3.6, both first-class |
| License | [Apache-2.0](LICENSE) |

## What gets built

Nine modules, two interchangeable lock backends, two independent fencing enforcement points, and the
operational apparatus to run the result on real infrastructure.

```
  +--- harness: scenarios | chaos (pause/kill/partition) | invariant checks | bench ---+
      | drives                                                              asserts |
      v                                                                             v
  +--------------+  acquire/renew/release        +--------------------------------+
  | payout-      |------------------------+      |          lock-server           |
  | executor     |  heartbeat             |      | web -> core (SessionRegistry,  |
  | (the worker) |<-- lock-client (SDK) --+----->| lease clock, token minting)    |
  |  deadline, checkStillHeld, onLockLost |      | store.pg    |    store.etcd    |
  +---+------+----------------------------+      +-----+--------------+-----------+
      |      | (2) submit  X-Fencing-Token             |              |
      |      v                                  +------v-----+  +-----v----------+
      |  +----------------+  POST /submit       | dlock-pg-  |  | dlock-etcd     |
      |  |   rail-proxy   |  (no token, no key) | lock       |  | 3-replica STS  |
      |  | fence point (c)|------------------->  (lockdb,    |  | ModRevision =  |
      |  | rail_high_water|<--ACK/DECLINE/t-out | REGIONAL)  |  | token          |
      |  +--------+-------+     +-----------+   +------------+  +----------------+
      |           |             | rail-stub |
      |           | intent      | non-      |   lock-api: zero-dependency types,
      | (1) claim | BEFORE      | idempotent|     shared by every box above
      |  (3) post | forward     +-----------+   deploy: Terraform + K8s + compose
      v           v                            build-logic: toolchain, Spotless
  +----------------------------------------------------+   +--------------+
  |  payment-resource | payout FSM | ledger | balance  |-->| dlock-pg-pay |
  |  fence point (a): UPDATE .. WHERE fence < :token   |   | (paydb,ZONAL)|
  +----------------------------------------------------+   +--------------+
```

| Module | Responsibility |
|---|---|
| `lock-api` | The Java and wire contract. **Zero third-party dependencies**, enforced in CI |
| `lock-server` | Lease authority; the only minter of fencing tokens; `store.pg` and `store.etcd` |
| `lock-client` | SDK: conservative monotonic deadline, heartbeat loop, `checkStillHeld`, `onLockLost` |
| `payment-resource` | Owns `paydb`; **fence point (a)** — the conditional `UPDATE` |
| `payout-executor` | The worker that composes the critical section end to end |
| `rail-proxy` | **Fence point (c)** — persisted high-water mark; records intent before forwarding |
| `rail-stub` | A deliberately non-idempotent external rail with injectable latency and failures |
| `harness` | Scenario runner, chaos injection, invariant checkers, benchmark driver |
| `deploy` | Terraform, Kubernetes manifests, local `docker compose` path |

Full inventory with dependency edges: [C5 §5.4](docs/contracts/C5-config-build-and-naming.md#ct5-modules).

## Getting started

The Gradle build is in place; the services themselves are still being written. To find your way in:

| If you want to | Start at |
|---|---|
| **Contribute code** | [`CONTRIBUTING.md`](CONTRIBUTING.md), then claim a task from the [board](tasks/README.md) |
| **Understand the argument** | This README, then [`docs/00-charter.md`](docs/00-charter.md) |
| **Review the design** | [`docs/03-architecture.md`](docs/03-architecture.md) and the [decision records](docs/adr/) |
| **Look up a name or a schema** | [`docs/04-contracts.md`](docs/04-contracts.md) — the index to every pinned identifier |
| **Read the SRE half** | [`docs/06-observability-and-slo.md`](docs/06-observability-and-slo.md), [`docs/08-operations.md`](docs/08-operations.md) |
| **Contribute as an AI agent** | [`AGENTS.md`](AGENTS.md) — required reading before generating anything |

The local path needs JDK 25 and no cloud account:

```console
git clone https://github.com/rednavis/distributed-lock-lab.git
cd distributed-lock-lab
./gradlew build          # builds all modules, runs the tests and the style check
./gradlew spotlessApply  # formats Java and Gradle files, adds the license header
```

`docker compose up` — the local stack with both backends, the rail and the services — arrives with
[`T-005`](tasks/T-005-compose-stack.md).

## The contracts are authoritative

Five documents pin every identifier this project is allowed to use — tables, columns, SQL statements,
Java signatures, HTTP paths, error codes, headers, metric names, log event names, configuration keys,
module names, cloud resource names.

| | |
|---|---|
| [C1](docs/contracts/C1-database-schemas.md) | Database schemas and the exact SQL |
| [C2](docs/contracts/C2-java-api.md) | Java API, the SPI, the SDK |
| [C3](docs/contracts/C3-http-surfaces.md) | HTTP surfaces, error envelope, timeouts |
| [C4](docs/contracts/C4-observability.md) | Metrics, logs, traces, cardinality rules |
| [C5](docs/contracts/C5-config-build-and-naming.md) | Configuration, build, and every naming convention |

**If a task specification and a contract disagree, the contract wins** — open a
[contract change issue](.github/ISSUE_TEMPLATE/contract-change.yml) rather than implementing either
version. Nine modules and two lock backends compile against one vocabulary, and no single contributor
sees more than a slice of it. A plausible synonym — `fencing_token` where the contract pins `fence` —
compiles, passes its own module's tests, and fails at every integration point three milestones later.

## What this is not

This is a **reference implementation and a teaching artifact.** It is not production-ready and must
never be deployed in front of real money.

Concretely missing, and deliberately so: no authentication or authorisation on the lock API, no
multi-tenancy, no quotas, single region, no key-space sharding, no shared/exclusive lock modes, no
strict FIFO fairness, no data-retention or PII story. It is not a Raft implementation — consensus
comes from etcd, and [ADR-001](docs/adr/ADR-001-etcd-as-the-consensus-store.md) explains why writing
our own would teach a different lesson than the one this project is for.

The full non-goals table, each with its reason, is [`docs/00-charter.md`](docs/00-charter.md#ch-nongoals).

The domain — a mid-size payment service provider, its payout queue, its external rail — is
**fictional.** Every quantity in this repository is an explicitly labelled assumption, not measured
production data. No benchmark number appears without the command, environment and date that produced
it.

## Start here

Five tasks are unblocked **right now** — nothing needs to be merged first, and three of them need no
Java at all:

| Issue | Task | What you need |
|---|---|---|
| [#11](https://github.com/rednavis/distributed-lock-lab/issues/11) | `T-001` Monorepo skeleton and Gradle settings | JDK 25, Gradle. **Blocks every other Java task — highest priority in the repo** |
| [#48](https://github.com/rednavis/distributed-lock-lab/issues/48) | `T-050` Terraform root and dev environment | Terraform CLI only. No cloud account, no billing |
| [#49](https://github.com/rednavis/distributed-lock-lab/issues/49) | `T-051` Terraform module: network | Terraform CLI only |
| [#50](https://github.com/rednavis/distributed-lock-lab/issues/50) | `T-052` Terraform module: two Cloud SQL instances | Terraform CLI only |
| [#66](https://github.com/rednavis/distributed-lock-lab/issues/66) | `T-068` The runbook, one entry per alert | A text editor. Pure prose |

Once `T-001` lands, [`T-023`](https://github.com/rednavis/distributed-lock-lab/issues/30) —
`rail-stub`, the deliberately non-idempotent rail — is the best first Java contribution: self-contained,
zero dependencies, and it is the hazard the whole experiment depends on.

Browse everything: **[ready to start](https://github.com/rednavis/distributed-lock-lab/issues?q=is%3Aissue+is%3Aopen+label%3A%22status%3Aready%22)** ·
**[good first issues](https://github.com/rednavis/distributed-lock-lab/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22)** ·
[all 63 tasks](https://github.com/rednavis/distributed-lock-lab/issues) ·
[the board](tasks/README.md) · [what blocks what](docs/12-parallelization-map.md)

## Contributing

Contributions are welcome and the project is structured to make them tractable: 63 independently
specified tasks, a dependency graph that says which can run in parallel, and contracts precise enough
that two people working in different modules will produce code that fits together.

- **New here?** [`CONTRIBUTING.md`](CONTRIBUTING.md) → [task board](tasks/README.md) → pick a
  `good first issue`
- **Governance and decision-making:** [`GOVERNANCE.md`](GOVERNANCE.md)
- **Behaviour:** [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md)
- **Security:** [`SECURITY.md`](SECURITY.md)
- **Questions:** [`SUPPORT.md`](SUPPORT.md)

Both humans and AI agents contribute here, under the same rules and the same review bar.
[`AGENTS.md`](AGENTS.md) states the additional obligations that apply to generated contributions.

## License

Apache License 2.0 — see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).

Contributions are accepted under the same license via the
[Developer Certificate of Origin](https://developercertificate.org/); sign your commits with
`git commit -s`. There is no separate CLA.
