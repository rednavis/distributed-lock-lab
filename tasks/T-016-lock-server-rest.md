# T-016 — lock-server HTTP surface

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M1 — Postgres lock backend · **Estimate** 45 minutes — **over budget; split as follows.**
**T-016a**: conventions, header binding, validation, the error envelope and the exception→code mapper,
plus L1–L3 (sessions). **T-016b**: L4–L8 (locks). Do a in order in one session each; b assumes a.

**Preconditions** — T-013 (core `LockService` wired over `PostgresLockStore`), T-014 (expiry signal),
T-015 (`revoke` with audit trail, so L8 has something to call). You inherit a fully working in-process
lock service with **no web layer at all**; `lock-server/web` is an empty package.

**Goal** — Expose exactly the eight endpoints L1–L8 of the C3 lock surface, with the contract error
envelope and a retry-safety verdict per error code that is derived from the contract, not guessed.

## 1. Why this task exists

The web layer is where a correct core gets ruined. Two specific ways: a `LockLostException` mapped to
500 (which every client library retries, sending a dead token back into the system), and a malformed
`X-Fencing-Token` coerced to `0` (which compares *below* every stored fence and reads as a benign
no-op). Both are cheap to write and expensive to discover. This task's real product is a single mapping
table from exception to `{code, http, retrySafe}` that the whole project can be argued from, plus the proof
that the surface is backend-agnostic (FR-09) so T-030's etcd store needs no controller change.

## 2. Contracts to obey

| What | Pinned at |
|---|---|
| Path prefix `/v1`, content type on **errors too**, header rules, idempotency scope, clock format, "GET is advisory" | `docs/contracts/C3-http-surfaces.md#ct3-conventions` |
| The error envelope: `code`, `message`, `retrySafe`, `retryAfterMillis`, `details`, `traceId` | `docs/contracts/C3-http-surfaces.md#ct3-conventions` |
| Error-code catalogue, HTTP status **and** the retry-safe verdict per code | `docs/contracts/C3-http-surfaces.md#ct3-errors` |
| L1–L8: methods, paths, request fields, bounds, 2xx bodies, per-endpoint code lists | `docs/contracts/C3-http-surfaces.md#ct3-lock` |
| Exception hierarchy and which exceptions are terminal | `docs/contracts/C2-java-api.md#ct2-exceptions` |
| Response record shapes (`LockHandle`, `LockInfo`, `LockOutcome`). **`LockOutcome` is the `forceRevoke` result only** — it backs L8 and nothing else; L4's 200 body is **`LockGrant`** (`C3#ct3-lock`) and the Java acquire result is `Optional<LockHandle>` | `docs/contracts/C2-java-api.md#ct2-records` |
| Token travels as a header/parameter, never a thread-local | `docs/contracts/C2-java-api.md#ct2-propagation` |
| `lock.acquire` timer with `outcome`=`granted`\|`contended`\|`error`; `traceId` from the active span | `docs/contracts/C4-observability.md#ct4-metrics`, `#ct4-traces` |
| The `lockBackend` custom health indicator; readiness **must fail** when the configured backend is unreachable; liveness **must not** touch the backend | `docs/contracts/C4-observability.md#ct4-health` |
| Defaults `lock.default.ttl`, `lock.session.ttl` supply the omitted `ttlMillis` | `docs/contracts/C5-config-build-and-naming.md#ct5-config` |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report, quoting both**
(`docs/04-contracts.md#c-precedence`). A retry-safety verdict differing from `#ct3-errors` is a stop, not
a judgement call.

## 3. Deliverables

