# T-027 — Integration test: no duplicate submission, ledger balances

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M2 (closes it) · **Estimate** 30 min (Testcontainers startup dominates; if the two-executor contention case does not fit, land the single-executor cases and note the gap for T-042)
**Preconditions** — T-024 (rail-proxy fence), T-025 (executor state machine), T-026 (both kill switches wired, restored to `true`). You inherit four services that each pass their own tests and have never been run together against one database.
**Goal** — Prove, in one automated test class, that the assembled M2 path submits each payout to the rail at most once and leaves a ledger whose entries sum to the balance.

## 1. Why this task exists

Every M2 module has been verified alone, which is exactly the condition under which a fence looks fine and the system still pays twice: the defects live at the seams — the claim/lease boundary, the token handed from grant to proxy, the ordering of intent row and socket. This task turns the milestone's claim into a checked invariant and produces the `rail_submission` audit trail that later milestones and the write-up quote as evidence.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| INV-01 `balance = sum(ledger)`, INV-02 one forwardable attempt per payout, INV-03 no partial leg set | `docs/contracts/C1-database-schemas.md#ct1-paydb`, `#ct1-fenced` |
| `ledger_leg_uq`, `rail_submission_attempt_uidx`, `account_balance_ck` as the schema-level guards | `docs/contracts/C1-database-schemas.md#ct1-paydb` |
| `P5 GET /v1/accounts/{id}` returns `ledgerSumMinor` recomputed and it must equal `balanceMinor` | `docs/contracts/C3-http-surfaces.md#ct3-pay` |
| Admission order and the outcome vocabulary written to `rail_submission` | `docs/contracts/C3-http-surfaces.md#ct3-railproxy` |
| `RAIL_AMBIGUOUS` is terminal for every worker and every token | `docs/contracts/C3-http-surfaces.md#ct3-ambiguity` |
| `rail.duplicate.attempted` and `lock.fenced.out` are must-be-zero with fencing on | `docs/contracts/C4-observability.md#ct4-zero` |
| Testcontainers/JUnit 5/Awaitility versions from the catalog only | `docs/contracts/C5-config-build-and-naming.md#ct5-catalog` |
| Both switches remain `true` for this task's assertions | `docs/contracts/C5-config-build-and-naming.md#ct5-killswitches` |

**Precedence:** if this spec and a contract disagree, the **CONTRACT wins** — stop and report both wordings; do not adjust an assertion to match the code.

## 3. Deliverables

| Path | What |
|---|---|
| `harness/src/test/java/dev/lock/harness/M2EndToEndIT.java` | The test class; the four scenarios below |
| `harness/src/test/java/dev/lock/harness/support/LabStack.java` | Testcontainers composition: one PostgreSQL 16 container, both Flyway trees applied, the four services started in-process on random ports with wired base URLs |
| `harness/src/test/java/dev/lock/harness/support/InvariantChecker.java` | Reusable SQL-backed invariant assertions (INV-01/02/03 + no negative balance), returning a report string, not a bare boolean |
| `harness/src/test/java/dev/lock/harness/support/Seeds.java` | Builders for one account with a known balance and N `PENDING` payouts |
| `harness/build.gradle.kts` (modify) | Test deps from the catalog; depends on all M2 modules |
| `docs/07-correctness-and-testing.md` (append) | One row per scenario mapping it to the invariant it proves |

## 4. Specification

**Stack.** One Postgres container hosting both logical databases (separate schemas or databases in one container is acceptable *for tests only* — the two-instance rule is a production/ADR-003 concern, and the test must not assume a shared transaction between them). Services run in-process with the real HTTP clients, not mocks; the rail stub is the only fake, and it stays hostile.

**Scenarios.**

| # | Setup | Must hold |
|---|---|---|
| S1 healthy | 1 account, 5 payouts, stub latency low, failure rate 0 | All 5 `POSTED`; 5 `ACKED` `rail_submission` rows and no others; 10 `ledger_entry` rows; `balance_minor` = opening − Σ debits; `rail.duplicate.attempted` = 0; `lock.fenced.out` = 0 |
| S2 ambiguity | stub latency above the proxy read timeout for one payout | That payout is `RAIL_AMBIGUOUS`; exactly one row, `outcome='TIMEOUT'`, `resolved_at` set; no ledger rows for it; the balance is unchanged by it; `payout.execute{outcome=ambiguous}` = 1 |
| S3 replay pressure | after S2, run the executor loop three more passes | Still `RAIL_AMBIGUOUS`; `rail_submission` count for that payout is still **1**; `rail.duplicate.attempted` still 0 (nothing was even attempted, because the loop must not select the state) |
| S4 duplicate attempt | force a second submission for an already-forwarded `payoutId` by calling the proxy directly with a higher token | 409 `DUPLICATE_SUBMISSION`; `rail.duplicate.attempted` = 1; the stub's call count is unchanged; a `duplicate_rail_submission_attempted` log event with `firstSubmittedAt` |

