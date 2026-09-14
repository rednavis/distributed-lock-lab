# C2 - Java API and SPI

Contract for the `lock-api`, `lock-client` and backend-SPI surfaces. Signatures and Javadoc only; no
implementation. Upstream: [00 charter](../00-charter.md), [01 requirements](../01-requirements.md)
(FR-01…FR-13, NFR-16), [02 domain model](../02-domain-model.md).

## 2.1 The `lock-api` zero-dependency rule {#ct2-zero-dep}

`lock-api` is a Java-25 module whose compile classpath is the JDK and nothing else — no Spring, no JDBC
driver, no etcd client, no Jackson, no logging facade, no annotations (not even Lombok). CI enforces it
as a dependency-count assertion (NFR-16), not as a convention.

| Why | Failure mode if the rule is dropped |
|---|---|
| The contract is the one artifact both backends and all callers share; it must not encode a stack. | `LockStore` grows a `DataSource` parameter, and the etcd backend cannot implement it. |
| A third party must be able to implement `LockStore` without inheriting our runtime. | An annotation from a web framework leaks in; the SPI is now unimplementable outside a Spring context. |
| Transport must stay pluggable (HTTP today, gRPC tomorrow). | An HTTP status code or `ResponseEntity` appears in a signature and the transport is welded to the semantics. |
| Version skew across services is survivable only if the shared jar is tiny. | A transitive Jackson bump forces a lockstep redeploy of five services to change one lock timeout. |

