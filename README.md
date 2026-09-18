# distributed-lock-lab

**A distributed lock service for the payout queue of a fictional mid-size payment service provider,
built to demonstrate one lesson: mutual exclusion does not protect a resource — the fencing token
does.**

## The fencing demonstration

Worker **A** holds the lock and stalls past its lease. The lock service does exactly the right thing:
it expires the lease and grants the lock to worker **B**, which completes the payout. Then **A** wakes
up, still believing it holds the lock, and tries to finish its work.

```console
# Illustrative transcript of the T-042 scenario, fencing on. Token values are examples.
[A] acquire payout:acct-7                            -> GRANTED  fencingToken=41
[A] SIGSTOP: paused past its lease; lock-server expires the lease on schedule
[B] acquire payout:acct-7                            -> GRANTED  fencingToken=42
[B] POST /v1/rail/submissions  X-Fencing-Token: 42   -> 200 ACKED   (rail_high_water: 42)
paydb=> UPDATE account SET balance_minor = balance_minor - 2500, fence = 42
paydb->  WHERE account_id = 'acct-7' AND currency = 'EUR' AND fence < 42 ...;
UPDATE 1
[A] SIGCONT: still believes it holds the lock, submits the same payout again
[A] POST /v1/rail/submissions  X-Fencing-Token: 41   -> 409 FENCED_OUT
    rejected: 41 is not greater than 42, and the rail is never called
paydb=> UPDATE account SET ... fence = 41 WHERE account_id = 'acct-7' AND fence < 41 ...;
UPDATE 0
    rejected: the row already carries fence 42
{"event":"fenced_out","resource":"account","resourceId":"acct-7",
 "presentedToken":41,"highestToken":42,"ownerId":"A"}
```

