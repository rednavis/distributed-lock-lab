# T-069 — Game day: fire every alert on purpose

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M6 — Observability and SRE · **Estimate** 30 min for the **local** run and the record (scenarios reachable from the compose stack). The GCP-only scenarios are explicitly deferred, not faked — see §4.4. A full cloud game day is a separate ~60-min sitting after M5 is live; do not try to fold it in here.

**Preconditions** — T-068 (`docs/runbook.md` on disk with twelve anchors and twelve `Last executed: never` lines). Also relied on as levers, all already built: T-005 (compose stack: lock-server, payment-resource, rail-proxy, rail-stub, Postgres, etcd), T-023 (rail-stub fault injectors), T-026 (both kill switches), T-042 (the fencing demo — the zombie-holder injector), T-045 (load generator), T-046 (fault matrix), T-060…T-067 (metrics, logs, alert policies).

**Goal** — Deliberately fire every alert, measure detection latency from captured timestamps, and write the exercise up so that no alert in the catalogue is still a hypothesis.

## 1. Why this task exists

`06 §6.10` states the position: an untested alert is a belief about a query nobody has ever seen return true, and `06 §6.7` test (3) requires a runbook section **executed at least once**. Until this task runs, every S1 page in the project is unverified and the twelve `Last executed: never` stamps from T-068 are honest. The output that carries weight in an interview is not "we have alerts" but "we fired all twelve, two were mis-tuned and the runbook for one was wrong."

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| The counters asserted after each injection (`lock_fenced_out_total`, `rail_duplicate_attempted_total`, `lock_lease_expired_total`) | `docs/contracts/C4-observability.md#ct4-metrics`, `#ct4-zero` |
| Log events grepped as the t1 evidence (`fenced_out`, `rail_ambiguous`, `lock_granted`) and their required fields | `docs/contracts/C4-observability.md#ct4-logs` |
| The scrape endpoint and port name deliberately broken in the telemetry-blind scenario | `docs/contracts/C4-observability.md#ct4-scrape` |
| Kill-switch keys used as injectors — and only as injectors | `docs/contracts/C5-config-build-and-naming.md#ct5-killswitches` |
| Resource names in injection commands (`dlock`, `dlock-etcd`, `dlock-pg-lock`) | `docs/contracts/C5-config-build-and-naming.md#ct5-naming` |
| Error codes and retry-safety expected during fail-closed scenarios | `docs/contracts/C3-http-surfaces.md#ct3-errors`, `#ct3-ambiguity` |

**Precedence:** if this spec and a contract disagree, the **contract wins** — stop and report. A game-day record that asserts a counter under the wrong name proves nothing and reads worse than no record.

## 3. Deliverables

| Path | What |
|---|---|
| `ops/game-day/run-scenario.sh` | **New.** One dispatcher taking a scenario id (`gd01`…`gd12`) plus `--dry-run`; prints the injection command, stamps t0, performs the injection, polls for the t1 signal, and appends a machine-readable line to the evidence file. No scenario logic duplicated per-file |
| `docs/game-day/README.md` | **New.** Standing procedure: roles (injector · on-call under test · scribe), the evidence-capture rule, the cadence (monthly and before any milestone is called done), how to add a scenario |
| `docs/game-day/GD-001.md` | **New.** The executed record: the twelve-row results table (§4.3), findings, and the verdict per alert |
| `docs/game-day/evidence/gd*.log` | **New.** Raw captured output per scenario — the only permitted source of any number in `GD-001.md` |
| `docs/game-day/GD-001-postmortem.md` | **New.** One finding written up against the `08 §8.5` blameless template |
| `docs/runbook.md` | **Modify.** `Last executed:` stamped with the date for exercised entries only; add any correction the exercise proved necessary |
| `docs/06-observability-and-slo.md` | **Modify.** `§6.10` cites "game day (T-068)" — repoint to **T-069**; add the results-file link |
| `docs/08-operations.md` | **Modify.** `§8.4` gains a link to `GD-001.md`; `§8.7` toil register gains anything this exercise did by hand twice |

