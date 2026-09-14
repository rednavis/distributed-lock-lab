# T-073 — The fencing experiment write-up

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M7 (benchmark, comparison, publication) · **Estimate** 30 min *if* T-042's capture directories are still on disk and one in-cluster run has been captured. If either capture is missing, re-running the experiment is a separate session — do the local half here and split the in-cluster section into **T-073b**; say so in the ledger.

**Preconditions**
- **T-042** — `harness/fencing-demo.sh` runs both modes and both single-switch variants, and each run leaves a timestamped capture directory under `harness/fencing-demo/out/` with logs, ledger/balance dumps, rail-stub request log, counter snapshots and trace ids.
- **T-046** — the fault-injection matrix is filled in, so this write-up can cite it instead of re-arguing coverage.
- **T-060…T-064** — telemetry verified in-cluster: structured logs queryable, log-based metrics present, traces carry `lock.token`. The in-cluster (pod-pause) variant of the experiment has been run at least once and its output saved.
- **T-070…T-072** — benchmark and failover numbers exist; this document links them rather than restating them.

**Goal** Turn the raw capture directories into one publishable document that shows, in real captured output, that fencing off corrupts the ledger and fencing on rejects the stale writer at both points — and that a reader can reproduce it in under ten minutes.

## 1. Why this task exists

The experiment already passes; nobody outside the repo can see that. A reviewer reads one document or none, and a claim of correctness with no output pasted under it reads as marketing. This file is the project's single most citable artifact and the evidence behind SC-03/SC-04 — so it must quote **captured** output, never a plausible reconstruction, and must be honest that the lock service behaved perfectly in both runs.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| Experiment steps, both verdicts, and the four "pass for the wrong reason" traps | `docs/07-correctness-and-testing.md#test-fencing` |
| Kill-switch names, defaults, and their demonstration-only purpose | `docs/contracts/C5-config-build-and-naming.md#ct5-killswitches` |
| Fence point (a): `UPDATE … WHERE fence < :token`, zero rows ⇒ 409 | `C1#ct1-fenced` |
| Fence point (c): rail-proxy persisted highest-token-per-account | `C3#ct3-railproxy` |
| Error codes quoted in the write-up and their retry verdicts | `C3#ct3-errors` |
| Log event names and required fields in every quoted excerpt | `C4#ct4-logs` |
| Metric names for the two must-be-zero counters | `C4#ct4-zero`, `C4#ct4-metrics` |
| Token = sequence / `ModRevision`; why a lock is warranted at all | `C1#ct1-seq`, `ADR-002`, `ADR-004`, `ADR-007` |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report, quoting both. Never "tidy" a quoted log line into contract-correct shape: if a captured field name differs from C4, that is a defect to report, not prose to fix.**

## 3. Deliverables

| Path | What |
|---|---|
| `docs/fencing-experiment.md` | new: the write-up, structured exactly as §4 orders it |
| `harness/fencing-demo/reference/off/` | new: the curated Run-1 capture promoted out of `out/` to a stable path (logs, psql dumps, rail-stub log, counters, trace ids) |
| `harness/fencing-demo/reference/on/` | new: the same for Run 2 |
| `harness/fencing-demo/reference/cluster-on/` | new: the in-cluster (pod-pause) capture, or a one-line `NOT-CAPTURED.md` stating why and what it would show |
| `harness/fencing-demo/reference/README.md` | new: provenance — which script invocation, which mode, which commit-free timestamp, which pause mechanism and offset |
| `docs/07-correctness-and-testing.md` | modify **only** §7.3's status line to point at `docs/fencing-experiment.md` and the reference directories |

## 4. Specification

**Sections, in this order.** (1) *The claim*, three sentences, with the outcome rung — Measured. (2) *Why a lock at all* — six lines maximum, the external non-rollbackable side effect, linking ADR-004; explicitly state that a bare balance update would need no lock. (3) *Setup* — a table of the five processes, the lease TTL, the executor work duration, the pause offset, the rail-stub delay, each marked measured or ASSUMPTION. (4) *Timeline* — a table with wall-clock offsets in seconds for A grants, A pauses, lease expires, B grants, B submits and posts, A resumes, A is rejected. No TikZ; a table or fenced ASCII. (5) *Run 1 — fencing off*, ending in the corruption verdict. (6) *Run 2 — fencing on*. (7) *Each fence point alone* — the two single-switch variants, one short paragraph each, making the point that a fence point which never saw the request has not been proven. (8) *What the lock service did in both runs* — nothing wrong; this is the lesson. (9) *Reproduce it* — the exact commands. (10) *In the cluster* — the pod-pause variant and what changed. (11) *What this does not prove* — links `#test-faults`, `#test-linearizability`, and the charter non-goals. (12) *Provenance and honesty*.

