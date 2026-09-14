# T-075 — Publication checklist

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M7 (benchmark, comparison, publication) · **Estimate** 30 min for the checklist, issue set, badge wiring and the leak scans — **but the clean-machine cold run can take 20–40 min of mostly waiting**. Run the cold run first in the background; if it is still going or fails, record the checklist as done and split the verified cold run into **T-075b**.

**Preconditions**
- **T-005, T-006** — the compose stack and the GitHub Actions workflows exist, so there is something for a badge to point at and a cold run to exercise.
- **T-042** — the one-command demo is the thing the cold run must reproduce.
- **T-073, T-074** — the fencing write-up and the final README exist; this task verifies and gates them rather than writing content.
- **T-070…T-072** — measured numbers exist, so the honesty rule has real claims to govern.

**Goal** Produce the go/no-go checklist that gates making this repository public, and satisfy every item on it except the ones that require a hosting URL.

## 1. Why this task exists

Publication is where a study project acquires reputational risk in both directions: a repo that does not build on a stranger's laptop reads as incompetence, and a repo described more strongly than it was measured reads as dishonesty — the worse of the two in an interview loop. A checklist makes both failure modes mechanical instead of a matter of nerve on the day. It also converts the known gaps into invitations rather than embarrassments.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| Non-goals and the "not production" statement the description must not contradict | `docs/00-charter.md#ch-nongoals`, `#ch-what` |
| Success criteria referenced by id in any external claim | `docs/00-charter.md#ch-success` |
| Which SC/NFR each milestone actually bought (do not claim a forfeited one) | `docs/10-delivery-plan.md` (the forfeit table) |
| Module and Gradle project names in issue titles | `C5#ct5-modules` |
| Config keys / kill-switch names quoted in an issue | `C5#ct5-config`, `#ct5-killswitches` |
| Cloud names, project id, region — the only such names allowed in public docs | `C5#ct5-naming`, `#ct5-env` |
| Metric, log-event and endpoint names quoted anywhere public | `C4#ct4-metrics`, `#ct4-logs`, `#ct4-scrape` |
| What CI runs; not-a-Raft-implementation and no-auth framing | `#test-ci`, `ADR-001`, `#ch-nongoals` |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report, quoting both.**

## 3. Deliverables

| Path | What |
|---|---|
| `docs/12-publication-checklist.md` | new: the gate itself — every item a checkbox with the command that proves it |
| `docs/good-first-issues.md` | new: 8–12 scoped starter issues, each with context, files, acceptance test and an honest size |
| `.github/ISSUE_TEMPLATE/good-first-issue.md` | new: the template those issues are filed from (context · files · acceptance · out of scope) |
| `.github/ISSUE_TEMPLATE/bug_report.md` | new: minimal, and it asks which backend and which kill-switch state |
| `harness/verify-clean-machine.sh` | new, executable: the cold-start verification driver described in §4 |
| `README.md` | modify: badge row only (build, checks, licence, "study project — not production"), placed under the title, above the demo |
| `docs/12-publication-checklist.md` | also carries the honesty rule table (see §4) |

## 4. Specification

**The clean-machine run.** "Clean" means: no `~/.gradle`, no `~/.m2`, no pre-pulled images, no local JDK assumption beyond what the wrapper bootstraps, and no environment variables set by the author's shell profile. `harness/verify-clean-machine.sh` must (a) create a scratch `HOME` and an isolated Gradle user home, (b) prune nothing globally but pull images fresh into that context, (c) run the single advertised entry point from README section 2, (d) print wall-clock cold time, peak disk footprint under the scratch home, and the demo's exit code, and (e) exit non-zero if any step needed a manual fix. Record the numbers in the checklist. Honest tooling prerequisites — Docker, and only that — are listed; if a second prerequisite turns out to be required, it goes in the README, not into the reader's discovery process.

**The checklist file.** Grouped gates, each item one line with a verifying command: *Builds cold* (the script above, plus `./gradlew build` from the scratch home) · *Runs cold* (demo exits 0; a second invocation is idempotent) · *Docs* (every `docs/` file linked; no broken relative link; no `[[wikilinks]]`) · *Contracts* (no identifier in code absent from C1–C5 — reuse T-034/T-046 checks if they exist) · *Leaks* (see below) · *Cost* (`terraform destroy` documented; no long-lived cloud resource left; no billing account id anywhere) · *Honesty* (the table below) · *Hosting* (badges resolve, topics set, description matches the charter) — the hosting group is explicitly marked **blocked until the repo is hosted**, not silently skipped.

**Leak scans.** Grep the whole tree for: real employer/partner/product names, personal e-mail addresses, absolute home paths, GCP billing account and project *numbers*, service-account key files (`*.json` with `private_key`), `.env` files, `kubeconfig` fragments, and any non-synthetic account or card-like number. Each scan is a command in the checklist so it can be re-run before every push.