## 4. Specification

### 4.1 Scenario set

Scenarios 1–10 are `08 §8.4` verbatim, in its numbering. Add two, matching the entries T-068 created: **11** etcd `wal_fsync` p99 (inject disk latency on the etcd container's data volume, or throttle its IO) → `#rb-etcd-disk` ticket; **12** `dlock-pg-lock` connection saturation (open connections up past 80 % of `max_connections` with the load generator's pool sized deliberately wrong) → `#rb-pg-connections` ticket.

### 4.2 What "fired" means locally

The compose stack has no Cloud Monitoring, so the chain is verified in two independently recorded legs:

| Leg | Measured how | Recorded as |
|---|---|---|
| **t0 → t1a** signal exists | the log event appears in the container log, or the counter increments in `curl :PORT/actuator/prometheus` | measured locally; a **lower bound** on detection latency |
| **t1a → t1b** alert condition true | the policy's PromQL evaluated by hand against the scraped text, or `promtool` if present | measured locally |
| **t1b → t2** notification arrives | only measurable on GCP | `NOT EXERCISED (local run)` — never estimated |

The record must state this split explicitly in its preamble; a reader must not be able to mistake a local lower bound for an end-to-end page latency.

### 4.3 The results table — one row per scenario

Columns, from `06 §6.10`: **#** · **scenario** · **injection command** · **t0** · **t1a** · **t1b** · **detection latency** · **notification (t2−t1b)** · **runbook anchor** · **runbook executed? correct? sufficient?** · **recovery / auto-resolve** · **evidence file** · **verdict (keep · retune · delete)**. Every timestamp cell is either a value traceable to a line in the cited evidence file, or the literal `NOT EXERCISED` plus a reason. `TBD`, `~`, and blank cells are all defects.

Targets to compare against (`06 §6.10`, ASSUMPTIONS, not SLOs): correctness counters ≤ 90 s · fast burn ≤ 5 min · symptom pages ≤ 5 min · cause tickets ≤ 30 min.

### 4.4 Deferred scenarios

Scenario 6 (`gcloud sql instances failover dlock-pg-lock`) and the Autopilot-eviction variant of scenario 3 require M5 infrastructure. Mark both `NOT EXERCISED — requires live GCP (M5)`, keep their rows, and leave the corresponding `Last executed:` line in `docs/runbook.md` as `never`. Deleting the row would hide the gap; stamping it would be a lie.

### 4.5 Findings and the failure clause

`08 §8.4` is explicit that an exercise which always passes is theatre. The record must contain a **Findings** section with at least one entry. If nothing failed, the finding is that the scenarios were too easy, and the section must name the harder variant for the next run (e.g. inject during a rolling restart, or two faults at once from the T-046 matrix). Scenario 10 — read each alert's `documentation` field cold, as if just woken — is a finding generator: any entry that reads "investigate" is a runbook defect logged here and fixed in `docs/runbook.md`, not an alert defect.

### 4.6 The dispatcher

