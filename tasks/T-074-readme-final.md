# T-074 — README final pass and design condensation

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M7 (benchmark, comparison, publication) · **Estimate** 30 min. Condensation is the slow part: the temptation is to add, and the job is to cut. If the non-goals table and the demo block are done but the doc map is not, stop there and record it — a half-rewritten README is worse than the T-008 one.

**Preconditions**
- **T-008** — `README.md`, `LICENSE`, `NOTICE`, `SECURITY.md`, `CONTRIBUTING.md` exist; the README is the *scaffold* version written before any code, so most of its claims are now either stale or understated.
- **T-042** — the one-command local demo exists and passes.
- **T-070…T-073** — measured benchmark and failover numbers exist to link, and `docs/fencing-experiment.md` plus the promoted reference captures exist; the README summarises that file and never re-argues it.

**Goal** Rewrite `README.md` so the first screen is the fencing demo a stranger can run, and the second is an honest statement of what the project is not.

## 1. Why this task exists

The scaffold README described intent; the repo now contains results, and a front page that still promises is a front page that undersells. More importantly, an unqualified distributed-lock repo invites exactly the wrong reading — that this is a lock service someone could deploy. The honest non-goals are not modesty, they are the strongest signal of judgment in the whole repo, so they get top-of-page real estate rather than an appendix.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| The non-goal list, verbatim in substance | `docs/00-charter.md#ch-nongoals` (incl. "no AuthN/AuthZ — this alone disqualifies the project from production") |
| What the project is / is not, and the V0-V1-V2 framing | `docs/00-charter.md#ch-what`, `#ch-versions` |
| Success criteria referenced by id (SC-nn), never re-worded | `docs/00-charter.md#ch-success` |
| Module and Gradle project names in the architecture table | `C5#ct5-modules`, `#ct5-layout` |
| Config keys, kill-switch names, cloud names, project id, region | `C5#ct5-config`, `#ct5-killswitches`, `#ct5-naming` |
| Any metric, log event or endpoint named on the front page | `C4#ct4-metrics`, `#ct4-logs`, `#ct4-scrape` |
| Why the lock is warranted; fencing outside the lock service; no Raft here | `ADR-002`, `ADR-004`, `ADR-007`, `ADR-001` |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report, quoting both. Marketing pressure on the front page is exactly where a contract name gets softened; do not soften one.**

## 3. Deliverables

| Path | What |
|---|---|
| `README.md` | modify (effectively rewrite in place), structured exactly as §4 orders it |
| `docs/fencing-experiment.md` | modify: one back-link line under its title noting the README carries the short version |
| `CONTRIBUTING.md` | modify: point the build section at the single entry point the README advertises, so the two cannot drift |

## 4. Specification

**Section order — non-negotiable, because the first screen is the whole game.**

| # | Section | Content and limit |
|---|---|---|
| 1 | Title + one-line subtitle | What it is in one line: a study-grade distributed lock service and the payout executor it protects, for a fictional mid-size payment service provider |
| 2 | **The demo** | The copy-pasteable block from `docs/fencing-experiment.md` §9, ≤ 12 lines, plus the two verdicts as a small table (fencing off ⇒ two rail submissions, double debit; on ⇒ one, rejected at both points). Nothing above it but the subtitle. |
| 3 | Why a lock is warranted here | ≤ 8 lines: the critical section spans a non-idempotent external rail; a bare balance update needs no lock. Link `ADR-004`. |
| 4 | **What this is NOT** | The table in §"Non-goals" below. Placed before the architecture, deliberately. |
| 5 | Architecture at a glance | One table: module → one-line job. One optional ASCII flow ≤ 15 lines. No TikZ, no images. |
| 6 | Measured results | Three or four rows only: p50/p99 acquire per backend, the failover dip, the fencing verdicts — each a number plus a link to the owning doc. Every row carries an outcome rung. |
| 7 | Run it yourself | Local (compose) and, in ≤ 5 lines, the GCP path with a cost warning and a `terraform destroy` reminder. |
| 8 | Doc map | Every file under `docs/` with a one-line purpose; the ADR index as one row. |
| 9 | Status and honesty | The outcome ladder — Analysed · Recommended · Decided · Implemented · Measured — which parts sit on which rung; then licence, security contact and contributing, one line each linking the T-008 files. |

