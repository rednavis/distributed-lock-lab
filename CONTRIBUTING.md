# Contributing to distributed-lock-lab

Thank you for considering a contribution. This document tells you how to find work, how to claim it,
what "finished" means here, and the handful of rules that are not negotiable.

Read [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) before participating. If you are an AI agent, or you
are driving one, [`AGENTS.md`](AGENTS.md) carries additional obligations and you must read it too.

---

## 1. What makes this project unusual

The design is finished before the code. There are **63 implementation task specifications** in
[`tasks/`](tasks/), each naming its preconditions, its deliverable files, its acceptance criteria, and
the exact commands that verify it. Five [contract documents](docs/contracts/) pin every identifier the
codebase is allowed to use.

This exists because of a specific failure mode. Nine modules and two lock backends compile against one
vocabulary, and no single contributor sees more than a slice of it. A plausible synonym —
`fencing_token` where the contract pins `fence` — compiles fine, passes its own module's tests, and
then fails at every integration point three milestones later, by which time twenty queries are written
against the wrong name. The contracts are how parallel work converges instead of diverging.

The practical consequence for you: **most design questions already have written answers.** Before
proposing an alternative, check [`docs/adr/`](docs/adr/) — fourteen decisions are recorded there with
their context and consequences, and reopening one costs more than it usually returns.

## 2. Ways to contribute

