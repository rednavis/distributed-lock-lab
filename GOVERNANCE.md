# Governance

How decisions get made in distributed-lock-lab, who makes them, and how that changes as the project
grows.

---

## 1. Current model: benevolent dictator, stated plainly

This project was designed by one person and is at the beginning of its implementation. Pretending to a
committee structure it does not have would be theatre, and it would slow down the contributors it is
meant to reassure.

So, honestly:

| | |
|---|---|
| **Lead maintainer** | [@AlexOreshkevich](https://github.com/AlexOreshkevich) |
| **Decision model** | Lead maintainer decides; disagreement is resolved in public, in the issue |
| **Maintainers** | Listed in [`MAINTAINERS.md`](MAINTAINERS.md) |
| **Governance review** | When the project reaches three active maintainers, or by request — see [§6](#6-evolving-this-document) |

What this buys you as a contributor: decisions are fast and someone is accountable for them. What it
costs: the lead maintainer's judgement is the tiebreak. [§5](#5-if-you-disagree) says what to do when
you think that judgement is wrong, and [§6](#6-evolving-this-document) says what triggers a move to a
broader model.

## 2. What requires what

Not every change carries the same risk, so not every change carries the same process.

| Change | Process | Approvals |
|---|---|---|
| Typo, broken link, formatting | Pull request | 1 maintainer |
| Task implementation matching its specification | Pull request | 1 maintainer |
| New task specification, or a substantive change to one | Issue, then pull request | 1 maintainer |
| Documentation restructuring | Issue first | 1 maintainer |
| **Change to a contract** (C1–C5) | [Contract change issue](.github/ISSUE_TEMPLATE/contract-change.yml), then an amendment row in [04 §4.5](docs/04-contracts.md#c-changelog) | **2 maintainers** |
| **Change to `lock-api`** | Issue, then pull request | **2 maintainers** |
| **Reversing a decision record** | A new ADR that supersedes the old one | **2 maintainers**, lead maintainer must be one |
| Scope change — anything on the [non-goals](docs/00-charter.md#ch-nongoals) list | Discussion, then an ADR | **Lead maintainer** |
| Adding or removing a maintainer | Issue | **Lead maintainer** |
| Changing this document | Pull request | **Lead maintainer** |

Where two approvals are required, they must come from two different people, and the author does not
count as one of them.

## 3. Review {#review}

**Every pull request needs at least one maintainer approval.** Nothing merges on the author's own
approval, including the lead maintainer's — the value of the second pair of eyes does not depend on
seniority, and a project where one person can merge unreviewed is a project with no real review
culture.

Reviewers commit to responding within **five working days**. If a review has gone quiet past that, a
nudge on the pull request is appropriate and welcome. If it stays quiet for ten working days and the
change is low-risk, any maintainer may merge it with a note explaining why.

Review is about the change, never the person. Reviewers are bound by the
[Code of Conduct](CODE_OF_CONDUCT.md) exactly as contributors are, and "the review was technically
correct" is not a defence to a hostile one.

**Merge strategy:** GitHub Flow — feature branches off `master`, squash merge back, with the pull
request title as the commit subject. `master` is protected: no direct pushes, required status checks,
required review, linear history.

## 4. Becoming a maintainer

There is no application. The path is:

1. **Land three non-trivial contributions.** A task implementation, a substantive documentation
   contribution, or a well-argued contract amendment each count. Typo fixes do not, not because they
   are unwelcome but because they do not demonstrate what the role requires.
2. **Review other people's work.** The job is mostly reviewing. Doing it before you hold the title is
   the clearest possible signal, and reviews from non-maintainers are genuinely useful.
3. **Be nominated** by an existing maintainer, or ask. Asking is not presumptuous. The lead maintainer
   decides, in the open, in an issue.

Maintainers are expected to review within the five-day commitment, uphold the contracts, and say "I
don't know" when they don't.

**Stepping back is normal and carries no stigma.** Say so in an issue, and you move to
[`MAINTAINERS.md`](MAINTAINERS.md)'s emeritus section. A maintainer who has been unreachable for six
months is moved to emeritus by the lead maintainer, with a note; returning is a matter of asking.

## 5. If you disagree

With a code review comment: reply on the pull request. Reviewers are wrong sometimes and expect to be
told so. If it stays deadlocked, ask a second maintainer to weigh in — that is not escalation, it is
normal.

With a decision record: write an ADR proposing to supersede it. This is the designed path, and
[ADR-000](docs/adr/ADR-000-template.md) is the template. A superseding ADR has to engage with the
original's reasoning — "I prefer X" is not an argument against a documented consequence, and the
existing ADRs are specific enough to argue with properly.

With a contract: open a [contract change issue](.github/ISSUE_TEMPLATE/contract-change.yml). Contracts
are meant to be amendable — they are just not meant to be amended *silently*, in a pull request, by one
person, at 2am.

With the lead maintainer: say so publicly in the issue. This project's decisions are written down
specifically so that they can be argued with. If you believe a decision is being made badly and saying
so publicly feels unsafe, that itself is a Code of Conduct matter — see its enforcement section.

## 6. Evolving this document

The single-maintainer model is appropriate for a project at this stage and will stop being appropriate.
The triggers to revisit it, stated now so that nobody has to argue about when the moment arrived:

- **Three or more active maintainers** — move to majority vote among maintainers for anything currently
  marked "lead maintainer", with the lead retaining a tiebreak.
- **The lead maintainer becomes unavailable for 90 days** — remaining maintainers select an interim
  lead by majority. If there are no other maintainers, the repository is archived with a note rather
  than left to rot with open pull requests.
- **A second organisation contributes substantially** — a written charter with named representation,
  because informal governance stops being fair once employment relationships enter the room.

Until one of those happens, this document stands as written.