**Evidence rule.** Every quoted excerpt is copied byte-for-byte from a file under `harness/fencing-demo/reference/`, is preceded by the relative path it came from, and is trimmed only by deleting whole lines — never by editing a value. Long JSON log lines may be pretty-printed **provided** the reference file holds the original and the write-up says so. Nothing is typed from memory.

**Run 1 must be shown as a failure.** The section carries, in captured form: two rail-stub request entries for one client reference; the `ledger_entry` rows showing two pairs; a balance that is not the sum; and the `fence` column proving the later mutation carried the *stale* token. State the customer-visible consequence in one sentence — a payout paid twice.

**Run 2 must show both rejections with numbers.** The `fenced_out` event with `presented` and `stored` tokens from `payment-resource`, the rail-proxy rejection with `presented` and `highest`, and non-zero readings of the two counters. State that a non-zero `lock.fenced.out` is healthy *inside this harness* and an alert everywhere else.

**Reproduction section.** A copy-pasteable block of at most twelve lines, laptop-only, no GCP account, and an honest wall-clock estimate for a cold start. It must be the same entry point T-074's README advertises — one command, not a tour.

**Sanitization.** Fictional mid-size PSP throughout. Grep the promoted captures for host names, e-mail addresses, real company names and absolute home-directory paths, and rewrite them in the capture files themselves so the write-up can quote verbatim.

## 5. Acceptance criteria

1. `docs/fencing-experiment.md` exists and contains all twelve sections of §4, in that order, each with a stable `{#anchor}`.
2. Every fenced excerpt in the write-up is preceded by a relative path that exists under `harness/fencing-demo/reference/`.
3. For at least eight quoted lines chosen at random, `grep -F` finds the line in the named reference file.
4. Run 1's section states two rail submissions, two ledger pairs, balance ≠ sum, and prints the stale `fence` value.
5. Run 2's section prints presented-vs-stored and presented-vs-highest token pairs and both counter readings.
6. The reproduction block is ≤ 12 lines and names no path that does not exist in the repo.
7. §11 exists and names at least three things the experiment does not establish, each with a link.
8. `grep -riE 'visa|redn|<any real employer>|@[a-z0-9.-]+\.(com|net)|/Users/'` over `docs/fencing-experiment.md` and `harness/fencing-demo/reference/` returns nothing.
9. §7.3's status line in `docs/07-correctness-and-testing.md` links this file; no other line of that doc changed.

## 6. Verification

```
ls -R harness/fencing-demo/reference/ | head -40
grep -c '^## ' docs/fencing-experiment.md
grep -oE 'harness/fencing-demo/reference/[a-zA-Z0-9/._-]+' docs/fencing-experiment.md | sort -u | xargs -I{} test -e {} && echo paths-ok
grep -riE '@[a-z0-9.-]+\.(com|net)|/Users/|/home/' docs/fencing-experiment.md harness/fencing-demo/reference/ ; echo "leaks=$?"
bash -n <(sed -n '/^```$/,$p' docs/fencing-experiment.md) 2>&1 | head -3
harness/fencing-demo.sh --fencing off ; echo "exit=$?"
harness/fencing-demo.sh --fencing on  ; echo "exit=$?"
```

Expected observable result: twelve `##` sections; `paths-ok`; the leak grep finds nothing (exit 1); both script invocations still exit 0, and their fresh verdicts match the numbers quoted in the write-up.

## 7. Out of scope

The README front page and its demo-first ordering — **T-074**. Badges, good-first-issues and the clean-machine run — **T-075**. Benchmark tables and the failover dip — **T-070…T-072**, linked only. Re-running or re-tuning the experiment: if a fresh run disagrees with the captures, stop and report the discrepancy; do not re-tune the pause offset here. Any PDF/booklet build.

## 8. Hazards

- **Fabricated or "cleaned up" output destroys the artifact's whole value.** If a needed line is missing from the captures, mark it `[not captured]` and say what would show it.
- A Run-1 section that reads as a success is the classic misread — the trap table in `#test-fencing` is normative; lead with the duplicate.
- Do not restate C1/C2 theory here; the no-duplicated-theory rule applies to `docs/` too — link the anchor. Quoting a token value is fine; a billing account, service-account key or project number is not. Do not run git (ADR-011).

## 9. On completion

Mark T-073 done in `tasks/README.md`, naming the two reference capture timestamps used and whether `cluster-on/` is real or `NOT-CAPTURED.md`. Note any excerpt that had to be marked `[not captured]`.
