# T-021 — payment-resource: account and ledger repositories

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M2 — protected resource and payout executor · **Estimate** 30 min (tight; if the
integration test set is not green at 30, stop and land the repositories plus the fenced-out and
happy-path cases, and record the remaining cases as T-021b in `tasks/README.md`)

**Preconditions** — T-020 (the `paydb` V1 migration exists and `PaydbMigrationIT` is green) and
T-010…T-017 (M1 supplied `lock-api` with the exception hierarchy, and the Testcontainers + Spring
JDBC patterns). You inherit `payment-resource` as a module with a schema and no persistence code.

**Goal** — Implement the conditional, fenced write path over `account`, `ledger_entry` and `payout` so
that a stale token can change nothing, and a zero-row result forces the caller to abort rather than
retry.

## 1. Why this task exists

This is fencing enforcement point **(a)** — the half of the safety argument that lives in a process the
lock service cannot influence (ADR-007). The whole project turns on one detail: the update predicate is
`fence < :token`, so a token equal to the stored fence *loses*, and zero affected rows is the only
signal a loser ever gets. Getting the zero-row obligation wrong — retrying, or re-acquiring and
continuing — converts a correctly fenced system into a double-paying one.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| Statements (a) account debit + fence advance, (b) ledger legs, (c) payout transition | `docs/contracts/C1-database-schemas.md#ct1-fenced` |
| What zero rows means per statement and what the caller must do | C1 `#ct1-fenced` (the "0 rows means / Caller must" table) |
| Table and column names | C1 `#ct1-paydb` |
| `FencedOutException` — where it lives, its semantics, that it is terminal and never retryable | `docs/contracts/C2-java-api.md#ct2-exceptions` |
| Token arrives as an explicit parameter, never a thread-local | C2 `#ct2-propagation` |
| Thread-safety and nullability of the types you add | C2 `#ct2-threading` |
| `payment.fencing.enabled` kill switch behaviour | C5 `#ct5-killswitches` |
| Counter `lock.fenced.out{resource=…}` and log event `fenced_out` field set | `docs/contracts/C4-observability.md#ct4-metrics`, `#ct4-logs`, `#ct4-zero` |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report**, quoting both
(`docs/04-contracts.md#c-precedence`). If a contract is silent on a name you need, stop; do not invent.

## 3. Deliverables

Paths are module-relative under `payment-resource/src/main/java/…/` with the package per C5
`#ct5-naming`; keep persistence types in one package and the transactional component beside them.

| Deliverable | What it is |
|---|---|
| `AccountRepository` | Statement (a) as one method returning the post-update balance and fence, plus the diagnostic read `SELECT fence, balance_minor` used only to classify a zero-row (a), plus the P5 read including a recomputed ledger sum. |
| `LedgerEntryRepository` | Statement (b), invoked once per leg (DEBIT then CREDIT), returning the generated `entry_id` or empty. |
| `PayoutRepository` | Claim read/transition for P2, statement (c) for P3, the `state=PENDING` listing query with keyset paging, and a single-payout read. |
| `PostingService` (name at your discretion, one per module) | The `@Transactional` composition: (a) → both legs of (b) → (c) in **one** transaction; classification and exception raising; the `fenced_out` log event and `lock.fenced.out` counter. |
| `payment-resource/src/test/java/…/FencedWriteIT.java` | Testcontainers integration test for the cases in §4. |
| `payment-resource/build.gradle.kts` | Add the `lock-api` project dependency and Micrometer; catalog only. |

## 4. Specification

**Statement text.** Use the SQL from C1 `#ct1-fenced` character-for-character in intent: explicit SQL
via Spring's `JdbcClient`. No JPA, no Hibernate, no dirty-checking layer — an ORM flush may reorder or
re-issue the write and the predicate is the safety property. Bind by the named parameters the contract
uses; never string-concatenate a token.

**Zero-row obligations**, exactly as C1 `#ct1-fenced` tabulates them:

| Statement returns 0 rows | Behaviour required |
|---|---|
| (a) | Roll back, then run the diagnostic read once. `fence >= presented` → `FencedOutException` carrying presented token, stored token, resource type `account`, resource id, owner id; log `fenced_out`; increment `lock.fenced.out{resource=account}`. Otherwise it is a funds rejection → payout `FAILED`, no exception of the fenced family. **No retry with a fresh token in either branch.** |
| (b) | Roll back the whole transaction — a partial leg set must never commit. `FencedOutException` with resource `ledger`. |
| (c) | Roll back; re-read `payout.state`. Already `POSTED` → succeed idempotently without posting again. Any other state → not-claimable, terminal. |

