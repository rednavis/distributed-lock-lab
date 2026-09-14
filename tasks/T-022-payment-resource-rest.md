# T-022 — payment-resource HTTP surface and the fenced-out signal

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M2 — protected resource and payout executor · **Estimate** 30 min (P1–P5 plus the error
mapping is the whole budget; if the web-layer tests are not green at 30, land P2/P3 and the exception
handler and defer P1/P4/P5 to T-022b, recorded in `tasks/README.md`)

**Preconditions** — T-020 (paydb schema) and T-021 (repositories, the transactional posting component
and `FencedOutException` raising all exist and are integration-tested). You inherit a
`payment-resource` module with a complete persistence layer and no controllers.

**Goal** — Expose the five contracted payment-resource paths and turn a fenced write into the exact
409 body, log event and counter that make a lost-mutual-exclusion event visible without a DB query.

## 1. Why this task exists

The fenced-out 409 is the project's primary evidence artifact: it is what the T-042 experiment observes and
what the M6 alert fires on. Its body carries both tokens so an on-call engineer never has to reconstruct
ordering across two databases at 03:00. This task also fixes the retry-safety story at the wire: the
difference between `INVALID_TOKEN` (client defect, 400) and `FENCED_OUT` (incident, 409, never retry) is
the difference between a bug report and a duplicate payment.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| Paths P1…P5, request fields, 2xx bodies, permitted codes per path | `docs/contracts/C3-http-surfaces.md#ct3-pay` |
| The five mandatory `details` fields on the 409 `FENCED_OUT` body | C3 `#ct3-pay` |
| Error envelope shape, header names `X-Fencing-Token` / `X-Owner-Id` / `X-Idempotency-Key` | C3 `#ct3-conventions` |
| Code→HTTP status and the retry-safe verdict for each | C3 `#ct3-errors` |
| `INVALID_TOKEN` triggers: absent, non-numeric, ≤0, int64 overflow; never coerce to 0 | C3 `#ct3-errors` |
| Log event `fenced_out` name and required fields; promoted labels | `docs/contracts/C4-observability.md#ct4-logs`, `#ct4-promotion` |
| Counter `lock.fenced.out{resource=…}` tag values and the zero-value expectation | C4 `#ct4-metrics`, `#ct4-zero` |
| Resource ids must not become metric tags | C4 `#ct4-cardinality` |
| Actuator/Prometheus exposure and the `http-metrics` port name | C4 `#ct4-scrape`, `#ct4-health` |
| The `payDb` custom health indicator (payment-resource only) and the fail-closed readiness rule | C4 `#ct4-health` |
| Exception semantics you map from | `docs/contracts/C2-java-api.md#ct2-exceptions` |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report**, quoting both.
Do not add an error code, path or `details` field the contract does not list
(`docs/04-contracts.md#c-precedence`).

## 3. Deliverables

Module-relative under `payment-resource/src/main/java/…/`, package per C5 `#ct5-naming`.

| Deliverable | What it is |
|---|---|
| `payment-resource/src/main/java/dev/lock/payments/resource/PaymentResourceApplication.java` | Boot entry point. The `@SpringBootApplication` class the Boot plugin's `bootJar` requires; T-020 and T-021 add persistence but never a Spring context, so this is the task that first makes the module runnable. |
| `PayoutController` | P1 list, P2 claim, P3 post, P4 payout read. |
| `AccountController` | P5 account read including the recomputed `ledgerSumMinor`. |
| Request/response records | One record per contracted body; field names exactly as C3 `#ct3-pay` spells them (camelCase JSON). Immutable records, no Lombok `@Data`. |
| `FencingTokenArgumentResolver` (or an equivalent single validation seam) | Parses `X-Fencing-Token` once, rejecting absent/non-numeric/≤0/overflow with `INVALID_TOKEN`. |
| `ApiExceptionHandler` | `@RestControllerAdvice` mapping the C2 exception hierarchy and the payment-domain failures onto the C3 codes and statuses; emits the `fenced_out` event and the counter for the fenced family. |
| `PayDbHealthIndicator` | The `payDb` indicator C4 `#ct4-health` pins for this service only: connectivity plus a trivial round trip against `paydb`, `DOWN` on any failure. |
| `payment-resource/src/main/resources/application.yaml` | Actuator exposure per C4 `#ct4-scrape`, `#ct4-health`; server port and management port naming. Health groups: `readiness` **includes `payDb`** so the pod fails closed and leaves the Service endpoints when `paydb` is unreachable; `liveness` touches no database. |
| `payment-resource/src/test/java/…/PaymentResourceApiIT.java` | Spring Boot web test over the Testcontainers `paydb` from T-020. |

## 4. Specification

**Header discipline.** P2 and P3 require all three custom headers; P1, P4, P5 require none and must
reject none. A missing `X-Owner-Id` on P2/P3 is `VALIDATION_FAILED` (400), not `INVALID_TOKEN` — the
codes are distinguishable so an operator can tell a broken client from a broken token.

**Token parsing.** One place, before the controller body runs. `Long.parseLong` failure, a value ≤ 0, a
value with leading `+`/whitespace, or 19+ digits overflowing `int64` all yield 400 `INVALID_TOKEN` with
no database access. A parsed token is passed onward as an explicit parameter (C2 `#ct2-propagation`).

**Status mapping**, from C3 `#ct3-errors` — the table is the source of truth; the mapping code must be a
single exhaustive switch over the code enum so a new code cannot be added without touching it:
`FENCED_OUT` 409, `PAYOUT_NOT_CLAIMABLE` 409, `INSUFFICIENT_FUNDS` 422, `IDEMPOTENCY_CONFLICT` 422,
`INVALID_TOKEN` 400, `VALIDATION_FAILED` 400, 404 for an unknown payout or account.

