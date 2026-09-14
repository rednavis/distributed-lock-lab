# T-026 — The two fencing kill switches

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M2 · **Estimate** 25 min
**Preconditions** — T-020..T-022 (`payment-resource` with the conditional `UPDATE … WHERE fence < :token` and ledger posting), **T-024** (rail-proxy high-water gate), **T-025** (executor driving the full path). You inherit a system that is *correct*: both fences are hard-wired on and there is no way to observe what they are buying.
**Goal** — Wire `payment.fencing.enabled` and `rail.proxy.fencing.enabled` end to end so each fence can be disabled at runtime with nothing else changed, and make disabling loudly visible.

## 1. Why this task exists

An unbreakable system teaches nothing: a reader cannot tell whether the fence prevents corruption or whether the corruption was never possible. These two flags make the counterfactual runnable — same lock service, same executor, same chaos, only the fence removed — and that A/B is the entire evidentiary value of the project (`#ct5-killswitches` calls them the most valuable configuration in the repository). They also make each enforcement point separately falsifiable, which matters because the two fences protect different things in different processes.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| Both key names, types, defaults (`true`), owning module, and that both are **runtime-togglable without redeploy** | `docs/contracts/C5-config-build-and-naming.md#ct5-config` |
| Exactly what each switch changes, off vs on, and which enforcement point it belongs to | `docs/contracts/C5-config-build-and-naming.md#ct5-killswitches` |
| Env-var forms `PAYMENT_FENCING_ENABLED`, `RAIL_PROXY_FENCING_ENABLED` | `docs/contracts/C5-config-build-and-naming.md#ct5-env` |
| The fenced statement whose predicate is dropped when off, and the (a)/(b) failure classification | `docs/contracts/C1-database-schemas.md#ct1-fenced` |
| The high-water check that is skipped when off — while the mark is **still recorded** | `docs/contracts/C3-http-surfaces.md#ct3-railproxy` |
| `lock.fenced.out` is a must-be-zero counter *when fencing is on* | `docs/contracts/C4-observability.md#ct4-zero` |
| Log event naming (snake_case, past tense) for the startup warning | `docs/contracts/C5-config-build-and-naming.md#ct5-naming`, `C4#ct4-logs` |
| Anything not on the config list is deliberately fixed — do not add a third switch | `docs/contracts/C5-config-build-and-naming.md#ct5-fixed` |

**Precedence:** if this spec and a contract disagree, the **CONTRACT wins** — stop and report both wordings. In particular, do not "improve" a key name or default.

## 3. Deliverables

| Path | What |
|---|---|
| `payment-resource/src/main/java/dev/lock/payments/resource/FencingProperties.java` | `@ConfigurationProperties` for `payment.fencing.enabled`, default `true` |
| `payment-resource/.../store/AccountStore.java` (modify) | Two named statements — fenced and unfenced — selected per call from the property; no string concatenation of the predicate |
| `payment-resource/.../FencingModeReporter.java` | Startup log + `Actuator` info contribution stating the effective mode |
| `rail-proxy/.../RailFencingProperties.java` | `@ConfigurationProperties` for `rail.proxy.fencing.enabled`, default `true` |
| `rail-proxy/.../admission/AdmissionService.java` (modify) | When off: still upsert/record the mark, skip the comparison, never return `FENCED_OUT` |
| `rail-proxy/.../FencingModeReporter.java` | Same startup reporting |
| both `application.yml` (modify) | Keys declared with default `true` and a comment naming the harness scenario that flips them |
| `docs/08-operations.md` (append one short subsection) | The operator note: how to flip, and that flipping off in anything but a harness run is an incident |
| `harness/src/main/resources/fencing-matrix.md` *or* an equivalent fixture list | The four-cell matrix (on/on, off/on, on/off, off/off) with the expected corruption in each cell — text only, scenarios are M4 |

## 4. Specification

**`payment.fencing.enabled` (enforcement point (a)).** On: the pinned conditional update; a stale writer matches zero rows, and the diagnostic `SELECT` classifies fenced-out vs insufficient funds. Off: the same update **without** the `fence < :token` predicate, so a stale writer overwrites a newer balance and `ledger_entry.fence` records a token below `account.fence`'s prior value. The ledger legs must still be written — the corruption we want to demonstrate is an unbalanced ledger and a lost balance, not a missing row. Selection happens per statement execution from the injected property so a config refresh takes effect without restart; do **not** branch by building SQL strings at runtime, and do not use a Spring profile (a profile is a redeploy).

