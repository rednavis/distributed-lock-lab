/*
 * Copyright 2026 The distributed-lock-lab Authors
 *
 * Part of distributed-lock-lab. The operator it models, Northwind Pay, a mid-size payment
 * service provider, is fictional.
 *
 * Licensed under the Apache License, Version 2.0. SPDX-License-Identifier: Apache-2.0
 */
package dev.lock.api;

import java.time.Instant;
import java.util.Objects;

/**
 * Diagnostic view of a key returned by {@link LockService#inspect}. Advisory, never authoritative.
 *
 * <p><strong>Never usable for a correctness decision</strong> (FR-07). The snapshot is stale the
 * moment it is returned and proves nothing; only a fencing token presented to a resource decides
 * anything.
 *
 * <p>Immutable and free to share between threads. No component is ever {@code null}; the {@link
 * Instant} values are UTC wall-clock and display-only.
 *
 * @param key the lock key; never blank
 * @param ownerId the holder at the instant of the read; never blank
 * @param fencingToken the holder's fencing token at the instant of the read; always positive
 * @param acquiredAt when the grant was issued
 * @param expiresAt when the lease was due to lapse, as of the read
 * @param waiterCount how many callers were waiting for the key at the instant of the read
 */
public record LockInfo(
    String key,
    String ownerId,
    long fencingToken,
    Instant acquiredAt,
    Instant expiresAt,
    int waiterCount) {

  /**
   * Validates the components.
   *
   * @throws NullPointerException if {@code key}, {@code ownerId}, {@code acquiredAt} or {@code
   *     expiresAt} is {@code null}
   * @throws IllegalArgumentException if {@code key} or {@code ownerId} is blank, or {@code
   *     fencingToken} is not positive
   */
  public LockInfo {
    if (Objects.requireNonNull(key, "key").isBlank()) {
      throw new IllegalArgumentException("key must not be blank");
    }
    if (Objects.requireNonNull(ownerId, "ownerId").isBlank()) {
      throw new IllegalArgumentException("ownerId must not be blank");
    }
    if (fencingToken <= 0) {
      throw new IllegalArgumentException("fencingToken must be positive, was " + fencingToken);
    }
    Objects.requireNonNull(acquiredAt, "acquiredAt");
    Objects.requireNonNull(expiresAt, "expiresAt");
  }
}