| Path | What |
|---|---|
| `lock-server/src/main/java/dev/lock/server/LockServerApplication.java` | Boot entry point (T-016a). The `@SpringBootApplication` class the Boot plugin's `bootJar` requires; the module has none before this task, which is why `docker build --build-arg MODULE=lock-server` is expected to fail for want of an application class until now (T-005 §5.9). |
| `lock-server/src/main/java/dev/lock/server/web/SessionController.java` | L1 create, L2 heartbeat, L3 delete |
| `lock-server/src/main/java/dev/lock/server/web/LockController.java` | L4 acquire, L5 renew, L6 release, L7 read, L8 revoke |
| `lock-server/src/main/java/dev/lock/server/web/dto/` | request/response records, one per endpoint direction; RFC 3339 UTC ms serialisation |
| `lock-server/src/main/java/dev/lock/server/web/ApiError.java` | the envelope record, field order exactly as C3 lists it |
| `lock-server/src/main/java/dev/lock/server/web/ErrorMapper.java` | the single exception→`{code, status, retrySafe, retryAfterMillis}` table |
| `lock-server/src/main/java/dev/lock/server/web/GlobalExceptionHandler.java` | `@RestControllerAdvice` delegating to `ErrorMapper`; no per-controller try/catch |
| `lock-server/src/main/java/dev/lock/server/web/FencingTokenArgumentResolver.java` | strict `X-Fencing-Token` parsing (or an equivalent binder) |
| `lock-server/src/main/java/dev/lock/server/web/LockBackendHealthIndicator.java` | the `lockBackend` indicator C4 `#ct4-health` pins: connectivity **plus a trivial round trip** against the configured backend (`lockdb` or etcd), reported `DOWN` on any failure — never `UNKNOWN`, never cached past one check |
| `lock-server/src/main/resources/application.yaml` (modify) | Actuator health groups: `readiness` **includes `lockBackend`** so the pod fails closed and leaves the Service endpoints when the backend is unreachable; `liveness` includes nothing that touches the backend (a backend outage must not restart-loop the fleet); exposure per C4 `#ct4-scrape` |
| `lock-server/src/test/java/dev/lock/server/web/` | `@WebMvcTest` slices with a mocked `LockService`: one test class per controller plus `ErrorMapperTest`, plus one test asserting readiness turns `DOWN`, and liveness stays `UP`, when the backend probe throws |

## 4. Specification

**Header binding.** `X-Owner-Id` required on every write (L1–L6, L8), ≤64 chars; absent or oversized →
`VALIDATION_FAILED` 400. `X-Fencing-Token` required on L5 and L6: parse as decimal `int64`, reject
absent, non-numeric, `≤0`, or overflowing with `INVALID_TOKEN` 400 — **never coerce, never default to
`0`, never fall back to a body field.** Where the contract allows `ownerId` in the body as an
alternative (L1), a body value and a header value that disagree is `VALIDATION_FAILED`, not
last-one-wins.

**Validation.** Enforce every bound C3 §3.3 states — key length and character class, session TTL range
with clamping-down permitted on L1 only, `waitMillis` range, `operator` non-blank, `reason` minimum
length, `expectedToken` positive when present. Bean Validation annotations on the DTOs plus a
`VALIDATION_FAILED` handler that populates `details.fields[]`. Reject before touching the service.

**The mapping table** is the centre of this task. One entry per exception type, each carrying the code,
status and `retrySafe` copied from `#ct3-errors` — `LOCK_CONTENDED` yes, `CONTENTION_EXCEEDED` yes but
only after `retryAfterMillis`, `NOT_LEADER` yes, `LOCK_LOST` no, `FENCED_OUT` never, `INVALID_TOKEN` no,
`SESSION_UNKNOWN`/`SESSION_EXPIRED` no. Populate `retryAfterMillis` on exactly the four codes C3 lists
it for. Any exception with no table entry maps to a 500 with a generic code and is logged at ERROR —
and the fact that the table is incomplete is a finding to report, not to paper over.

**Per-endpoint notes.** L5 returns the **same** token — renew extends, never re-issues; a test must
assert token equality. L6 is compare-and-delete and a `LOCK_LOST` there is benign: 409, logged at INFO,
no WARN, no alert. L7 requires no auth-bearing headers, returns 404 when the key was never seen, and its
javadoc/OpenAPI description must state that it is advisory and must never gate a decision to act. L8
returns `revokedToken`, `newTokenFloor` and `revocationId` from T-015's record, maps a stale
`expectedToken` to `FENCED_OUT` 409 and not-held to 404.

**Envelope and correlation.** Every non-2xx body is `application/json; charset=utf-8` with the six-field
envelope; `traceId` comes from the active OpenTelemetry span. Configure Spring so the container's
default HTML error page is unreachable — an HTML 503 from the error dispatcher breaks every client's
parse of a token mismatch.

**Metrics.** Time L4 with `lock.acquire`, tagging `outcome` `granted` / `contended` / `error` — mapped
from the outcome, never from the HTTP status. No path or key in any tag.

## 5. Acceptance criteria

