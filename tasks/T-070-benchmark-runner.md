# T-070 — Benchmark protocol runner and environment capture

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M7 (benchmark, comparison, publication) · **Estimate** 30 min of session time for the
runner, the aggregation and the capture. **The measurement itself is not in that budget:** executing the
full plan is ≈2–3 h of unattended wall clock the operator starts afterwards. Do not begin a real run
inside this session; make `--dry-run` and one short smoke run prove the mechanics.

**Preconditions** — T-045 and T-046 done (harness load profiles, `CriticalSectionTracker`, `RunSummary`
JSON, the `--faults` CLI); T-034 done (both backends parity-tested); T-050…T-059 done (the cluster and
both databases are live in `dlock-lab`); T-060…T-069 done (the scrape is *verified* — an unscraped
benchmark is an unmeasured one). You inherit a harness that can drive a profile and count violations but
has no notion of warm-up, repeats, or where it ran.

**Goal** — Turn the §7.8 benchmark protocol into a runner that discards warm-up, repeats each profile
three times, reports median with min–max spread, and refuses to emit a number whose environment or
safety counters are not fully captured.

## 1. Why this task exists

Every unquotable performance claim in this industry fails for one of three reasons: it measured a cold
JVM, it was a single run, or nobody wrote down where it ran. The runner exists to make all three
impossible by construction rather than by discipline. Aggregation lives in code so that "median with
spread" is the only shape a result can take, and so the comparison table T-072 fills is regenerable
from committed raw data rather than retyped from a terminal scrollback.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| Version/tier facts that the capture must read, never guess | [C5 `#ct5-catalog`](../docs/contracts/C5-config-build-and-naming.md#ct5-catalog) |
| Cloud resource and cluster names recorded in the capture | [C5 `#ct5-naming`](../docs/contracts/C5-config-build-and-naming.md#ct5-naming), [`#ct5-env`](../docs/contracts/C5-config-build-and-naming.md#ct5-env) |
| `lock.backend`, TTLs, safety margin recorded per run | [C5 `#ct5-config`](../docs/contracts/C5-config-build-and-naming.md#ct5-config) |
| The two counters whose healthy value is exactly zero | [C4 `#ct4-zero`](../docs/contracts/C4-observability.md#ct4-zero) |
| Metric names read back for CPU/IO/election counts — no new meters | [C4 `#ct4-metrics`](../docs/contracts/C4-observability.md#ct4-metrics), [`#ct4-scrape`](../docs/contracts/C4-observability.md#ct4-scrape) |
| Error-rate breakdown keyed by the real error catalogue | [C3 `#ct3-errors`](../docs/contracts/C3-http-surfaces.md#ct3-errors) |
| Client timeouts / safety margin that bound a latency sample | [C3 `#ct3-timeouts`](../docs/contracts/C3-http-surfaces.md#ct3-timeouts) |

**Precedence:** if this spec and a contract disagree, the **contract wins** — stop and report, implement
neither ([04 §4.4](../docs/04-contracts.md#c-precedence)).

## 3. Deliverables

| Path | What |
|---|---|
| `harness/src/main/java/dev/lock/harness/bench/BenchmarkPlan.java` | Record: backend, profile, `warmupSeconds`, `steadySeconds`, `repeats`, scale, run id |
| `harness/src/main/java/dev/lock/harness/bench/BenchmarkRunner.java` | Drives warm-up → steady → cool → repeat; delegates workload to T-045 |
| `harness/src/main/java/dev/lock/harness/bench/EnvironmentCapture.java` | Collects the §7.8 environment fields; signatures only |
| `harness/src/main/java/dev/lock/harness/bench/RunArtifact.java` | One repeat: plan + environment + latency percentiles + counters + verdict |
| `harness/src/main/java/dev/lock/harness/bench/Aggregate.java` | Median and min–max across repeats; no mean, no single-best |
| `harness/bench/protocol.md` | The committed protocol, the run matrix, and the declared deviation (§4) |
| `harness/bench/raw/README.md` | Why exported artifacts are copied here by hand and never regenerated |
| `harness/src/test/java/dev/lock/harness/bench/BenchmarkRunnerTest.java` | Mechanics tests (§5) |

## 4. Specification

**Run shape.** 2-minute warm-up, **discarded and never merged into any percentile**, then a
**5-minute steady-state window**, then 3 repeats of that pair per (backend × profile) cell.

**Declared deviation — write it in `harness/bench/protocol.md` in full sentences.**
[07 §7.8](../docs/07-correctness-and-testing.md#test-benchmark) asks for ≥10 min steady state; M7
runs **5 min** to keep the destructive-experiment day inside the cost window. The consequence is stated,
not hidden: 5 min may under-sample checkpoint and GC tails, so p99.9 and max carry lower confidence than
p50/p99, and the write-up must say so wherever those two are quoted.

**Run matrix.** `uncontended`, `lightly-contended`, `hot-key`, `long-hold` × {`pg`, `etcd`} × 3 repeats.
`churn` is optional and runs only if the day has slack. `long-hold` keeps its 30 s hold, so its steady
window is extended to the smallest multiple of the hold that exceeds 5 min — record the actual value.

**Aggregation.** Per cell report **median with min–max spread** for p50/p90/p99/p99.9/max acquire
latency, throughput, and error rate by code. A mean must be impossible to obtain from the API: no field,
no accessor, no helper. A cell with fewer than 3 usable repeats is `INSUFFICIENT`, never averaged.

**Poison rule.** A repeat whose violation counter, `lock.fenced.out`, or `rail.duplicate.attempted` is
non-zero is `POISONED`: excluded from aggregation, retained in raw output, and it fails the whole cell.
The runner exits non-zero and prints the counter, the key, and the run id. Rerunning is not the remedy.

**Environment capture is mandatory and fail-fast.** Fields: Java build and JVM flags; every image digest;
Spring Boot, PostgreSQL, etcd versions read from the version catalog; GKE Autopilot node class and
cluster name; region; both Cloud SQL instance tiers and their REGIONAL/ZONAL setting; where the harness
itself runs; concurrent load; the effective `lock.*` config; wall-clock start/end. Any field null or
blank **aborts before the warm-up starts** — capturing it afterwards is capturing a different system.

**Output.** `harness/build/bench/<runId>/repeat-<n>.json` per repeat, `aggregate.json` and
`aggregate.md` per plan. Exported cells are copied by hand into `harness/bench/raw/` so T-072 has
committed inputs. No file under `harness/bench/raw/` is ever edited after export.

**Sanity gate before quoting anything.** Compare against the §7.9 calibration priors: an uncontended
in-region acquire outside single-digit milliseconds means the harness is out of region or a pool is
empty. The runner prints the prior next to the measurement; it does not silently pass.

## 5. Acceptance criteria

1. `./gradlew :harness:test` green; Spotless clean.
2. A test proves warm-up samples are absent from the aggregate: a plan whose warm-up phase records a
   deliberately huge latency yields an aggregate max unaffected by it.
3. A test proves median and min–max are computed over 3 repeats, and that 2 repeats yield `INSUFFICIENT`.
4. `grep -ri 'mean\|average' harness/src/main/java/dev/lock/harness/bench/` returns nothing.
5. A test with a non-zero violation counter yields `POISONED`, a non-zero process exit, and no aggregate.
6. A test with one blank environment field aborts before any workload starts.
7. `harness/bench/protocol.md` states the 5-minute deviation, its consequence, and the run matrix.
8. `--dry-run` prints the full 24-cell matrix with per-cell and total wall-clock estimates.

## 6. Verification

- `./gradlew :harness:test --tests '*Bench*'` → green.
- `./gradlew :harness:run --args='--bench --dry-run'` → exit 0; 24 cells; total estimate printed.
- `./gradlew :harness:run --args='--bench --backend=pg --profile=uncontended --warmup=10 --steady=20 --repeats=3 --scale=0.02'`
  → exit 0; three `repeat-*.json` plus `aggregate.json`.
- `jq '.environment | to_entries | map(select(.value==null)) | length' harness/build/bench/*/repeat-1.json` → `0`.
- `jq '.p99AcquireMillis.median, .p99AcquireMillis.min, .p99AcquireMillis.max' harness/build/bench/*/aggregate.json` → three numbers.

## 7. Out of scope

The failover and leader-kill runs (**T-071**). Filling the comparison table (**T-072**). The published
write-up and any narrative interpretation (T-073…075). New metrics or dashboards (M6, done). Tuning:
this task measures, it does not improve a number.

## 8. Hazards

Running a real 2.5-hour plan inside this session and delivering nothing. Capturing environment after the
run. Reporting a mean because the median looked worse. Quoting a flattering number whose run had a
non-zero counter — [07 §7.9](../docs/07-correctness-and-testing.md#test-comparison) forbids it
outright. Adding a Micrometer meter for latency instead of using T-045's `LatencySink`, which would
breach the cardinality budget ([C4 `#ct4-cardinality`](../docs/contracts/C4-observability.md#ct4-cardinality)).

## 9. On completion

Mark the T-070 row done in `tasks/README.md` (create the row if the ledger lacks it). Record the declared
5-minute deviation in the ledger note as well as in `harness/bench/protocol.md`, and state whether the
real plan has been executed yet — T-072 must not be started against a dry run.
