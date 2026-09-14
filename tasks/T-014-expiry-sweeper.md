# T-014 — ExpirySweeper and the expiry signal

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M1 — Postgres lock backend · **Estimate** 25 minutes (fits one session)

**Preconditions** — T-001…T-008 (M0 foundations: `build-logic`, version catalog, module skeletons,
`lock-api` types on disk), T-010…T-013 (lockdb Flyway tree, `PostgresLockStore` with
`tryInsert`/`extend`/`deleteIfOwner`/`read`, pg `SessionRegistry`, the core `LockService` wired behind
`lock.backend=pg`). You inherit a lock-server that can grant, renew and release against a real
`lock_entry`, and whose expired rows are simply *ignored* by the acquire statement — nobody deletes
them and nobody notices. Confirm the exact inherited state in `tasks/README.md`.

**Goal** — Implement the scheduled reaper behind `LockStore.reapExpired(Instant)` so every expired
lease produces one WARN `lease_expired` log event and one `lock.lease.expired` increment.

## 1. Why this task exists

Expiry is already *true* in the data before any sweeper runs — the acquire statement steals an
expired row on its own, so the reaper buys liveness and observability, not correctness. What the project
actually needs is the **signal**: a lease reaching its deadline means a holder died, GC-paused, or was
partitioned mid-critical-section, which is exactly the condition that later manufactures a fenced-out
write at the resource. Without this task the most important precursor event in the system is invisible,
and T-042's fencing experiment has no "before" evidence. Treating expiry as routine bookkeeping —
DEBUG, or no log at all — is the defect being avoided.

## 2. Contracts to obey

| What | Pinned at |
|---|---|
| `reapExpired(Instant now)` signature, idempotence, multi-replica safety, index to use | `docs/contracts/C2-java-api.md#ct2-spi` |
| `lock_entry` columns, `lock_entry_expiry_idx`, cascade behaviour | `docs/contracts/C1-database-schemas.md#ct1-lockdb` |
| Metric `lock.lease.expired` — Counter, unit events, tag `backend`=`pg`\|`etcd`, emitter lock-server | `docs/contracts/C4-observability.md#ct4-metrics` |
| Log event `lease_expired` — WARN, lock-server, fields `lockKey`, `token`, `expiredAtMillis`, `overdueMillis` | `docs/contracts/C4-observability.md#ct4-logs` |
| Gauge `lock.held.current` must reflect post-sweep reality | `docs/contracts/C4-observability.md#ct4-metrics` |
| Metric tag sets are not configurable; no key/token in tags | `docs/contracts/C5-config-build-and-naming.md#ct5-fixed`, `#ct4-cardinality` |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report the mismatch,
quoting both.** Do not reconcile locally (`docs/04-contracts.md#c-precedence`).

## 3. Deliverables

| Path | What |
|---|---|
| `lock-server/src/main/java/dev/lock/server/store/pg/PostgresLockStore.java` | add `reapExpired` — set-based delete of expired rows, returning the count |
| `lock-server/src/main/java/dev/lock/server/core/ExpirySweeper.java` | the scheduled component: calls `reapExpired`, emits one log event + one counter increment per reaped lease |
| `lock-server/src/main/java/dev/lock/server/core/LockMetrics.java` | register/increment `lock.lease.expired` and keep `lock.held.current` truthful (create if T-013 did not) |
| `lock-server/src/main/resources/application.yaml` | enable scheduling; no new config key (see §8) |
| `lock-server/src/test/java/dev/lock/server/core/ExpirySweeperTest.java` | unit test with a stubbed `LockStore`: counter arithmetic and log emission |

## 4. Specification

**The reap statement** must delete from `lock_entry` where the expiry column is at or before the
passed `now`, and must **return the reaped rows' key, owner and token** — the log event cannot be
written from a bare row count. Use a single `DELETE … RETURNING`; do not select-then-delete, which
races another replica and double-counts. The method is idempotent and concurrency-safe: a row reaped
by another replica simply does not come back, so the second replica logs nothing.