1. Exactly eight handler methods exist across the two controllers, matching L1–L8 method+path; `grep -R "@RequestMapping\|@PostMapping\|@GetMapping\|@DeleteMapping" lock-server/src/main` shows no ninth lock/session route.
2. `ErrorMapperTest` asserts code, status and `retrySafe` for every code in `#ct3-errors` that lock-server can raise, and fails if a code is unmapped.
3. Every non-2xx response from every endpoint deserialises into `ApiError` with non-null `code`, `message`, `retrySafe` and `traceId`, and content type `application/json`.
4. Requests with `X-Fencing-Token` values `""`, `abc`, `0`, `-1`, `9223372036854775808` all yield 400 `INVALID_TOKEN`; none reaches the service (verified with a mocked service and `verifyNoInteractions`).
5. L5 response `fencingToken` equals the request header token.
6. L6 against an expired/taken-over grant returns 409 `LOCK_LOST` and logs at INFO, not WARN.
7. L7 needs no `X-Owner-Id` and returns 404 for an unseen key.
8. L8 with a stale `expectedToken` returns 409 `FENCED_OUT`; with no live grant, 404.
9. No controller catches an application exception itself; all mapping flows through the advice.
10. Nothing in `web/` references `PostgresLockStore` or any pg type — only `LockService` and `lock-api` types (FR-09).
11. With the backend stopped, `/actuator/health/readiness` returns 503 with `lockBackend` `DOWN` while `/actuator/health/liveness` still returns 200 — the fail-closed rule of C4 `#ct4-health`, asserted, not assumed.
12. `./gradlew :lock-server:bootJar` produces a **runnable** jar: the task succeeds and the archive's
    `Start-Class` manifest attribute is `dev.lock.server.LockServerApplication`
    (`unzip -p lock-server/build/libs/*.jar META-INF/MANIFEST.MF | grep Start-Class`), so the M5 image
    build and the Kubernetes Deployment have a main class to start. A `bootJar` that builds but has no
    `Start-Class` is a failure of this criterion, not a warning.

## 6. Verification

- `./gradlew :lock-server:spotlessCheck :lock-server:test` — green.
- `./gradlew :lock-server:bootRun`, then: `curl -i -XPOST -H 'X-Owner-Id: w1' -H 'Content-Type: application/json' -d '{"ttlMillis":15000}' localhost:8080/v1/sessions` → 201 with `sessionId`.
- Acquire, then acquire the same key as `w2` → `409` and a body whose `code` is `LOCK_CONTENDED`, `retrySafe` is `true`, `details.holderOwnerId` is `w1`.
- `curl -i -XDELETE -H 'X-Owner-Id: w1' -H 'X-Fencing-Token: abc' localhost:8080/v1/locks/payout:acct-1` → 400, `code=INVALID_TOKEN`, `retrySafe=false`.
- `curl -s localhost:8080/v1/locks/payout:acct-1 | jq .held` → `true`, with no headers sent.
- `curl -s localhost:8080/actuator/prometheus | grep lock_acquire_seconds_count` → series for `outcome="granted"` and `outcome="contended"`.

## 7. Out of scope

The client SDK, heartbeat loop and safety margin (T-040s), `payment-resource` / `rail-proxy` /
`rail-stub` surfaces (M2), server-side blocking waits beyond honouring `waitMillis`, OpenAPI publication,
authn/authz, and Testcontainers-backed end-to-end coverage of these endpoints (T-017 covers the store;
harness-level coverage is M4).

## 8. Hazards

- **`LOCK_LOST` as 500** is the headline defect: clients retry 500s, and `#ct3-errors` marks the code
  **not retry-safe**. A 5xx here reintroduces the dead token.
- **Coercing a malformed token to `0`** — `#ct3-errors`, `INVALID_TOKEN` row: `0` compares below every
  stored fence and looks like a benign no-op.
- **`FENCED_OUT` is never retry-safe and is incident-grade** (NFR-06); do not emit `retryAfterMillis` with it.
- Deriving `outcome` tags from HTTP status conflates `contended` with `error` and corrupts the M7 benchmark.
- Do **not** run `git` (ADR-011).

## 9. On completion

Mark the T-016a row done in `tasks/README.md` (the T-016b row is closed by the following session — both
rows are mandatory; M1 does not exit with L4–L8 missing), listing any error code C3 defines that
lock-server cannot raise and any code you had to raise that C3 does not define — the latter is a stop.
