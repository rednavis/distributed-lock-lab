# T-023 — rail-stub: the non-idempotent external rail

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M2 — protected resource and payout executor · **Estimate** 25 min

**Preconditions** — T-001…T-008 (M0 build, catalog, Spotless, CI) only. T-020…T-022 are complete but
this module must not depend on any of them; the stub is written last in M2's resource block because it
is what the executor tasks will point at.

**Goal** — Build a Spring Boot service that stands in for a hostile third-party payment rail: no
idempotency, no token awareness, and configurable latency, failure and duplicate-acceptance rates.

## 1. Why this task exists

The distributed lock in this project is justified *only* because the critical section contains an external
side effect no transaction can undo (ADR-004). If the stub deduplicated by `clientRef`, the rail itself
would provide the safety property and the entire project would prove nothing. So the stub is deliberately
memoryless and deliberately willing to pay twice — and its injected latency is the mechanism that makes
an executor's lease expire mid-flight, which is the origin of every stale writer in the experiments.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| `POST /submit` request fields, the 200 body, the 402 decline, the dropped-connection behaviour | `docs/contracts/C3-http-surfaces.md#ct3-railstub` |
| **No token header, no idempotency header** on this surface | C3 `#ct3-railstub`, `#ct3-conventions` |
| Why the stub must stay non-idempotent (FR-21) | C3 `#ct3-railstub` |
| How an ambiguous outcome is interpreted by callers (context only — the stub implements none of it) | C3 `#ct3-ambiguity` |
| `rail.stub.latency-ms`, `rail.stub.failure-rate`, `rail.stub.duplicate-ack-rate` — the only config keys | `docs/contracts/C5-config-build-and-naming.md#ct5-config` |
| Module name and Gradle project path | C5 `#ct5-modules`, `#ct5-layout` |
| Whether the stub emits any custom metric | `docs/contracts/C4-observability.md#ct4-metrics` — implement only what is named there; if nothing is, Actuator defaults only |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report**, quoting both.
Adding a fourth `rail.stub.*` key is a contract change requiring a §4.5 row, not an implementation
decision (`docs/04-contracts.md#c-precedence`).

## 3. Deliverables

| Path | What it is |
|---|---|
| `rail-stub/build.gradle.kts` | Spring Boot web only, via the version catalog. **No** `lock-api`, no `payment-resource`, no JDBC, no Flyway, no Testcontainers-postgres. |
| `rail-stub/src/main/java/…/RailStubApplication.java` | Boot entry point. |
| `rail-stub/src/main/java/…/SubmitController.java` | The single `POST /submit` handler. |
| `rail-stub/src/main/java/…/RailStubProperties.java` | `@ConfigurationProperties` over the three keys, with validation. |
| `rail-stub/src/main/java/…/ChaosDecider.java` | The seam that decides latency / failure / duplicate-acceptance for a call; replaceable in tests. |
| `rail-stub/src/main/resources/application.yaml` | Defaults per C5 `#ct5-config`. |
| `rail-stub/src/test/java/…/RailStubBehaviourTest.java` | Web-layer test of the behaviour matrix in §4. |
| `rail-stub/README.md` | 10–20 lines: what it simulates, the three knobs, and the standing rule that it must never be made idempotent. |

## 4. Specification

**The handler.** Accepts `clientRef`, `amountMinor` (int64), `currency`. It reads no headers beyond the
HTTP basics: any `X-Fencing-Token`, `X-Owner-Id` or `X-Idempotency-Key` present is ignored entirely and
must not appear anywhere in the module's source. Success is 200 with a freshly generated `railReference`
(a new opaque id on **every** accepted call, never a previously issued one) and `acceptedAt`.

**Behaviour matrix**, applied per call in this order:

| Step | Rule |
|---|---|
| 1. Latency | Sleep `rail.stub.latency-ms` before doing anything else. This is what expires the caller's lease. |
| 2. Failure | With probability `rail.stub.failure-rate`, fail instead of accepting. Failures manifest two ways in roughly equal share: a 5xx response, and holding the connection then dropping it without a response (the caller sees a timeout/reset). Both are *ambiguous* from the outside — that is the intent. |
| 3. Duplicate | If this `clientRef` has been seen before: with probability `rail.stub.duplicate-ack-rate` accept it **again** and return a *new* `railReference`; otherwise return 402 declined. Under no circumstance return the original reference, and never 200 with the same reference twice. |
| 4. Otherwise | Accept: 200 with a new `railReference`. |