**The sweeper** runs on a fixed delay, computes `overdueMillis` as `now − expires_at`, and for each
reaped lease emits exactly one WARN `lease_expired` structured event with the four contract fields and
exactly one `lock.lease.expired` increment tagged `backend=pg` (take the tag value from
`LockStore.backendId()`, never a literal — T-030 adds etcd behind the same seam). A sweep that reaps
nothing must log **nothing** at WARN and may log at DEBUG. Errors from the store are caught, logged at
ERROR, and must not kill the scheduler thread.

**Level discipline.** WARN, not INFO, and one line per lease, not one summary line per sweep: the
log-based metric in `#ct4-lbm` counts events, and an aggregated line makes the count wrong.

**Interval.** Hard-code the fixed delay as a documented constant in `ExpirySweeper` (1 second — at or
below a quarter of the shortest permitted lease so `overdueMillis` stays a meaningful measurement).
C5 §5.1 pins no sweep-interval key; inventing one is a contract change, so do not add one.

## 5. Acceptance criteria

1. `PostgresLockStore.reapExpired(Instant)` exists with the C2 signature and returns the number of rows deleted.
2. The reap SQL is a single statement using `RETURNING`, and `EXPLAIN` shows `lock_entry_expiry_idx` is available to it (an index scan is not required on a tiny table; the index must exist and the predicate must be on the indexed column).
3. `ExpirySweeper` is a Spring-scheduled component with the interval expressed as a named constant, and no new key appears in `application.yaml` or C5 §5.1.
4. For N reaped leases in one sweep, the process emits exactly N WARN events named `lease_expired`, each carrying `lockKey`, `token`, `expiredAtMillis`, `overdueMillis`.
5. `lock.lease.expired` increases by exactly N, with tag `backend` sourced from `backendId()`.
6. No metric tag anywhere in this task carries a lock key, owner id or token.
7. A sweep that reaps zero rows produces no WARN output.
8. A store exception during a sweep is logged at ERROR and the next scheduled sweep still runs.

## 6. Verification

- `./gradlew :lock-server:spotlessCheck :lock-server:test` — green.
- `./gradlew :lock-server:bootRun` against the local lockdb, then acquire a lock with `ttlMillis=1000`
  via `curl -XPOST .../v1/locks/payout:acct-1/acquire` and stop heartbeating. Within ~2 s the log shows
  one `lease_expired` WARN with `overdueMillis` under 1500.
- `curl -s localhost:8080/actuator/prometheus | grep lock_lease_expired_total` → value `1.0`, tag `backend="pg"`.
- `psql "$LOCKDB_URL" -c 'select count(*) from lock_entry'` → `0`.
- Repeat the acquire twice more without heartbeat; the counter reads `3.0` and three distinct WARN lines exist.

## 7. Out of scope

Session-level expiry and heartbeat lapse (owned by T-012/T-013 — a dead *session* cascades and is a
different event), `forceRevoke` and `lock_revocation` (T-015), the HTTP surface (T-016), Testcontainers
integration coverage of takeover (T-017), the etcd lease equivalent (T-030…T-034), alerting and the
log-based metric wiring in Cloud Logging (T-060s), and any dashboard.

## 8. Hazards

- **Reaping is liveness, not correctness** (`#ct2-spi`, `reapExpired` row). Never make acquire depend
  on the sweeper having run; the steal-if-expired statement stays authoritative.
- **Select-then-delete double-counts** across replicas and inflates the very counter an operator will
  use to judge client health.
- **Summary logging breaks the log-based metric** (`#ct4-lbm`).
- **Tag temptation**: adding `key` to `lock.lease.expired` is the cardinality explosion forbidden by
  `#ct4-cardinality` and hard-coded closed by `#ct5-fixed`.
- Do **not** run `git` — the repo is deliberately not a git repository (ADR-011).

## 9. On completion

Mark the T-014 row done in `tasks/README.md` (add the row if the ledger does not yet list it), naming
the sweep-interval constant and its value. Note any deviation, and any place where a contract was
silent and you stopped rather than invented a name.
