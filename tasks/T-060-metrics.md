# T-060 — LockMetrics instrumentation

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M6 — Observability and SRE · **Estimate** 30 min (audit + four small classes; if the
existing call sites turn out to be scattered, stop after the two zero-counters and split the gauges out)

**Preconditions** — T-016, T-021, T-024, T-025, T-041 emitted meters ad hoc as they were written;
**T-047** for `lock.session.lost` specifically — it is the task that gives `payout-executor` a real SDK
session and registers an `onLockLost` listener, so the SDK-owned counter has something to count;
M5 (T-050…T-059) is applied, so all five services run on `dlock-gke`. You inherit a repo where most of
the C4 catalogue exists somewhere but no module has a single owner for its meter names.

**Goal** — Make every metric in C4 §4.2 exist exactly once, with exactly its pinned tag keys, behind one
owning class per service, and prove no meter carries an identifier tag.

## 1. Why this task exists

The catalogue was implemented piecemeal by six earlier tasks, so a name can be misspelled, a tag key can
be missing on one call site, and both failures are silent — a missing tag key makes Micrometer publish a
*second* series and halves every rate, and a wrong name makes a `< threshold` alert look like health
(C4 §4.2). Centralising the names is also what makes the cardinality rule testable: one class per service
is a place a test can enumerate.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| Meter names, types, units, tag keys and their closed value sets, emitter per meter | [C4 §4.2](../docs/contracts/C4-observability.md#ct4-metrics) |
| Never tag with a lock key, account id, payout id, idempotency key, fencing token, or a path containing one | [C4 §4.3](../docs/contracts/C4-observability.md#ct4-cardinality) |
| `lock.fenced.out` and `rail.duplicate.attempted` are invariant counters whose healthy state is no data | [C4 §4.4](../docs/contracts/C4-observability.md#ct4-zero) |
| Exported name strings (`_total`, `_seconds_count`) that later queries use | [C4 §4.2](../docs/contracts/C4-observability.md#ct4-metrics) |
| Tag sets are hard-coded, not configurable | [C5 §5.8](../docs/contracts/C5-config-build-and-naming.md#ct5-fixed) |
| `lock.backend` supplies the `backend` tag value at startup | [C5 §5.1](../docs/contracts/C5-config-build-and-naming.md#ct5-config) |

**Precedence:** if this spec and a contract disagree, the **contract wins** — stop, quote both, report.

## 3. Deliverables

| Path | What |
|---|---|
| `lock-server/src/main/java/dev/lock/server/core/LockMetrics.java` | New. The only place lock-server names a meter: `lock.acquire`, `lock.lease.expired`, `lock.held.current`, `lock.waiters.current` |
| lock-server call sites in `core`, `store/pg`, `store/etcd`, `web` (modify) | Delete inline `registry.counter(...)`/`Timer` lookups; call `LockMetrics` |
| `payment-resource/src/main/java/dev/lock/payments/resource/ResourceMetrics.java` | New. `lock.fenced.out` with `resource`=`account`\|`ledger` only |
| `rail-proxy/src/main/java/dev/lock/rail/proxy/RailProxyMetrics.java` (modify) | `rail.submission`, `rail.duplicate.attempted`, `lock.fenced.out{resource=rail}` — reconcile names/tags with C4 |
| `payout-executor/src/main/java/dev/lock/payments/executor/ExecutorMetrics.java` (modify) | Add the `payout.backlog.age.seconds` gauge; keep `payout.execute` |
| `lock-client/src/main/java/dev/lock/client/SessionLossMetrics.java` | New. Registers `lock.session.lost{backend}` **in `lock-client`** — C4 §4.2 pins the emitter as lock-client, and the `service` label plus S3's denominator (sessions opened, client-side) are only coherent if the counter lives where the sessions do. It is fed by the session-loss callback defined in **T-040** and registered by the executor in **T-047**. **If T-047 has not landed, no deployed service registers `onLockLost`, so the counter has no source**: register the meter as a zero-valued counter in the SDK, add a TODO naming T-047, and say so in §9 rather than moving it into `payout-executor` or inventing a local expiry timer to fire it |
| `lock-server/src/test/java/.../MetricsCatalogTest.java` | Asserts the catalogue and the cardinality rule (§4) |
| `docs/06-observability-and-slo.md` (modify) | One row per meter: Micrometer name → exported name, as implemented |

## 4. Specification

**One owner per service.** Each `*Metrics` class takes a `MeterRegistry` by constructor
(`@RequiredArgsConstructor`), resolves the `backend` tag once from `lock.backend`, and exposes intention
named methods (`recordAcquire(outcome, duration)`, `fencedOut(resource)`, `duplicateAttempted()`). No
other class in the module may reference `MeterRegistry`; that is what the test enforces.

**Registration timing.** Warm up (pre-register at construction) every meter *except* the two invariant
counters, so a freshly started pod already publishes each series and a dashboard is never empty for a
reason that looks like an outage. Do **not** warm up `lock.fenced.out` or `rail.duplicate.attempted`:
C4 §4.4 defines their healthy state as *no data points*, and the alert is `> 0` with no window.

**Gauges.** `lock.held.current`, `lock.waiters.current` and `payout.backlog.age.seconds` must be
registered against a state object the owning class holds a **strong** reference to. Micrometer gauges
keep a weak reference; a lambda over a local or a collected object silently reports NaN and the series
disappears. Backlog age is derived from the oldest `pending` payout's creation timestamp, sampled by the
existing executor poll, not computed inside the gauge callback.

**Tag discipline.** `backend` is the configured backend of the process, never per request. Every tag key
listed for a meter is present on every data point — a partially tagged emission is a different series.
`outcome` and `resource` values come from `enum`s or `switch` over the closed sets, never a free string;
an unmapped value maps to `error`, never to itself.

**The test.** `MetricsCatalogTest` drives one grant, one contended acquire, one release and one expiry
against a `SimpleMeterRegistry`, then asserts: (1) the exact set of meter ids present equals the expected
set; (2) for each, the tag key set equals C4's; (3) every tag *value* seen is in the closed set; (4) no
tag key matches `(?i).*(key|id|token|account|payout|path).*`; (5) no tag value is longer than 32
characters or contains a digit run of 6+ (an id smuggled in as a string). Mirror (4) and (5) as an
assertion in the payment-resource and rail-proxy test slices too.

## 5. Acceptance criteria

1. `grep -rn "MeterRegistry" lock-server/src/main/java payment-resource/src/main/java rail-proxy/src/main/java payout-executor/src/main/java` returns hits **only** in the five `*Metrics` classes and Spring config.
2. All ten Micrometer names of C4 §4.2 appear in the repo, each in exactly one `*Metrics` class.
3. `MetricsCatalogTest` fails if a tag key is added to `lock.lease.expired` (verify by adding one locally, running, then reverting).
4. Neither `lock.fenced.out` nor `rail.duplicate.attempted` appears in a scrape of a freshly started service that has processed no payout.
5. After one successful payout through the local stack, `lock_acquire_seconds_count`, `payout_execute_seconds_count{outcome="posted"}`, `rail_submission_total{outcome="acked"}`, `lock_held_current` and `payout_backlog_age_seconds` are all present.
6. `docs/06-observability-and-slo.md` lists ten Micrometer→exported name pairs matching C4 §4.2 verbatim.

## 6. Verification

`./gradlew spotlessApply check` — green. Bring up the local stack from T-005
(`docker compose -f deploy/compose/compose.yaml up -d`), run one payout through the executor, then
`curl -s localhost:8080/actuator/prometheus | grep -E '^lock_|^payout_|^rail_' | sort` — expect the
criterion-5 lines and **no** `lock_fenced_out_total` / `rail_duplicate_attempted_total`.
`curl -s localhost:8080/actuator/prometheus | grep -E '\{[^}]*(payoutId|accountId|lockKey|token)' `
— expect no output (exit 1).

## 7. Out of scope

Scrape wiring and `PodMonitoring` (T-061). Log fields (T-062). Log-based metrics (T-063). Dashboards,
SLOs and alert policies (T-064+). Trace spans and `lock.token` attributes (later M6 task). Do not add
meters that are not in C4 §4.2 — that needs a contract amendment first.

## 8. Hazards

- A missing tag key is not an error; it forks the series and halves the rate (C4 §4.2 rule b).
- Warming up the two invariant counters destroys the "no data = healthy" property C4 §4.4 relies on.
- A weakly referenced gauge lambda reports NaN forever with no log line.
- `resource` has three values across two services; payment-resource must never emit `rail`.

## 9. On completion

Mark the T-060 row done in `tasks/README.md`. Note any meter you could not centralise and why, and note
whether `lock.session.lost` was registered in `lock-client` (as specified) or elsewhere.
