# T-015 — forceRevoke and the revocation audit trail

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M1 — Postgres lock backend · **Estimate** 30 minutes (at the limit; if the token-floor
mechanism turns out to need a schema change, stop after §4 step 1 and hand the migration to a follow-up)

**Preconditions** — T-010…T-013 (lockdb migrations incl. `lock_revocation`, `PostgresLockStore` with insert /
extend / delete-if-owner / read, core `LockService`), T-014 (sweeper, `LockMetrics`, structured-logging
helper in place). You inherit a store that can grant and release but has no way for a human to take a
lock away from a wedged holder.

**Goal** — Implement `LockStore.revoke` and the `LockService` force-revoke path so a revocation
atomically deletes the grant, **advances the token floor**, and appends one `lock_revocation` row
naming the operator and the reason.

## 1. Why this task exists

An operator faced with a holder that will not die needs an eviction button, and the naive
implementation — delete the row — is actively dangerous: the evicted holder's in-flight write still
carries a token above the resource fence and lands anyway (`#ct3-lock`, failure mode L8). Revocation is
therefore a *token* operation, not a delete. And because it is the one privileged, unaudited-by-default
mutation in the system, it must be impossible to perform anonymously: operator identity and a
substantive reason are required arguments, not optional metadata.

## 2. Contracts to obey

| What | Pinned at |
|---|---|
| `LockOutcome revoke(String key, String operator, String reason)` and the "delete + advance floor + append `lock_revocation`, atomically" rule | `docs/contracts/C2-java-api.md#ct2-spi` |
| `LockService` force-revoke method, `operator` non-blank, javadoc semantics | `docs/contracts/C2-java-api.md#ct2-lockservice` |
| `LockOutcome` shape incl. the `revoked` decision | `docs/contracts/C2-java-api.md#ct2-records` |
| `lock_revocation` columns: `id`, `lock_key`, `prev_owner`, `prev_token`, `operator`, `reason`, `revoked_at`; append-only, never read on the hot path | `docs/contracts/C1-database-schemas.md#ct1-lockdb` |
| Token monotonicity per key — a token strictly greater than any ever issued, including revoked ones (INV-04) | `docs/contracts/C1-database-schemas.md#ct1-seq`, `#ct2-spi` |
| Log event `lock_revoked` — WARN, lock-server, fields `lockKey`, `token`, `revokedBy`, `reason` | `docs/contracts/C4-observability.md#ct4-logs` |
| Log-based metric `lock_revocations` derives from that event with labels `service`, `reason` | `docs/contracts/C4-observability.md#ct4-lbm` |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report, quoting both**
(`docs/04-contracts.md#c-precedence`). In particular, if C1 §1.2/§1.7 pin no column or sequence
mechanism by which the floor can be *advanced*, that is contract silence: **stop and request an
amendment** rather than inventing a column.

## 3. Deliverables

| Path | What |
|---|---|
| `lock-server/src/main/java/dev/lock/server/store/pg/PostgresLockStore.java` | implement `revoke` — one transaction: read holder, delete grant, advance floor, insert `lock_revocation` |
| `lock-server/src/main/java/dev/lock/server/core/DefaultLockService.java` | force-revoke entry point: validation, WARN `lock_revoked`, `LockOutcome` mapping |
| `lock-server/src/main/java/dev/lock/server/core/RevocationRecord.java` | immutable carrier of the four audit values + generated `id` (the `revocationId` L8 later returns) |
| `lock-server/src/test/java/dev/lock/server/store/pg/PostgresLockStoreRevokeTest.java` | unit-level test against the store seam (full Testcontainers matrix is T-017) |

## 4. Specification

1. **Floor advance first.** Read C1 `#ct1-seq` and `#ct1-lockdb` and determine, from the contract only,
   how a revoked key's floor is raised so a later `tryInsert` cannot issue a token at or below the
   revoked one. With a single global `fencing_token_seq` the floor rises for free; if the contract
   requires the advanced value to be *recorded*, record it exactly where the contract says. Report which
   reading you applied in §9.
2. **Atomicity.** Holder read, grant delete, floor advance and `lock_revocation` insert occur in one
   transaction. A partial revocation — audit row without eviction, or eviction without audit row — is a
   defect; the audit trail is the only evidence the eviction ever happened.