**`rail.proxy.fencing.enabled` (enforcement point (c)).** On: admission step 1 rejects a token not strictly greater than the mark. Off: the mark is **still advanced and recorded** — otherwise the off-run leaves no evidence of what the on-run would have blocked — but the comparison result is ignored and the submission proceeds to the rail. The duplicate-per-payout check (step 2) and the intent-before-socket rule (step 3) are **not** governed by this switch and must keep working when it is off; this switch removes the *stale-token* defence only.

**Loudness.** Each service logs at WARN once at startup when its switch is off, with an event name in the pinned snake_case past-tense style and a field naming the disabled enforcement point; when on, one INFO line. The `/actuator/info` payload exposes the effective boolean for both, so a harness run can record the configuration it actually ran under rather than the configuration someone believes it ran under.

**No third switch.** There is no master flag, no per-account override, no "warn-only" mode. Two booleans, two enforcement points.

## 5. Acceptance criteria

1. `./gradlew :payment-resource:build :rail-proxy:build` passes, Spotless clean.
2. `grep -rn "payment.fencing.enabled\|rail.proxy.fencing.enabled" --include=*.java --include=*.yml` shows each key read in exactly one owning module and declared in that module's `application.yml`.
3. Both properties default to `true` when absent from config — proven by a test that binds an empty environment.
4. `PAYMENT_FENCING_ENABLED=false` and `RAIL_PROXY_FENCING_ENABLED=false` are honoured (env-var binding test, not just the dotted key).
5. Payment fencing off: a submission with a token lower than `account.fence` succeeds, `account.fence` moves **backwards**, and `sum(ledger_entry.amount_minor)` no longer equals `balance_minor` — asserted by a test that reads paydb.
6. Payment fencing on: the same input yields `FencedOutException` and `lock_fenced_out_total{resource="account"}` increments.
7. Rail fencing off: a stale token reaches the stub (stub call log shows the request) **and** `rail_high_water.highest_token` still reflects the highest token seen.
8. Rail fencing off does **not** disable duplicate detection: a repeat `payoutId` still returns 409 `DUPLICATE_SUBMISSION`.
9. Each service emits its WARN startup event when its switch is off; asserted with a log-capturing test.
10. `/actuator/info` on both services reports the effective flag value.
11. No new configuration key beyond these two appears in either `application.yml` (`#ct5-fixed`).

## 6. Verification

```
./gradlew :payment-resource:test :rail-proxy:test
PAYMENT_FENCING_ENABLED=false ./gradlew :payment-resource:bootRun
RAIL_PROXY_FENCING_ENABLED=false ./gradlew :rail-proxy:bootRun
curl -s localhost:8082/actuator/info | grep -i fenc
curl -s localhost:8083/actuator/info | grep -i fenc
psql "$PAYDB_URL" -c "select balance_minor, fence from account"
psql "$PAYDB_URL" -c "select (select sum(amount_minor) from ledger_entry where direction='DEBIT') as debits,
                             (select sum(amount_minor) from ledger_entry where direction='CREDIT') as credits"
curl -s localhost:8082/actuator/prometheus | grep lock_fenced_out_total
```
Expected: WARN lines on both boots; with the switches off, a fence that has moved backwards and debits ≠ credits vs the balance; with them on, `lock_fenced_out_total` incrementing instead.

## 7. Out of scope

The harness scenarios and the local fencing experiment that *run* the matrix (T-042 and M4); the benchmark write-up (M7); ConfigMap/Deployment plumbing of these env vars on GKE (M5); the M2 integration test (T-027) — it asserts the on-state only.

## 8. Hazards

Implementing "off" as a Spring profile or a build flag — the contract requires a runtime toggle without redeploy. Letting the rail switch also disable duplicate detection or the intent-row ordering, which conflates two independent defences and makes the off-run prove the wrong thing. Forgetting to keep advancing `rail_high_water` when off, which destroys the comparison the report needs. Shipping either switch off, or flipping one to silence an alert: `#ct5-killswitches` — they must never be off outside a harness run, and both must be re-enabled and re-verified afterwards.

## 9. On completion

Mark T-026 done in `tasks/README.md`, and record in the notes that both switches were restored to `true` on disk.