**The honesty rule.** A table with three columns — *tempting phrase* · *what is actually true* · *rung*. Rows must cover: "distributed lock service" (yes, with no auth, one shard, one region), "production-ready" (no — link the no-auth non-goal), "implements Raft" (no — etcd does), "highly available" (the pay instance is deliberately ZONAL, ADR-003), "linearizable" (only what the checker verified, and only under the tested faults), "handles N ops/s" (only at the harness configuration recorded with the number), "zero duplicate payments" (only that the must-be-zero counters stayed zero under the tested matrix). Rule of use, stated once: **every external claim — README, CV bullet, post, interview answer — carries a rung: Analysed · Recommended · Decided · Implemented · Measured, and a Measured claim names the harness and the run.**

**Good-first-issues.** Real gaps, not busywork. Draw them from the forfeit table and the risk register: an unimplemented lock backend adapter behind the SPI, a missing fault-matrix cell, a metric without a dashboard panel, a runbook step nobody has executed, a documented-but-untested config default, a flaky-test quarantine, a Terraform variable with no validation. Each issue names the exact files, the acceptance test to add, and a size in the honest range 30 min – 3 h. No issue may require a real GCP account without saying so in its first line.

## 5. Acceptance criteria

1. `harness/verify-clean-machine.sh` exists, is executable, and exits 0 on this machine with the scratch home; its output prints cold time, disk footprint and demo exit code.
2. `docs/12-publication-checklist.md` has ≥ 8 gate groups; every item has a runnable command; the hosting group is marked blocked-until-hosted.
3. Every leak scan listed in §4 appears as a copy-pasteable command and returns no match when run.
4. The honesty table has ≥ 7 rows including "production-ready" and "implements Raft", each with a rung.
5. `docs/good-first-issues.md` lists 8–12 issues; each names ≥ 1 existing file path, an acceptance test, and a size; every named path exists.
6. Both issue templates exist under `.github/ISSUE_TEMPLATE/` and the bug template asks for backend and kill-switch state.
7. `README.md` has a badge row directly under the title and above the demo block, including a non-CI "study project — not production" badge; README stays ≤ 205 lines.
8. Badge URLs use one clearly-marked placeholder owner/repo, and the checklist's hosting group contains the item to replace it.
9. Re-running the demo twice in the same clean environment gives the same verdict (no first-run-only setup hidden in the author's machine).

## 6. Verification

```
bash harness/verify-clean-machine.sh 2>&1 | tail -20
grep -c '^- \[ \]' docs/12-publication-checklist.md
grep -rniE 'private_key|BEGIN [A-Z ]*PRIVATE KEY|billingAccounts/|/Users/|/home/[a-z]' --exclude-dir=.git . ; echo "leaks=$?"
grep -oE '`[a-zA-Z0-9/._-]+\.(md|sh|kts|tf|java|yaml)`' docs/good-first-issues.md | tr -d '`' | sort -u | xargs -I{} test -e {} && echo paths-ok
head -12 README.md
ls .github/ISSUE_TEMPLATE/
```

Expected observable result: the clean-machine script exits 0 and prints three numbers; `leaks=1`; `paths-ok`; the README's first twelve lines show title, badge row, then the demo block; both templates listed.

## 7. Out of scope

Creating releases or tags, announcing the project anywhere, and any change to governance or licensing — those are maintainer decisions ([`GOVERNANCE.md`](../GOVERNANCE.md)), not a task's. The repository is already public ([ADR-012](../docs/adr/ADR-012-git-and-public-publication.md)); this task makes it *presentable*, which is a different thing. Also out: any new feature, and re-running the benchmark.

## 8. Hazards

- **Never hand-write a badge that asserts a status.** A literal "passing" image URL that is not wired to the real workflow is a false claim in the most prominent position in the repository. Point badges at the actual GitHub Actions workflow, and if one cannot be verified yet, mark it in the checklist rather than faking it.
- The cold run is the item most likely to expose a hidden dependency on the author's machine — a locally installed JDK, a cached image, a shell alias. Treat every manual fix you make as a README defect and record it.
- A "not production" badge does not license overclaiming elsewhere; the honesty table governs the prose too.
- Do not add a `.gitignore`-driven secret scan and call it a leak scan. Scan the working tree **and the committed history** — a secret removed in a later commit is still public in an earlier one, and rotation is the only real remedy ([`SECURITY.md`](../SECURITY.md)).
- Good-first-issues that are actually 6-hour architecture changes drive contributors away and misrepresent the repo's state; size them honestly or move them to the risk register.

## 9. On completion

Mark T-075 done in `tasks/README.md` with the measured cold time, the disk footprint, the count of unchecked hosting-blocked items, and any prerequisite the cold run discovered that the README had to gain. If the cold run was deferred, open the T-075b row and say so explicitly — M7 is not complete while it is unverified.
