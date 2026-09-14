# T-024 — rail-proxy: the fencing gate for a resource that cannot check

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M2 · **Estimate** 30 min (tight; if `rail_high_water` is not already migrated by T-020, split the migration out as T-024a)
**Preconditions** — T-001..T-008 (Gradle monorepo, catalog, `build-logic`, Spotless, CI); T-020..T-023 (paydb Flyway tree incl. `rail_submission` + `rail_high_water`, `payment-resource` claim/post with fence point (a), `rail-stub` with its chaos knobs). You inherit a repo where paydb exists and the stub is reachable, but **nothing stands between an executor and the rail** — any token, however stale, can move money.
**Goal** — Implement `rail-proxy` as the only process permitted to call the rail, gating every submission behind a durable per-account high-water mark checked *before* the rail is touched.

## 1. Why this task exists

Enforcement point (a) protects a Postgres row, which can compare tokens because it is a database. The rail cannot: no token, no idempotency key, no memory (`#ct3-railstub`). So the fence is relocated into a process we do control, in front of it — and the mark it compares against must outlive a crash, a rollout and an Autopilot eviction, because those are exactly the events that manufacture stale writers. This is enforcement point (c), and the reason the project has a third process instead of a `HashMap`.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| `POST /v1/rail/submissions` shape; all six error codes; the four-step admission order; why in-memory/per-replica/read-replica marks are wrong | `docs/contracts/C3-http-surfaces.md#ct3-railproxy` |
| Timeout means *unknown*, never *retry*; strict `>` not `>=` | `docs/contracts/C3-http-surfaces.md#ct3-ambiguity` |
| `rail_submission` / `rail_high_water` columns; `rail_submission_attempt_uidx`; the `ON CONFLICT … WHERE highest_token < EXCLUDED.highest_token RETURNING` upsert | `docs/contracts/C1-database-schemas.md#ct1-paydb`, `#ct1-fenced` |
| `X-Fencing-Token`, `X-Owner-Id`, `X-Idempotency-Key` | `docs/contracts/C3-http-surfaces.md#ct3-conventions` |
| Proxy→rail hop timeout and zero retries | `docs/contracts/C3-http-surfaces.md#ct3-timeouts` |
| `rail.submission{outcome}`, `rail.duplicate.attempted`, `lock.fenced.out{resource=rail}`; logs `fenced_out`, `duplicate_rail_submission_attempted`, `rail_ambiguous` + required fields | `docs/contracts/C4-observability.md#ct4-metrics`, `#ct4-zero`, `#ct4-logs`, `#ct4-cardinality` |
| Module name, package `dev.lock.rail.proxy`, paydb datasource key | `docs/contracts/C5-config-build-and-naming.md#ct5-modules`, `#ct5-config` |

**Precedence:** if this spec and a contract disagree, the **CONTRACT wins** — stop, report both wordings, implement neither. Contract silence is not permission to invent a name.

## 3. Deliverables

| Path | What |
|---|---|
| `rail-proxy/build.gradle.kts` | Boot app; catalog aliases only; depends on `lock-api` — **not** `lock-client`, `lock-server` or `payment-resource` |
| `rail-proxy/src/main/java/dev/lock/rail/proxy/RailProxyApplication.java` | Entry point |
| `.../web/RailSubmissionController.java` + request/response records | The single endpoint; header binding; failure→pinned-code mapping |
| `.../admission/AdmissionService.java` | Steps 1–4 in order; owns transaction boundaries |
| `.../store/HighWaterStore.java`, `.../store/RailSubmissionStore.java` | Plain JDBC over the two tables; no ORM |
| `.../rail/RailClient.java` | One-shot forward, explicit read timeout, no retry |
| `.../RailProxyMetrics.java` | Micrometer counters, closed tag-value sets from C4 |
| `.../health/RailStubHealthIndicator.java` | The `railStub` indicator C4 `#ct4-health` pins for this service only: reachability of the configured rail base URL, `DOWN` on any failure |
| `rail-proxy/src/main/resources/application.yml` | Port, paydb datasource, rail base URL, `rail.proxy.fencing.enabled` **declared, default `true`** (wiring is T-026). Health groups: `readiness` **includes `railStub`** so the pod fails closed rather than admitting submissions it cannot forward; `liveness` touches neither the rail nor paydb |
| `settings.gradle.kts` | Include the module if T-004 left it commented out |

## 4. Specification

