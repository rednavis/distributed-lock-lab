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
import java.time.Instant;
import java.util.Optional;

/**
 * The backend SPI (C2 §2.5). Implemented twice: {@code dev.lock.server.store.pg} ({@code lockdb},
 * {@code fencing_token_seq}) and {@code dev.lock.server.store.etcd} ({@code ModRevision} as the
 * token, FR-10). Both must be observably identical.
 *
 * <p><strong>An empty {@code Optional} means different things on different methods, and a caller
 * must never conflate them.</strong> From {@link #tryInsert} it means "held by another live grant —
 * not mine". From {@link #extend} it means "LOST NOW". A transient failure is neither: it is a
 * {@link ContentionException}.
 *
 * <p><strong>Threading.</strong> Implementations are thread-safe and shareable as singletons. Every
 * method is a single round trip, and none blocks uninterruptibly (C2 §2.8).
 *
 * <p><strong>Nullability.</strong> No parameter and no return value is ever {@code null}; absence
 * is {@link Optional}. Failures are the exceptions of this package, never a wrapped driver
 * exception or store status (C2 §2.1).
 */
public interface LockStore {

  /**
   * Inserts a new grant for {@code key}. Atomic and single-shot. Allocates a token <strong>strictly
   * greater</strong> than any token ever issued for that key, including revoked ones. Must never
   * block on a row lock longer than the call deadline.
   *
   * @param key the lock key
   * @param ownerId the identity the grant is issued to
   * @param sessionId the session the grant is attached to
   * @param ttl the lease length
   * @return the new grant, or empty meaning the key is held by another live grant — not mine
   */
  Optional<LockHandle> tryInsert(String key, String ownerId, String sessionId, Duration ttl);

  /**
   * Extends the grant identified by {@code key} and {@code fencingToken}. That pair is the whole
   * predicate, matching C1 §1.4, because one global sequence makes a token unique per grant.
   *
   * <p><strong>{@link Optional#empty()} means LOST NOW</strong> — expired, revoked, or taken over.
   * It is terminal and must never be treated as transient. A backend must not return empty for a
   * network blip, a pool timeout, or a serialization failure — those are {@link
   * ContentionException}. Conflating them is the most common bug in home-grown lock clients: the
   * caller retries a lost lock as if it were transient and two holders proceed.
   *
   * @param key the lock key
   * @param fencingToken the token of the grant to extend
   * @param ttl the new lease length
   * @return the same token with a later expiry, or empty meaning LOST NOW
   * @throws ContentionException a transient store failure prevented a decision; the grant may still
   *     be held
   */
  Optional<LockHandle> extend(String key, long fencingToken, Duration ttl);

  /**
   * Compare-and-delete on the pinned pair ({@code lock_key}, {@code fencing_token}) and nothing
   * else (C1 §1.4, C3 L6). Never deletes another grant. Despite the name, ownership is proved by
   * the token, not by an {@code ownerId} argument.
   *
   * @param key the lock key
   * @param fencingToken the token of the grant to delete
   * @return {@code true} if the grant was deleted; {@code false} only when the grant no longer
   *     matches
   */
  boolean deleteIfOwner(String key, long fencingToken);

  /**
   * Point-in-time read of a key; may be a follower read.
   *
   * <p><strong>Explicitly non-authoritative: never usable for a correctness decision.</strong> The
   * value is stale the moment it returns; only a fencing token presented to a resource decides
   * anything (FR-07).
   *
   * @param key the lock key
   * @return the current grant, or empty if unheld at the instant of the read
   */
  Optional<LockInfo> read(String key);

  /**
   * Deletes the current grant regardless of owner and <strong>advances the token floor</strong> in
   * the same atomic step, and appends a {@code lock_revocation} row. Reusing or lowering a token
   * silently disables fencing for that key (INV-04).
   *
   * @param key the lock key
   * @param operator human or automation identity, recorded in {@code lock_revocation}
   * @param reason free text, recorded in {@code lock_revocation}
   * @return the outcome, including the previous owner and both token values
   */
  LockOutcome revoke(String key, String operator, String reason);

  /**
   * Removes grants whose lease has expired as of {@code now}. Idempotent and safe to run
   * concurrently on many replicas; uses {@code lock_entry_expiry_idx}.
   *
   * <p>Reaping is a liveness mechanism only: a grant is already expired before the reaper runs.
   *
   * @param now the instant expiry is judged against
   * @return the number of grants removed
   */
  int reapExpired(Instant now);

  /**
   * Names this backend for the metric tag, and for nothing else.
   *
   * @return {@code "pg"} or {@code "etcd"}
   */
  String backendId();
}