**The `clientRef` memory is not deduplication.** The stub keeps an in-process set of seen refs solely to
apply the duplicate rate; it never uses it to suppress a second payment. State is in-memory, unbounded
in principle but capped by a bounded structure, and lost on restart — that is fine and should be stated
in the README, because a rail with durable memory would be a rail with idempotency.

**Determinism in tests without new config.** Drive behaviour by setting the three rates to `0.0` or
`1.0`, and by injecting a test double for `ChaosDecider`. Do **not** add a seed, clock or profile key to
`rail.stub.*` — that is a contract change (§2). Keep the randomness source behind `ChaosDecider` so the
controller itself is deterministic and testable.

**Validation.** Both rates are `[0.0, 1.0]`; latency is `>= 0`; out-of-range values must fail fast at
startup, not silently clamp. Defaults come from C5 `#ct5-config`; if that pins values, use them
verbatim, otherwise a quiet default (no latency, no failures, no duplicate acceptance) so a plain run
is boring and the experiments turn the knobs explicitly.

**Test cases**, in prose: with all rates 0 and one fresh `clientRef`, 200 and a well-formed reference;
two calls with the *same* `clientRef` and `duplicate-ack-rate=1.0` yield two 200s with two **different**
references (this is the FR-21 proof and should be named as such in the test); the same pair with
`duplicate-ack-rate=0.0` yields 200 then 402; `failure-rate=1.0` yields a failure and never a 200;
`latency-ms=250` measurably delays the response; a request carrying all three custom headers behaves
identically to one carrying none; an out-of-range rate fails context startup.

## 5. Acceptance criteria

1. `rail-stub` exposes exactly one path, `POST /submit`.
2. No occurrence of `X-Fencing-Token`, `X-Owner-Id`, `X-Idempotency-Key`, `fence`, `token` or `lock` in
   `rail-stub/src/main` (case-insensitive), beyond the README's explanatory prose.
3. `rail-stub/build.gradle.kts` declares no dependency on any other project in the repo.
4. Only the three contracted `rail.stub.*` keys exist; no fourth key.
5. Every accepted call returns a distinct `railReference`; the duplicate test asserts inequality.
6. The duplicate-acceptance test is named so it is obvious it is the non-idempotence proof.
7. Failure injection produces both a 5xx and a no-response manifestation, each covered by a test.
8. `./gradlew :rail-stub:check` green, Spotless clean.

## 6. Verification

- `./gradlew :rail-stub:test` → green, the seven cases in §4.
- `grep -rniE 'fencing|idempot|x-owner|lock' rail-stub/src/main` → no output.
- `grep -rn "project(" rail-stub/build.gradle.kts` → no output.
- `grep -rn 'rail\.stub\.' rail-stub/src/main rail-stub/src/main/resources | sort -u` → exactly three keys.
- `./gradlew :rail-stub:bootRun --args='--rail.stub.duplicate-ack-rate=1.0'` then twice:
  `curl -s -X POST localhost:8090/submit -H 'content-type: application/json' -d '{"clientRef":"c-1","amountMinor":1000,"currency":"EUR"}'`
  → two 200s with two different `railReference` values.
- Same with `--rail.stub.latency-ms=250` and `curl -w '%{time_total}\n'` → ≥ 0.25 s.

## 7. Out of scope

`rail-proxy` — the submission record, the persisted high-water mark, enforcement point (c), and
`RAIL_AMBIGUOUS` classification all belong to the proxy tasks, not here. The executor's retry policy.
Any `rail_submission` or `rail_high_water` write. Containerisation and Kubernetes manifests (M5).
Metrics beyond whatever C4 `#ct4-metrics` explicitly names for this module.

## 8. Hazards

- **The single biggest trap is "fixing" the stub.** A future session will notice duplicate payments and
  be tempted to dedupe by `clientRef`; C3 `#ct3-railstub` states a project that did so would prove nothing.
  The README must say this in the imperative.
- Returning the *same* `railReference` for a repeat `clientRef` is accidental idempotency and defeats
  the experiment as surely as an explicit dedupe.
- A blocking sleep is intended here; on Boot 4.1 with virtual threads it will not starve the pool, but
  do not "optimise" it into an async delay that fails to hold the connection open.
- The dropped-connection case must produce no response body at all; returning 503 for it collapses two
  distinguishable client experiences into one and hides the ambiguity the project studies
  (C3 `#ct3-ambiguity`: `RAIL_UNAVAILABLE` vs `RAIL_AMBIGUOUS`).
- Latency default must not be nonzero, or every downstream test in M2–M4 slows down mysteriously.

## 9. On completion

Mark T-023 done in `tasks/README.md` and note the stub's port and the default rate values there, since
the executor and harness tasks configure against them.
