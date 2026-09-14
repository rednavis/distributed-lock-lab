# T-030 — EtcdLockStore.tryAcquire

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M3 (etcd backend) · **Estimate** 30 min (tight; if the jetcd client bring-up runs long,
stop after acceptance criterion 5 and leave the token-monotonicity test for the start of T-031)

**Preconditions** — T-001…T-008 (M0: Gradle monorepo, version catalog, `lock-api` compiling, Spotless,
CI) and T-010…T-017 (M1: `dev.lock.server.store.pg` implements the whole `LockStore` SPI, `core` wires
one store by `lock.backend`, the T-017 behaviour matrix is green against pg). You inherit a repo where
`LockStore` is a *proven* interface, not a sketch — do not reshape it to suit etcd.

**Goal** — Implement `tryInsert` in a new `dev.lock.server.store.etcd.EtcdLockStore` as a single etcd
`Txn` guarded by `CreateRevision == 0`, whose `ModRevision` **captured from the txn response** becomes
the fencing token.

## 1. Why this task exists

etcd is the recommended production backend precisely because the token is monotonic *by construction*
rather than by procedure ([ADR-002](../docs/adr/ADR-002-fencing-token-source.md)): the cluster's
revision counter is the token source, so there is no sequence to protect across restore. This task also
proves the SPI shaped in M1 can host a second, structurally different store without changing `lock-api`
— if it cannot, that is a finding to report, not an interface to edit.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| `LockStore.tryInsert` signature and semantics (atomic, single-shot, token strictly greater than any ever issued for the key, empty = held) | [C2 `#ct2-spi`](../docs/contracts/C2-java-api.md#ct2-spi) |
| `LockHandle` fields, incl. `clientDeadlineNanos` monotonic basis and `serverExpiry` being informational only | [C2 `#ct2-records`](../docs/contracts/C2-java-api.md#ct2-records) |
| `backendId()` returns exactly `"etcd"` | [C2 `#ct2-spi`](../docs/contracts/C2-java-api.md#ct2-spi) |
| `lock.backend`, `etcd.endpoints`, `lock.default.ttl` key names and defaults | [C5 `#ct5-config`](../docs/contracts/C5-config-build-and-naming.md#ct5-config) |
| `jetcd-core` version comes only from the version catalog; `lock-server` is the only module allowed to see it | [C5 `#ct5-catalog`](../docs/contracts/C5-config-build-and-naming.md#ct5-catalog), [`#ct5-modules`](../docs/contracts/C5-config-build-and-naming.md#ct5-modules) |
| `lock.acquire` metric name and its closed tag value set (`backend=etcd`) | [C4 `#ct4-metrics`](../docs/contracts/C4-observability.md#ct4-metrics) |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report the mismatch,
quoting both.** Do not "fix" it locally.

## 3. Deliverables

| Path | What |
|---|---|
| `lock-server/src/main/java/dev/lock/server/store/etcd/EtcdLockStore.java` | `LockStore` implementation; only `tryInsert`, `read`, `backendId` live here this task; the rest throw `UnsupportedOperationException` with a `// T-032` marker |
| `lock-server/src/main/java/dev/lock/server/store/etcd/EtcdKeys.java` | Key-layout helper: the single `/dlock/<namespace>/lock/<key>` prefix scheme and its parse/format pair |
| `lock-server/src/main/java/dev/lock/server/store/etcd/EtcdStoreConfig.java` | Spring `@Configuration`, `@ConditionalOnProperty(lock.backend=etcd)`, builds one shared jetcd `Client` bean from `etcd.endpoints` |
| `gradle/libs.versions.toml` (modify) | Add the `jetcd-core` alias if T-004 did not already |
| `lock-server/build.gradle.kts` (modify) | Consume the alias; keep `lock-api` dependency-free |
| `lock-server/src/test/java/dev/lock/server/store/etcd/EtcdLockStoreAcquireTest.java` | Testcontainers-backed tests per §4 |

## 4. Specification

**Key layout.** One key per lock, no directory-per-lock, no sequential znode emulation. The value is a
compact serialised grant record carrying `ownerId`, `sessionId`, and `serverExpiry`; the token is *not*
stored in the value — it *is* the key's `ModRevision`, and duplicating it invites the two to diverge.

**The acquire transaction.** Exactly one `Txn`: `If(CreateRevision(key) == 0) Then(Put(key, value,
withLease(sessionLease))) Else(Get(key))`. `CreateRevision == 0` is the "does not exist" predicate;
using `Version == 0` or a prior `Get` plus a `Put` reintroduces the race the txn exists to remove. No
expiry column, no reaper, no steal-if-expired branch — the lease owns expiry (contrast the pg store,
whose acquire statement must handle expired rows itself,
[C1 `#ct1-acquire`](../docs/contracts/C1-database-schemas.md#ct1-acquire)).

**Token capture — the point of the task.** On `succeeded == true`, read the `ModRevision` of the
`PutResponse`'s header/`prevKv`-free response: the txn response header's `revision` for the winning put
is the grant's revision, and that value is the token, captured once, in the same response that granted
the lock. It is then immutable for the life of the grant. **Never re-`Get` the key later to "refresh"
the token**: a subsequent unrelated write, a lease-keepalive-induced revision bump, or a competitor's
failed txn moves the cluster revision, so a re-read yields a *different* number that still passes every
happy-path test and silently breaks fencing (INV-04) the first time two holders overlap. Store the
captured token on the returned `LockHandle` and, for T-032, in an in-JVM per-grant record.

**On failure.** `succeeded == false` ⇒ return `Optional.empty()` — held by a live grant, which is a
*decision*, not an error. A jetcd/gRPC failure, deadline exceeded, or `NoLeader`/unavailable status is
**not** empty: map to `ContentionException` or `NotLeaderException`
([C2 `#ct2-exceptions`](../docs/contracts/C2-java-api.md#ct2-exceptions)). Also return the observed
response header revision to the caller-side wait path — T-033 needs it; expose it on an
etcd-package-internal type, never on `lock-api`.

**Session lease.** This task takes the lease id as a constructor/parameter input and does **not** create
or renew leases; a fixed lease acquired in test setup is acceptable. T-031 owns lease lifecycle.

**Deadlines and TTL.** Every jetcd call gets an explicit timeout derived from the call deadline. Build
`clientDeadlineNanos` from `System.nanoTime()` plus the granted TTL, exactly as the pg store does;
`serverExpiry` is derived for logs only.

## 5. Acceptance criteria

1. `./gradlew :lock-server:compileJava` passes; `lock-api` gained no new dependency (check its
   `build.gradle.kts` and `dependencies` output).
2. A test asserts two concurrent `tryInsert` calls for one key against a real etcd container yield
   exactly one non-empty result.
3. A test asserts the returned `fencingToken` equals the revision reported by the granting txn response
   and is non-zero.
4. A **monotonicity** test: acquire → release-by-raw-delete → acquire again, N≥5 cycles, asserting the
   token strictly increases every cycle and never repeats.
5. A **no-refresh** test: after a grant, perform an unrelated `Put` to a different key, then assert the
   handle's token is unchanged (this test fails if any code path re-reads the token).
6. `backendId()` returns `"etcd"`; `lock.acquire` is timed with `backend=etcd`.
7. Spotless clean; Lombok usage limited to `@RequiredArgsConstructor` / `@Slf4j`.

## 6. Verification

```
./gradlew :lock-server:spotlessCheck :lock-server:test --tests '*EtcdLockStoreAcquireTest*'
./gradlew :lock-api:dependencies --configuration runtimeClasspath   # expect: no dependencies
```
Expected: all tests green; the dependencies report for `lock-api` lists nothing.

## 7. Out of scope

Lease creation and keepalive (**T-031**); `extend`/`deleteIfOwner`/`revoke`/`reapExpired` (**T-032**);
watch-based waiting (**T-033**); running the T-017 matrix against etcd (**T-034**); any Kubernetes
`dlock-etcd` StatefulSet (**M5**); wiring etcd into `payout-executor` config (**T-034**).

## 8. Hazards

The trap is that re-reading `ModRevision` is *indistinguishable from correct* under sequential tests —
C2 `#ct2-spi` requires a token "strictly greater than any token ever issued for that key", and a
refreshed revision satisfies that too while no longer identifying the grant. Criterion 5 is the only
guard; do not weaken it. Second trap: returning `Optional.empty()` for a gRPC `UNAVAILABLE` — C2
`#ct2-spi` calls conflating LOST-NOW with transient failure "the most common bug in home-grown lock
clients". Third: one jetcd `Client` per store instance, not per call — a client per acquire leaks
connections and stalls the container tests.

## 9. On completion

Mark the T-030 row done in `tasks/README.md`. Note any deviation (especially any place the C2 SPI did
not fit etcd) as a line item there, and escalate rather than editing C2.
