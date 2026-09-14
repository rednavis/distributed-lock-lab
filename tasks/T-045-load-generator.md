# T-045 — Load generator and the violation detector

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M4 (client SDK + correctness proof) · **Estimate** 30 min for the generator, the five
profile definitions and the detector at *smoke* durations. Full-length runs (10–15 min each) are
executed, not written, in T-070 — do not spend session time waiting.

**Preconditions** — T-040…T-044 done. You inherit: the SDK with heartbeat and conservative deadline, a
locally runnable stack (lock-server, payment-resource, rail-proxy, rail-stub), the harness scenario
runner, and T-044's `HistoryRecorder` / `RecordingLockService` which this task reuses rather than
re-implements.

**Goal** — Drive the lock service through the five pinned load profiles on virtual threads while a
shared critical-section tracker counts violations of *effect* exclusivity, and fail the run unless that
count is exactly zero.

## 1. Why this task exists

Latency numbers are the cheap part; anyone can produce a p99. The reason to build a load generator is
that contention is the only thing that manufactures the interleavings a hand-written test never
reaches, and the only output that matters is a counter that must read zero. Making the detector a
first-class, run-failing gate — not a chart — is what stops the project from tuning throughput while
quietly corrupting money.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| SDK call surface and lease/deadline semantics the generator must not bypass | [C2 `#ct2-sdk`](../docs/contracts/C2-java-api.md#ct2-sdk), [`#ct2-threading`](../docs/contracts/C2-java-api.md#ct2-threading) |
| Token passed explicitly to the resource — **never** a thread-local (fatal under virtual threads) | [C2 `#ct2-propagation`](../docs/contracts/C2-java-api.md#ct2-propagation) |
| `CONTENTION_EXCEEDED` and which errors the generator may retry | [C3 `#ct3-errors`](../docs/contracts/C3-http-surfaces.md#ct3-errors) |
| Per-hop timeouts and retry budgets; jitter expectations | [C3 `#ct3-timeouts`](../docs/contracts/C3-http-surfaces.md#ct3-timeouts) |
| Fenced write as the definition of a successful mutation | [C1 `#ct1-fenced`](../docs/contracts/C1-database-schemas.md#ct1-fenced) |
| The two counters whose healthy value is exactly zero | [C4 `#ct4-zero`](../docs/contracts/C4-observability.md#ct4-zero) |
| Config keys the profiles are allowed to vary (TTLs, safety margin) | [C5 `#ct5-config`](../docs/contracts/C5-config-build-and-naming.md#ct5-config) |

**Precedence:** contract beats spec — stop and report a mismatch ([04 §4.4](../docs/04-contracts.md#c-precedence)).

## 3. Deliverables

| Path | What |
|---|---|
| `harness/src/main/java/dev/lock/harness/load/LoadProfile.java` | Record + the five named constants: clients, keys, hold, duration |
| `harness/src/main/java/dev/lock/harness/load/LoadGenerator.java` | Virtual-thread-per-client driver; open-loop pacing; graceful drain |
| `harness/src/main/java/dev/lock/harness/load/CriticalSectionTracker.java` | The shared detector (§4) |
| `harness/src/main/java/dev/lock/harness/load/RunSummary.java` | Per-run result record, serialised to JSON |
| `harness/src/main/java/dev/lock/harness/load/LatencySink.java` | Percentile accumulation from recorded samples; no new Micrometer meters |
| `harness/src/test/java/dev/lock/harness/load/CriticalSectionTrackerTest.java` | Detector unit tests, including a deliberately stale token |
| `harness/src/test/java/dev/lock/harness/load/LoadGeneratorSmokeTest.java` | 10-second `uncontended` + `hotkey` run against Testcontainers |
| `harness/load/README.md` | The profile table, the scale-factor rule, how to read a summary, what a non-zero count means |

## 4. Specification

**The five profiles** — values are fixed by [07 §7.7](../docs/07-correctness-and-testing.md#test-load)
and must be copied exactly, not re-derived:

| Name | Clients | Keys | Hold | Full duration | Reveals |
|---|---|---|---|---|---|
| `uncontended` | 10 | 10 000 | 50 ms | 10 min | Baseline acquire latency (NFR-03 p50/p99) |
| `lightly-contended` | 50 | 500 | 100 ms | 10 min | Retry/backoff, whether jitter spreads |
| `hot-key` | 200 | 1 | 100 ms | 5 min | Queueing, starvation, `CONTENTION_EXCEEDED` shape |
| `long-hold` | 20 | 20 | 30 s | 15 min | Renewal, heartbeat loss, session-TTL interaction |
| `churn` | 100 | 1 000 | 10 ms | 10 min | Session create/destroy cost, backend write amplification |

A `--scale` CLI factor shortens duration only (never client or key counts, which are the physics of the
profile). CI and this session run `--scale` such that each profile is ≤15 s; `long-hold` keeps its 30 s
hold and therefore has a floor of one hold plus drain.

**Concurrency.** One virtual thread per client via a structured-concurrency scope; the JVM must never be
configured with a fixed platform pool for clients. 200 hot-key clients must not require 200 platform
threads — if it does, blocking JDBC or a pinned synchronized block is the cause and is a finding worth
writing down.

**The detector.** For every key, a shared cell holding `(ownerId, token, mutationSeq)`. On each
successful *mutation* (the fenced `UPDATE` reporting one affected row), the tracker records the token.
A violation is recorded when, for one key, a successful mutation is observed with a token **lower than
or equal to** the highest token already successfully applied, or when two distinct owners both record a
success at the same highest token. It is **not** a violation for two owners to hold handles
simultaneously, nor for a stale owner to *attempt* a write and be refused — assert effect, never belief
([07 §7.2](../docs/07-correctness-and-testing.md#test-invariant), INV-01/INV-05).

**Run gate.** A run exits non-zero if `violations != 0`, or if `lock.fenced.out` or
`rail.duplicate.attempted` scraped from `/actuator/prometheus` moved during the run. Print the three
numbers before any latency figure, in that order.

**Summary output.** `harness/build/load/<profile>-<runId>.json`: profile, scale, backend, wall
duration, acquires attempted/granted/contended/errored, p50/p95/p99/max acquire latency, violations,
the two zero-counters, and the T-044 history path if recording was enabled (`--record`).

## 5. Acceptance criteria

1. `./gradlew :harness:test` green; Spotless clean.
2. All five profile constants exist with exactly the table's clients/keys/hold/duration values.
3. `CriticalSectionTrackerTest` proves: an in-order token sequence yields 0 violations; a replayed
   lower-or-equal token that reports a *successful* mutation yields exactly 1; two concurrent holders
   with only one successful mutation yield 0.
4. The smoke test runs `uncontended` and `hot-key` at reduced scale and asserts `violations == 0`.
5. Killing the generator mid-run leaves no client thread blocked past drain, and the summary JSON is
   still written (partial runs are labelled `truncated: true`).
6. No new metric name appears anywhere in `harness/load/**`; latency comes from `LatencySink`.
7. `harness/load/README.md` states the rule in one line: a non-zero violation count stops all tuning.

## 6. Verification

- `./gradlew :harness:test --tests '*Load*' --tests '*CriticalSection*'` → green.
- `./gradlew :harness:run --args='--load=hot-key --scale=0.02 --record'` → exit 0; stdout begins with
  `violations=0 fenced_out=0 rail_duplicate_attempted=0`.
- `jq '.violations, .p99AcquireMillis' harness/build/load/hot-key-*.json` → `0` and a number.
- `curl -s localhost:8080/actuator/prometheus | grep -E 'lock_fenced_out|rail_duplicate_attempted'` →
  either absent or unchanged from the pre-run value.
- Sanity check that the detector can fail: temporarily set `payment.fencing.enabled=false`
  ([C5 `#ct5-killswitches`](../docs/contracts/C5-config-build-and-naming.md#ct5-killswitches)) with the
  T-042 pause fixture, confirm a non-zero count, then revert. Record the number in the README.

## 7. Out of scope

The fault-injection matrix (**T-046**). Full-length runs, backend-vs-backend comparison tables and the
published benchmark protocol (**T-070…075**). Dashboards, alerts and SLO burn (**M6**). Cloud execution
(**M5**) — everything here runs on `docker compose`.

## 8. Hazards

- **Asserting mutual exclusion of belief will go red on a correct system** and the instinctive fix
  (a sleep, a looser assertion) destroys the only test that mattered ([07 §7.2](../docs/07-correctness-and-testing.md#test-invariant)).
- Thread-locals for the fencing token break silently under virtual threads and are forbidden by
  [C2 `#ct2-propagation`](../docs/contracts/C2-java-api.md#ct2-propagation).
- Closed-loop pacing hides queueing: a slow acquire must not reduce offered load, or the hot-key
  profile measures nothing.
- 10 000 keys × churn will exceed a small connection pool; the failure looks like lock contention and
  is not. Log pool saturation separately.
- NEVER run `git` (ADR-011).

## 9. On completion

Mark T-045 done in `tasks/README.md`; note the scale factor used, the observed p99 per profile at that
scale (clearly labelled as smoke, not benchmark), and any profile the local machine cannot sustain.
