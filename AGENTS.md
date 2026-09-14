# AGENTS.md — working agreement for AI contributors

Read this file **in full** before generating anything for this repository. It is short on purpose.

This project accepts contributions from AI agents. It accepts them under the same license, the same
review bar, and the same [Code of Conduct](CODE_OF_CONDUCT.md) as any other contribution. This file
states what is *additionally* true when the contributor is a model.

Everything in [`CONTRIBUTING.md`](CONTRIBUTING.md) applies to you. Read it as well; this file does not
repeat it.

---

## 1. The one thing to understand first

The design is finished before the code, and **the contracts are authoritative.**

[`docs/04-contracts.md`](docs/04-contracts.md) and the five documents under
[`docs/contracts/`](docs/contracts/) pin every name this codebase is allowed to use: tables, columns,
SQL statements, Java signatures, HTTP paths, error codes, headers, metric names, log event names, span
attributes, configuration keys, module names, and cloud resource names.

Reading order for a fresh context is **C5 → C1 → C2 → C3 → C4**.

If a contract looks wrong, or is silent, or two contracts disagree, or a task specification contradicts
one: **stop and report.** Do not invent an alternative. Open a
[contract change issue](.github/ISSUE_TEMPLATE/contract-change.yml) stating what you found and what
decision you need.

*Why this matters more for you than for a human:* you are very good at producing a plausible synonym.
`fencing_token` where the contract pins `fence` compiles, passes its own module's tests, reads
correctly to a reviewer who does not have C1 open, and fails at every integration point three
milestones later. Human contributors ask "is that the right name?" out of uncertainty. You will not
feel that uncertainty, so you have to check instead.

## 2. Scope discipline

**Do exactly the task you claimed. Then stop.**

Do not implement the next task because it looks related. Do not refactor an adjacent file because you
noticed something. Do not improve an earlier task's output on your way past — improving it silently
makes the next specification, written against the old shape, wrong.

Each task specification has an explicit **§7 Out of scope** section. It is there because the boundary
is not obvious from inside the task. Honour it.

If you genuinely need to change something outside your task's deliverables, that is a
[deviation](CONTRIBUTING.md#10-recording-a-deviation): do it, and write it down.

## 3. Evidence, not assertion

The [definition of done](CONTRIBUTING.md#7-definition-of-done) has six gates. Gate 4 — *the
observability the task specifies actually emits* — is the one that gets skipped by agents specifically,
because the code plainly calls the meter and that feels like enough.

It is not enough. Scrape the endpoint. Read the JSON. Paste the output into the pull request.

The same applies to every claim you make in a PR description. "Tests pass" is not evidence; the test
output is. If you did not run a command, do not describe its result. If a verification step was
impossible in your environment — no Docker, no cloud credentials — **say that explicitly** rather than
omitting it. A PR that says "I could not verify gate 4, here is why" is reviewable. One that implies
verification that did not happen is not, and it poisons the reviewer's trust in everything else you
wrote.

## 4. Disclosure

Add a `Co-Authored-By` trailer naming the tool to every commit you generate, alongside the required
`Signed-off-by`:

```
Signed-off-by: Your Name <you@example.com>
Co-Authored-By: Claude <noreply@anthropic.com>
```

The human who submits the pull request is the contributor of record. The DCO sign-off is theirs, and
it means they have reviewed the contribution and have the right to submit it under Apache-2.0. **Do not
sign off on behalf of a human.**

This is disclosure, not disqualification. Generated code is welcome here. Undisclosed generated code is
not, because reviewers calibrate differently — and reasonably so — for a patch nobody has read.

## 5. Guardrails

All of [`CONTRIBUTING.md` §8](CONTRIBUTING.md#8-rules-that-are-not-negotiable) binds you. Three of them
fail in a way that is specific to how models work, so they are restated here.

**Never disable a fencing check to make something work.** `payment.fencing.enabled` and
`rail.proxy.fencing.enabled` exist only to demonstrate corruption inside a named experiment. If a
fencing check is rejecting your write, the write is wrong — that is the system working. Turning the
check off makes the build green and removes the entire point of the repository, and it does so
*quietly*. This is the single most damaging thing you could do here, and it will look locally like
progress at the moment you do it.

**Never weaken a test to make it pass.** Widening a tolerance, loosening an assertion, adding
`@Disabled`, or catching the exception the test exists to observe converts a real defect into a green
build. The test is the requirement. A red test is information; a suppressed one is a lie with a
timestamp.

**Never present a number you did not measure.** No benchmark figure, latency, or throughput claim goes
into this repository without the command, the environment and the date that produced it. If you are
tempted to write a plausible-looking p99, write "unmeasured" instead.

## 6. When you are blocked

Stop. Do not guess.

A guess that turns out wrong costs more than a stopped task, because the next three contributions build
on it and nobody knows to check.

1. Bring the branch to a **buildable** state.
2. Write the blocker in the issue: what you found, what you tried, what decision is needed, and from
   whom.
3. If part of the work is genuinely complete, [split the task](CONTRIBUTING.md#9-when-a-task-turns-out-to-be-bigger-than-it-looked)
   and land the finished half.
4. Stop. Do not start something else "while you are here."

Specifically, these are blockers and not puzzles to solve creatively: a contract conflict, a missing
prerequisite from an unmerged task, a credential you do not have, a genuine ambiguity in a
specification.

## 7. Context hygiene

You will not see the whole repository, and the parts you do see will not stay in context. Practical
consequences:

- **Re-read the contract sections your task cites, in this session.** Do not rely on a summary of them,
  including your own from earlier in the same session. The specification's §2 table names exactly which
  anchors to read.
- **Trust the files over your memory of the files.** If a long session has you believing the schema says
  something, open the schema.
- **Prefer the specification's wording over your own paraphrase** when writing code that must match a
  pinned name. Several contracts say "copy this statement character-for-character." They mean it —
  [C1 `#ct1-acquire`](docs/contracts/C1-database-schemas.md#ct1-acquire) is a single SQL statement whose
  correctness depends on a `nextval()` appearing in two places, and a faithful-looking paraphrase that
  drops one of them still passes five of the six tests.

## 8. What a good agent pull request looks like

- One task. The files its specification lists, and no others.
- Every pinned name verified against the contract, not recalled.
- The §6 verification commands actually run, with their output pasted in.
- Any deviation recorded with what, why, blast radius, contract impact.
- The ledger row in [`tasks/README.md`](tasks/README.md) updated.
- Honest about what was not verified and why.
- Sign-off from the human submitting it; `Co-Authored-By` naming you.

A pull request like that gets reviewed on its merits. One that quietly renamed two columns, skipped
gate 4, and asserts that everything works costs a maintainer an hour to discover it cannot be trusted —
and after the second one, generated contributions stop being welcome. The bar exists so that the door
stays open.
