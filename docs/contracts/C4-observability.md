# C4 - Observability contract

Pinned identifiers for metrics, log events, spans and scrape wiring. Peers: [C1](C1-database-schemas.md),
[C2](C2-java-api.md), [C3](C3-http-surfaces.md), [C5](C5-config-build-and-naming.md).

## 4.1 Scope and the three-layer rule {#ct4-scope}

**Alert on metrics. Diagnose with logs. Explain with traces.** Each layer has a different cost curve:
metrics are cheap per data point and expensive per *series*; logs are cheap per field and expensive per
*line*; traces are cheap per span and expensive per *percentage sampled*. Put each fact where its cost
is lowest.

A corollary worth stating plainly: **an alert that fires from a log query is usually a metric nobody
created yet.** A log query as an alert source is fragile (§4.7); the correct fix is to
name the signal, emit it as a counter, and alert on that.

This file pins **identifiers and schemas only**. SLO targets, alert thresholds, burn-rate windows and
dashboards live in `docs/06-observability-and-slo.md`. If a number appears here it is a shape (a unit, a
suffix, an interval), not a target.

## 4.2 Metrics catalogue {#ct4-metrics}

Micrometer + Spring Boot Actuator, exported at `/actuator/prometheus`. The **exported name** column is
the string every SLO filter, PromQL query and Cloud Monitoring alert condition must use. Micrometer
lower-cases and replaces `.` with `_`; counters gain `_total`; timers publish
`_seconds_count` / `_seconds_sum` / `_seconds_bucket`; gauges are bare. Google Managed Service for
Prometheus additionally exposes the descriptor as `prometheus.googleapis.com/<name>/counter` or
`/histogram`. **Getting this string wrong is the single most common reason a metrics query silently
returns nothing** - a wrong metric name is not an error, it is an empty result, which on a
`< threshold` condition looks exactly like health.

| Micrometer name | Type | Unit | Tag keys → allowed values | Emitter | Exported name | Incident question |
|---|---|---|---|---|---|---|
| `lock.acquire` | Timer | seconds | `backend`=`pg`\|`etcd`; `outcome`=`granted`\|`contended`\|`error` | lock-server | `lock_acquire_seconds_{count,sum,bucket}` → `.../lock_acquire/histogram` | Is the lock slow, or just busy? |
| `lock.lease.expired` | Counter | events | `backend`=`pg`\|`etcd` | lock-server | `lock_lease_expired_total` → `.../counter` | Are holders dying or just pausing? |
| `lock.fenced.out` | Counter | events | `resource`=`account`\|`ledger`\|`rail` | payment-resource, rail-proxy | `lock_fenced_out_total` → `.../counter` | Did a zombie holder reach a writer? |
| `lock.held.current` | Gauge | locks | `backend`=`pg`\|`etcd` | lock-server | `lock_held_current` | How many locks exist right now? |
| `lock.waiters.current` | Gauge | waiters | `backend`=`pg`\|`etcd` | lock-server | `lock_waiters_current` | Is contention queueing or churning? |
| `lock.session.lost` | Counter | events | `backend`=`pg`\|`etcd` | lock-client | `lock_session_lost_total` → `.../counter` | Did the client lose its keepalive? |
| `payout.execute` | Timer | seconds | `outcome`=`posted`\|`failed`\|`abandoned`\|`ambiguous` | payout-executor | `payout_execute_seconds_{count,sum,bucket}` → `.../histogram` | Is the business outcome healthy? |
| `rail.submission` | Counter | events | `outcome`=`acked`\|`rejected`\|`timeout`\|`fenced` | rail-proxy | `rail_submission_total` → `.../counter` | Is the external rail the problem? |
| `rail.duplicate.attempted` | Counter | events | *(none)* | rail-proxy | `rail_duplicate_attempted_total` → `.../counter` | Did we nearly pay twice? |
| `payout.backlog.age.seconds` | Gauge | seconds | *(none)* | payout-executor | `payout_backlog_age_seconds` | Is the oldest pending payout aging? |

Rules: (a) `backend` is the **configured** backend of the emitting process, never a per-request value;
(b) every tag key listed must be present on every data point - Micrometer treats a missing tag as a
*different* series, which splits a rate in half without any error; (c) no tag key or value not in this
table may be added without editing this table first.

## 4.3 Cardinality: a hard constraint {#ct4-cardinality}