**(1) Fence first.** In one transaction, run the pinned conditional upsert of `rail_high_water` for `accountId` with the presented token. An unchanged/zero-row result means the token is not strictly greater than the stored mark → write a `rail_submission` row with `outcome='FENCED'`, emit `fenced_out` (`resource=rail`), `lock.fenced.out{resource=rail}`, `rail.submission{outcome=fenced}`, return 409 `FENCED_OUT`. **No rail call is made on this path — that is the whole point.**

**(2) Duplicate check.** Reject a second forwardable attempt for a `payoutId` with 409 `DUPLICATE_SUBMISSION`, `rail.duplicate.attempted`, and `duplicate_rail_submission_attempted` carrying `firstSubmittedAt`. Treat the `rail_submission_attempt_uidx` unique violation as the same outcome — the index, not a prior `SELECT`, is the authority, so a race cannot slip through.

**(3) Intent before socket.** Commit the row with `outcome` NULL **before** any connection is opened, so a proxy crash still leaves evidence that a submission may have been presented. A commit failure is 503 `RAIL_UNAVAILABLE` — safe, because nothing was presented.

**(4) Forward exactly once** and resolve the row: ack → `ACKED` + `rail_reference` + `resolved_at`, 200; decline → `REJECTED`, 422; timeout or dropped connection → `TIMEOUT`, log `rail_ambiguous`, 409 `RAIL_AMBIGUOUS`. No retry, no fallback, no second socket, on any outcome.

Every attempt — fenced, duplicate, acked, rejected, ambiguous — leaves exactly one row carrying `presented_token`. That table is the audit trail T-027 reads.

## 5. Acceptance criteria

1. `./gradlew :rail-proxy:build` passes, Spotless clean.
2. A strictly-greater token returns 200 and advances `rail_high_water.highest_token` to it.
3. A lower **or equal** token returns 409 `FENCED_OUT` with a `rail_submission` row `outcome='FENCED'`.
4. On the fenced path the stub records **no** inbound call (assert against the stub's call log, not proxy logs).
5. A repeat `payoutId` returns 409 `DUPLICATE_SUBMISSION` and increments `rail.duplicate.attempted`.
6. With `rail.stub.latency-ms` above the read timeout: 409 `RAIL_AMBIGUOUS`, row `TIMEOUT` with `resolved_at`, exactly one row.
7. Restarting the proxy between the two submissions does not re-admit the lower token — criterion 3 still holds after restart.
8. `grep -rn "HashMap\|ConcurrentHashMap" rail-proxy/src/main/java` finds no high-water storage.
9. `/actuator/prometheus` exposes `rail_submission_total`, `rail_duplicate_attempted_total`, `lock_fenced_out_total` with only pinned tag values; no `payoutId` as a tag.

## 6. Verification

```
./gradlew :rail-proxy:build :rail-proxy:test
./gradlew :rail-proxy:bootRun            # with rail-stub running
curl -si localhost:8083/v1/rail/submissions -H 'X-Fencing-Token: 42' -H 'X-Owner-Id: w1' \
  -H 'X-Idempotency-Key: k1' -H 'Content-Type: application/json' -d '{…}'   # 200
curl -si localhost:8083/v1/rail/submissions -H 'X-Fencing-Token: 41' …      # 409 FENCED_OUT
psql "$PAYDB_URL" -c 'select outcome, presented_token, rail_reference from rail_submission order by submitted_at'
psql "$PAYDB_URL" -c 'select * from rail_high_water'
curl -s localhost:8083/actuator/prometheus | grep -E 'rail_submission_total|rail_duplicate'
```
Expected: one `ACKED` row, one `FENCED` row, mark = 42, stub logged one call.

## 7. Out of scope

The executor that calls this proxy (T-025); making `rail.proxy.fencing.enabled=false` actually bypass the check (T-026); the cross-module duplicate/ledger test (T-027); Cloud SQL/Terraform/workload identity (M5); dashboards and `PodMonitoring` (M6).

## 8. Hazards

Forwarding inside the intent transaction — it pins a paydb connection for the stub's injected latency and, worse, a rollback erases the evidence that money may have moved. Advancing the mark *after* the rail call re-opens the exact hole this task closes. Auto-retrying a timeout: `#ct3-ambiguity` — "submit again to be safe" turns a possible single payment into a probable double payment, and no `WHERE fence <` clause can un-pay a beneficiary. `>=` instead of `>` admits an equal-token replay.

## 9. On completion

Mark the T-024 row done in `tasks/README.md`; record any deviation, and any contract mismatch you had to stop on, in that row's notes.
