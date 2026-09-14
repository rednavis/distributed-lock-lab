# ADR-013 — Parallel contribution replaces strict sequencing {#adr13}

**Status:** Accepted, 2026-09-14
**Related:** [ADR-012](ADR-012-git-and-public-publication.md), [10 §10.1](../10-delivery-plan.md#dp-execution), [12 parallelization map](../12-parallelization-map.md)
**Deciders:** lead maintainer
**Requirements touched:** —
**Contracts touched:** [04 §4.4 precedence](../04-contracts.md#c-precedence)

## A13.1 Context {#adr13-context}

The delivery plan was written for a single implementer and made strict sequencing a rule: tasks execute
in ascending id order, each assuming every lower-numbered task is complete, with no fan-out even where
the dependency graph permitted it.

That rule was not arbitrary. **Ordering was serving as the coordination mechanism.** If you always work
the lowest-numbered unfinished task, two pieces of work can never collide, nobody needs to negotiate
who owns which file, and every task can safely assume the state left by every earlier one. It was a
cheap substitute for coordination, and it worked because there was one worker.

With many contributors it stops working, for a reason that has nothing to do with discipline: **an open
contributor base cannot be serialised.** People arrive with different skills, different interests, and
an hour on a Tuesday. Telling the person who wants to write Terraform that they must wait for the
PostgreSQL backend to finish does not produce an ordered project — it produces no Terraform, and one
fewer contributor.

The property that must survive is the one strict ordering was protecting: **nine modules and two lock
backends must compile against one vocabulary**, and no single contributor sees more than a slice of it.

## A13.2 Decision {#adr13-decision}

**We replace strict sequencing with the dependency graph, because ordering was a proxy for coordination
and we now have three better mechanisms that address coordination directly.**

| Mechanism | What it coordinates | Where it lives |
|---|---|---|
| **The contracts** | Every shared name — tables, columns, signatures, paths, metric names, config keys | [`docs/contracts/`](../contracts/), authoritative, amendable only with two approvals |
| **Preconditions** | The real, per-task dependency, made explicit instead of implied by numbering | The Preconditions line of each specification; aggregated in [12](../12-parallelization-map.md) |
| **The ledger** | What is actually done, so nobody duplicates work | [`tasks/README.md`](../../tasks/README.md), updated as part of the definition of done |

Task ids become **identifiers, not a schedule.** `T-023` may legitimately land before `T-011`. Any task
whose preconditions are merged may be claimed and worked concurrently with any other such task.

This is a straight trade: strict ordering was **one** mechanism that was simple and total; this is
**three** mechanisms that are each partial and must each be maintained. The trade is only worth it
because contracts were already going to exist — they were written to solve the same problem for a
single implementer working across a context boundary.

## A13.3 Consequences {#adr13-consequences}

| | |
|---|---|
| **Positive** | Contributors can work in parallel lanes that never collide: documentation and Terraform authoring need no Java at all and are available before the build exists. The etcd backend does not have to wait for the PostgreSQL backend — only the parity suite does. Wall-clock time to [`T-042`](../../tasks/T-042-fencing-demo.md), the central claim, drops substantially with even two contributors |
| **Negative** | Three mechanisms must be maintained where one sufficed. [`12-parallelization-map.md`](../12-parallelization-map.md) is derived data and goes stale silently — a wrong dependency graph costs somebody a wasted afternoon. Shared files (`gradle/libs.versions.toml`, `tasks/README.md`) now produce routine rebase conflicts. A contract amendment mid-flight can invalidate work already in progress, which strict ordering made impossible |
| **What we accept** | **Two contributors can independently introduce the same helper under two names**, which strict ordering structurally prevented. This is bounded by three controls: the contracts pin everything that crosses a module boundary, so divergence is confined to module-internal code where it is cheap; code review is the second net; and the quality gate at each milestone boundary ([10 §10.8](../10-delivery-plan.md#dp-gates)) explicitly checks for contract drift. What we do **not** accept is divergence in a *pinned* name — that is a contract violation, not a merge conflict |

## A13.4 Alternatives considered {#adr13-alternatives}

| Alternative | Why it is attractive | Why rejected |
|---|---|---|
| **Keep strict sequencing** | Proven. Zero coordination overhead. No possibility of two people colliding | Incompatible with an open contributor base. It would mean a queue of one, with everyone else waiting — which in practice means everyone else leaving |
| **Free-for-all: no dependency tracking, resolve conflicts in review** | Simplest to operate. No map to maintain | Wastes contributor effort on tasks that cannot be completed, which is the most reliable way to lose a first-time contributor. The preconditions are real: a task whose prerequisite is unmerged edits files that do not exist |
| **Assign tasks to contributors centrally** | Guarantees no collisions; maintainer sees the whole board | Turns the maintainer into a scheduler and a bottleneck, and it is a poor fit for volunteers who contribute when they have time rather than when they are assigned |
| **Split into multiple repositories**, one per module, each sequential internally | Real isolation; each repo stays simple | Contradicts [ADR-010](ADR-010-monorepo-single-gradle-build.md). It would also make an atomic contract change across modules impossible, which is the one operation that most needs to be atomic |

## A13.5 Revisit when {#adr13-revisit}

| Trigger | Threshold | Then |
|---|---|---|
| Duplicate helpers under different names appear in review | 3 occurrences | The contracts are too thin. Open a contract amendment extending C2 to cover the affected seam |
| The parallelization map is found wrong | 2 occurrences | Derive it mechanically from the specifications' Preconditions lines rather than maintaining it by hand |
| Contract amendments invalidate in-flight work | 2 occurrences | Introduce a freeze protocol: amendments land only at milestone boundaries |
| Sustained contributor count drops to one | 3 months | Strict sequencing becomes correct again. Reverting is cheap — the contracts and preconditions remain useful either way |