**Non-goals table (section 4).** At least eight rows, each: the thing, one clause of *why*, and a link to the charter or ADR that argues it. Must include, in this shape: **not production-ready** (no authN/authZ on the lock API — single sentence, no hedging); **not a Raft implementation** (etcd and PostgreSQL provide the consensus; `ModRevision` is a better token than one written here); not multi-region; no shared/exclusive modes; no strict FIFO fairness; no multi-tenancy or quotas; no admin UI; no real rail, money or PII. Do not invent new non-goals — the charter list is the source; if the charter is missing one you believe is real, report it rather than adding it.

**Condensation rules.** The README explains nothing that a `docs/` file owns: replace any explanatory paragraph over five lines with a link to the anchor that owns it. No duplicated theory, no restated contract tables, no schema DDL, no Java signatures. Target ≤ 200 lines; if you are over, the fix is deletion, not tightening prose.

**Tone.** First person singular for decisions, plain about limits, no superlatives. Forbidden on the front page: "production-grade", "battle-tested", "enterprise", "highly available" (the pay instance is ZONAL by design — ADR-003), "handles N TPS" without the harness config beside it.


## 5. Acceptance criteria

1. `README.md` is ≤ 200 lines and its first fenced block is the demo, appearing before any architecture prose.
2. The "What this is NOT" section appears earlier in the file than the architecture section (compare `grep -n` line numbers).
3. That section has ≥ 8 rows, and contains the strings "not a Raft implementation" and an explicit statement that there is no authentication or authorization on the lock API.
4. Every file present under `docs/` (top level) is linked at least once from the README.
5. No relative link in `README.md` points at a non-existent path.
6. No fenced block exceeds 15 lines, no Java/SQL/HCL/YAML source is in the file, and `grep -iE 'production-grade|battle-tested|enterprise-grade|highly available'` returns nothing.
7. Section 6 gives at least three numbers, each with a link and an outcome rung word; `README.md` contains no Obsidian `[[wikilinks]]`.
8. `CONTRIBUTING.md`'s build instruction names the same entry point as README section 7 (identical command string).

## 6. Verification

```
wc -l README.md
grep -n '^## ' README.md
grep -n 'NOT\|Raft\|authentication' README.md | head
grep -oE '\]\((\./)?(docs|harness|tasks)/[a-zA-Z0-9/._#-]+\)' README.md | tr -d '])(' | sed 's/#.*//' | sort -u | xargs -I{} test -e {} && echo links-ok
ls docs/*.md | while read f; do grep -q "$(basename $f)" README.md || echo "unlinked: $f"; done
grep -iE 'production-grade|battle-tested|enterprise|highly available|\[\[' README.md ; echo "bad=$?"
```

Then run the demo command copied straight out of README section 2. Expected observable result: ≤ 200 lines; `links-ok`; no `unlinked:` lines; `bad=1`; the pasted demo command runs unmodified and exits 0.

## 7. Out of scope

The fencing write-up's content — **T-073** owns it; the README links and summarises only. Badges, issue templates, the clean-machine cold run and the honesty rule for external posts — **T-075**. Editing `docs/00-charter.md` non-goals: if a row is wrong, report it. Any change to `LICENSE`, `NOTICE` or `SECURITY.md`.

## 8. Hazards

- **Adding is easy, cutting is the task.** A 400-line README with a buried demo fails this task even if every sentence is true. And do not weaken the no-auth statement into "authentication is left as future work" — the charter's wording is deliberate and load-bearing.
- Copy the demo command from `docs/fencing-experiment.md` and run it verbatim; a front-page command that fails is the worst first impression. Pull numbers from the M7 result docs by reference — a stale p99 discredits the measured ones. Do not run git, and do not add badges here (they need a published URL — T-075) — ADR-011.

## 9. On completion

Mark T-074 done in `tasks/README.md` with the final README line count and a note of anything deleted that a later task may want back.
