# T-067 — OpenTelemetry tracing

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M6 · **Estimate** 30 min for four services if instrumentation is autoconfigured and only attributes and sampling are hand-written. **If any service still lacks an OTel exporter dependency, this exceeds 30 min** — split: T-067a wiring + steady-state sampling in all four services, T-067b the seven span names/attributes and the 100 %-on-error rule.

**Preconditions** — T-025 (payout-executor orchestrates the four hops), T-022/T-024 (payment-resource and rail-proxy enforce the two fence points), T-040/T-041 (client SDK propagates the token as a parameter/header), T-056…T-059 (all four services deployed), T-064…T-066 (metrics-side observability complete). You inherit a system that is fully measured **in aggregate** and cannot answer a question about one payout.

**Goal** — Emit the seven spans of C4 §4.8 across the four services with `lock.token` on every span, 1 % parent-based head sampling in steady state, and 100 % retention on error paths.

## 1. Why this task exists

Metrics are aggregates by construction, but a fencing incident is a claim about **one** token's history across four processes: issued here, accepted there, rejected over there. Doc 06 §6.9 makes filtering on `lock.token` the whole point — without it one incident becomes four unrelated traces. Sampling is the other half: 1 % is ample for latency shape and unaffordable for a five-a-month event, so the rare-and-critical population is exempted from sampling rather than being hoped for.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| The seven span names, their parent, owning service, and required attributes | `docs/contracts/C4-observability.md#ct4-traces` (authority — do not rename a span) |
| `lock.token` on **every** span; span attributes are exempt from the cardinality budget | `#ct4-traces`, `#ct4-cardinality` |
| The three events that force retention: `fenced_out`, `rail_ambiguous`, `duplicate_rail_submission_attempted` | `#ct4-logs`, `#ct4-zero` |
| Token reaches a callee as an explicit parameter or header — **never** a thread-local | `docs/contracts/C2-java-api.md#ct2-propagation` |
| `X-Fencing-Token`, `X-Owner-Id`, `X-Idempotency-Key` on the wire | `docs/contracts/C3-http-surfaces.md#ct3-conventions` |
| Sampling rates: 1 % steady state, 100 % error paths, 100 % time-boxed for benchmark/game day | doc 06 `#obs-traces` |
| `otel-bom` version, config-key naming, no new hard-coded value | `docs/contracts/C5-config-build-and-naming.md#ct5-catalog`, `#ct5-config`, `#ct5-fixed` |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report** (doc 04 §4.4). A span name absent from `#ct4-traces` is a contract amendment, not a local decision.

## 3. Deliverables

| Path | What |
|---|---|
| `gradle/libs.versions.toml` (modify) | Add the OTel exporter/instrumentation aliases needed alongside the existing `otel-bom` — versions only here |
| `build-logic/…` convention plugin (modify) | Apply the tracing dependency set once, to the four service modules only; `lock-api` stays zero-dependency (`#ct2-zero-dep`) |
| `lock-server`, `payment-resource`, `rail-proxy`, `payout-executor`, `rail-stub` — a small tracing support class per service (annotation/aspect or a thin span helper) | Creates the spans and sets the required attributes; **no business logic moves into it** |
| `application.yaml` in each service (modify) | Sampler = parent-based with a configurable ratio, exporter target, service name; the ratio is a config key with default `0.01` |
| `deploy/k8s/*` (modify) | Exporter endpoint + sampling-ratio env vars per workload; the game-day override documented, not hard-coded to 1.0 |
| `docs/06-observability-and-slo.md` (modify §6.9) | Replace the stale task reference (currently T-065) with T-067; leave the rates unchanged |
| `docs/08-operations.md` (modify) | Add ≤ 10 lines: how to raise sampling to 100 % for a time-boxed run and how to filter Trace Explorer by `lock.token` |

## 4. Specification

**Spans.** Exactly the seven rows of C4 §4.8 — `payout.execute` (root, executor), `lock.acquire`, `payment.claim`, `rail.submit`, `rail.stub.process`, `ledger.post`, `lock.release` — with the parent relationships as pinned there. One payout attempt is one trace; a retry is a new attempt and therefore carries `payout.attempt`.