3. **Validation, server-side.** `operator` non-blank after trim; `reason` at least 8 characters after
   trim (matching the L8 request rule so HTTP and in-process callers cannot diverge); both length-capped
   to the column widths. Reject before opening the transaction. A blank operator is never defaulted to
   `"system"`, `"admin"`, or the pod name — an unattributable revocation must be impossible to record.
4. **Not-held is not an error.** Revoking a key with no live grant returns the `LockOutcome` decision
   the contract specifies for that case and writes **no** `lock_revocation` row and **no** WARN event.
5. **`prev_owner` / `prev_token`** come from the row being deleted, captured inside the transaction —
   not from caller-supplied hints.
6. **Observability.** Exactly one WARN `lock_revoked` per successful revocation, with the four contract
   fields; `revokedBy` carries the validated operator. `reason` must be free text in the log but is
   promoted to a label by `#ct4-lbm`, so do **not** interpolate ids into it in this task's own callers.
   Emit **no new Micrometer counter** — C4 §4.2 defines none for revocation; the count is derived from
   logs. Adding `lock.revoked` would be a contract change.
7. **The sweeper is not an operator.** T-014's expiry path must remain untouched: expiry deletes and
   logs `lease_expired`; it does not write `lock_revocation`.

## 5. Acceptance criteria

1. `PostgresLockStore.revoke` matches the C2 signature exactly and is annotated/wrapped as a single transaction.
2. Revoking a held key deletes exactly one `lock_entry` row and inserts exactly one `lock_revocation` row whose `prev_owner` and `prev_token` equal the deleted grant's values.
3. After a revocation, the next successful `tryInsert` on the same key yields a token strictly greater than the revoked `prev_token`.
4. Blank/whitespace `operator`, or a `reason` under 8 trimmed characters, is rejected before any write; `lock_revocation` row count is unchanged and no default operator string exists anywhere in the code.
5. Revoking a key with no live grant writes nothing and emits no WARN.
6. One WARN `lock_revoked` per successful revocation with `lockKey`, `token`, `revokedBy`, `reason`.
7. `grep -R "lock.revoked" lock-server/src/main` returns nothing (no invented metric).
8. Nothing in the production source reads `lock_revocation` (append-only, off the hot path).

## 6. Verification

- `./gradlew :lock-server:spotlessCheck :lock-server:test` — green.
- With the server running: acquire `payout:acct-9`, note the token, then invoke the revoke path (via the
  test or a temporary CLI runner — the HTTP endpoint arrives in T-016).
- `psql "$LOCKDB_URL" -c 'select lock_key, prev_owner, prev_token, operator, reason from lock_revocation'`
  → one row with the acquiring owner and the noted token.
- `psql "$LOCKDB_URL" -c "select count(*) from lock_entry where lock_key='payout:acct-9'"` → `0`.
- Re-acquire the same key and compare `fencingToken` to `prev_token` — strictly greater.
- Attempt a revoke with `reason='short'`; the call fails validation and the `lock_revocation` count stays at 1.

## 7. Out of scope

`POST /v1/locks/{key}/revoke` (L8) with its `expectedToken` CAS guard, the `revokedToken` /
`newTokenFloor` / `revocationId` response body and the 404/409 mapping — **all T-016**. Authn/authz for
operators, an operator CLI, Cloud Logging sinks and the `lock_revocations` log-based metric (T-060s),
and the etcd revoke implementation (T-030s).

## 8. Hazards

- **Delete-only revoke** is the trap L8 names explicitly (`#ct3-lock`): the evicted holder's write still
  clears the resource fence. If your implementation does not raise the floor, it is wrong even if tests pass.
- **Reusing or lowering a token silently disables fencing for that key — INV-04** (`#ct2-spi`).
- Two statements outside one transaction produce an eviction with no audit row; the audit trail is the
  deliverable, not a side effect.
- Do **not** run `git` (ADR-011).

## 9. On completion

Mark the T-015 row done in `tasks/README.md`, stating which floor-advance reading of C1 you applied and
whether any contract silence forced a stop.
