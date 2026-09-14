# ADR-000 — Template {#adr0}

**Status:** Accepted, 2026-08-21 (as the house format; this file is never itself a decision)
**Supersedes / superseded by:** —
**Related:** [00-charter §0.5](../00-charter.md#ch-nongoals), [04-contracts §4.5](../04-contracts.md#c-changelog)

## A0.1 When to write an ADR {#adr0-when}

Write one when a choice is **hard to reverse, contested, or surprising to a reader who arrives later**.
Everything else belongs in a contract or a task spec.

| Write an ADR | Do not write an ADR |
|---|---|
| A decision that constrains a module boundary, a storage engine, a cloud shape, or a correctness argument | A name — that is a contract change ([§4.5](../04-contracts.md#c-changelog)) |
| A decision where the rejected option is the one most readers would expect | A default value that will be *measured* and tuned (charter D-04) |
| A decision that buys one property by paying with another, and the payment must stay visible | A step-by-step how-to — that is `08-operations.md` |
| Accepting a known defect on purpose (e.g. [ADR-005](ADR-005-gke-autopilot-eviction-as-budgeted-unavailability.md)) | Restating a requirement — cite the FR/NFR/INV id instead |

**The test:** if in six months someone would reasonably ask *"why on earth is it like this?"*, the answer
must already exist as an ADR. *Failure mode of skipping it:* the next maintainer "fixes" the decision,
the tests still pass, and the property it protected is silently gone.

**Rules.** One decision per file. Numbered `ADR-NNN-kebab-title.md`, never renumbered. Immutable once
Accepted — a change is a **new** ADR that supersedes it, with both links updated. Never restate a
contract; cite it by relative path plus anchor. Every number is a labelled ASSUMPTION unless it is a
measurement, in which case say what measured it.

## A0.2 The template {#adr0-template}

Copy from here down.

---

# ADR-NNN — <decision in one noun phrase> {#adrN}

**Status:** Proposed | Accepted, YYYY-MM-DD | Superseded by ADR-MMM
**Deciders:** <role(s)>
**Requirements touched:** FR-nn, NFR-nn, INV-nn
**Contracts touched:** [Cn §n.n](../contracts/Cn-....md#anchor)

## AN.1 Context {#adrN-context}

What forces are in play, in 5–12 lines. State the constraint that makes this decision non-obvious, and
the one property that must survive. Cite requirement ids rather than re-arguing them. No solutions here.

## AN.2 Decision {#adrN-decision}

One sentence in the active voice, present tense: *"We <verb> <thing>, because <the single reason>."*
Then, if needed, a table of the decision's parts. If this section needs three paragraphs, the ADR is
carrying two decisions — split it.

## AN.3 Consequences {#adrN-consequences}

| | |
|---|---|
| **Positive** | What we now get that we did not have. Name the requirement each item discharges. |
| **Negative** | What is now harder, slower, or more expensive. Real numbers where they exist. |
| **What we accept** | The residual defect we are choosing to live with, plus how it is *bounded* — a budget, an alert, a kill switch, a test. An unbounded accepted defect is not accepted, it is unnoticed. |

## AN.4 Alternatives considered {#adrN-alternatives}

| Alternative | Why it is attractive | Why rejected |
|---|---|---|
| … | … | The **specific** reason, not "not a good fit". Name the failure mode it would produce here. |

At least two alternatives, one of which a reasonable engineer would have picked. "Do nothing" counts.

## AN.5 Revisit when {#adrN-revisit}

| Trigger | Threshold (ASSUMPTION unless measured) | Then |
|---|---|---|
| … | A number or an event, never "if it becomes a problem" | Which ADR is opened, and what evidence it needs |

---

## A0.3 Register {#adr0-register}

Every decision in the repository. **Read the relevant row before proposing a change** — reopening a
decision without engaging its recorded reasoning is the most common way a contribution is sent back.

| ADR | Decision | Status |
|---|---|---|
| [001](ADR-001-etcd-as-the-consensus-store.md) | etcd as the consensus store; the API and SDK carry the correctness | Accepted 2026-08-21 |
| [002](ADR-002-fencing-token-source.md) | Fencing token source: `ModRevision` captured **at grant time** | Accepted 2026-08-21 |
| [003](ADR-003-two-databases-two-instances.md) | Two databases on two instances, REGIONAL lock / ZONAL pay | Accepted 2026-08-21 |
| [004](ADR-004-payout-executor-as-the-protected-operation.md) | The payout executor is the protected operation | Accepted 2026-08-21 |
| [005](ADR-005-gke-autopilot-eviction-as-budgeted-unavailability.md) | Autopilot eviction accepted as budgeted unavailability | Accepted 2026-08-21 |
| [006](ADR-006-conservative-client-side-expiry.md) | Conservative client-side expiry on a monotonic clock — a **liveness** device, never safety | Accepted 2026-08-21 |
| [007](ADR-007-rail-fencing-proxy-for-a-non-cas-resource.md) | A fencing proxy with a **persisted** high-water mark for a resource that cannot compare-and-set | Accepted 2026-08-21 |
| [008](ADR-008-terraform-layout-state-and-provider-versions.md) | Terraform layout, remote state, and forward-pinned provider versions | Accepted 2026-08-21 |
| [009](ADR-009-managed-prometheus-and-slos-in-terraform.md) | Managed Prometheus, and SLOs defined as code in Terraform | Accepted 2026-08-21 |
| [010](ADR-010-monorepo-single-gradle-build.md) | Monorepo with a single Gradle build and one version catalog | Accepted 2026-08-21 |
| [011](ADR-011-no-git-initialisation-yet.md) | ~~Version control and publication deliberately deferred~~ | **Superseded by [012](ADR-012-git-and-public-publication.md)** |
| [012](ADR-012-git-and-public-publication.md) | Git version control and public publication | Accepted 2026-09-14 |
| [013](ADR-013-parallel-contribution-model.md) | Parallel contribution replaces strict sequencing | Accepted 2026-09-14 |
| [014](ADR-014-apache-2-and-dco.md) | Apache-2.0 with DCO sign-off, no CLA | Accepted 2026-09-14 |

### Proposing a new one

1. Copy the template in [A0.2](#adr0-template). Take the next free number; **never renumber**.
2. Open a pull request labelled `type:adr`. It needs **two maintainer approvals**
   ([`GOVERNANCE.md` §2](../../GOVERNANCE.md#2-what-requires-what)).
3. Reversing an existing decision means a **new** ADR that supersedes it — never an edit to the old
   file. Update both files' status lines in the same pull request, and add a row here.
4. A superseding ADR must engage with the original's reasoning. "I prefer X" is not an argument against
   a documented consequence, and these ADRs are specific enough to argue with properly —
   [012](ADR-012-git-and-public-publication.md) is the worked example.