**Attributes.** Set every required attribute from the C4 table, plus `lock.token` on all seven. Notes that matter: `fence.result` on `payment.claim` and `ledger.post` takes `accepted`|`fenced`; `rail.submit` records `fence.highest_token` (the persisted high-water mark it compared against, per ADR-007) alongside the presented token — the pair is what makes a fencing decision legible; `lock.release` records `lock.held_ms` and `lock.release_reason`, and `lock.held_ms` is the §6.6 leading indicator, so it must be set even on the abnormal release paths.

**Context propagation.** W3C `traceparent` on every hop, plus the C3 headers. The fencing token travels as `X-Fencing-Token` and as a span attribute — it must **not** be read back out of the span or a thread-local to make a fencing decision (`#ct2-propagation`); the span is a copy for humans, never the source of truth.

**Sampling.** Parent-based head sampling everywhere, ratio `0.01` at the root. State in a comment why parent-based is mandatory: an independent decision per service keeps a trace in one process and drops it in the next, and a broken trace is worse than none. **Error paths are 100 %:** any span with error status, and any trace containing one of the three events above, is retained. Implement it as a sampler that cannot un-sample a finished trace by luck — the pragmatic form is to force the sampling decision **at span creation** the moment the process knows it is on an error/fencing path (set the recording decision on the local root before the outcome is returned), and to document the residual limitation if a decision is discovered too late. No tail sampling: it needs a buffering collector, deliberately out of scope (§6.9).

**Cost guard.** One sentence in the runbook addition: a forgotten 100 % override is the cheapest way to make this project expensive.

## 5. Acceptance criteria

1. All seven C4 §4.8 span names appear in the codebase and nowhere is an eighth span name introduced (grep the span constants against the contract table).
2. `lock.token` is set on all seven spans — verified per span in code and once end-to-end in a real trace.
3. A local end-to-end payout (compose stack) produces **one** trace with all seven spans and the pinned parent-child shape.
4. `payment.claim` and `ledger.post` carry `fence.result`; `rail.submit` carries both `lock.token` and `fence.highest_token`.
5. Steady-state sampling ratio is a config key defaulting to `0.01`, present in each service's config and overridable by env var per `#ct5-env`.
6. Re-running the T-042 fencing demo yields a trace containing `fenced_out` that is **retained** even with the ratio at 0.01, and the trace shows the lower token rejected after the higher one was accepted.
7. `lock-api` gained no dependency (`#ct2-zero-dep`); check its build file.
8. No fencing decision reads a token from a span or thread-local (grep for thread-local usage in the three enforcing services returns nothing).
9. Full build and existing tests pass; Spotless clean.

## 6. Verification

```
./gradlew spotlessApply build
docker compose up -d && ./gradlew :harness:runOnePayout   # or the T-042 script for the fenced case
# local exporter: confirm 7 spans in one trace, then in-cluster:
kubectl -n dlock get deploy -o jsonpath='{range .items[*]}{.metadata.name}{" "}{end}'
gcloud trace list --project dlock-lab --limit 5
```
In Cloud Trace, filter by `lock.token=<token from the demo>` and expect the complete life of one grant across four services. Then run ~200 payouts at ratio 0.01 and expect roughly 2 traces, plus **every** fenced/ambiguous run present regardless.

## 7. Out of scope

New metrics or log events (C4 owns them; T-060…T-063 emitted them), alert policies and dashboards (**T-064…T-066**), game-day detection latency (**T-068/069**), tail sampling and a collector deployment (explicitly rejected in §6.9), and benchmark-run 100 % sampling operations (**M7**).

## 8. Hazards

The trap in this task is a sampling decision made **after** the interesting thing happened: an outcome discovered at the end of `rail.submit` cannot retroactively sample a trace whose root already declined — decide at the local root, and if a path genuinely cannot know in time, document it rather than claiming 100 % you do not have. Second: mixing propagators or leaving one service on a non-parent-based sampler silently shreds traces at exactly the four-process boundary this task exists to cross. Third: `lock.token` is cheap on spans but **not** on metrics — do not let the attribute leak into a metric tag (`#ct4-cardinality`).

## 9. On completion

Mark the T-067 row done in `tasks/README.md`. Record the measured trace count for the 200-payout run, any path where the error-retention decision is best-effort rather than guaranteed, and the doc-06 §6.9 task-id correction.
