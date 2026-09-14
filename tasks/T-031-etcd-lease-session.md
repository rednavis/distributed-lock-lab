# T-031 — etcd lease per session and keepAlive

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M3 · **Estimate** 30 min

**Preconditions** — T-030 done: `EtcdLockStore.tryInsert` grants via a `CreateRevision == 0` txn and
takes a lease id as an input, with the rest of the SPI still stubbed. You also inherit the pg
`SessionRegistry` from M1 as the behavioural reference.

**Goal** — Implement `dev.lock.server.store.etcd.EtcdSessionRegistry`: one etcd lease per **session**,
one streaming keepalive per lease, and lease death as the single mechanism that releases every lock the
session held.

## 1. Why this task exists

FR-04 says a dead session loses every lock it holds. In pg that costs a `lock_entry_session_idx` cascade
plus a reaper; in etcd it is free *if and only if* the lease is scoped to the session and every grant key
is attached to it — one lease per lock would make session death a fan-out of N deletes that can partly
fail. This is the structural difference the backend-comparison table
([07 §7.9](../docs/07-correctness-and-testing.md#test-comparison)) exists to record.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| `SessionRegistry` signatures and the rule that `heartbeat` returning `false` is **terminal** | [C2 `#ct2-spi`](../docs/contracts/C2-java-api.md#ct2-spi) |
| `ContentionException` / `NotLeaderException` are the retryable mappings; a dead session is not one of them | [C2 `#ct2-exceptions`](../docs/contracts/C2-java-api.md#ct2-exceptions) |
| `lock.session.ttl` and `lock.client.safety-margin` key names and defaults | [C5 `#ct5-config`](../docs/contracts/C5-config-build-and-naming.md#ct5-config) |
| `session_opened` / `session_lost` / `lease_expired` log event names and required fields | [C4 `#ct4-logs`](../docs/contracts/C4-observability.md#ct4-logs) |
| Session/lease metric names and tag sets | [C4 `#ct4-metrics`](../docs/contracts/C4-observability.md#ct4-metrics), cardinality budget in [`#ct4-cardinality`](../docs/contracts/C4-observability.md#ct4-cardinality) |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report.** In
particular, do not invent a metric or log field name; C4 is the only source.

## 3. Deliverables

| Path | What |
|---|---|
| `lock-server/src/main/java/dev/lock/server/store/etcd/EtcdSessionRegistry.java` | `SessionRegistry` impl: lease grant, keepalive stream, revoke, `locksOf` |
| `lock-server/src/main/java/dev/lock/server/store/etcd/EtcdSession.java` | Per-session state: session id, lease id, the keepalive observer handle, the set of granted keys, a liveness flag |
| `lock-server/src/main/java/dev/lock/server/store/etcd/EtcdLockStore.java` (modify) | Resolve the lease id from the session id instead of receiving it; refuse to grant on a session that is not live |
| `lock-server/src/main/java/dev/lock/server/store/etcd/EtcdStoreConfig.java` (modify) | Expose the registry bean under the same `lock.backend=etcd` condition |
| `lock-server/src/test/java/dev/lock/server/store/etcd/EtcdSessionLeaseTest.java` | Tests per §4 |

## 4. Specification

**Session id vs lease id.** `openSession` grants exactly one lease with TTL `lock.session.ttl` and
returns an opaque session id string. The mapping session id → lease id is held in one concurrent map,
and the *lease id is never exposed outside the etcd package* — the SPI is deliberately lease-free so the
pg path cannot inherit etcd vocabulary ([10 §delivery](../docs/10-delivery-plan.md)).

**One keepalive stream, not a timer per lock.** Use jetcd's streaming keepalive for the lease
(`keepAlive(leaseId, observer)`), one stream per session. Do **not** implement heartbeating as a loop of
single-shot `keepAliveOnce` calls per held key: that scales with lock count instead of session count and
turns a transient stall into partial expiry. The observer's `onError`/`onCompleted` transitions the
session to not-live and is what makes `heartbeat` return `false`.

**`heartbeat(sessionId, ttl)` semantics.** It does **not** send a keepalive — the stream already does.
It reports liveness: `true` while the stream is healthy and the lease's remaining TTL exceeds the safety
margin; `false` **only** when the lease is known dead (observer error terminal, `TimeToLive` reports the
lease absent, or explicit revoke). A gRPC blip while the stream is retrying is `ContentionException`,
not `false` — C2 marks `false` terminal, and a caller that receives it must abandon its critical
section, so a false `false` is a self-inflicted availability incident.

**Attachment.** Every grant put from T-030 carries `withLease(leaseId)` for the *session's* lease. This
is the one line that makes lease death a cascade. `EtcdSession` also tracks keys locally so `locksOf`
answers without a range scan; treat the local set as a cache and reconcile from a prefix range read when
it is asked for authoritatively.

**`closeSession`.** Revokes the lease (which deletes all attached keys atomically from the client's
point of view), stops the keepalive stream, removes the map entry, returns the count of keys that were
attached. Idempotent: a second call returns 0, never throws.

**Server restart.** A `lock-server` restart loses the in-JVM session map; the leases die with their
keepalive streams and etcd cleans up. Document this in a class-level comment as *intended* behaviour and
note the divergence from pg, where `lock_session` rows survive the process — this line feeds T-034 and
the §7.9 comparison table.

**Telemetry.** Emit the C4-pinned session events on open, on transition-to-dead, and on close. Tag by
`backend=etcd`, never by session id (cardinality budget).

## 5. Acceptance criteria

1. A test acquires **three** locks on one session against a real etcd container, revokes the lease
   directly via the etcd API, and asserts all three keys are gone with a single prefix range read.
2. A test asserts exactly one lease exists per session: after opening a session and taking three locks,
   `Lease.leases()` (or equivalent) reports one lease whose attached-key count is three.
3. A test asserts `heartbeat` returns `true` while the stream is up, and `false` after the lease is
   revoked out-of-band — and that the false result is stable/terminal on a second call.
4. A test asserts a stopped keepalive leads to expiry within roughly `lock.session.ttl` (assert
   eventual, with a generous timeout; do not assert exact timing).
5. A test asserts `tryInsert` on an unknown or dead session id does not create a key.
6. `closeSession` called twice returns the attached count then `0`, with no exception.
7. Spotless clean; no lease id appears in any signature outside `dev.lock.server.store.etcd`.

## 6. Verification

```
./gradlew :lock-server:spotlessCheck :lock-server:test --tests '*EtcdSessionLeaseTest*' --tests '*EtcdLockStoreAcquireTest*'
grep -rn "leaseId\|LeaseGrantResponse" lock-server/src/main/java --include=*.java | grep -v "store/etcd"
```
Expected: tests green; the `grep` prints **nothing**.

## 7. Out of scope

`extend`/`deleteIfOwner`/`revoke`/`inspect` (**T-032**); watch (**T-033**); parity runs (**T-034**);
client-side SDK heartbeat scheduling (already M1/M4 territory — do not duplicate it here); etcd cluster
topology, PVCs, quorum loss (**M5**).

## 8. Hazards

The specific trap: jetcd's keepalive observer swallows errors if you register no `onError`, so the
session looks alive forever and `heartbeat` never returns `false` — INV-06's split-brain path. Second
trap: TTL arithmetic. The lease TTL is a *server* TTL; the client's safety decision uses
`clientDeadlineNanos` discounted by `lock.client.safety-margin`
([C2 D3](../docs/contracts/C2-java-api.md#ct2-records)) — do not "simplify" by comparing wall clocks.
Third: leaking `leaseId` into `SessionRegistry` or `LockHandle` is a C2 contract change, not a
refactor.

## 9. On completion

Mark T-031 done in `tasks/README.md`; record the restart-semantics divergence (pg session rows survive,
etcd leases do not) as a note for T-034 and §7.9.