Consequences: time is `long` nanos or `java.time.Instant`, never a framework clock; identifiers are
`String` or `UUID`, never a domain object from `paydb`; failures are the exceptions in [2.4](#ct2-exceptions),
never a wrapped `SQLException` or an etcd status.

## 2.2 `LockService` {#ct2-lockservice}

`dev.lock.api.LockService` — the whole caller-visible lock contract. Six methods; all implementations are
thread-safe ([2.8](#ct2-threading)).

```java
package dev.lock.api;

public interface LockService {

  /**
   * Acquires {@code key}, waiting up to {@code maxWait} for a conflicting holder to go away.
   * Retries internally with bounded, jittered backoff (FR-28). Never blocks longer than
   * {@code maxWait} plus one in-flight round trip.
   *
   * @return a handle if the lock was granted; {@link java.util.Optional#empty()} if {@code maxWait}
   *         elapsed while the lock was legitimately held by someone else. Empty is NOT an error and
   *         MUST NOT be retried in a tight loop; it means "not mine, do not proceed" (FR-27).
   * @throws ContentionException  a store-level conflict could not be resolved within the deadline
   * @throws NotLeaderException   this node is not the current leader for the key's shard
   */
  Optional<LockHandle> acquire(String key, String ownerId, Duration ttl, Duration maxWait);

  /**
   * Single-shot acquire: one attempt, no waiting, no backoff.
   *
   * @return a handle, or empty if the lock is currently held by anyone else (including a holder whose
   *         lease has not yet been reaped). Empty is the normal, expected outcome under contention.
   */
  Optional<LockHandle> tryAcquire(String key, String ownerId, Duration ttl);

  /**
   * Extends the lease of a grant this caller still owns.
   *
   * @return a NEW handle carrying the same {@code fencingToken} and a later deadline, or
   *         {@link java.util.Optional#empty()} meaning THE LOCK IS LOST NOW — expired, revoked, or
   *         taken over. Empty is terminal for this handle: the caller MUST abandon the critical
   *         section and MUST NOT treat it as a transient error ([2.5](#ct2-spi), FR-06, FR-12).
   */
  Optional<LockHandle> renew(LockHandle handle);

  /**
   * Compare-and-delete release of the caller's own grant (FR-05). Idempotent: releasing a handle
   * whose grant is already gone is a no-op.
   *
   * @return {@code true} if this handle's grant was the one removed; {@code false} if the grant had
   *         already expired or been revoked — informational only, never an error path.
   */
  boolean release(LockHandle handle);

  /**
   * Diagnostic snapshot of a key.
   *
   * @return the current grant, or empty if unheld at the instant of the read.
   * @implNote NEVER usable for a correctness decision (FR-07). The value is stale the moment it
   *          returns; only a fencing token presented to a resource decides anything.
   */
  Optional<LockInfo> inspect(String key);

  /**
   * Break-glass: deletes the current grant regardless of owner and advances the token past it, so the
   * displaced holder is fenced out at every resource (FR-08, INV-04).
   *
   * @param operator human or automation identity, recorded in {@code lock_revocation}; must be non-blank
   * @param reason   free text, recorded and alerted on; must be non-blank
   * @return the outcome, including the previous owner and both token values, for the audit trail
   */
  LockOutcome forceRevoke(String key, String operator, String reason);
}
```

## 2.3 `LockHandle`, `LockInfo`, and the four decisions {#ct2-records}

```java
package dev.lock.api;

/** Proof of a held grant. The only object that authorises a protected side effect. */
public record LockHandle(
    String key,
    String ownerId,
    String sessionId,
    long fencingToken,        // never null, never zero, strictly increasing per key (FR-02)
    long clientDeadlineNanos, // System.nanoTime() basis; authoritative for this JVM (FR-11)
    Instant serverExpiry) {}  // informational, wall-clock, for logs and dashboards only

/** Diagnostic view of a key returned by {@link LockService#inspect}. Advisory, never authoritative. */
public record LockInfo(
    String key,
    String ownerId,
    long fencingToken,
    Instant acquiredAt,
    Instant expiresAt,
    int waiterCount) {}

/**
 * Result of a {@link LockService#forceRevoke} — and of nothing else. The name {@code LockOutcome} is
 * reserved for this record: acquire returns {@code Optional<LockHandle>} (result GRANTED/CONTENDED,
 * never a {@code LockOutcome}), and the HTTP acquire body is {@code LockGrant}.
 */
public record LockOutcome(
    String key,
    boolean revoked,
    String previousOwnerId,
    long previousToken,
    long newTokenFloor) {}
```

| # | Decision | One-sentence defence |
|---|---|---|
| D1 | `release` takes the `LockHandle`, not the key | "Release someone else's lock" is then inexpressible in the type system rather than merely rejected at runtime, which removes a whole class of caller bug instead of logging it (FR-05). |
| D2 | `fencingToken` is a non-optional `long` on the handle | Every caller that holds proof of a lock is also holding the token, so the type system pushes it into the write path instead of letting fencing be an optional afterthought (FR-15). |
| D3 | `clientDeadlineNanos` is authoritative; `serverExpiry` is informational | The client owns the safety decision using a monotonic clock discounted by `lock.client.safety-margin`, because comparing a server wall-clock instant against a local one silently imports clock skew and NTP steps into a correctness check (FR-11). |
| D4 | `forceRevoke` exists, with `operator` and `reason` | Every lock service acquires a stuck lock at 3 a.m.; making the break-glass path a first-class, audited, token-advancing API is how it stops being an ad-hoc `DELETE` typed into a psql prompt (FR-08, FR-30). |

## 2.4 Exception hierarchy {#ct2-exceptions}

All extend `RuntimeException`; all live in `dev.lock.api`; none wrap a driver exception.

| Type | Thrown when | Retry-safe? | Caller obligation |
|---|---|---|---|
| `LockLostException` | The SDK's local deadline passed, `renew` returned empty, or `checkStillHeld` found the grant gone — `checkStillHeld` throws this directly rather than reporting it as a return value ([§2.6](#ct2-sdk)); also thrown when the caller uses a handle already known to be lost | **No** — terminal | Abort the critical section; do not re-acquire inside the same unit of work; emit `session_lost`/`lease_expired` |
| `FencedOutException` | A resource rejected a write because `presented_token <= stored fence` (`account.fence`, `ledger_entry.fence`, or `rail_high_water.highest_token`) | **No** — never | Stop; this process has been superseded. Incident-grade (NFR-06); log `fenced_out` with key, presented and stored token; count `lock.fenced.out` |
| `ContentionException` | A store-level conflict (serialization failure, CAS loop exhaustion) prevented a decision within the deadline | **Yes**, with jittered backoff | Retry the acquire, bounded (FR-28) |
| `NotLeaderException` | The addressed node is not the leader for the key's shard, or an etcd election is in flight | **Yes**, after a short delay | Retry, ideally after refreshing routing (NFR-02) |

Rule: "retry-safe" describes retrying the **acquire**, never the side effect. A `FencedOutException`
from the rail proxy means another worker may already own the payout; retrying the submission is exactly
the duplicate payment INV-02 forbids.

## 2.5 `LockStore` SPI and `SessionRegistry` {#ct2-spi}

Implemented twice: `dev.lock.server.store.pg` (`lockdb`, `fencing_token_seq`) and
`dev.lock.server.store.etcd` (`ModRevision` as the token, FR-10). Both must be observably identical.

```java
package dev.lock.api;

public interface LockStore {
  Optional<LockHandle> tryInsert(String key, String ownerId, String sessionId, Duration ttl);
  Optional<LockHandle> extend(String key, long fencingToken, Duration ttl);
  boolean deleteIfOwner(String key, long fencingToken);
  Optional<LockInfo> read(String key);
  LockOutcome revoke(String key, String operator, String reason);
  int reapExpired(Instant now);
  String backendId();   // "pg" | "etcd"; used only for the metric tag
}

public interface SessionRegistry {
  String openSession(String ownerId, Duration ttl);
  boolean heartbeat(String sessionId, Duration ttl);   // false == session is dead, do not retry
  int closeSession(String sessionId);                  // returns locks released
  Set<String> locksOf(String sessionId);
}
```

| Method | Semantics a backend MUST honour |
|---|---|
| `tryInsert` | Atomic; single-shot; allocates a token **strictly greater** than any token ever issued for that key, including revoked ones. Empty = held by another live grant. Must never block on a row lock longer than the call deadline. |
| `extend` | Conditional on `key` **and** `fencingToken` — that pair is the whole predicate, matching [C1 §1.4](C1-database-schemas.md#ct1-renew), because one global sequence makes a token unique per grant. Returns the same token with a later expiry, or **empty meaning LOST NOW**. A backend must not return empty for a network blip, a pool timeout, or a serialization failure — those are `ContentionException`. Conflating them is the most common bug in home-grown lock clients: the caller retries a lost lock as if it were transient and two holders proceed. |
| `deleteIfOwner` | Compare-and-delete on the pinned pair `(lock_key, fencing_token)` and nothing else ([C1 §1.4](C1-database-schemas.md#ct1-renew), [C3 L6](C3-http-surfaces.md#ct3-lock)); `false` only when the grant no longer matches. Never deletes another grant. Despite the name, ownership is proved by the token, not by an `ownerId` argument. |
| `read` | Point-in-time, may be a follower read, explicitly non-authoritative. |
| `revoke` | Deletes and **advances the token floor** in the same atomic step, appends `lock_revocation`. Reusing or lowering a token silently disables fencing for that key — INV-04. |
| `reapExpired` | Idempotent, safe to run concurrently on many replicas, uses `lock_entry_expiry_idx`; reaping is a liveness mechanism only — expiry is already true before the reaper runs. |
| `heartbeat` | `false` is terminal: the session is gone and every lock on it is gone with it (FR-04). Cascade via `lock_entry_session_idx`. |

## 2.6 Client SDK surface {#ct2-sdk}

`dev.lock.client` — the only artifact application code depends on besides `lock-api`.

| Member | Signature | Contract |
|---|---|---|
| Session | `LockClientSession openSession(String ownerId)` | Opens one session, starts the heartbeat scheduler, `AutoCloseable`; closing releases every lock on it |
| Held check | `void checkStillHeld(LockHandle handle) throws LockLostException` | Compares `System.nanoTime()` against `clientDeadlineNanos`, optionally confirming with the server (FR-13). **Returns normally or throws `LockLostException` — there is no boolean.** A boolean invites `if (checkStillHeld(h)) { … }` with no `else`, i.e. a silently skipped side effect; the design intent is that the application aborts loudly the moment the local deadline has passed |
| Lost listener | `void onLockLost(java.util.function.Consumer<LockHandle> listener)` | Fired as soon as the local deadline passes or a heartbeat fails; runs on the SDK's scheduler thread, must not block (FR-12) |

**`checkStillHeld` narrows the race window; it cannot close it.** The lease can expire in the
nanoseconds between the check returning normally and the side effect leaving the process, and no amount
of checking removes that gap. It is a **liveness optimisation** that stops obviously-doomed work early. The
**fencing token is the safety mechanism** — the two enforcement points in [C3 §3.5](C3-http-surfaces.md#ct3-railproxy) and the
`paydb` conditional updates ([C1 §1.6](C1-database-schemas.md#ct1-fenced)) are what make a stale holder harmless.

## 2.7 Token propagation: parameter or header, never thread-local {#ct2-propagation}

| Hop | How the token travels |
|---|---|
| Executor → resource/proxy HTTP | `X-Fencing-Token` header, with `X-Owner-Id` and `X-Idempotency-Key` |
| Any internal Java call | An explicit `long fencingToken` parameter, or the `LockHandle` itself |
| Async / executor / reactive boundary | Captured explicitly into the task's arguments |

Rule: the token is **never** carried in a `ThreadLocal`, an MDC entry, a Spring request scope, or a
`ScopedValue`. Failure mode: the first time work moves to another thread pool — a `CompletableFuture`
continuation, a `@Async` method, a virtual-thread executor, a Kafka listener — the value is silently
absent, the write is attempted with a null or zero token, and either the write is rejected as fenced for
the wrong reason or, worse, a code path defaults to "no token" and skips the check. An explicit
parameter turns that into a compile error.

## 2.8 Threading and nullability {#ct2-threading}

| Convention | Rule |
|---|---|
| Thread safety | `LockService`, `LockStore`, `SessionRegistry` implementations are thread-safe and shareable as singletons; `LockHandle`, `LockInfo`, `LockOutcome` are immutable records and free to share |
| Blocking | `acquire` is the only method that may block beyond a round trip, bounded by `maxWait`; every other method is single-round-trip. No method blocks uninterruptibly |
| Nullability | No parameter and no return value is ever `null`; absence is `Optional`. Blank strings are rejected as `IllegalArgumentException`, which is a caller bug, not a lock outcome |
| Time | Durations are `java.time.Duration`; deadlines inside a JVM are `System.nanoTime()`; wall-clock values are `Instant` in UTC and are display-only |
| Cancellation | Interrupting a thread inside `acquire` abandons the wait and, if a grant was already recorded, releases it before propagating |
| Logging | `lock-api` emits nothing; all events in [C5 observability] are emitted by the server, SDK and executor modules |
