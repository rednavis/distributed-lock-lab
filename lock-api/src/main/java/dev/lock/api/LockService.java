/*
 * Copyright 2026 The distributed-lock-lab Authors
 *
 * Part of distributed-lock-lab. The operator it models, Northwind Pay, a mid-size payment
 * service provider, is fictional.
 *
 * Licensed under the Apache License, Version 2.0. SPDX-License-Identifier: Apache-2.0
 */
package dev.lock.api;

import java.time.Duration;
import java.util.Optional;

/**
 * The whole caller-visible lock contract (C2 §2.2): six methods, and no convenience overloads.
 *
 * <p><strong>Threading.</strong> Every implementation is thread-safe and shareable as a singleton.
 * {@link #acquire} is the only method that may block beyond one round trip, and only for up to
 * {@code maxWait}; every other method is a single round trip. No method blocks uninterruptibly (C2
 * §2.8).
 *
 * <p><strong>Nullability.</strong> No parameter and no return value is ever {@code null}; absence
 * is {@link Optional}. A blank string argument is rejected with {@link IllegalArgumentException},
 * which is a caller bug, not a lock outcome (C2 §2.8).
 *
 * <p><strong>Token propagation.</strong> The fencing token reaches a callee as an explicit {@code
 * long fencingToken} parameter or inside the {@link LockHandle} itself, never through a
 * thread-bound or request-scoped slot (C2 §2.7).
 */
public interface LockService {

  /**
   * Acquires {@code key}, waiting up to {@code maxWait} for a conflicting holder to go away.
   * Retries internally with bounded, jittered backoff (FR-28). Never blocks longer than {@code
   * maxWait} plus one in-flight round trip.
   *
   * <p><strong>{@link Optional#empty()} means "not mine, do not proceed".</strong> It is the
   * expected outcome when someone else legitimately holds the lock, not an error, and it must never
   * be retried in a tight loop. The caller must not perform the protected side effect (FR-27).
   *
   * <p>Interrupting the calling thread abandons the wait and, if a grant was already recorded,
   * releases it before the interrupt propagates (C2 §2.8).
   *
   * @param key the lock key; must be non-blank
   * @param ownerId the caller's identity, recorded on the grant; must be non-blank
   * @param ttl the lease length requested for the grant
   * @param maxWait how long to wait for a conflicting holder to go away
   * @return a handle if the lock was granted; {@link java.util.Optional#empty()} if {@code maxWait}
   *     elapsed while the lock was legitimately held by someone else. Empty is NOT an error and
   *     MUST NOT be retried in a tight loop; it means "not mine, do not proceed" (FR-27).
   * @throws ContentionException a store-level conflict could not be resolved within the deadline
   * @throws NotLeaderException this node is not the current leader for the key's shard
   */
  Optional<LockHandle> acquire(String key, String ownerId, Duration ttl, Duration maxWait);

  /**
   * Single-shot acquire: one attempt, no waiting, no backoff.
   *
   * <p><strong>{@link Optional#empty()} means "not mine, do not proceed".</strong> It is the
   * normal, expected outcome under contention, not an error, and it must never be retried in a
   * tight loop. The caller must not perform the protected side effect (FR-27).
   *
   * @param key the lock key; must be non-blank
   * @param ownerId the caller's identity, recorded on the grant; must be non-blank
   * @param ttl the lease length requested for the grant
   * @return a handle, or empty if the lock is currently held by anyone else (including a holder
   *     whose lease has not yet been reaped). Empty is the normal, expected outcome under
   *     contention.
   */
  Optional<LockHandle> tryAcquire(String key, String ownerId, Duration ttl);

  /**
   * Extends the lease of a grant this caller still owns.
   *
   * <p><strong>{@link Optional#empty()} means THE LOCK IS LOST NOW.</strong> It is terminal for
   * this handle and never a transient error: the caller must abandon the critical section at once
   * and must not retry the renewal. A caller that treats it as transient keeps working on a lock
   * someone else may already hold, and two holders proceed (C2 §2.5).
   *
   * @param handle the handle of the grant to extend
   * @return a NEW handle carrying the same {@code fencingToken} and a later deadline, or {@link
   *     java.util.Optional#empty()} meaning THE LOCK IS LOST NOW — expired, revoked, or taken over.
   *     Empty is terminal for this handle: the caller MUST abandon the critical section and MUST
   *     NOT treat it as a transient error (C2 §2.5, FR-06, FR-12).
   */
  Optional<LockHandle> renew(LockHandle handle);

  /**
   * Compare-and-delete release of the caller's own grant (FR-05). Idempotent: releasing a handle
   * whose grant is already gone is a no-op.
   *
   * <p>Taking the handle rather than the key makes "release someone else's lock" inexpressible in
   * the type system (C2 §2.3, D1).
   *
   * @param handle the handle of the grant to release
   * @return {@code true} if this handle's grant was the one removed; {@code false} if the grant had
   *     already expired or been revoked — informational only, never an error path.
   */
  boolean release(LockHandle handle);

  /**
   * Diagnostic snapshot of a key.
   *
   * <p><strong>Advisory only: never usable for a correctness decision.</strong> Do not decide
   * whether to proceed, retry or write from this value.
   *
   * @param key the lock key; must be non-blank
   * @return the current grant, or empty if unheld at the instant of the read.
   * @implNote NEVER usable for a correctness decision (FR-07). The value is stale the moment it
   *     returns; only a fencing token presented to a resource decides anything.
   */
  Optional<LockInfo> inspect(String key);

  /**
   * Break-glass: deletes the current grant regardless of owner and advances the token past it, so
   * the displaced holder is fenced out at every resource (FR-08, INV-04).
   *
   * @param key the lock key; must be non-blank
   * @param operator human or automation identity, recorded in {@code lock_revocation}; must be
   *     non-blank
   * @param reason free text, recorded and alerted on; must be non-blank
   * @return the outcome, including the previous owner and both token values, for the audit trail
   */
  LockOutcome forceRevoke(String key, String operator, String reason);
}