**Never tag a metric with the lock key, the account id, the payout id, an idempotency key, a fencing
token, or a URL path containing any of them.**

Ten million account ids means ten million time series per metric, times every other tag on it. Both
Cloud Monitoring and Prometheus price *and* perform on **active time-series count**, not on data
volume: the bill, ingestion latency, query latency and the head block's memory all scale with series,
so one careless tag can make a dashboard un-loadable and a cost line unexplainable. Unbounded tags also
never *stop* growing - each new account permanently adds a series.

**The one-line reviewer test:** *a tag value is allowed only if you can write the complete set of its
possible values on one line of this document.* `backend` (2), `outcome` (≤4), `resource` (3) pass.
`accountId` fails. If you cannot enumerate it, it is a **log field**, where the same value costs one
key in one line and is queryable and indexable for free.

Getting this backwards - identifiers in tags, aggregate counts in log text - is the **single most
expensive mistake in practical observability**, and it is expensive in both directions: the bill goes
up and the queries you actually need stop working.

## 4.4 The two counters whose healthy value is exactly zero {#ct4-zero}

`lock.fenced.out` and `rail.duplicate.attempted` are **invariant-violation counters**. Correct
operation produces no data points at all.

| Counter | Non-zero means (both things, always) |
|---|---|
| `lock.fenced.out` | The fence **worked**: a stale token was rejected at the Postgres row (`UPDATE ... WHERE fence < :token`) or at the rail-proxy high-water mark. It also means a holder that believed it held the lock reached a writer - so somewhere there was a GC pause, a container freeze, a network partition, or a client-side lease bug. |
| `rail.duplicate.attempted` | The rail-proxy **blocked** a second submission of one payout to a non-idempotent rail. It also means an executor retried or re-claimed work it should have owned exclusively - a lease/claim boundary defect, not a rail defect. |

Because the expected value is exactly zero, a **threshold of `> 0` with no duration window and no
`for:` clause is correct, not noisy**: there is no baseline to distinguish signal from, and a single
increment is already a completed safety-net catch. This is the *one legitimate exception* to "never
alert on a single data point" - the rule exists because one sample of a noisy signal is meaningless,
and these signals have no noise floor.

Standing interpretation: **a fence firing is never nothing.** Do not close the alert on "the safety net
held." The safety net holding is the *evidence* that a liveness assumption in the client broke; close it
on the explanation of *why a zombie existed*, with the `fenced_out` log line (§4.5) as the starting
point.

## 4.5 Structured log event schema {#ct4-logs}

JSON to stdout, ingested by Cloud Logging. **Common fields on every event:** `timestamp` (RFC 3339,
string), `severity` (string), `service` (string, from §4.2 emitters), `event` (string, from this table),
`traceId` (string, 32 hex), `spanId` (string, 16 hex), `ownerId` (string, present on every
lock-scoped event). No secrets, no full request bodies.