**The fenced-out signal.** On `FencedOutException` the handler must, in this order: build the 409 body
whose `details` carries `presentedToken`, `highestToken`, `resourceType` (`account`|`ledger`|`payout`),
`resourceId`, `ownerId`; emit the structured log event `fenced_out` with the field set C4 `#ct4-logs`
requires; increment `lock.fenced.out` tagged only with `resource`. `resourceId` and `ownerId` go in the
log and the body, never into a tag. The response must be sufficient to alert on with no DB query — that
is the acceptance test, not a nicety.

**P3 success body** returns both ledger legs and the post-update balance, so a caller can verify
`balance = Σ entries` from the response alone (FR-25 spirit). P5 recomputes `ledgerSumMinor` with a
`SUM` query rather than trusting `balance_minor`; when they disagree the endpoint still returns 200 with
both values — divergence is data the reconciliation task consumes, not an HTTP failure.

**`PaymentResourceApiIT` cases**, in prose: P2 with a winning token returns 200 with `state=CLAIMED` and
the post-update `accountFence`; P2 with a stale token returns 409 with `code=FENCED_OUT` and all five
`details` fields present and correctly valued; P2 with `X-Fencing-Token: abc`, with `0`, with `-1`, and
with the header absent each return 400 `INVALID_TOKEN` and leave the account row untouched; P3 against a
payout not in `RAIL_ACKED` returns 409 `PAYOUT_NOT_CLAIMABLE`; P3 happy path returns two balanced
entries and a balance equal to their sum; P1 filters by state and pages via `nextCursor`; P5 returns
`balanceMinor == ledgerSumMinor` after a clean post; the `lock.fenced.out{resource=account}` counter
reads exactly 1 after the single fenced case and 0 before it.

## 5. Acceptance criteria

1. Exactly five paths are exposed; `grep` of `@GetMapping`/`@PostMapping` matches C3 `#ct3-pay` with no
   extra path.
2. Every error response body uses the C3 `#ct3-conventions` envelope and a code from `#ct3-errors` —
   no free-text error strings, no Spring default error body reaching a client.
3. The 409 `FENCED_OUT` body carries all five `details` fields, asserted by name in the test.
4. Four `INVALID_TOKEN` cases (non-numeric, zero, negative, absent) are tested and none touches the DB.
5. `lock.fenced.out` is tagged with `resource` only; no metric anywhere carries a payout or account id.
6. `fenced_out` is emitted exactly once per fenced response, with the C4-required fields.
7. `/actuator/prometheus` is exposed and lists `lock_fenced_out_total`.
8. `./gradlew :payment-resource:check` green, Spotless clean.
9. `./gradlew :payment-resource:bootJar` produces a **runnable** jar: the task succeeds and the
   archive's `Start-Class` manifest attribute is
   `dev.lock.payments.resource.PaymentResourceApplication`
   (`unzip -p payment-resource/build/libs/*.jar META-INF/MANIFEST.MF | grep Start-Class`), so the M5
   image build and the Kubernetes Deployment have a main class to start. A `bootJar` that builds but
   has no `Start-Class` is a failure of this criterion, not a warning.

## 6. Verification

- `./gradlew :payment-resource:test --tests '*PaymentResourceApiIT'` → green.
- `./gradlew :payment-resource:bootRun` then:
  `curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/v1/payouts/<id>/claim -H 'X-Fencing-Token: abc' -H 'X-Owner-Id: w1' -H 'X-Idempotency-Key: k1' -d '{"expectedState":"PENDING"}' -H 'content-type: application/json'` → `400`.
- Same call with a stale numeric token → `409`, and the body piped through `jq '.details'` shows the
  five fields.
- `curl -s localhost:8080/actuator/prometheus | grep lock_fenced_out_total` → one series, tag `resource`.
- `curl -s localhost:8080/actuator/prometheus | grep -c 'payout_id\|account_id'` → `0`.

## 7. Out of scope

The payout executor and its lock client usage (T-024…T-027). The rail stub (T-023) and rail-proxy's
high-water fence. OpenTelemetry spans, dashboards and alert policies (M6, T-060s). Authentication —
deliberately absent lab-wide. Idempotency-key *replay storage*: this task validates the header and
detects a materially different body for a known key; durable replay semantics belong to the executor.

## 8. Hazards

- A bare 409 is a contract violation, not a style choice (C3 `#ct3-pay`) — the two tokens in the body
  are the whole point.
- `FENCED_OUT` is **never** retry-safe (C3 `#ct3-errors`); do not add `Retry-After`, and do not let a
  generic 409 handler group it with `LOCK_CONTENDED`, which *is* retryable.
- Never coerce a malformed token to 0 — 0 sits below every stored fence and reads as a benign no-op.
- Spring's default `/error` page leaks stack traces and bypasses the envelope; disable it explicitly.
- Micrometer will happily accept a high-cardinality tag; C4 `#ct4-cardinality` forbids per-payout tags
  and the budget is enforced in M6 where it is too late to redesign.
- The Actuator container port must be named `http-metrics` or the scrape fails **silently**
  (C4 `#ct4-scrape`); set the name now even though the manifests land in M5.

## 9. On completion

Mark T-022 done in `tasks/README.md`. Record any code you needed that C3 `#ct3-errors` does not list —
as a proposed §4.5 change-log row, not an edit to the contract.
