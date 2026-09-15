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
 * The store could not reach a decision in time. <strong>Retry-safe, with jittered backoff.</strong>
 *
 * <p><strong>Trigger.</strong> A store-level conflict (serialization failure, CAS loop exhaustion)
 * prevented a decision within the deadline. A network blip or a pool timeout is this exception too:
 * a backend must never report it as an empty {@link LockStore#extend} result, which means the lock
 * is lost (C2 §2.5).
 *
 * <p><strong>Retry verdict.</strong> Yes, with jittered backoff.
 *
 * <p><strong>Caller obligation.</strong> Retry the acquire, bounded (FR-28). When the retry budget
 * runs out, do not proceed: a worker that cannot acquire a lock does not execute the payout
 * (FR-27).
 *
 * <p><strong>Operator action.</strong> None for a single occurrence. Sustained, it shows up as
 * acquire unavailability: follow the {@code lock-acquire-unavailable-burn} runbook (docs/08
 * §8.2.4). Never make acquire fail open.
 *
 * <p>One of the four failures of this package (C2 §2.4). All four are unchecked, none wraps a
 * driver exception, and each carries its retry verdict in its type alone: {@code
 * ContentionException} and {@link NotLeaderException} are retry-safe; {@link LockLostException} and
 * {@link FencedOutException} are terminal. "Retry-safe" describes retrying the acquire, never the
 * protected side effect.
 */
public class ContentionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message which conflict prevented the decision
   */
  public ContentionException(String message) {
    super(message);
  }
}