Behaviour, not code: takes a scenario id; refuses an unknown id with a non-zero exit; `--dry-run` prints the injection and the expected signal without touching anything; on a real run it writes ISO-8601 t0, executes the injection, polls the signal source at a fixed short interval up to a per-scenario deadline, writes t1a/t1b or a timeout marker, then prints the revert command and **does not** revert automatically (the on-call under test practises the runbook's remediation). Every scenario also appends its own reverted-state confirmation line.

## 5. Acceptance criteria

1. `ops/game-day/run-scenario.sh` is executable; `run-scenario.sh gd99` exits non-zero; `--dry-run` for each of `gd01`…`gd12` prints an injection and an expected signal and exits 0.
2. `docs/game-day/GD-001.md` contains exactly twelve result rows, numbered 1–12.
3. Every timestamp cell is either an ISO-8601 value or `NOT EXERCISED` with a reason — `grep -n 'TBD\|???\|^| *|' ` finds nothing in the table.
4. Every row cites an evidence file under `docs/game-day/evidence/` that exists and is non-empty, except rows marked `NOT EXERCISED`.
5. Every row's runbook-anchor cell names an anchor that exists in `docs/runbook.md`.
6. Scenarios 1 and 2 show the counters going from 0 to ≥ 1 in the evidence, and back to a steady state after remediation.
7. Scenario 8 (token rewind) records a detection verdict; if nothing detected it, that is written as the top finding, not softened.
8. `docs/runbook.md` has a dated `Last executed:` line for every exercised entry and `never` for the deferred ones; the counts add up to twelve.
9. `docs/game-day/GD-001-postmortem.md` follows the `08 §8.5` section order and names no individual.
10. `06 §6.10` no longer says the game day is T-068.
11. The Findings section has ≥ 1 entry; if all scenarios passed, it names the harder variant for the next run.

## 6. Verification

```bash
cd /Users/aarashke/Projects/Profile/materials/system-design/distributed-lock-lab
for s in $(seq -f 'gd%02g' 1 12); do ops/game-day/run-scenario.sh --dry-run $s >/dev/null || echo "FAIL $s"; done
ops/game-day/run-scenario.sh gd99; echo "exit=$?"          # expect non-zero
grep -c '^| *[0-9]' docs/game-day/GD-001.md                # expect 12
grep -n 'TBD\|???' docs/game-day/GD-001.md                 # expect NO output
ls -l docs/game-day/evidence/                              # every cited file present, non-empty
grep -o '#rb-[a-z-]*' docs/game-day/GD-001.md | sort -u | while read a; do grep -q "{$a}" docs/runbook.md || echo "missing $a"; done
grep -c 'Last executed:' docs/runbook.md                   # 12; and:
grep -c 'Last executed: never' docs/runbook.md             # equals the NOT EXERCISED entry count
grep -n 'T-068' docs/06-observability-and-slo.md           # expect no game-day reference
```

## 7. Out of scope

Writing or re-tuning alert policies (T-066 owns them; a `retune` verdict here is a recorded finding plus a follow-up row in `tasks/README.md`, not an edit). Authoring new runbook entries (T-068). The cloud game day and Cloud SQL failover (post-M5 rerun of this same procedure). Benchmark numbers and the published write-up (M7, T-070…T-075). Chaos automation running unattended in CI — deliberately not built.

## 8. Hazards

- **Fabricated latency is the failure mode that discredits the whole project.** Any number not traceable to a line in an evidence file must be `NOT EXERCISED`. Rounding a poll interval into a "≈2 s recovery" is fabrication.
- **The kill switches are injectors, never remediation.** Scenario 2 uses `rail.proxy.fencing.enabled=false` to prove the guard is load-bearing, then restores it in the same session and the evidence file records the restore. `08 §8.2.1`'s DO NOT still governs everything outside the exercise.
- Scenario 9 renames the container port away from `http-metrics` — the trap fails **silently** (`C4 #ct4-scrape`). Verify the port is restored by re-scraping, not by reading the manifest.
- Scenario 5 (quorum loss) tempts `etcdctl snapshot restore` without the token advance. A restore that rewinds revisions is scenario 8's bug; if the exercise triggers it accidentally, that is the finding of the day.
- Scenario 3 must show acquires failing **closed**; executors that crash-loop instead of parking work, or that fail open, are a product defect found by the game day — record it and open a follow-up row rather than patching code inside this task.
- Poll intervals set too coarse make every latency look identical to the interval. Poll fast, deadline generously.

## 9. On completion

Mark the T-069 row done in `tasks/README.md` and add a follow-up row for the post-M5 cloud rerun plus one row per `retune`/`delete` verdict and per product defect found. Note deviations: the `T-068 → T-069` cross-reference fix in `06 §6.10`, which scenarios were deferred and why, and any scenario whose injector did not exist and had to be improvised.
