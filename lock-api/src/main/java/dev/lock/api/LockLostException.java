/*
 * Copyright 2026 The distributed-lock-lab Authors
 *
 * Part of distributed-lock-lab. The operator it models, Northwind Pay, a mid-size payment
 * service provider, is fictional.
 *
 * Licensed under the Apache License, Version 2.0. SPDX-License-Identifier: Apache-2.0
 */
package dev.lock.api;

/**
 * The caller no longer holds the grant it is acting on. <strong>Terminal: never
 * retry-safe.</strong>
 *
 * <p><strong>Trigger.</strong> The SDK's local deadline passed, {@link LockService#renew} returned
 * empty, or {@code checkStillHeld} found the grant gone — {@code checkStillHeld} throws this
 * directly rather than reporting it as a return value (C2 §2.6). Also thrown when the caller uses a
 * handle already known to be lost.
 *
 * <p><strong>Retry verdict.</strong> No — terminal. The grant is gone, and a write made with its
 * token can only be fenced out.
 *
 * <p><strong>Caller obligation.</strong> Abort the critical section; do not re-acquire inside the
 * same unit of work; emit {@code session_lost} or {@code lease_expired}.
 *
 * <p><strong>Operator action.</strong> None for a single occurrence. A rising rate is the early
 * warning that precedes fenced-out writes: follow the {@code lease-expiry-elevated} runbook
 * (docs/08 §8.2.6) — right-size the lease length and the client safety margin together, and shorten
 * the critical section. Do not hide it behind a very large lease.
 *
 * <p>One of the four failures of this package (C2 §2.4). All four are unchecked, none wraps a
 * driver exception, and each carries its retry verdict in its type alone: {@link
 * ContentionException} and {@link NotLeaderException} are retry-safe; {@code LockLostException} and
 * {@link FencedOutException} are terminal. "Retry-safe" describes retrying the acquire, never the
 * protected side effect.
 */
public class LockLostException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message which grant was lost and how the loss was detected
   */
  public LockLostException(String message) {
    super(message);
  }
}
