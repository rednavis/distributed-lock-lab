# T-072 — Fill the backend comparison table

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M7 (benchmark, comparison, publication) · **Estimate** 25 min. Fits, *provided* T-070's
plan and T-071's rounds have really been executed and exported. If either is a dry run or a partial,
**stop immediately and report** — there is nothing to fill and a half-filled table is worse than a blank one.

**Preconditions** — T-070 done and **executed**: `harness/bench/raw/` holds aggregate artifacts for every
(backend × profile) cell with complete environment capture. T-071 done and **executed**:
`harness/bench/failover-pg.md` and `failover-etcd.md` hold the four numbers per backend. You inherit a
repo where every input needed by this table already exists on disk as committed raw data.

**Goal** — Generate the [07 §7.9](../docs/07-correctness-and-testing.md#test-comparison) comparison
table mechanically from the committed raw data, with a recommendation stated together with its cost.

## 1. Why this task exists

This table is the single most citable artifact the project produces, and it is exactly the kind of thing
people fill in from memory two weeks later. Generating it from the exported JSON makes G7 real — the
table regenerates from committed raw data — and makes the alternative failure impossible: a cell nobody
can trace back to a run. It is also the moment the recommendation must be defended with its cost, since a
recommendation that lists only benefits reads as advocacy, not engineering.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| The two must-be-zero counters that gate the whole table | [C4 `#ct4-zero`](../docs/contracts/C4-observability.md#ct4-zero) |
| Metric and tag names quoted in any cell or footnote | [C4 `#ct4-metrics`](../docs/contracts/C4-observability.md#ct4-metrics) |
| Error codes named in the throughput-ceiling cell | [C3 `#ct3-errors`](../docs/contracts/C3-http-surfaces.md#ct3-errors) |
| Token-source cells — `fencing_token_seq` vs `ModRevision` at grant time | [C1 `#ct1-seq`](../docs/contracts/C1-database-schemas.md#ct1-seq) |
| Versions/tiers quoted in the environment footer | [C5 `#ct5-catalog`](../docs/contracts/C5-config-build-and-naming.md#ct5-catalog), [`#ct5-naming`](../docs/contracts/C5-config-build-and-naming.md#ct5-naming) |
| Toil items counted in the operational-cost cell | [08 §8.7](../docs/08-operations.md#ops-toil) |

**Precedence:** if this spec and a contract disagree, the **contract wins** — stop and report, implement
neither ([04 §4.4](../docs/04-contracts.md#c-precedence)).

## 3. Deliverables

| Path | What |
|---|---|
| `harness/src/main/java/dev/lock/harness/bench/ComparisonTable.java` | Generator: reads `harness/bench/raw/**` and the two failover files, emits the table; refuses on any gate failure |
| `harness/bench/comparison.md` | **The generated artifact** — the filled table, a provenance column, and the environment footer |
| `harness/bench/raw/MANIFEST.md` | Inventory: one row per raw artifact — cell, backend, profile, run id, repeats, wall-clock start |
| `docs/07-correctness-and-testing.md` §7.9 | Table filled in place, marked generated, pointing at `harness/bench/comparison.md` as the source |
| `harness/src/test/java/dev/lock/harness/bench/ComparisonTableTest.java` | Gate and determinism tests (§5) |

## 4. Specification

**The gate runs first.** Sum the violation counter, `lock.fenced.out` and `rail.duplicate.attempted`
across **every** artifact in `harness/bench/raw/**` plus both failover files. If the total is non-zero the
generator writes **no table**, exits non-zero, and prints the offending run id, key, tokens and
timestamps. [07 §7.9](../docs/07-correctness-and-testing.md#test-comparison) is explicit: a
non-zero violation count invalidates the whole table. **A footnote is not an option** — the deliverable in
that case is a defect report naming the suspected component and the next diagnostic, and this task stops.
A lock that occasionally permits two effective holders has no performance story to publish.

**Cell rules.** Every latency and throughput cell is `median (min–max)` in milliseconds or ops/s, from
T-070's aggregates only. Failover cells come from T-071 as `median (min–max)` unavailability with recovery
in parentheses. Every measured cell carries the run ids it came from in the provenance column. A cell
with no measurement is the literal string **`[not measured]`** — never an estimate, never a prior, never a
tilde. The two token-source cells are already correct in §7.9 and are copied verbatim.

**Rows to fill:** p50/p99 uncontended in-region · p99 hot key · throughput ceiling before error rate
rises (state the code that rose and the threshold used) · failover/election unavailability window · token
source · safety events across the corpus (must be 0 and must be the *computed* total, not a typed 0) ·
operational cost and toil · recommendation.

**The operational-cost cell is counted, not opined.** Count what the project actually performed per backend:
schema migrations applied, compaction/defragmentation runs, member replacements, failover recoveries,
alerts fired, manual interventions. Cite [08 §8.7](../docs/08-operations.md#ops-toil) for the items.
Anything not performed during the project is `[not measured]` — projected steady-state toil is opinion.

**The recommendation carries its cost.** etcd remains the recommended production choice. State the cost
in the same cell: quorum operations, compaction and defragmentation discipline, member replacement order,
a smaller operator population, and no managed regional failover. **If Postgres won a measured
dimension — p99 under contention is plausible at this scale — say so in the table, in that row, without
softening.** Add one line naming the scale at which the recommendation would flip.

**Determinism.** The generator is a pure function of the files it reads: same inputs, byte-identical
output, no timestamps of its own beyond those carried in the artifacts.

## 5. Acceptance criteria

1. `./gradlew :harness:test` green; Spotless clean.
2. A test proves a single non-zero violation counter anywhere in the inputs produces no output file and a
   non-zero exit, with the run id in the message.
3. A test proves a missing raw artifact yields `[not measured]` in that cell and never a copied prior.
4. Running the generator twice produces byte-identical `harness/bench/comparison.md`
   (`diff` of two runs is empty).
5. Every measured cell in `harness/bench/comparison.md` has a non-empty provenance entry naming a run id
   that exists in `harness/bench/raw/MANIFEST.md`.
6. `grep -nE '~|approx|about |roughly' harness/bench/comparison.md` returns nothing.
7. The safety-events row shows a computed total of 0 for both backends.
8. §7.9 of `docs/07-correctness-and-testing.md` contains the same numbers as the generated file and
   a line naming it as the source; no cell in §7.9 is left blank or hand-typed.
9. The recommendation cell contains both the choice and its cost, plus the flip-condition line.

## 6. Verification

- `./gradlew :harness:test --tests '*Comparison*'` → green.
- `./gradlew :harness:run --args='--comparison'` → exit 0; `harness/bench/comparison.md` written.
- `./gradlew :harness:run --args='--comparison --out=/tmp/cmp.md'` then
  `diff /tmp/cmp.md harness/bench/comparison.md` → no output.
- `grep -c '\[not measured\]' harness/bench/comparison.md` → a number the ledger note explains.
- `grep -n 'fencing_token_seq\|ModRevision' harness/bench/comparison.md` → both present.

## 7. Out of scope

Collecting new measurements — if a cell is missing, the fix is a rerun of T-070/T-071, not a new run
invented here. The published article, the traceability walk-through, and the one-month error-budget
report (T-073…075). Tuning either backend. Any change to a contract.

## 8. Hazards

The dominant trap is the one [07 §7.9](../docs/07-correctness-and-testing.md#test-comparison) names:
publishing with a footnote instead of finding the bug. Second is a flattering number — an uncontended
acquire at 30 ms, or a Cloud SQL failover under a second — copied into the table because the run
completed; those are instrumentation failures with the priors already written down. Third is a typed `0`
in the safety row; it must be computed from the artifacts or the row is decoration.

## 9. On completion

Mark the T-072 row done in `tasks/README.md`. List every `[not measured]` cell and why. If the gate
fired, record the ledger row as **blocked** with the defect report path — never as done.
