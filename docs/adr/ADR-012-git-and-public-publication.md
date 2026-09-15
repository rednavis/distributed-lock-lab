# ADR-012 — Git version control and public publication {#adr12}

**Status:** Accepted, 2026-09-14
**Supersedes:** [ADR-011 — No git initialisation yet](ADR-011-no-git-initialisation-yet.md)
**Deciders:** lead maintainer
**Requirements touched:** NFR-15 (local-only build path)
**Contracts touched:** [C5 §5.5](../contracts/C5-config-build-and-naming.md#ct5-layout)

## A12.1 Context {#adr12-context}

[ADR-011](ADR-011-no-git-initialisation-yet.md) deliberately kept this repository out of version
control. Its reasoning was sound for the situation it described: a specification tree written by one
person on one machine, where the only history worth having was the task ledger, and where an
accidental `git push` before a sanitisation review could publish something that should not be public.
It listed publication as a manual, gated step — never a side effect.

Three things have now changed.

First, **the sanitisation review has been performed.** The domain is fictional and unnamed, every
quantity is a labelled assumption, and no real organisation, customer, partner or credential appears
anywhere in the tree. The condition ADR-011 set for publication is met.

Second, **the execution model has changed from one implementer to many.** ADR-011's argument that "the
ledger is the history" holds only while exactly one person edits the tree. With concurrent
contributors there is no coherent single history to keep in a Markdown table, no way to attribute a
change, no way to review one before it lands, and no way to revert one afterwards.

Third, **the implementation phase requires a mechanism the ledger cannot provide.** Code review,
continuous integration, atomic multi-file changes, and bisecting a regression are all things git
provides and a progress table does not.

The property that must survive: **nothing enters this repository that should not be public.**

## A12.2 Decision {#adr12-decision}

**We place the repository under git and publish it publicly on GitHub, because the project has moved
from single-author specification to multi-contributor implementation, and every coordination mechanism
that phase requires is built on version control.**

| Part | Decision |
|---|---|
| Version control | git, single repository, monorepo ([ADR-010](ADR-010-monorepo-single-gradle-build.md)) |
| Branching | **GitHub Flow**: `master` is the single long-lived branch and is protected; all work happens on short-lived feature branches off it, merged back by squash. Linear history |
| Visibility | Public from the first commit |
| History | Begins at publication. The specification tree is imported as one initial commit, not reconstructed |
| Licence | Apache-2.0 with DCO sign-off ([ADR-014](ADR-014-apache-2-and-dco.md)) |
| Contribution model | Parallel, issue-and-pull-request ([ADR-013](ADR-013-parallel-contribution-model.md)) |
| Sanitisation | Now a **standing CI check** (`.github/workflows/docs.yml`), not a one-time gate |

The single most important inversion: ADR-011 made publication a **manual action guarded by a review**.
This ADR makes it **the default state guarded by automation**. A one-time review protects a tree that
never changes again; this tree now changes daily, by people the lead maintainer has never met.

## A12.3 Consequences {#adr12-consequences}

| | |
|---|---|
| **Positive** | Code review becomes possible, which is the precondition for accepting outside contributions at all. CI can enforce what prose can only request — the zero-dependency rule for `lock-api`, the metric cardinality check, the DCO, the absence of credential material. Attribution and revert become available. Contributors can see what changed and why, which the ledger never showed |
| **Negative** | Every contributor now needs git fluency, which is a real barrier for a first-time open-source contributor and is why [`CONTRIBUTING.md`](../../CONTRIBUTING.md) spells out the branch and commit conventions rather than assuming them. The ledger in `tasks/README.md` is now **partially redundant** with git history, and the two can disagree — see [A12.5](#adr12-revisit). Public visibility means mistakes are public |
| **What we accept** | **A mistake committed here is permanent**, because a public repository is mirrored, cloned and indexed faster than it can be rewritten. Force-pushing `master` does not unpublish anything. This is bounded by three controls: the hygiene job in `docs.yml` fails the build on credential patterns; `master` is protected so nothing reaches it without review; and [`SECURITY.md`](../../SECURITY.md) instructs finders to report committed secrets **privately**, because a public issue is a pointer to the secret |

## A12.4 Alternatives considered {#adr12-alternatives}

| Alternative | Why it is attractive | Why rejected |
|---|---|---|
| **Keep ADR-011 as written** | Zero risk of publishing something regrettable. No git fluency required of anyone | Makes outside contribution impossible. The project would have to be built by one person, which is the situation ADR-011 assumed and the one we are deliberately leaving |
| **Private repository, invite contributors individually** | Review and CI without public exposure. Reversible | Contradicts the purpose. This repository's product is *evidence* — an argument about fencing that people can read and check. A private teaching artifact teaches nobody. It also creates a gatekeeping step that scales badly and selects for people already known to the maintainer |
| **Publish only the documentation, keep code private** | The argument reaches readers; implementation stays controlled | Splits the contracts from the code they constrain, which is precisely the coupling that makes them work. [SC-12](../00-charter.md#ch-success) requires a reader to trace a token through the doc set **and the code** |
| **Reconstruct a synthetic history** for the specification tree | A prettier log; per-document attribution | Fabricated commit dates are a lie in the one artifact whose value is being checkable. One honest initial commit is better than 94 invented ones |

## A12.5 Revisit when {#adr12-revisit}

| Trigger | Threshold | Then |
|---|---|---|
| The ledger and git history disagree about what is done | Any occurrence | The ledger is authoritative for *status*; git is authoritative for *content*. If they diverge repeatedly, open an ADR on replacing the ledger with GitHub Projects |
| Credential or real data reaches `master` | Any occurrence | Rotate first, then rewrite history, then treat the CI check's miss as the actual defect and strengthen it |
| The repository gains a second contributing organisation | First occurrence | [`GOVERNANCE.md` §6](../../GOVERNANCE.md#6-evolving-this-document) requires a written charter with named representation |
| Review queue exceeds sustainable throughput | >8 open PRs for >2 weeks | Recruit maintainers ([`GOVERNANCE.md` §4](../../GOVERNANCE.md#4-becoming-a-maintainer)); do not solve it by merging with less review |