**Ordering.** (a) before (b) is load-bearing: (b)'s predicate `a.fence <= :token` only passes because
(a) advanced the fence to `:token`. Do not reorder, and do not collapse the two legs into one insert.

**Equality loses.** A token equal to the stored fence must be fenced out at (a). This deserves its own
test, because `<=` "looks safer" and silently readmits the previous holder.

**Kill switch.** With `payment.fencing.enabled=false` the fence predicates are dropped so the project can
*demonstrate* corruption (C5 `#ct5-killswitches`). Implement it as a distinct statement variant chosen
at the repository boundary — never by passing a sentinel token such as 0 (C3 `#ct3-errors`,
`INVALID_TOKEN`). Default is enabled; log a WARN at startup when it is off.

**`FencedWriteIT` cases**, described in prose: happy path posts two balanced legs and leaves
`balance = Σ entries`; a lower token is fenced out at (a) and mutates nothing; an equal token is fenced
out at (a); insufficient funds with a *winning* token classifies as a funds rejection, not fenced out;
a fence advanced by another connection between (a) and (b) rolls back with zero ledger rows persisted;
(c) against an already-`POSTED` payout succeeds without a third ledger row; the counter
`lock.fenced.out` increments exactly once per fenced-out call; with the kill switch off, the stale
token succeeds and the balance visibly corrupts (this test asserts the corruption, and is the project's
evidence).

## 5. Acceptance criteria

1. Each of statements (a), (b), (c) appears in exactly one place in the module.
2. Every fence comparison in production code is strict `<` at (a) and `<=` at (b)/(c) per C1; no other
   comparison operator appears against `fence` or `claim_token`.
3. No production code path retries a statement after a zero-row result.
4. `FencedOutException` is thrown with all five detail values named in C3 `#ct3-pay` available to the
   caller (presented, highest, resourceType, resourceId, ownerId).
5. `FencedWriteIT` contains all eight cases in §4 and is green.
6. Nothing in `payment-resource` reads or writes `rail_high_water`.
7. No JPA/Hibernate dependency appears in `payment-resource/build.gradle.kts`.
8. `./gradlew :payment-resource:check` green, Spotless clean.

## 6. Verification

- `./gradlew :payment-resource:test --tests '*FencedWriteIT'` → green, eight tests.
- `grep -rn 'fence *<=\|fence *=' payment-resource/src/main/java | grep -i update` → only the (b)/(c)
  predicates; statement (a) must show `fence *<`.
- `grep -rn 'rail_high_water' payment-resource/src/main` → no output.
- `grep -rniE 'hibernate|jakarta.persistence' payment-resource/build.gradle.kts` → no output.
- Run the kill-switch test alone and confirm the asserted balance is wrong by exactly one payout amount.

## 7. Out of scope

Controllers, DTOs, HTTP status mapping and the 409 body (T-022). The rail stub (T-023). The executor
that orchestrates claim → submit → post (T-024…T-027). Rail-proxy's high-water fence, enforcement
point (c) (M2/M3 per the plan, not here). Reconciliation (M4). Spans and dashboards (M6).

## 8. Hazards

- The (a) predicate **conflates** fenced-out with insufficient funds; the diagnostic read is mandatory
  and must be a separate read after rollback, not part of the same failed transaction.
- Never coerce a missing or malformed token to 0: 0 compares below every fence and reads as a benign
  no-op (C3 `#ct3-errors`).
- `lock.fenced.out` is a must-be-zero counter in a healthy run (C4 `#ct4-zero`) — never increment it
  for a funds rejection, or the alert becomes noise and the proof becomes unfalsifiable.
- Resource ids are logged, **never** used as metric tags (C3 `#ct3-pay`, C4 `#ct4-cardinality`).
- One transaction spans (a)(b)(c); a `@Transactional` on each repository method instead of the service
  breaks INV-03 the first time (b) fails.

## 9. On completion

Mark T-021 done in `tasks/README.md`, and record the chosen name of the transactional component there
so T-022 references it rather than guessing.
