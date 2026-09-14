# T-062 — Structured JSON logging

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M6 — Observability and SRE · **Estimate** 30 min (eleven events across five services; if
the existing call sites are string-concatenated `log.info` lines, do lock-server + rail-proxy first and
split payment-resource/executor/client into a follow-up)

**Preconditions** — T-060 (meters centralised per service) and T-061 (scrape proven), so telemetry
plumbing is live and only the log side is unstructured. Earlier tasks T-016, T-021, T-024, T-025, T-040
already log at the right places, but as prose messages with values interpolated into the text.

**Goal** — Emit all eleven C4 §4.5 events as JSON to stdout with their full field sets, so every value is
a queryable `jsonPayload` attribute rather than a substring of a message.

## 1. Why this task exists

An operator holding a `fenced_out` alert needs to answer "which account, which token was presented, which
token had already been recorded" in one query; if those values live inside a formatted sentence, the only
tool left is a regex over free text that breaks the next time someone rewords the message. Structured
fields are also the precondition for T-063: a log-based metric can only extract labels from fields, and
C4 §4.7 deliberately converts events into metrics rather than alerting on a message string.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| The eleven `event` names, their level, emitter, and full event-specific field list with types | [C4 §4.5](../docs/contracts/C4-observability.md#ct4-logs) |
| Common fields on every event: `timestamp`, `severity`, `service`, `event`, `traceId`, `spanId`, `ownerId` | [C4 §4.5](../docs/contracts/C4-observability.md#ct4-logs) |
| `fenced_out`, `duplicate_rail_submission_attempted`, `rail_ambiguous` are **ERROR**; the rest INFO/WARN as tabled | [C4 §4.5](../docs/contracts/C4-observability.md#ct4-logs) |
| No secrets, no full request bodies | [C4 §4.5](../docs/contracts/C4-observability.md#ct4-logs) |
| Identifiers belong in log fields, never in metric tags | [C4 §4.3](../docs/contracts/C4-observability.md#ct4-cardinality) |
| Which fields may later become labels — do not pre-empt it here | [C4 §4.6](../docs/contracts/C4-observability.md#ct4-promotion) |
| `service` values are the emitter names of the metric catalogue | [C4 §4.2](../docs/contracts/C4-observability.md#ct4-metrics) |
| Ambiguity semantics behind `rail_ambiguous` | [C3 §3.7](../docs/contracts/C3-http-surfaces.md#ct3-ambiguity) |

**Precedence:** if this spec and a contract disagree, the **contract wins** — stop, quote both, report.
A field C4 requires but the code cannot supply is a **stop**, not a `null` to be quietly omitted.

## 3. Deliverables

| Path | What |
|---|---|
| `deploy/k8s/<workload>/…` app config × 5 and each module's `src/main/resources/application.yaml` (modify) | Enable Spring Boot's native structured console logging in the GCP/JSON layout; keep human-readable console output in the local Compose profile only |
| `lock-server/src/main/java/dev/lock/server/core/LockEvents.java` | New. One method per lock-server event: `lock_granted`, `lock_released`, `lease_expired`, `lock_revoked` |
| `payment-resource/src/main/java/dev/lock/payments/resource/ResourceEvents.java` | New. `fenced_out` for `resource`=`account`\|`ledger` |
| `rail-proxy/src/main/java/dev/lock/rail/proxy/RailEvents.java` | New. `rail_submitted`, `rail_ambiguous`, `duplicate_rail_submission_attempted`, `fenced_out{resource=rail}` |
| `payout-executor/src/main/java/dev/lock/payments/executor/ExecutorEvents.java` | New. `payout_claimed`, `payout_posted` |
| `lock-client/src/main/java/dev/lock/client/ClientEvents.java` | New. `session_lost` (SLF4J only — lock-client stays free of Spring) |
| call sites in the five modules (modify) | Replace interpolated `log.*` lines with the `*Events` calls; delete the old message text |
| `lock-server/src/test/java/.../LogSchemaTest.java` (+ one slice in rail-proxy) | Captures emitted events and asserts the schema (§4) |
| `docs/06-observability-and-slo.md` (modify) | The eleven events as an operator table: event → severity → the one question it answers |

## 4. Specification

**Mechanism.** Use Spring Boot's built-in structured logging (`logging.structured.format.console`) so no
custom encoder or extra dependency is introduced, and attach event-specific fields as SLF4J key-value
pairs via the fluent API (`atError().addKeyValue(...)`). Do **not** build the JSON by hand, and do not put
values into the message: the message is a short constant human label, the fields carry the data. `lock-client`
must reach the same shape with SLF4J alone — it has no Spring dependency (C5 §5.4).

**Common fields.** `service` is a constant per module. `traceId`/`spanId` come from the active span
context (Micrometer tracing/OTel MDC); when there is no span they are omitted, never faked or blank.
`ownerId` is present on every lock-scoped event; on `fenced_out` it is the **rejected** caller.

**Per-event fields.** Implement exactly the C4 §4.5 columns, with the types as tabled: `token`,
`presentedToken`, `highestToken`, `leaseMillis`, `waitMillis`, `heldMillis`, `expiredAtMillis`,
`overdueMillis`, `lastRenewMillis`, `elapsedMillis` are **numbers**, not strings — a stringly-typed token
cannot be compared with `>` in a log query. `ledgerEntryIds` is an array. `railRef` is nullable and its
key is still emitted when null on `rail_submitted`/`rail_ambiguous`, because its absence is the signal.

**Naming.** Field keys are `lowerCamelCase` exactly as C4 spells them. A synonym (`lockKeyName`,
`fencingToken`) is a contract violation and breaks T-063's filters silently.

**Level discipline.** Only the three tabled events are ERROR. Do not additionally log a stack trace on
`fenced_out` — the fence firing is expected mechanics, and a stack trace invites treating it as a bug in
the writer rather than evidence of a zombie holder (C4 §4.4).

**The test.** `LogSchemaTest` uses a capturing appender (or `OutputCaptureExtension`) around one grant,
one release, one expiry and one fenced write; parses each line as JSON; asserts per event that the key set
equals *common ∪ event-specific*, that no extra key is present, that numeric fields deserialise as
numbers, and that no captured line contains a database password, a JDBC URL, or an `Authorization` value.

## 5. Acceptance criteria

1. All eleven event names appear exactly once as a string literal in the repo, each inside its owning `*Events` class; `grep -rn 'lock_granted\|fenced_out\|rail_ambiguous' --include=*.java` shows no other producer.
2. `grep -rn 'log\.\(info\|warn\|error\)(".*" *+' --include=*.java` returns nothing in the five modules — no interpolated log messages remain on these paths.
3. `LogSchemaTest` fails when any single required field is removed from `lock_granted` (verify by removing one, running, reverting).
4. Every emitted line parses as JSON and carries `severity`, `service`, `event`.
5. `token`-family fields deserialise as JSON numbers, asserted in the test.
6. Running the Compose stack locally produces human-readable logs; the GKE profile produces JSON — both from the same code, config-only difference.
7. `docs/06-observability-and-slo.md` has an eleven-row event table matching C4 §4.5 names and severities.

## 6. Verification

`./gradlew spotlessApply check` — green. Then with the local stack up and the `gke`-shaped profile forced:
`docker compose -f deploy/compose/compose.yaml logs lock-server | grep '"event":"lock_granted"' | head -1 | python3 -m json.tool`
— expect a single object containing `lockKey`, `token`, `backend`, `leaseMillis`, `waitMillis`, plus the
common fields. Run the T-042 fencing scenario, then
`... logs | grep '"event":"fenced_out"' | python3 -m json.tool` — expect `presentedToken` < `highestToken`
as numbers and `severity: "ERROR"`. On the cluster:
`gcloud logging read 'jsonPayload.event="fenced_out"' --project dlock-lab --limit 1` — expect one entry
with `jsonPayload` fields, not a single `textPayload` string.

## 7. Out of scope

Log-based metrics, log sinks, log buckets and retention (T-063 owns the Terraform). Alert policies and
SLOs (T-064+). Trace export configuration and span attributes. Do not promote any field to a label here —
promotion is a T-063 decision governed by C4 §4.6.

## 8. Hazards

- Values inside the message string are invisible to `jsonPayload` queries — the whole point of the task.
- Emitting a token as a string quietly disables numeric comparison in Log Explorer and in T-063 filters.
- A renamed field is a silent break: T-063's filters match on `jsonPayload.event` and label extractors
  read named fields, and a miss returns empty rather than erroring (C4 §4.7).
- `lock-client` must not gain a Spring dependency to get JSON logging; the host application configures it.
- Never log a datasource password or a full rail request body (C4 §4.5).

## 9. On completion

Mark the T-062 row done in `tasks/README.md`. List any event you could not populate fully and the missing
field, and state which structured-logging format value was used for the GKE profile.
