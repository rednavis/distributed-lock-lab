# T-044 — Linearizability history recorder and export

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M4 (client SDK + correctness proof) · **Estimate** 30 min (exporter + fixtures only; a
full multi-hour checked corpus belongs to T-070)

**Preconditions** — T-040…T-043 done. You inherit: a working `lock-client` with the conservative
monotonic deadline, the `harness` module with a scenario runner and the T-042 SIGSTOP fixtures on disk,
and the T-043 deterministic-simulation entry point with reproducible seeds. Both `lock.backend=pg` and
(post T-030…034, already done) `etcd` are selectable.

**Goal** — Record real concurrent lock histories in the Jepsen-style invoke/response form and export
them as newline-delimited JSON that an *existing* checker (Porcupine or Knossos) can verdict.

## 1. Why this task exists

T-042 proves one hand-built schedule is safe; that is an anecdote. A recorded history plus an external
checker turns "I believe mutual exclusion of grants holds" into a falsifiable claim over *every*
schedule the harness happened to produce, and — more valuable — yields a minimal counterexample when it
does not. The checker is deliberately somebody else's code: a home-grown checker that says "pass" is
indistinguishable from a home-grown checker that is broken, and debugging it costs the whole milestone.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| Operation names and return shapes recorded (`acquire`, `renew`, `release`, `info`) | [C2 `#ct2-lockservice`](../docs/contracts/C2-java-api.md#ct2-lockservice), [`#ct2-records`](../docs/contracts/C2-java-api.md#ct2-records) |
| Which failures are *definite* (`fail`) and which are *unknown* (`info`) | [C3 `#ct3-errors`](../docs/contracts/C3-http-surfaces.md#ct3-errors), [`#ct3-ambiguity`](../docs/contracts/C3-http-surfaces.md#ct3-ambiguity) |
| Exception → event-type mapping (`ContentionException` vs `NotLeaderException` vs timeout) | [C2 `#ct2-exceptions`](../docs/contracts/C2-java-api.md#ct2-exceptions) |
| No new metric, log-event or span name may be invented by the recorder | [C4 `#ct4-metrics`](../docs/contracts/C4-observability.md#ct4-metrics), [`#ct4-logs`](../docs/contracts/C4-observability.md#ct4-logs) |
| Module, package (`dev.lock.harness`) and path | [C5 `#ct5-modules`](../docs/contracts/C5-config-build-and-naming.md#ct5-modules), [`#ct5-layout`](../docs/contracts/C5-config-build-and-naming.md#ct5-layout) |

**Precedence:** if this spec and a contract disagree, the **contract wins** — stop, report both texts,
implement neither ([04 §4.4](../docs/04-contracts.md#c-precedence)).

## 3. Deliverables

| Path | What |
|---|---|
| `harness/src/main/java/dev/lock/harness/history/HistoryEvent.java` | Record: `process`, `op`, `key`, `type`, `token`, `code`, `tNs`; JSON field names exactly as in [07 §7.5](../docs/07-correctness-and-testing.md#test-linearizability) |
| `harness/src/main/java/dev/lock/harness/history/HistoryRecorder.java` | Thread-safe append-only recorder; `invoke(...)` / `ok(...)` / `fail(...)` / `info(...)`; bounded, fail-fast |
| `harness/src/main/java/dev/lock/harness/history/HistoryWriter.java` | Flushes to NDJSON, one file per key, header record first |
| `harness/src/main/java/dev/lock/harness/history/RecordingLockService.java` | Decorator over the `LockService`/SDK seam that records around every call |
| `harness/checkers/README.md` | The model, the exact Porcupine and Knossos invocations, how to read a counterexample |
| `harness/checkers/fixtures/known-bad-double-grant.ndjson` | Hand-written 12–20-event history that *must* be rejected |
| `harness/checkers/fixtures/known-good-serial.ndjson` | Hand-written history that must be accepted |
| `harness/src/test/java/dev/lock/harness/history/HistoryRecorderTest.java` | Mapping and ordering tests (see §5) |

## 4. Specification

**Event grammar.** One `invoke` per call, exactly one terminal event per `invoke`, same `process` id.
`process` is a stable small integer per logical client, never a thread id (virtual threads are recycled
and a recycled id makes the history nonsense). `tNs` comes from one monotonic source captured once per
JVM; wall-clock time appears only in the header record.

**Terminal-type mapping.** The only judgement call in the task, and the one that decides whether the
verdict means anything:

| Outcome | Event type | Rationale |
|---|---|---|
| Grant returned | `ok` with `token` | |
| Definite rejection (contention exhausted, not-leader refusal, validation error) | `fail` | Per C3 the operation provably did not take effect |
| Timeout, connection reset, 5xx, any ambiguous code | `info` | Outcome genuinely unknown — see §8 |

**Header record.** First line of every file: run id, seed, `lock.backend`, profile name, TTL and
safety-margin values in force, checker model version. A history without the seed is not reproducible
and therefore not evidence.

**Recorder discipline.** Buffer per process, lock-free, drained after the run so file I/O does not
perturb timing. The buffer is **bounded and throws on overflow** — a silently dropped event can turn a
real violation into a pass. No logging inside the recorder hot path.

**Model for the checker.** Single-holder-with-monotonic-token: state is `(holder, token)`; `acquire`
succeeds only from unheld or expired-and-stolen and must return a token strictly greater than every
token previously returned for that key; `release` by a non-holder is a no-op; `info` events are treated
as possibly-happened. Document this in `harness/checkers/README.md` in prose plus a state table, and
state which checker you ran and its version.

**Harness knobs are CLI arguments** (`--seed`, `--key-count`, `--out`), not Spring config keys. If you
believe you need a new `lock.*` / `payment.*` key, that is a contract change — stop (§2).

## 5. Acceptance criteria

1. `./gradlew :harness:test` passes; Spotless clean.
2. A test asserts every `invoke` has exactly one terminal event and that types are only `ok|fail|info`.
3. A test asserts a simulated timeout maps to `info`, and that no code path can map a timeout to `fail`.
4. Running the recorder over the T-043 simulation with a fixed seed twice produces byte-identical
   NDJSON except for the header's wall-clock field.
5. The external checker returns *valid* for `known-good-serial.ndjson` and *invalid* for
   `known-bad-double-grant.ndjson`, and the counterexample it prints is pasted into
   `harness/checkers/README.md`.
6. At least one history recorded from a real concurrent run (≥8 processes, ≥1 hot key) is checked and
   its verdict recorded in that README with checker name, version and command line.
7. No file under `harness/` references a metric, log-event or span name absent from C4.

## 6. Verification

- `./gradlew :harness:test --tests '*History*'` → green.
- `./gradlew :harness:run --args='--scenario=hotkey --processes=8 --keys=1 --seed=42 --out=build/histories/r1'`
  → `build/histories/r1/payout_p-1.ndjson` exists, first line is the header, `wc -l` ≥ 200.
- `python3 -c "import json,sys;[json.loads(l) for l in open('harness/build/histories/r1/payout_p-1.ndjson')]"`
  → no exception (every line is valid JSON).
- Porcupine: `go run ./harness/checkers/porcupine -history <file>` → `OK` for the real run, `ILLEGAL`
  plus an event index for the known-bad fixture. Knossos equivalent documented as the alternative.

## 7. Out of scope

The DST resource/payout model, the resource-model invariants **INV-02/INV-03**, and schedule shrinking:
those stay inside T-043 as **T-043b** and are *not* inherited here — this task adds no simulation scope.
The always-on checkers INV-01/04/05/06 land in T-043a ([07 §7.4](../docs/07-correctness-and-testing.md#test-dst)).
Load profiles and the shared critical-section violation counter (**T-045**). The fault matrix
(**T-046**). Long-duration corpora, backend-vs-backend comparison and the published counterexample
narrative (**M7, T-070…075**). Do not add metrics or dashboards here — M6 owns telemetry.

## 8. Hazards

- **Recording an `info` as a `fail` is the classic way to get a meaningless verdict** ([07
  §7.5](../docs/07-correctness-and-testing.md#test-linearizability)). Treat "unknown" as the
  default for anything that is not an explicit contract response code.
- Do **not** assert single-holder *belief* anywhere in this task; the model constrains grants and token
  order only ([07 §7.2](../docs/07-correctness-and-testing.md#test-invariant), INV-06).
- A pass proves nothing about schedules you did not explore. Never write "linearizable" without the
  workload, duration and checker version beside it.
- NEVER run `git` — the repo is deliberately un-initialised (ADR-011).

## 9. On completion

Mark the T-044 row done in `tasks/README.md`; record the checker chosen, its version, and any event
type you were unsure how to classify (that ambiguity is itself a finding).