S4 is the one place `rail.duplicate.attempted` is allowed to be non-zero, and the test must say so explicitly — elsewhere a non-zero value fails the run.

**Assertions are SQL-first.** Read the truth from paydb: `payout.state`, `rail_submission` grouped by `payout_id` and `outcome`, `ledger_entry` summed per direction, `account.balance_minor` and `fence`, `rail_high_water.highest_token`. Cross-check the same facts through `P5` so a divergence between the recomputed `ledgerSumMinor` and the stored balance fails loudly. Use Awaitility for the asynchronous loop, with a bounded timeout — never `Thread.sleep` as the synchronisation mechanism.

**Failure output is the deliverable, not the pass.** When an invariant breaks, the checker must print the offending rows — payout id, state, per-attempt outcome and token, the debit/credit sums and the balance — so a reader of CI output can see *how* the system paid twice without opening psql. A test that only says "expected true but was false" is not acceptable here.

**Stub call log.** Assert against the stub's own record of inbound calls, not against proxy logs; "the rail was never called" is only meaningful if the rail says so.

## 5. Acceptance criteria

1. `./gradlew :harness:test --tests '*M2EndToEndIT*'` passes from a clean state, twice in a row, with no manual setup.
2. S1..S4 each exist as a separately named `@Test` and each fails independently when its invariant is broken.
3. `InvariantChecker` fails S1 if a `ledger_entry` leg is deleted, if `balance_minor` is nudged, or if a second `ACKED` row is inserted for one payout — proven by three deliberately corrupted fixtures (or documented negative-control runs).
4. Total `rail_submission` rows with `outcome IN ('ACKED','TIMEOUT')` per `payout_id` is ≤ 1 in every scenario, asserted by a query, not by counting in Java memory.
5. Σ DEBIT = Σ CREDIT across `ledger_entry` at the end of every scenario.
6. `rail_duplicate_attempted_total` is 0 after S1–S3 and 1 after S4.
7. `lock_fenced_out_total` is 0 across all four scenarios (both switches on).
8. The test asserts both kill switches read `true` at startup and aborts if not.
9. CI runs this class; the workflow name and job are visible in `.github/workflows`.
10. `docs/07-correctness-and-testing.md` names each scenario against its invariant.

## 6. Verification

```
./gradlew :harness:test --tests '*M2EndToEndIT*' --info
./gradlew :harness:test --tests '*M2EndToEndIT*'          # second run, same result
psql "$PAYDB_URL" -c "select payout_id, count(*) filter (where outcome in ('ACKED','TIMEOUT')) from rail_submission group by payout_id having count(*) > 1"
psql "$PAYDB_URL" -c "select direction, sum(amount_minor) from ledger_entry group by direction"
curl -s localhost:8083/actuator/prometheus | grep rail_duplicate_attempted_total
```
Expected: green twice; the `having` query returns **zero rows**; the two direction sums are equal; the duplicate counter matches the scenario expectation.

## 7. Out of scope

The etcd backend and re-running these scenarios against it (M3); the kill-switch-off corruption run and the fencing experiment (T-042 and M4); chaos injection beyond the stub's three knobs (M4); latency benchmarking (M7); running any of this on GKE (M5). Do not add production code here — if a scenario cannot be expressed, that is a finding for the ledger, not a licence to patch a module.

## 8. Hazards

Asserting on proxy logs instead of the stub's call log, which passes even when the rail was called. Sharing one transaction across lockdb and paydb in the test harness, which makes the fence look stronger than it is (`#ct1-scope`). `Thread.sleep` tuned until green — it will flake in CI and hide a real ordering bug. Re-seeding between scenarios without resetting `rail_high_water`, which silently fences later scenarios and produces confusing `FENCED` rows. Treating a non-zero `lock.fenced.out` as noise: with fencing on, one occurrence is incident-grade (`#ct4-zero`).

## 9. On completion

Mark T-027 done in `tasks/README.md` and record M2 as closed, listing any scenario deferred to T-042 and the reason.
