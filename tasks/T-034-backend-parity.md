# T-034 — Backend parity suite

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M3 (closing task) · **Estimate** 35 min — **over budget; split into the two ledger rows T-034a/T-034b**: part A =
parameterise the T-017 matrix over both stores (criteria 1–5); part B = the executor run under
`lock.backend=etcd` plus the divergence table (criteria 6–9). Part A must land first.

**Preconditions** — T-030…T-033 done: the etcd store implements the full `LockStore` SPI, sessions own
leases, release is compare-and-delete, and waiting is watch-based with a polling fallback. From M1 you
inherit the T-017 behaviour matrix, currently hard-wired to the pg store; from M2 a working
`payout-executor` end-to-end against pg.

**Goal** — Run one identical behavioural suite against both `LockStore` implementations through the SPI
and turn every remaining difference into either a fixed bug or a written, justified divergence.

## 1. Why this task exists

SC-02 is the claim "the *same* `payout-executor` passes the M2 suite with `lock.backend=etcd`, no code
change" ([10 §M3](../docs/10-delivery-plan.md)). A suite that exists twice — once per backend — cannot
support that claim, because the two copies drift and each backend ends up tested against its own
behaviour. One parameterised suite is the only artifact that makes "both backends are first-class"
falsifiable, and the divergence table is what an interviewer will actually read.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| The SPI is the *only* seam the suite may touch — no `instanceof`, no backend-specific assertion branches beyond the registered divergences | [C2 `#ct2-spi`](../docs/contracts/C2-java-api.md#ct2-spi) |
| Exception semantics that must be identical across backends (retryable vs terminal) | [C2 `#ct2-exceptions`](../docs/contracts/C2-java-api.md#ct2-exceptions) |
| `backendId()` is the only permitted backend discriminator, and only for the metric tag | [C2 `#ct2-spi`](../docs/contracts/C2-java-api.md#ct2-spi) |
| `lock.backend` selects one store at startup; switching live is forbidden | [C5 `#ct5-config`](../docs/contracts/C5-config-build-and-naming.md#ct5-config) |
| Metric/log/span names must be identical across backends, differing only in the `backend` tag | [C4 `#ct4-metrics`](../docs/contracts/C4-observability.md#ct4-metrics), [`#ct4-logs`](../docs/contracts/C4-observability.md#ct4-logs) |
| What CI is allowed to run and how long it may take | [07 §7.10](../docs/07-correctness-and-testing.md#test-ci) |
| The comparison table this task fills in | [07 §7.9](../docs/07-correctness-and-testing.md#test-comparison) |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report.**

## 3. Deliverables

| Path | What |
|---|---|
| `lock-server/src/test/java/dev/lock/server/store/LockStoreContractTest.java` | Abstract/parameterised suite: the whole T-017 matrix expressed against `LockStore` + `SessionRegistry` only |
| `lock-server/src/test/java/dev/lock/server/store/PostgresLockStoreContractTest.java` | Binds the suite to the pg store (Testcontainers PostgreSQL 16 + Flyway) |
| `lock-server/src/test/java/dev/lock/server/store/EtcdLockStoreContractTest.java` | Binds the suite to the etcd store (Testcontainers etcd 3.6) |
| `lock-server/src/test/java/dev/lock/server/store/*` (modify T-017's tests) | Move backend-agnostic cases into the shared suite; leave only genuinely pg-specific cases (SQL, reaper, sequence) behind |
| `docs/07-correctness-and-testing.md` (modify §7.9 only) | Fill in the comparison table rows this task can answer |
| `docs/BACKEND-DIVERGENCES.md` | The register: one row per accepted difference, with the reason and the contract clause that permits it |

## 4. Specification

**Suite shape.** One test class holding every case, with two abstract hooks: build a `LockStore` and a
`SessionRegistry` for a fresh, isolated namespace, and a hook to *kill a session out of band* (pg: delete
the session row / expire it; etcd: revoke the lease). Nothing else may vary. Per-test isolation comes from
a unique key namespace, not from restarting containers — restarts will blow the CI budget.

**Cases the shared suite must cover** (all already validated for pg in T-017; the point is that etcd meets
the same bar): single-holder mutual exclusion under concurrency; token strictly increasing across
acquire/release cycles and across a force-revoke; `extend` returning the same token; `extend` on a lost
grant returning empty; stale-releaser attempt failing; wrong-session release failing; session death
releasing all N locks; heartbeat `false` being terminal; transport failure surfacing as
`ContentionException` and never as LOST; `read` returning a plausible `LockInfo`; `revoke` advancing the
floor; `acquire`-with-wait completing within `maxWait` and respecting interruption.

**Divergences known going in** — verify each, then register it in `docs/BACKEND-DIVERGENCES.md` with a
one-line justification, or fix it if it is not actually justified:

| # | pg | etcd |
|---|---|---|
| D-1 | `reapExpired` deletes expired rows and returns a count | No-op returning 0; leases own expiry |
| D-2 | `lock_session` rows survive a `lock-server` restart | Leases die with the keepalive stream (T-031) |
| D-3 | Token from `fencing_token_seq`, protected by restore procedure | `ModRevision` captured at grant, monotonic by construction |
| D-4 | Waiting is jittered backoff only | Watch from observed revision + 1, with backoff fallback (T-033) |
| D-5 | Force-revoke writes `lock_revocation` | Floor key written in the same txn |

Anything the suite finds that is *not* on that list is a **bug** until proven otherwise; do not add a row
to make a red test green.

**Executor run (part B).** Start the M2 stack with `lock.backend=etcd` and no other change, execute the
happy-path payout flow, and assert: the ledger rows and balance are correct, `rail.duplicate.attempted`
and `lock.fenced.out` are exactly zero ([C4 `#ct4-zero`](../docs/contracts/C4-observability.md#ct4-zero)),
and no `payout-executor` source file was modified — that last one is the SC-02 claim. Also assert the same
metric *names* appear under both backends, differing only in the `backend` tag.

**CI.** Add the etcd binding to the same CI job as pg if the combined runtime stays within the §7.10
budget; if it does not, put the etcd binding behind a tag that the nightly job runs and say so in
`tasks/README.md`.

## 5. Acceptance criteria

1. Exactly one file contains the shared cases; `grep -c '@Test' ` on the two binding classes returns 0
   (they only supply hooks).
2. `./gradlew :lock-server:test` runs the shared suite twice — the test report shows both
   `PostgresLockStoreContractTest` and `EtcdLockStoreContractTest` with the same test-name list.
3. No `instanceof`, no `backendId()`-conditional assertion appears in the shared suite (grep).
4. Every case listed in §4 exists by name in the shared suite.
5. `docs/BACKEND-DIVERGENCES.md` exists with a row per divergence, each citing its cause and the
   contract clause that permits it; the D-1…D-5 set is present.
6. §7.9's table in `docs/07-correctness-and-testing.md` has its answerable cells filled; any cell
   this milestone cannot answer says `[M7]`, not a guess.
7. The executor run under `lock.backend=etcd` completes the happy path with both must-be-zero counters
   at zero.
8. No file under `payout-executor/src/main` was modified by this task (list the files you touched in
   `tasks/README.md` and confirm none is under that path).
9. CI configuration states plainly where the etcd binding runs (same job or nightly).

## 6. Verification

```
./gradlew :lock-server:spotlessCheck :lock-server:test
grep -rn "instanceof\|backendId()" lock-server/src/test/java/dev/lock/server/store/LockStoreContractTest.java
./gradlew :payout-executor:bootRun --args='--lock.backend=etcd'   # then drive the M2 happy-path script
curl -s localhost:8080/actuator/prometheus | grep -E 'rail_duplicate_attempted|lock_fenced_out'
```
Expected: both bindings green with identical test-name lists; the grep prints nothing; both counters read
`0.0`.

## 7. Out of scope

Latency or throughput numbers and the pg-vs-etcd performance verdict (**M7**, T-070…075); the SIGSTOP
fencing experiment (**T-042**); etcd on GKE, PVCs, quorum-loss drills (**M5**); linearizability checking
(**M4**); editing any contract file — a needed change escalates via [04 §4.5](../docs/04-contracts.md#c-changelog).

## 8. Hazards

The failure mode of this task is *diplomatic*: it is much easier to add a divergence row than to fix an
etcd bug, and every unjustified row silently weakens the SC-02 claim the milestone exists to support. Rule
of thumb — a divergence is legitimate only if it follows from the substrate (leases, revisions) and is
invisible to `payout-executor`; if a caller could observe it, it is a bug. Second hazard: flakiness. Two
containers plus session-death tests will produce timing flakes; use generous eventual assertions, never
fixed sleeps tuned to a laptop. Third: do not "temporarily" widen an exception type to make both backends
agree — C2 `#ct2-exceptions` is contract.

## 9. On completion

Mark T-034 done in `tasks/README.md` and mark **M3 complete** there; list every touched file, state where
the etcd suite runs in CI, and record any divergence you registered so M7 can cite it.
