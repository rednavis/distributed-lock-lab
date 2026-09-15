/*
 * Copyright 2026 The distributed-lock-lab Authors
 *
 * Part of distributed-lock-lab. The operator it models, Northwind Pay, a mid-size payment
 * service provider, is fictional.
 *
 * Licensed under the Apache License, Version 2.0. SPDX-License-Identifier: Apache-2.0
 */
package dev.lock.api;

import java.util.Objects;

/**
 * Result of a {@link LockService#forceRevoke} — and of nothing else. The name {@code LockOutcome}
 * is reserved for this record: acquire returns {@code Optional<LockHandle>} (result
 * GRANTED/CONTENDED, never a {@code LockOutcome}), and the HTTP acquire body is {@code LockGrant}.
 *
 * <p>Carries the previous owner and both token values for the audit trail.
 *
 * <p>Immutable and free to share between threads. No component is ever {@code null}.
 *
 * @param key the lock key the revoke addressed; never blank
 * @param revoked {@code true} if a grant was deleted
 * @param previousOwnerId the owner of the deleted grant; never {@code null}
 * @param previousToken the fencing token of the deleted grant
 * @param newTokenFloor the token floor after the revoke, advanced past {@code previousToken} so the
 *     displaced holder is fenced out at every resource (INV-04)
 */
public record LockOutcome(
    String key, boolean revoked, String previousOwnerId, long previousToken, long newTokenFloor) {

  /**
   * Validates the components.
   *
   * @throws NullPointerException if {@code key} or {@code previousOwnerId} is {@code null}
   * @throws IllegalArgumentException if {@code key} is blank
   */
  public LockOutcome {
    if (Objects.requireNonNull(key, "key").isBlank()) {
      throw new IllegalArgumentException("key must not be blank");
    }
    Objects.requireNonNull(previousOwnerId, "previousOwnerId");
  }
}