| Contribution | Where it starts |
|---|---|
| Implement a task | The [task board](tasks/README.md); open or claim the matching issue |
| Fix a bug | [Bug report](.github/ISSUE_TEMPLATE/bug.yml) |
| Improve documentation | [Docs issue](.github/ISSUE_TEMPLATE/docs.yml); small fixes may go straight to a PR |
| Challenge a contract | [Contract change](.github/ISSUE_TEMPLATE/contract-change.yml) — never a silent PR |
| Challenge a decision | A new ADR proposing to supersede the old one; see [ADR-000](docs/adr/ADR-000-template.md) |
| Propose new scope | Open a discussion first. Check [non-goals](docs/00-charter.md#ch-nongoals) — the answer may already be "deliberately not" |

**We do not accept:** contributions that add real company, customer, partner, or product names;
contributions containing real payment data, personal data, or credentials; and benchmark numbers
without the command, environment and date that produced them.

## 3. Finding work you can actually start

Tasks are **not** all available at once. Each specification lists preconditions, and a task whose
preconditions are unmet cannot be implemented — the files it edits do not exist yet.

Three ways to find something ready:

1. **The `status: ready` label** on issues. A maintainer sets it when every precondition is merged.
   Start here.
2. **[`docs/12-parallelization-map.md`](docs/12-parallelization-map.md)** — the dependency graph,
   showing which tasks can proceed simultaneously and which form the critical path. Several lanes run
   independently; the documentation and Terraform lanes in particular need no Java at all.
3. **`good first issue`** — tasks scoped to be completable without holding the whole architecture in
   your head. [`T-002`](tasks/T-002-version-catalog.md), [`T-023`](tasks/T-023-rail-stub.md) and
   [`T-068`](tasks/T-068-runbook.md) are deliberately shaped this way.

The board's [ledger](tasks/README.md#3-ledger) is the durable record of what is done; GitHub issues are
the working surface. If the two disagree, the ledger is authoritative and the discrepancy is a bug
worth reporting.

There is also a **[project board](https://github.com/orgs/rednavis/projects/3)** with every task on it.
Group it by **Milestone** for the delivery plan, or filter `label:status:ready` for work with no
unmerged blockers.

## 4. Claiming a task

To avoid two people building the same thing:

1. **Comment `/claim` on the issue** (or say so in plain words). A maintainer assigns it to you.
2. **One task at a time** per contributor, until you have landed one. This is not a trust issue — it
   keeps the board honest about what is actually in flight.
3. **A claim lapses after 14 days without a linked draft PR or a progress comment.** No explanation is
   owed and no fault is implied; life happens. Say so and reclaim it whenever you like.
4. If a task turns out to be bigger than its specification, **say so in the issue** rather than
   silently expanding scope. Splitting is normal — see [§9](#9-when-a-task-turns-out-to-be-bigger-than-it-looked).

Unclaimed tasks are fair game. Claimed ones are not; open a second issue if you want to work adjacent
to someone.

## 5. Setting up

Until milestone M0 lands there is no build to run — creating it *is* M0. From M0 onward:

```console
git clone https://github.com/rednavis/distributed-lock-lab.git
cd distributed-lock-lab
./gradlew build            # all modules, full test suite
./gradlew spotlessApply    # format before committing
docker compose up          # local stack, no cloud account required
```

**Requirements:** JDK 25 (the Gradle toolchain will fetch one if your default differs), Docker for
Testcontainers, and roughly 8 GB of RAM for the full local stack. No Google Cloud account is needed
for anything up to and including the fencing experiment — milestones M0 through M4 are entirely local,
by design ([NFR-15](docs/01-requirements.md#br-nfr)).

Milestones M5 and M6 provision real infrastructure that **accrues real charges.** Do not run them
casually; read [`docs/05-infrastructure.md`](docs/05-infrastructure.md#gcp-cost) first, and use a
dedicated Google Cloud project you are willing to delete.

## 6. The working agreement

### Branches and commits

**GitHub Flow.** `master` is the only long-lived branch. All work happens on a short-lived feature
branch off `master` and is squash-merged back through a reviewed pull request. Nothing is ever pushed
directly to `master` — branch protection enforces this.

Name the branch after its task:

```
<type>/<task-id>-<slug>      feat/T-011-pg-tryacquire
                             docs/T-068-runbook
                             fix/token-regression-on-restore
```

Commits follow [Conventional Commits](https://www.conventionalcommits.org/) and must be **signed off**
under the [Developer Certificate of Origin](https://developercertificate.org/):

```console
git commit -s -m "feat(lock-server): implement PostgresLockStore.tryAcquire

Implements the single atomic INSERT .. ON CONFLICT pinned in C1 #ct1-acquire,
including the nextval() in the DO UPDATE branch so takeover mints a strictly
greater token.

Closes #47"
```

`git commit -s` appends the `Signed-off-by` trailer, which is your statement that you wrote the patch
or otherwise have the right to submit it under Apache-2.0. There is no separate CLA. A DCO check runs
on every PR.

If your contribution was generated with AI assistance, add a `Co-Authored-By` trailer naming the tool.
This is a disclosure requirement, not a disqualification — see [`AGENTS.md`](AGENTS.md).

### Pull requests

One task, one pull request. Open it as a **draft** early if you want feedback in progress; mark it
ready when the definition of done in [§7](#7-definition-of-done) holds.

The [PR template](.github/PULL_REQUEST_TEMPLATE.md) asks you to paste the output of the verification
commands from your task specification's §6. Please actually paste it. "Tests pass" is not evidence;
the terminal output is.

### Review

- Every PR needs **one maintainer approval**; PRs touching a contract, an ADR, or `lock-api` need
  **two** ([`GOVERNANCE.md`](GOVERNANCE.md#review)).
- [`CODEOWNERS`](.github/CODEOWNERS) routes review requests automatically.
- Reviewers are asked to respond within five working days. If yours has gone quiet, a polite nudge on
  the PR is welcome and will not annoy anyone.
- Review comments are about the code, never the author. Reviewers: see the
  [Code of Conduct](CODE_OF_CONDUCT.md), which binds you as much as it binds contributors.

## 7. Definition of done

A task is done when **all six** hold. Five out of six is not done.

| # | Gate | How you know |
|---|---|---|
| 1 | **It builds** | `./gradlew build` succeeds from a clean checkout |
| 2 | **Style passes** | `./gradlew spotlessCheck` is green. Do not reformat files your task does not touch — it buries the real change in noise |
| 3 | **The specified tests pass** | Exactly the tests your specification names, plus everything that was already green. A previously green test that is now red is a **failure, not a flake** |
| 4 | **The observability actually emits** | Scrape it and look. If your task adds a metric, `curl /actuator/prometheus` and confirm the name and tags match [C4 §4.2](docs/contracts/C4-observability.md#ct4-metrics). If it adds a log event, trigger it and read the JSON. "The code calls the meter" is not evidence |
| 5 | **The ledger is updated** | Your PR updates the task's row in [`tasks/README.md`](tasks/README.md) with status, the command that proves it, and anything the next contributor should know |
| 6 | **Deviations are recorded** | Any difference between the specification and what you built is written down — see [§10](#10-recording-a-deviation) |

**Gate 4 is the one that gets skipped, and it is the one that fails silently.** The named-port trap in
[C4 §4.9](docs/contracts/C4-observability.md#ct4-scrape) is the canonical example: everything looks
correct, the code is right, and nothing is ever scraped. Verify by observation, in the order that
contract gives.

## 8. Rules that are not negotiable

Each of these has cost real projects real money.

| Rule | Why |
|---|---|
| **Never disable a fencing check to make something work.** | `payment.fencing.enabled` and `rail.proxy.fencing.enabled` exist **only** to demonstrate corruption inside a named experiment. Flipping either to get past a failure destroys the one thing this project is for, and it destroys it *quietly* — the build goes green and the safety property is gone. If fencing rejects your write, **the write is wrong.** `lock.fenced.out` and `rail.duplicate.attempted` are must-be-zero counters ([C4 §4.4](docs/contracts/C4-observability.md#ct4-zero)) |
| **Never weaken a test to make it pass.** | Loosening an assertion, widening a tolerance, adding `@Disabled`, catching the exception the test exists to observe — all of these convert a real defect into a green build. The test is the requirement. If the test is wrong, that is a contract question: stop and open an issue |
| **Never add a dependency outside the version catalog.** | One catalog is the only place versions appear ([C5 §5.3](docs/contracts/C5-config-build-and-naming.md#ct5-catalog)). A stray coordinate in a `build.gradle.kts` is a supply-chain and reproducibility hole. `lock-api` has **zero** third-party dependencies and that is a contract, not a preference |
| **Never deviate from a contract silently.** | If a contract looks wrong, is silent, or contradicts a task specification, open a [contract change issue](.github/ISSUE_TEMPLATE/contract-change.yml). Do not invent an alternative. Precedence rules are in [04 §4.4](docs/04-contracts.md#c-precedence) |
| **No real data, ever.** | Synthetic fixtures only. No secrets in source, no secrets in Terraform state, no real company names |
| **Never publish an unreproducible number.** | Every benchmark figure carries the command, the environment and the date that produced it. An unreproducible number here is worse than no number |

## 9. When a task turns out to be bigger than it looked

Split it. Do not sprawl.

1. Bring your branch to a **buildable** state. A broken build is the worst possible handoff.
2. Open a follow-up issue for the remainder, written clearly enough that somebody else could pick it up
   without reconstructing your reasoning.
3. Take the **next reserved id in that milestone's gap** for the remainder — `T-009`, `T-018`, `T-019`,
   `T-028`, `T-029`, `T-035`…`T-039`, `T-048`, `T-049` exist precisely for this. Do not append
   `T-017b`, `T-017c`.
4. Update the ledger row to `split`, pointing at the new id.
5. Land what you have.

If you are **blocked** — a contract conflict, a missing prerequisite, a genuine ambiguity — say so in
the issue, state precisely what decision is needed and from whom, and stop. Do not unblock yourself by
guessing. A guess that turns out wrong costs more than a stopped task, because everything downstream
builds on it.

## 10. Recording a deviation

Any difference between what the specification said and what you built is a deviation, however small and
however justified. Record it in the PR description and in the ledger row:

| Field | Content |
|---|---|
| **What** | The specification said X; the repository now has Y |
| **Why** | The specific reason — not "cleaner" or "more idiomatic" |
| **Blast radius** | Which later tasks, contracts or documents are now inconsistent |
| **Contract impact** | None, or: which contract needs an amendment |

An unrecorded deviation is indistinguishable from a bug for everyone who comes after you, and it is
found by the person who trusted the document — which is the most expensive way to find it.

## 11. Decisions that are settled

These are recorded in [`docs/adr/`](docs/adr/) and are not reopened without a superseding ADR that
engages with the original reasoning:

Java 25 · Gradle Kotlin DSL with a version catalog (never Maven) · Spring Boot 4.1 · PostgreSQL 16 ·
etcd 3.6 · Flyway · Micrometer and Actuator · OpenTelemetry · Terraform · Testcontainers · JUnit 5 ·
Spotless with google-java-format · Lombok limited to `@RequiredArgsConstructor` and `@Slf4j` · GitHub
Actions.

Also settled: **two databases on separate instances** (one instance for both would fail the lock and
the resource together and destroy the failover experiment); **both backends first-class**, with
PostgreSQL taught first because its state is inspectable with `SELECT`, and etcd recommended for
production because its tokens are monotonic by construction rather than by procedure; **GKE Autopilot
accepted**, with its extra leader elections treated as a budgeted expense against the error budget.

Proposing a change to any of these is legitimate. Doing it inside an unrelated PR is not.

## 12. Getting help

Stuck, unsure whether something is a bug, or want to sanity-check an approach before writing code —
[`SUPPORT.md`](SUPPORT.md) lists the channels. Asking early is cheaper than a rewrite, and no question
about this codebase is too basic to ask.