The lock service behaved **correctly in both halves of that transcript.** It granted one lease at a
time and expired the first one on schedule. That is precisely what makes this class of bug so easy to
miss in code review, and it is the entire argument of this project. Set `payment.fencing.enabled` and
`rail.proxy.fencing.enabled` to `false` — the two kill switches of
[C5 §5.2](docs/contracts/C5-config-build-and-naming.md#ct5-killswitches), and **nothing else changed**:
same lock service, same backend, same test — and the stale worker's submission reaches a
non-idempotent payment rail while the ledger stops balancing. Both default to on, are logged at `WARN`
when off, and exist only so that the corruption can be demonstrated inside a named experiment.

→ The experiment is task [`T-042`](tasks/T-042-fencing-demo.md); the transcript above illustrates it
and is replaced by captured output when that task lands. The mechanism is
[`docs/03-architecture.md`](docs/03-architecture.md#arch-flows), flow C.

## Why a lock at all

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
external payment rail — the one shape for which a distributed lock is genuinely the right answer
([ADR-004](docs/adr/ADR-004-payout-executor-as-the-protected-operation.md)).

## The two fence points

Because the two protected resources have different capabilities, fencing is enforced **twice**, and
**both enforcement points live in processes the lock service does not control.**

| Resource | Enforcement mechanism | Process | Contract |
|---|---|---|---|
| PostgreSQL row in `paydb` | conditional `UPDATE … WHERE fence < :token` | `payment-resource` | [C1](docs/contracts/C1-database-schemas.md#ct1-fenced) |
| External rail (cannot be modified) | **persisted** highest-token-per-account high-water mark, checked before the rail is called | `rail-proxy` | [C3](docs/contracts/C3-http-surfaces.md#ct3-railproxy) |

The high-water mark is persisted rather than in-memory, so that a proxy restart cannot forget it and
re-admit a stale writer — see [ADR-007](docs/adr/ADR-007-rail-fencing-proxy-for-a-non-cas-resource.md).

Neither enforcement point lives inside `lock-server`. That is the load-bearing design decision of the
whole project: if the `fence < :token` check ran inside the lock service, the service would be
validating its own grants, and the bug being demonstrated — *the server was right, the holder was
wrong, and the resource believed the holder* — could not occur at all
([ADR-007](docs/adr/ADR-007-rail-fencing-proxy-for-a-non-cas-resource.md),
[architecture §3.3](docs/03-architecture.md#arch-boundaries)).

## Architecture at a glance

```
                      acquire / renew / release           +-----------------+
   +------------------+   (lock-client SDK)               |   lock-server   |
   | payout-executor  |---------------------------------> | lease clock,    |
   | the worker; owns |   fencing token                   | token minting   |
   | the critical     | <---------------------------------|                 |
   | section          |                                   +--+-----------+--+
   +--+------------+--+                                      |           |
      |            |                                 +-------v--+   +----v-----+
      |            | (2) X-Fencing-Token             | lockdb   |   |   etcd   |
      |            v                                 | (pg)     |   | ModRev = |
      |     +------+-------+  POST /submit           +----------+   | token    |
      |     |  rail-proxy  |  (no token, no key)     +----------+   +----------+
      |     | fence point  |-----------------------> | rail-stub|
      |     | rail_high_   | <---- ACK / DECLINE --- | non-     |
      |     | water        |                         | idempotent|
      |     +--------------+                         +----------+
      | (1) claim, (3) post   X-Fencing-Token
      v
   +-------------------------------------------+       +-------------+
   | payment-resource: payout FSM, ledger,     |------>|    paydb    |
   | balance; fence point: WHERE fence < :token|       |    (pg)     |
   +-------------------------------------------+       +-------------+
```

## Both backends are first class

One SPI — `LockStore` ([C2 §2.5](docs/contracts/C2-java-api.md#ct2-spi)) — and two implementations
that mint the fencing token differently:

| Backend | Token | Monotonic because |
|---|---|---|
| PostgreSQL | `nextval('fencing_token_seq')`, one global sequence | **procedure** — a restore must advance the sequence, or an operator recreates a token |
| etcd | the lock key's `ModRevision`, **captured at grant time** from the winning CAS transaction | **construction** — the cluster revision never moves backwards |

The same `payout-executor`, unchanged, runs against both, selected by one configuration key
([ADR-002](docs/adr/ADR-002-fencing-token-source.md), [SC-02](docs/00-charter.md#ch-success)).

## Run it locally

JDK 25 and Docker; no cloud account is needed for anything up to and including the fencing experiment.

```console
./gradlew build                                   # all modules, tests and the style check
./gradlew spotlessApply                           # format Java and Gradle files
docker compose --project-directory deploy/compose up -d --wait   # PostgreSQL x2 + etcd
```

The local stack, its ports and its profiles are documented in
[`deploy/compose/README.md`](deploy/compose/README.md).

## Repository map

The layout is pinned by [C5 §5.5](docs/contracts/C5-config-build-and-naming.md#ct5-layout).

```
distributed-lock-lab/
  settings.gradle.kts          # module registry + version catalog wiring; the only place modules are declared
  build.gradle.kts             # applies convention plugins only - no versions, no per-module logic
  gradle/libs.versions.toml    # the single source of every version
  build-logic/                 # convention plugins: toolchain, format, test, Lombok policy
  lock-api/                    # dev.lock.api - dependency-free contract
  lock-server/                 # dev.lock.server.{core,store.pg,store.etcd,web} + lockdb migrations
  lock-client/                 # dev.lock.client - SDK with the conservative deadline
  payment-resource/            # dev.lock.payments.resource + paydb migrations
  payout-executor/             # dev.lock.payments.executor - the critical section
  rail-proxy/                  # dev.lock.rail.proxy - high-water fencing, attempt-before-forward
  rail-stub/                   # dev.lock.rail.stub - the non-idempotent hazard
  harness/                     # dev.lock.harness - scenarios, chaos, invariant checks
  deploy/
    terraform/                 # root module + env dirs; state in gs://dlock-tfstate
    k8s/                       # manifests per workload, one dir per k8s SA
    compose/                   # local no-cloud path
    images/                    # the shared service Dockerfile
  docs/                        # this document set; docs/contracts/ = C1..C5, docs/adr/ = decisions
  tasks/                       # the 63 task specifications and the ledger
  config/                      # shared static analysis and style configuration
  scripts/                     # check-docs.sh and the repository bootstrap scripts
  .github/workflows/           # build, infra, container, codeql, docs, pages, labels, dco
```

Migrations sit **inside the owning module** (`lock-server` owns lockdb, `payment-resource` owns
paydb), because a shared migrations directory is how two databases silently acquire each other's
tables.

## Toolchain

Every dependency version lives in [`gradle/libs.versions.toml`](gradle/libs.versions.toml) and nowhere
else ([C5 §5.3](docs/contracts/C5-config-build-and-naming.md#ct5-catalog)).

| Component | Version | Where it is pinned |
|---|---|---|
| Spring Boot (BOM and plugin) | 4.1.0 | catalog `spring-boot-bom` |
| PostgreSQL JDBC driver | 42.7.5 | catalog `postgresql` |
| Flyway | 11.8.0 | catalog `flyway-core` |
| jetcd | 0.8.5 | catalog `jetcd-core` |
| OpenTelemetry BOM | 1.49.0 | catalog `otel-bom` |
| Lombok | 1.18.46 | catalog `lombok` |
| JUnit BOM | 6.0.3 | catalog `junit-bom` |
| AssertJ | 3.27.7 | catalog `assertj` |
| Awaitility | 4.3.0 | catalog `awaitility` |
| Testcontainers BOM | 1.21.0 | catalog `testcontainers-bom` |
| Spotless plugin | 7.0.3 | catalog `spotless-plugin` |
| google-java-format | 1.28.0 | catalog `google-java-format` |
| PostgreSQL image | 16.15 | catalog `postgres-image` |
| etcd image | 3.6.14 | catalog `etcd-image` |
| Eclipse Temurin image | 25.0.4_7 | catalog `temurin-image` |
| Java | 25 | Gradle toolchain in `build-logic/src/main/kotlin/dlock.java-base.gradle.kts` — the catalog has no toolchain row |
| Gradle | 9.5.0 | `gradle/wrapper/gradle-wrapper.properties` |
| Terraform | 1.15 | [C5 §5.4](docs/contracts/C5-config-build-and-naming.md#ct5-modules) and `.github/workflows/infra.yml` |

## Non-goals

Written down because *not writing them down* is how a teaching project becomes an unfinished platform.
This table reproduces [charter §0.5](docs/00-charter.md#ch-nongoals); that file is the authority.

| Out of scope | Why |
|---|---|
| Custom Raft implementation | The backends already provide consensus; writing Raft is a different project with a different lesson. etcd's `ModRevision` is a better token than one we would implement ([ADR-001](docs/adr/ADR-001-etcd-as-the-consensus-store.md)) |
| Multi-region / global locking | Cross-region quorum latency is ruinous and it obscures the single lesson this project exists to teach. Single region, documented as a constraint |
| Shared / exclusive (read-write) modes | Extra state in the state machine, no new insight about fencing |
| Strict FIFO fairness | Barging locks with jittered backoff demonstrate the mechanics; a fairness queue adds a starvation-versus-throughput analysis that is a separate topic |
| Multi-tenancy, namespaces, quotas | An operations concern, not a correctness one, and there is exactly one customer ([charter §0.3](docs/00-charter.md#ch-customer)) |
| Admin UI | An operator CLI plus `psql`/`etcdctl` covers break-glass. A UI is the first thing to cut |
| AuthN/AuthZ on the lock API | One in-cluster caller; adding it would double the surface without touching the lesson. **This alone disqualifies the project from production** |
| Key-space sharding | One shard is arithmetically sufficient for the assumed load; sharding is designed on paper only ([architecture §3.8](docs/03-architecture.md#arch-scale)) |
| Real payment rails, real money, real PII | Fictional domain; the rail is a stub |

**Never cut, at any schedule pressure:** fencing tokens, conservative client-side expiry in the SDK,
the correctness harness, the runbook. Without the first the project has no premise; without the rest
it is a claim rather than a system.

Several non-goals have a documented trigger condition that would make them correct. Proposing one is
legitimate; expect to be pointed at the trigger first.

## Status

[![build](https://github.com/rednavis/distributed-lock-lab/actions/workflows/build.yml/badge.svg)](https://github.com/rednavis/distributed-lock-lab/actions/workflows/build.yml)
[![docs](https://github.com/rednavis/distributed-lock-lab/actions/workflows/docs.yml/badge.svg)](https://github.com/rednavis/distributed-lock-lab/actions/workflows/docs.yml)
[![pages](https://github.com/rednavis/distributed-lock-lab/actions/workflows/pages.yml/badge.svg)](https://github.com/rednavis/distributed-lock-lab/actions/workflows/pages.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Contributions](https://img.shields.io/badge/contributions-welcome-brightgreen.svg)](CONTRIBUTING.md)

📖 **[Read the documentation site →](https://rednavis.github.io/distributed-lock-lab/)** — the full
doc set with search and working cross-references.

| | |
|---|---|
| Milestone complete | **M0 — Foundations**: the Gradle build, nine modules, the version catalog, `lock-api`, the local compose stack, CI and this front matter (T-001…T-008) |
| In progress | **M1 — PostgreSQL lock backend** ([roadmap](ROADMAP.md), [delivery plan](docs/10-delivery-plan.md#dp-milestones)) |
| Not yet written | Every service's behaviour: M1 onward. The fencing experiment of the first section arrives with [`T-042`](tasks/T-042-fencing-demo.md) |
| Measured numbers | None. No latency, throughput or success-rate figure appears anywhere in this repository until [`T-070`](tasks/T-070-benchmark-runner.md) measures one — **[unmeasured]** until then |
| Tasks ready to claim | [`status:ready`](https://github.com/rednavis/distributed-lock-lab/issues?q=is%3Aissue+is%3Aopen+label%3A%22status%3Aready%22) · [`good first issue`](https://github.com/rednavis/distributed-lock-lab/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22) · [the board](tasks/README.md) · [what blocks what](docs/12-parallelization-map.md) |

**This software must never be deployed in front of real money.** There is **no authentication and no
authorisation on the lock API**: any caller that can reach `lock-server` can acquire, renew, release
or force-revoke any lock. That single absence disqualifies it from production on its own, and it is a
deliberate non-goal rather than an oversight. The rest of the security posture is in
[`SECURITY.md`](SECURITY.md) and [architecture §3.9](docs/03-architecture.md#arch-security).

The domain — a mid-size payment service provider, its payout queue, its external rail — is
**fictional.** Every quantity in this repository is an explicitly labelled assumption, not measured
production data.

## Documentation

**The contracts are authoritative.** Five documents pin every identifier this project is allowed to
use — tables, columns, SQL statements, Java signatures, HTTP paths, error codes, headers, metric
names, log event names, configuration keys, module names, cloud resource names. **If a task
specification and a contract disagree, the contract wins** — open a
[contract change issue](.github/ISSUE_TEMPLATE/contract-change.yml) rather than implementing either
version. Reading order for a fresh session is C5 → C1 → C2 → C3 → C4.

| Contract | Pins |
|---|---|
| [C1](docs/contracts/C1-database-schemas.md) | Database schemas and the exact SQL, including the fenced `UPDATE` |
| [C2](docs/contracts/C2-java-api.md) | Java API, the `LockStore` SPI, the SDK, the zero-dependency rule |
| [C3](docs/contracts/C3-http-surfaces.md) | HTTP surfaces, error envelope, timeouts |
| [C4](docs/contracts/C4-observability.md) | Metrics, logs, traces, cardinality rules |
| [C5](docs/contracts/C5-config-build-and-naming.md) | Configuration, build, and every naming convention |

The doc set, indexed with reading paths in [`docs/README.md`](docs/README.md):

| | |
|---|---|
| [00 charter](docs/00-charter.md) | Problem, customer, success criteria, non-goals |
| [01 requirements](docs/01-requirements.md) | Functional and non-functional requirements, invariants |
| [02 domain model](docs/02-domain-model.md) | Payouts, ledger, accounts, the state machine |
| [03 architecture](docs/03-architecture.md) | Components, boundaries, the four flows, failure modes |
| [04 contracts](docs/04-contracts.md) | Index to the five contracts, precedence, change log |
| [05 infrastructure](docs/05-infrastructure.md) | GCP topology and what it costs |
| [06 observability and SLO](docs/06-observability-and-slo.md) | SLIs, SLOs, error-budget policy |
| [07 correctness and testing](docs/07-correctness-and-testing.md) | Invariants, simulation, linearizability |
| [08 operations](docs/08-operations.md) | Runbooks, break-glass, game day |
| [09 risks](docs/09-risks.md) | Risk register |
| [10 delivery plan](docs/10-delivery-plan.md) | Eight milestones and the critical path |
| [11 glossary](docs/11-glossary.md) | Terms, used consistently across the set |
| [12 parallelization map](docs/12-parallelization-map.md) | Which tasks can run at the same time |
| [decision records](docs/adr/) | Fourteen ADRs plus the template, each with context and consequences |

| If you want to | Start at |
|---|---|
| **Contribute code** | [`CONTRIBUTING.md`](CONTRIBUTING.md), then claim a task from the [board](tasks/README.md) |
| **Understand the argument** | This page, then [`docs/00-charter.md`](docs/00-charter.md) |
| **Review the design** | [`docs/03-architecture.md`](docs/03-architecture.md) and the [decision records](docs/adr/) |
| **Look up a name or a schema** | [`docs/04-contracts.md`](docs/04-contracts.md) — the index to every pinned identifier |
| **Read the SRE half** | [`docs/06-observability-and-slo.md`](docs/06-observability-and-slo.md), [`docs/08-operations.md`](docs/08-operations.md) |
| **Contribute as an AI agent** | [`AGENTS.md`](AGENTS.md) — required reading before generating anything |

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