| `event` | Level | Emitter | Event-specific fields (type - meaning) |
|---|---|---|---|
| `lock_granted` | INFO | lock-server | `lockKey` str - resource being locked; `token` long - fencing token issued; `backend` str; `leaseMillis` long - lease length granted; `waitMillis` long - time spent queued |
| `lock_released` | INFO | lock-server | `lockKey` str; `token` long; `heldMillis` long - actual hold duration; `reason` str - `normal`\|`abandoned` |
| `lease_expired` | WARN | lock-server | `lockKey` str; `token` long - token of the expired holder; `expiredAtMillis` long; `overdueMillis` long - how far past the deadline the sweep found it |
| `lock_revoked` | WARN | lock-server | `lockKey` str; `token` long; `revokedBy` str - operator or sweeper identity; `reason` str |
| `fenced_out` | **ERROR** | payment-resource, rail-proxy | `resource` str - `account`\|`ledger`\|`rail`; `resourceId` str - account/ledger/payout id rejected; `presentedToken` long - token the caller offered; `highestToken` long - token already recorded at the writer; `ownerId` str - the rejected caller |
| `session_lost` | WARN | lock-client | `lockKey` str; `token` long; `backend` str; `lastRenewMillis` long - age of the last successful renew; `cause` str |
| `payout_claimed` | INFO | payout-executor | `payoutId` str; `accountId` str; `token` long - fence acquired with the claim; `attempt` int |
| `rail_submitted` | INFO | rail-proxy | `payoutId` str; `idempotencyKey` str; `presentedToken` long; `railRef` str\|null - rail-side reference if acked |
| `rail_ambiguous` | **ERROR** | rail-proxy | `payoutId` str; `idempotencyKey` str; `presentedToken` long; `elapsedMillis` long - elapsed time at which the submission timed out with no ack (per [C3 §3.7](C3-http-surfaces.md#ct3-ambiguity)); `railRef` str\|null |
| `payout_posted` | INFO | payout-executor | `payoutId` str; `accountId` str; `token` long; `ledgerEntryIds` str[] - the double-entry rows written; `railRef` str |
| `duplicate_rail_submission_attempted` | **ERROR** | rail-proxy | `payoutId` str; `idempotencyKey` str; `presentedToken` long; `highestToken` long; `firstSubmittedAt` str - timestamp of the accepted submission |

`fenced_out` and `duplicate_rail_submission_attempted` are ERROR because each *is* a caught invariant
violation; `rail_ambiguous` is ERROR because it may have moved real money with no confirmation and
requires reconciliation, not a retry.

## 4.6 Field promotion to log-based-metric labels {#ct4-promotion}

| May be promoted (bounded) | Must NEVER be promoted (unbounded) |
|---|---|
| `event`, `service`, `severity`, `resource`, `outcome`, `backend`, `reason` | `token`, `presentedToken`, `highestToken`, `lockKey`, `accountId`, `payoutId`, `resourceId`, `idempotencyKey`, `railRef`, `ledgerEntryIds`, `traceId`, `spanId` |

The right-hand column is not a style preference: a log-based metric label is a time-series dimension, so
promoting `payoutId` recreates exactly the explosion §4.3 forbids - with the added trap that it happens
in a console UI far from code review. Same one-line test as §4.3. Keep the forbidden fields queryable in
the log payload; that is what they are for.

## 4.7 Log-based metrics to derive {#ct4-lbm}

| Derived metric | Source filter (Cloud Logging) | Labels | Purpose |
|---|---|---|---|
| `lock_zombie_write_attempts` | `jsonPayload.event="fenced_out"` | `service`, `resource` | Independent confirmation of `lock.fenced.out` from a different pipeline |
| `rail_ambiguous_outcomes` | `jsonPayload.event="rail_ambiguous"` | `service` | Reconciliation queue depth driver |
| `rail_duplicate_blocks` | `jsonPayload.event="duplicate_rail_submission_attempted"` | `service` | Cross-check against `rail.duplicate.attempted` |
| `lock_revocations` | `jsonPayload.event="lock_revoked"` | `service`, `reason` | Operator/sweeper intervention rate |
| `client_session_losses` | `jsonPayload.event="session_lost"` | `service`, `backend` | Client-side keepalive health where no server metric exists |

Why convert rather than alert on the query: **the log line's wording will change, the metric name will
not.** A query alert is coupled to a message string that any refactor can edit without failing a build,
and it silently stops matching. The indirection also forces the useful decision - *what is the signal?* -
instead of *what is the message?* Where a first-class Micrometer counter already exists (§4.2), the
log-based twin is a **cross-check from an independent pipeline**, not the alert source.

## 4.8 Traces {#ct4-traces}

OpenTelemetry, exported to Cloud Trace. One payout attempt is one trace.

| Span | Parent | Service | Required attributes |
|---|---|---|---|
| `payout.execute` | root | payout-executor | `payout.id`, `account.id`, `payout.attempt`, `lock.backend` |
| `lock.acquire` | `payout.execute` | lock-server | `lock.key`, `lock.backend`, `lock.token`, `lock.outcome`, `lock.wait_ms`, `lock.lease_ms` |
| `payment.claim` | `payout.execute` | payment-resource | `payout.id`, `lock.token`, `fence.result`=`accepted`\|`fenced` |
| `rail.submit` | `payout.execute` | rail-proxy | `payout.id`, `idempotency.key`, `lock.token`, `fence.highest_token`, `rail.outcome` |
| `rail.stub.process` | `rail.submit` | rail-stub | `idempotency.key`, `rail.injected_fault`, `rail.latency_ms` |
| `ledger.post` | `payout.execute` | payment-resource | `payout.id`, `lock.token`, `ledger.entry_count`, `fence.result` |
| `lock.release` | `payout.execute` | lock-server | `lock.key`, `lock.token`, `lock.held_ms`, `lock.release_reason` |

**`lock.token` on every span is the load-bearing attribute.** Span attributes are per-span and not
subject to the §4.3 cardinality budget, so the token is free here and priceless: filtering traces by one
token yields the *complete story of one lock's life* across four processes - when it was issued, which
writers accepted it, which rejected it, and whether a *lower* token showed up afterwards. Without it,
a fencing incident is four unrelated traces.

Sampling: **low rate in steady state** (parent-based, head sampling, rate configured in
`docs/06-observability-and-slo.md`), **100 % retained on error paths** - any trace containing a span
with error status, or a `fenced_out` / `rail_ambiguous` / `duplicate_rail_submission_attempted` event.
Rare-and-critical is exactly the population uniform sampling loses.

## 4.9 Scrape contract {#ct4-scrape}

| Item | Value |
|---|---|
| Path | `/actuator/prometheus` (Actuator; `management.endpoints.web.exposure.include` must list `prometheus`, `health`) |
| Container port number | `8080` (all services) |
| **Container port name** | `http-metrics` - the `PodMonitoring` `spec.endpoints[].port` selects on **this name** |
| Scrape interval | `30s` |
| Cluster / project / region | `dlock-gke` / `dlock-lab` / `europe-central2` |
| Collector | Google Managed Service for Prometheus, one `PodMonitoring` CRD per namespace, label-selecting the project services |

**The named-port trap:** `PodMonitoring` may reference either a port *name* or a number, and a name that
does not exist on the container matches nothing. There is no error - the target simply never appears, and
every query returns empty (§4.2). If a service's `containerPort` is unnamed, or named `http` while the
CRD says `http-metrics`, **that is the bug**, and it is by far the most common reason "no metrics appear."

Verification step, in order, after any deploy: (1) `curl` the pod's `/actuator/prometheus` and grep for
`lock_acquire_seconds_count`; (2) confirm the target is listed as up for the `PodMonitoring`; (3) query
the exported name in Cloud Monitoring / Metrics Explorer and confirm a data point within two scrape
intervals. Passing (1) but failing (2) is always wiring - port name, label selector, or namespace.

## 4.10 Health and readiness {#ct4-health}

| Endpoint | Semantics | Backend dependency |
|---|---|---|
| `/actuator/health/liveness` | Process is alive; restart if failing | none - must not touch the backend |
| `/actuator/health/readiness` | Safe to receive traffic | **must fail** when the configured lock backend (lockdb or etcd) is unreachable |
| `/actuator/health` | Aggregate, for humans | includes both groups |

Custom indicators: `lockBackend` (connectivity + a trivial round trip against the configured backend),
`payDb` (payment-resource only), `railStub` (rail-proxy only).

**This service fails closed by design.** With no reachable lock backend, no lock can be granted, no
fencing token minted, and no token verified - so serving traffic would mean either refusing everything
loudly or, far worse, proceeding unfenced against a non-idempotent rail. Readiness failure removes the
pod from endpoints, so callers see a fast connection-level failure and retry elsewhere instead of
accumulating timeouts on a pod that cannot succeed. Liveness must **not** depend on the backend: a
backend outage would otherwise restart-loop the whole fleet and destroy the diagnostic evidence.

## 4.11 Deliberately not specified here {#ct4-nonspec}

| Not here | Where |
|---|---|
| SLO targets, error budgets, burn-rate windows, alert thresholds and durations, dashboard layout | `docs/06-observability-and-slo.md` |
| The numeric steady-state trace sampling rate | `docs/06-observability-and-slo.md` |
| Histogram bucket boundaries and percentile publication settings | Micrometer config, `docs/06-observability-and-slo.md` |
| Log retention, sinks, exclusion filters, log-router config | Terraform under `deploy/terraform/` ([C5 §5.5](C5-config-build-and-naming.md#ct5-layout)) - there is no `infra/` tree |
| Config key defaults and the kill switches `payment.fencing.enabled` / `rail.proxy.fencing.enabled` | [C5 §5.1-§5.2](C5-config-build-and-naming.md#ct5-config) |
| HTTP status and error codes that produce these outcomes | [C3 §3.2](C3-http-surfaces.md#ct3-errors) |
| Table and column names behind the fenced writes | [C1 §1.6](C1-database-schemas.md#ct1-fenced) |
| Alerting/notification channels, on-call routing, runbook text | out of scope for the project |
