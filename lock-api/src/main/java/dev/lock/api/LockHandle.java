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
 * Proof of a held grant. The only object that authorises a protected side effect.
 *
 * <p><strong>{@code clientDeadlineNanos} is authoritative; {@code serverExpiry} is
 * informational.</strong> Whether this JVM may still act on the grant is decided only against
 * {@code clientDeadlineNanos}, on the monotonic {@link System#nanoTime()} clock. {@code
 * serverExpiry} is the server's wall-clock view, for logs and dashboards only: comparing it against
 * a local clock silently brings clock skew and NTP steps into a correctness check (C2 §2.3, D3;
 * FR-11).
 *
 * <p>Every holder of a handle also holds its fencing token, and must present it to every protected
 * resource (C2 §2.3, D2). A renewal returns a new handle with the same {@code fencingToken}.
 *
 * <p>Immutable and free to share between threads. No component is ever {@code null}.
 *
 * @param key the lock key; never blank
 * @param ownerId the identity the grant was issued to; never blank
 * @param sessionId the session the grant is attached to; never blank
 * @param fencingToken never null, never zero, strictly increasing per key (FR-02)
 * @param clientDeadlineNanos {@link System#nanoTime()} basis; authoritative for this JVM (FR-11)
 * @param serverExpiry informational, wall-clock, for logs and dashboards only
 */
public record LockHandle(
    String key,
    String ownerId,
    String sessionId,
    long fencingToken,
    long clientDeadlineNanos,
    Instant serverExpiry) {

  /**
   * Validates the components.
   *
   * @throws NullPointerException if {@code key}, {@code ownerId}, {@code sessionId} or {@code
   *     serverExpiry} is {@code null}
   * @throws IllegalArgumentException if {@code key}, {@code ownerId} or {@code sessionId} is blank,
   *     or {@code fencingToken} is not positive
   */
  public LockHandle {
    if (Objects.requireNonNull(key, "key").isBlank()) {
      throw new IllegalArgumentException("key must not be blank");
    }
    if (Objects.requireNonNull(ownerId, "ownerId").isBlank()) {
      throw new IllegalArgumentException("ownerId must not be blank");
    }
    if (Objects.requireNonNull(sessionId, "sessionId").isBlank()) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    if (fencingToken <= 0) {
      throw new IllegalArgumentException("fencingToken must be positive, was " + fencingToken);
    }
    Objects.requireNonNull(serverExpiry, "serverExpiry");
  }
}
