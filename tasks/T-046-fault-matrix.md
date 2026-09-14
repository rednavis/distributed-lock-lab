# T-046 — Fault-injection matrix runner

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M4 (client SDK + correctness proof) · **Estimate** 30 min — the runner plus the **seven
locally injectable rows**. The five cloud-only rows are declared and skipped here and executed in M6
(T-060…069). If the seven local rows do not fit, stop after five and take the next reserved id
(T-048 — **T-047 is a real task**, the executor's SDK migration) for the remainder, writing its
one-line spec immediately.

**Preconditions** — T-040…T-045 done. You inherit: the compose stack, the T-042 SIGSTOP fixture, the
T-044 history recorder, and T-045's `CriticalSectionTracker` and run gate — the matrix runner drives a
short load profile *underneath* each fault rather than inventing its own workload.

**Goal** — Turn the fault table into an executable matrix where each row names its injection, its
expected behaviour and a blank observed column, and where filling the observed column is the artifact.

## 1. Why this task exists

A safety claim is only as strong as the faults it survived, and a table of ticks almost always means the
faults were too gentle. Encoding the matrix as a runner forces every row to have a real injection
mechanism and a real observation, and leaves the divergences visible instead of rounded off. The
honest gap between *expected* and *observed* is the most persuasive thing this project will produce.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| Session GC / lease expiry behaviour after a client crash | [C1 `#ct1-acquire`](../docs/contracts/C1-database-schemas.md#ct1-acquire), [`#ct1-renew`](../docs/contracts/C1-database-schemas.md#ct1-renew) |
| Token monotonicity across restart, failover and force-revoke | [C1 `#ct1-seq`](../docs/contracts/C1-database-schemas.md#ct1-seq) |
| Fenced write behaviour for a stale token (zero rows) | [C1 `#ct1-fenced`](../docs/contracts/C1-database-schemas.md#ct1-fenced) |
| `NOT_LEADER` / never-fail-open on the degraded side | [C3 `#ct3-errors`](../docs/contracts/C3-http-surfaces.md#ct3-errors), [C2 `#ct2-exceptions`](../docs/contracts/C2-java-api.md#ct2-exceptions) |
| `RAIL_AMBIGUOUS`, attempt-before-forward, no automatic retry | [C3 `#ct3-ambiguity`](../docs/contracts/C3-http-surfaces.md#ct3-ambiguity), [`#ct3-railproxy`](../docs/contracts/C3-http-surfaces.md#ct3-railproxy) |
| Rail-stub injection knobs (latency, failure, duplicate ack, timeout) | [C3 `#ct3-railstub`](../docs/contracts/C3-http-surfaces.md#ct3-railstub), [C5 `#ct5-config`](../docs/contracts/C5-config-build-and-naming.md#ct5-config) |
| `fenced_out` / `rail_ambiguous` log events and required fields used as observations | [C4 `#ct4-logs`](../docs/contracts/C4-observability.md#ct4-logs), [`#ct4-zero`](../docs/contracts/C4-observability.md#ct4-zero) |

**Precedence:** if this spec and a contract disagree, the **contract wins** — stop and report, implement
neither ([04 §4.4](../docs/04-contracts.md#c-precedence)).

## 3. Deliverables

| Path | What |
|---|---|
| `harness/src/main/java/dev/lock/harness/faults/Fault.java` | Row identity: id, name, injection kind, environment (`LOCAL` / `CLOUD`), expectation text, assertions to run |
| `harness/src/main/java/dev/lock/harness/faults/FaultInjector.java` | SPI: `inject()`, `heal()`, `isAvailableHere()` — signatures only |
| `harness/src/main/java/dev/lock/harness/faults/local/` | Seven injectors: `SigkillInjector`, `SigstopInjector`, `SlowNetworkInjector`, `ClockSkewInjector`, `DuplicateRequestInjector`, `RailTimeoutInjector`, `RailDuplicateAckInjector` |
| `harness/src/main/java/dev/lock/harness/faults/FaultMatrixRunner.java` | Ordered driver: baseline → inject → observe → heal → verify recovery |
| `harness/src/main/java/dev/lock/harness/faults/Observation.java` | Per-row result: expectation, observed text, `detectionLatencyMillis`, safety counters, verdict enum |
| `harness/faults/fault-matrix.md` | The full twelve-row table with the **Observed column blank** |
| `harness/faults/README.md` | How to run one row, why cloud rows are skipped locally, the gap-is-the-write-up rule |
| `harness/src/test/java/dev/lock/harness/faults/FaultMatrixRunnerTest.java` | Runner-mechanics tests (§5) |

## 4. Specification

**The twelve rows are fixed** by [07 §7.6](../docs/07-correctness-and-testing.md#test-faults) and
copied verbatim into `harness/faults/fault-matrix.md`, in that order, with the fourth column empty.
Environment classification:

| Row | Injection here | Env |
|---|---|---|
| Client crash | `docker kill -s KILL` the executor container | LOCAL |
| Client pause | `kill -STOP` / `-CONT` (reuse the T-042 fixture) | LOCAL |
| Slow network | `tc netem delay` in the backend-link container (needs `NET_ADMIN`) | LOCAL |
| Clock jump forward / backward | `libfaketime` on one executor container (two rows) | LOCAL |
| Duplicate request | Replay the last acquire/renew with the same idempotency key | LOCAL |
| Rail timeout | Rail-stub timeout injection | LOCAL |
| Rail duplicate ack | Rail-stub double ack for one submission | LOCAL |
| Network partition (lock-server ↔ backend) | iptables DROP — needs privileged compose or a cluster | CLOUD |
| etcd leader kill | Delete the leader pod in `dlock-etcd` | CLOUD |
| Cloud SQL primary failover | `gcloud sql instances failover dlock-pg-lock` | CLOUD |
| Disk full on lockdb | Fill the volume | CLOUD |

`isAvailableHere()` returning false makes the row **SKIPPED, never PASSED**. A skipped row prints the
owning task id (T-060…069) so the reader knows where it went.

**Per-row protocol.** Start the T-045 `lightly-contended` profile at small scale; capture the two
zero-counters and the highest token per key as a baseline; inject; observe for a bounded window; heal;
then assert recovery. Every row asserts, in addition to its own expectation, the three universal
invariants: violations still 0, tokens still strictly increasing per key, at most one submission per
payout reaching a terminal rail outcome (INV-02/INV-04).

**Detection latency.** For every row that has (or will have) an alert, record milliseconds from
injection to first `fenced_out` / `rail_ambiguous` / `NOT_LEADER` observation. That number — not the
tick — is the SRE deliverable, so it is a required field even when the alert itself lands in M6.

**Verdicts.** `MATCHED` · `DIVERGED` (observed differs; requires a one-paragraph note) · `SKIPPED` ·
`INCONCLUSIVE` (injection did not demonstrably take effect — never silently a pass). A run summary
writes `harness/build/faults/<runId>.md` with the same table plus the Observed column filled from
`Observation`, leaving `harness/faults/fault-matrix.md` untouched as the blank template.

**Injectors shell out**, they do not reimplement chaos: `docker kill`, `docker exec … tc`, `kill`.
Every `inject()` must have a `heal()` that is safe to call twice and is invoked in a finally-equivalent
path, or a failed run leaves a poisoned container for the next task.

## 5. Acceptance criteria

1. `./gradlew :harness:test` green; Spotless clean.
2. `harness/faults/fault-matrix.md` contains all twelve rows in §7.6 order with an empty Observed cell.
3. `FaultMatrixRunner --list` prints twelve rows with env and owning task for the five cloud rows.
4. A test proves an unavailable injector yields `SKIPPED`, and that an injection whose effect cannot be
   confirmed yields `INCONCLUSIVE` rather than `MATCHED`.
5. The seven LOCAL rows execute end to end; every one produces an `Observation` with non-null observed
   text, and each records `violations == 0`.
6. The `client pause` row reproduces T-042: exactly one rail submission and at least one `fenced_out`
   log line carrying presented and highest tokens.
7. `heal()` is verified idempotent by a test that calls it twice on a healthy stack.
8. Run summary exists at `harness/build/faults/<runId>.md`; the template file is unmodified afterwards.

## 6. Verification

- `./gradlew :harness:test --tests '*Fault*'` → green.
- `./gradlew :harness:run --args='--faults --env=local --scale=0.02'` → exit 0; stdout table shows 7
  executed, 5 skipped, 0 `INCONCLUSIVE`.
- `./gradlew :harness:run --args='--fault=client-pause'` → summary row `MATCHED`, and
  `docker compose logs rail-stub | grep -c 'submission received'` returns `1` for the payout under test.
- `docker compose ps` after any run → all services `running`, no container left paused or with a
  lingering `netem` qdisc (`docker exec <svc> tc qdisc show` shows no delay).
- `psql "$PAYDB_URL" -c 'select payout_id, fence from payout order by fence desc limit 5'` → fences
  non-decreasing, no regression versus the pre-run baseline printed by the runner.

## 7. Out of scope

Executing the five CLOUD rows, alert rules, detection-latency SLOs and the game day (**M6,
T-060…069**). Terraform and cluster creation (**M5**). The published narrative and comparison tables
(**M7**). Do not fill the Observed column in the committed template — the runner writes a copy.

## 8. Hazards

- **A perfect table means the faults were too gentle** ([07 §7.6](../docs/07-correctness-and-testing.md#test-faults)).
  Resist rewording an expectation to match what you saw; log the divergence instead.
- A fence firing is never nothing ([C4 `#ct4-zero`](../docs/contracts/C4-observability.md#ct4-zero)):
  in the pause row, `lock.fenced.out > 0` is the *expected* outcome; in every other row it is a finding.
- `libfaketime` and `tc netem` need container capabilities that Autopilot will not grant — that is why
  those rows are LOCAL-only and the partition row is not. Do not weaken the compose file's security
  settings for the whole stack to make one row run.
- Clock rows can wedge the session heartbeat permanently; always `heal()` and then assert a fresh
  acquire succeeds before declaring the row finished.
- NEVER run `git` (ADR-011).

## 9. On completion

Mark T-046 done in `tasks/README.md`, and record which of the seven local rows `DIVERGED` plus the
one-paragraph explanation each — those paragraphs are the seed of the M7 write-up.
