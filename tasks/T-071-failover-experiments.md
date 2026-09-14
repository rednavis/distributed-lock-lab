# T-071 — Failover experiments, both backends

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M7 (benchmark, comparison, publication) · **Estimate** 30 min of session time for the
experiment driver and the two result files. **The experiments themselves do not fit alongside it:** a
Cloud SQL failover plus settle is ≈10 min of wall clock each and the etcd rounds need a leader
re-identified between rounds. **Proposed split:** this session writes the driver and executes the etcd
half; the operator executes the Cloud SQL half in a second sitting of **the same task id** and pastes
its output. Do not claim T-073 — the publication tasks own it.

**Preconditions** — T-070 done (benchmark runner, environment capture, poison rule, `harness/bench/`).
T-046 done: its **`etcd leader kill`** and **`Cloud SQL primary failover`** rows are marked SKIPPED with
this milestone as owner, and this task is where their Observed cells get filled. T-052/T-057 done, so
`dlock-pg-lock` is REGIONAL and `dlock-etcd` is a real multi-member StatefulSet. T-065…T-069 done: the
alerts and runbooks in [08 §8.2](../docs/08-operations.md#ops-runbook) exist, so detection latency is
measurable rather than theoretical.

**Goal** — Kill the Cloud SQL primary and, separately, the etcd leader under live load, and produce the
unavailability window, the recovery time, the detection latency, and proof of zero safety events for each.

## 1. Why this task exists

The steady-state benchmark measures the easy half. The whole argument for etcd over a relational lock
table rests on what happens during a leader change, and that difference is roughly two orders of
magnitude — the number nobody has because nobody breaks their own primary. Doing it under load, with the
violation detector running, also tests the claim that matters more than latency: that a failover degrades
availability and **never** safety.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| Token monotonicity across a failover — a regression is the top-severity finding | [C1 `#ct1-seq`](../docs/contracts/C1-database-schemas.md#ct1-seq), [08 §8.2.9](../docs/08-operations.md#rb-token-regression) |
| Stale-token writes still rejected during and after the window | [C1 `#ct1-fenced`](../docs/contracts/C1-database-schemas.md#ct1-fenced) |
| `NOT_LEADER` / unavailable behaviour — degraded means refuse, never grant | [C3 `#ct3-errors`](../docs/contracts/C3-http-surfaces.md#ct3-errors), [C2 `#ct2-exceptions`](../docs/contracts/C2-java-api.md#ct2-exceptions) |
| Lease-vs-timeout arithmetic bounding what a client may conclude | [C3 `#ct3-timeouts`](../docs/contracts/C3-http-surfaces.md#ct3-timeouts) |
| Zero-counters that must stay zero throughout | [C4 `#ct4-zero`](../docs/contracts/C4-observability.md#ct4-zero) |
| Election / quorum / lease-expiry metric names read back | [C4 `#ct4-metrics`](../docs/contracts/C4-observability.md#ct4-metrics) |
| `fenced_out`, `lease_expired`, `not_leader` log events used as observations | [C4 `#ct4-logs`](../docs/contracts/C4-observability.md#ct4-logs) |
| Instance and cluster names used on the command line | [C5 `#ct5-naming`](../docs/contracts/C5-config-build-and-naming.md#ct5-naming) |

**Precedence:** if this spec and a contract disagree, the **contract wins** — stop and report, implement
neither ([04 §4.4](../docs/04-contracts.md#c-precedence)).

## 3. Deliverables

| Path | What |
|---|---|
| `harness/src/main/java/dev/lock/harness/bench/failover/FailoverExperiment.java` | Driver: baseline → inject at minute 5 → sample continuously → detect recovery → verify |
| `harness/src/main/java/dev/lock/harness/bench/failover/FailoverKill.java` | Two named kills (`PG_PRIMARY`, `ETCD_LEADER`) as shell-out steps; signatures only |
| `harness/src/main/java/dev/lock/harness/bench/failover/Window.java` | Record: `firstFailureAt`, `lastFailureAt`, `firstSuccessAfterAt`, `unavailableMillis`, `recoveryMillis`, `detectionMillis`, error codes seen |
| `harness/bench/failover-pg.md` | Cloud SQL result: rounds, windows, counters, the exact command lines, the prior-vs-measured line |
| `harness/bench/failover-etcd.md` | etcd result, same shape, plus election count and which member won |
| `harness/src/test/java/dev/lock/harness/bench/failover/FailoverExperimentTest.java` | Window-arithmetic and guard tests (§5) |

## 4. Specification

**Protocol per round.** Start the `lightly-contended` profile with the violation detector on. Run 5 min
of steady state. Inject the kill. Keep sampling at ≤100 ms so a sub-second etcd window is resolvable at
all — a 1 s sampling interval cannot measure a 400 ms outage and must not be used to claim one. Continue
2 min past the first success. Then verify recovery.

**Rounds.** etcd: **3 rounds**, leader re-identified before each. Cloud SQL: **2 rounds** minimum, 3 if
the cost window allows; each failover must be followed by an explicit settle and a re-check that the
instance is `RUNNABLE` before the next round. Report median with min–max exactly as T-070 does.

**Four numbers per round, defined so they cannot drift.** *Unavailability* = first failed acquire to the
last failed acquire in the same contiguous burst. *Recovery* = kill to first successful acquire.
*Detection* = kill to the first alert-relevant observation (`not_leader`, `lease_expired`, quorum metric
change). *Residual degradation* = time until p99 returns within the min–max spread of the pre-kill
baseline; a fully recovered availability with a doubled p99 is a finding, not a success.

**The measurement trap, stated in both result files.** A sub-second Cloud SQL number means the proxy
reconnect was timed, not the failover: the prior is **tens of seconds**. An etcd number in the tens of
seconds means the client never observed the election or a lease was mis-tuned: the prior is
**sub-second**. Each file prints prior, measured, and a one-line reconciliation. If they disagree by more
than one order of magnitude, the correct output is "instrumentation suspect" — not the number.

**Safety assertions, every round, non-negotiable.** Violation counter 0; `lock.fenced.out` 0 with
fencing on; `rail.duplicate.attempted` 0; tokens strictly increasing per key across the kill; at most one
terminal rail outcome per payout. A token regression, even with a clean window, ends the experiment and
becomes the finding — [08 §8.2.9](../docs/08-operations.md#rb-token-regression) is the response.

**Kills shell out; they do not simulate.** `gcloud sql instances failover dlock-pg-lock` for the first;
identify the etcd leader from endpoint status and delete that pod for the second. Deleting an arbitrary
member measures a follower restart and proves nothing about elections. Both kills record the exact
command and its timestamp into the result file — the timestamp is the origin of all four numbers.

**Cost discipline.** Run these on the same cloud day as T-070 and tear down after
([10 §10.6](../docs/10-delivery-plan.md#dp-cost)); exporting before destroying is what turns an
experiment into evidence.

## 5. Acceptance criteria

1. `./gradlew :harness:test` green; Spotless clean.
2. A test proves the four window fields are derived from one recorded kill timestamp and that a sampling
   interval above 100 ms makes the driver refuse to report a sub-second window.
3. A test proves a token regression in the sampled stream fails the round regardless of the window.
4. A test proves an unavailability window with zero observed failures yields `INCONCLUSIVE`, not 0 ms.
5. `harness/bench/failover-etcd.md` records 3 rounds with median and min–max for all four numbers, the
   election count, the winning member, and all four safety assertions at 0.
6. `harness/bench/failover-pg.md` exists with the same shape; if the Cloud SQL half is deferred to the
   second sitting it says so explicitly at the top, with no placeholder numbers anywhere.
7. Both files carry the prior-vs-measured reconciliation line.
8. The two previously SKIPPED T-046 rows are updated to `MATCHED` or `DIVERGED` with a note.

## 6. Verification

- `./gradlew :harness:test --tests '*Failover*'` → green.
- `gcloud sql instances describe dlock-pg-lock --project dlock-lab --format='value(state,settings.availabilityType)'`
  → `RUNNABLE  REGIONAL` before and after each round.
- `kubectl -n dlock exec dlock-etcd-0 -- etcdctl endpoint status --cluster -w table` → one leader; rerun
  after the kill and confirm the leader moved.
- `./gradlew :harness:run --args='--failover=etcd --rounds=3 --sample-ms=50'` → exit 0; window table printed.
- `jq '[.rounds[].violations] | add' harness/build/bench/failover-*/summary.json` → `0`.

## 7. Out of scope

Steady-state benchmarking (T-070, done). Filling the comparison table (**T-072**). Narrative write-up and
the error-budget report (T-073…075). Alert or runbook changes (M6) — if a runbook is wrong, record the
divergence here and leave the fix to a follow-up.

## 8. Hazards

Timing the connector's reconnect and publishing it as a Cloud SQL failover. Killing a follower and
calling it an election. Sampling too coarsely to resolve the etcd window, then quoting the sample
interval as the result. Leaving the instance mid-failover and running T-072 against a degraded primary.
Destroying the cluster before exporting — [10 §10.6](../docs/10-delivery-plan.md#dp-cost) names this as
the second-worst failure of the cloud day.

## 9. On completion

Mark the T-071 row done in `tasks/README.md` — or **partial**, naming which half remains and what the
second sitting must run. Note any divergence from the priors and any runbook step that did not match.
