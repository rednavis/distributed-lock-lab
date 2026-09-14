# Documentation index

The complete doc set for distributed-lock-lab, and the paths through it.

Everything here is **docs-as-code**: reviewed in pull requests, cross-linked with stable anchors, and
checked in CI for broken links. A documentation defect is a real defect in a repository where the
design is written before the code.

---

## Start here, depending on why you came

| I want to… | Read, in order |
|---|---|
| **Understand the argument** | [`../README.md`](../README.md) → [00 charter](00-charter.md) → [03 architecture §3.4](03-architecture.md#arch-flows) (flow C, the paused worker) |
| **Contribute code** | [`../CONTRIBUTING.md`](../CONTRIBUTING.md) → [12 parallelization map](12-parallelization-map.md) → [`../tasks/README.md`](../tasks/README.md) → your task's specification |
| **Contribute as an AI agent** | [`../AGENTS.md`](../AGENTS.md) first, then the path above |
| **Review the design** | [00](00-charter.md) → [01](01-requirements.md) → [02](02-domain-model.md) → [03](03-architecture.md) → [adr/](adr/) |
| **Implement anything** | [C5](contracts/C5-config-build-and-naming.md) → [C1](contracts/C1-database-schemas.md) → [C2](contracts/C2-java-api.md) → [C3](contracts/C3-http-surfaces.md) → [C4](contracts/C4-observability.md) |
| **Look up one name** | [04 contracts](04-contracts.md) — the index to every pinned identifier |
| **Understand the SRE half** | [06 observability](06-observability-and-slo.md) → [08 operations](08-operations.md) |
| **Spend money safely** | [05 infrastructure §cost](05-infrastructure.md#gcp-cost) **before** anything else |
| **Decode a term** | [11 glossary](11-glossary.md) |

## The problem (read these first)

| Doc | What it establishes |
|---|---|
| [**00 — Charter**](00-charter.md) | Scope, the V0/V1/V2 framing, the named first customer, **success criteria SC-01…SC-12**, and the non-goals with their reasons |
| [**01 — Requirements**](01-requirements.md) | Use cases, functional and non-functional requirements, and **the correctness invariants INV-01…INV-08** |
| [**02 — Domain model**](02-domain-model.md) | Entities, the payout and lock state machines, and where each invariant is enforced |

## The solution

| Doc | What it establishes |
|---|---|
| [**03 — Architecture**](03-architecture.md) | Modules and their boundaries, the four flows with the token at every hop, the safety/liveness split, the failure catalogue, and the scale arithmetic |
| [**04 — Contracts**](04-contracts.md) | The index to all pinned anchors, **the precedence rules**, and the amendment changelog |
| [**05 — Infrastructure**](05-infrastructure.md) | Cloud project, Terraform and Kubernetes inventory, Autopilot trade-offs, **and what it costs** |
| [**06 — Observability and SLO**](06-observability-and-slo.md) | SLIs, SLOs, the error-budget policy with named consequences, alerts, dashboards, cardinality rules |
| [**07 — Correctness and testing**](07-correctness-and-testing.md) | The test pyramid, **the `SIGSTOP` experiment**, deterministic simulation, linearizability checking |
| [**08 — Operations**](08-operations.md) | Runbooks, break-glass procedures, the game day, the postmortem template, the toil register |
| [**09 — Risks**](09-risks.md) | Risks with probability, impact, mitigation and owner |

## Working on it

| Doc | What it establishes |
|---|---|
| [**10 — Delivery plan**](10-delivery-plan.md) | The eight milestones with objectively checkable exit criteria, the dependency graph, the critical path, quality gates |
| [**11 — Glossary**](11-glossary.md) | Every term of art, including the ones that look ordinary and are not |
| [**12 — Parallelization map**](12-parallelization-map.md) | **What can be started right now**, the five lanes, fan-out points, collision risks |

## The contracts

**Authoritative.** They pin every name the codebase may use. If a task specification and a contract
disagree, **the contract wins** — open a
[contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) rather than implementing either
version.

| Contract | Pins |
|---|---|
| [**C1 — Database schemas**](contracts/C1-database-schemas.md) | Tables, columns, indexes, sequences, and the **exact SQL** for acquire and the fenced update |
| [**C2 — Java API**](contracts/C2-java-api.md) | Types, exceptions, the `LockStore`/`SessionRegistry` SPI, the SDK, token propagation |
| [**C3 — HTTP surfaces**](contracts/C3-http-surfaces.md) | Paths, headers, the error envelope and its codes, timeouts and retry budgets |
| [**C4 — Observability**](contracts/C4-observability.md) | Metric names and tags, log event names, span attributes, **the cardinality rules**, the scrape contract |
| [**C5 — Config, build, naming**](contracts/C5-config-build-and-naming.md) | Configuration keys, the two kill switches, the version catalog, module inventory, every naming convention |

Reading order for a fresh start is **C5 → C1 → C2 → C3 → C4**: C5 tells you what things are called
before the others start using the names.

## Decision records

Fourteen decisions, one per file, each with context, options, decision and consequences. The register
is [ADR-000 §A0.3](adr/ADR-000-template.md#adr0-register).

The ones most likely to answer a question you are about to ask:

| ADR | Answers |
|---|---|
| [001](adr/ADR-001-etcd-as-the-consensus-store.md) | "Why not implement Raft?" |
| [002](adr/ADR-002-fencing-token-source.md) | "Where does the token actually come from, per backend?" |
| [003](adr/ADR-003-two-databases-two-instances.md) | "Why two Cloud SQL instances? That doubles the cost." |
| [004](adr/ADR-004-payout-executor-as-the-protected-operation.md) | "Why not just lock a bank balance like every other tutorial?" |
| [006](adr/ADR-006-conservative-client-side-expiry.md) | "Why is the client's deadline shorter than the server's?" |
| [007](adr/ADR-007-rail-fencing-proxy-for-a-non-cas-resource.md) | "How do you fence something you cannot modify?" |
| [012](adr/ADR-012-git-and-public-publication.md) | "Why did this repository have no git history until recently?" |
| [013](adr/ADR-013-parallel-contribution-model.md) | "Why can I work `T-023` before `T-011`?" |

## Conventions in this doc set

| Convention | Meaning |
|---|---|
| **ASSUMPTION** | An invented figure, not measured data. Every number is one of these unless it names the command that measured it |
| `FR-nn` / `NFR-nn` | A functional or non-functional requirement, defined in [01](01-requirements.md#br-fr) |
| `INV-nn` | A correctness invariant — [01 §1.7](01-requirements.md#br-invariants). Each has an automated check that fails when it is deliberately broken |
| `SC-nn` | A success criterion — [00 §0.4](00-charter.md#ch-success). True or false at a given commit |
| `A-nn` | A labelled assumption about the fictional workload |
| `T-0nn` | An implementation task in [`../tasks/`](../tasks/) |
| `{#anchor}` | A pinned anchor. Cite these rather than section numbers; numbers move, anchors do not |
| **fence point (a) / (c)** | The two independent fencing enforcement points — the PostgreSQL row and the rail proxy |

**The domain is fictional.** A mid-size payment service provider, deliberately unnamed, as are all
counterparties. Nothing in this doc set describes a real organisation or production system.

## Keeping the docs true

- Every document carries a **Status / Owner / Last reviewed** header. Update it when you substantively
  change the file.
- Cite **anchors**, not section numbers.
- A number without its command, environment and date does not belong here.
- Changing a **contract** is a breaking change: two approvals, an amendment row in
  [04 §4.5](04-contracts.md#c-changelog), and a revisit of every dependent task specification.
- Reversing an **ADR** means a new ADR that supersedes it, never an edit to the accepted one.
- Broken relative links fail CI (`.github/workflows/docs.yml`).

Found something wrong, unclear, or missing? That is a
[documentation issue](../.github/ISSUE_TEMPLATE/docs.yml), and describing exactly where you got stuck
is more useful than proposing wording — the author cannot see their own blind spot.
