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
 * A protected resource rejected a write from a superseded holder. <strong>Terminal: never
 * retry-safe, and incident-grade.</strong>
 *
 * <p><strong>Trigger.</strong> A resource rejected a write because the presented token was less
 * than or equal to the stored fence ({@code account.fence}, {@code ledger_entry.fence}, or {@code
 * rail_high_water.highest_token}). A newer holder exists.
 *
 * <p><strong>Retry verdict.</strong> No — never. Another worker may already own the payout;
 * retrying the submission is exactly the duplicate payment INV-02 forbids.
 *
 * <p><strong>Caller obligation.</strong> Stop; this process has been superseded. Abort the whole
 * critical section: do not retry, and do not re-acquire and continue. Log {@code fenced_out} with
 * the key, the presented token and the stored token — {@link #key()}, {@link #presentedToken()} and
 * {@link #highestToken()} — and count {@code lock.fenced.out}.
 *
 * <p><strong>Operator action.</strong> Incident-grade (NFR-06): follow the {@code fenced-out}
 * runbook (docs/08 §8.2.1). Fix the client — the lease length, the hold time, the safety margin, or
 * the pause that outlived the lease — and re-drive the delayed payout through the normal claim
 * path. Never disable a fencing check to stop the alerts.
 *
 * <p>One of the four failures of this package (C2 §2.4). All four are unchecked, none wraps a
 * driver exception, and each carries its retry verdict in its type alone: {@link
 * ContentionException} and {@link NotLeaderException} are retry-safe; {@link LockLostException} and
 * {@code FencedOutException} are terminal. "Retry-safe" describes retrying the acquire, never the
 * protected side effect.
 */
public class FencedOutException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The lock key the rejected write was made under. */
  private final String key;

  /** The fencing token the rejected caller presented. */
  private final long presentedToken;

  /** The token already stored at the resource, which {@link #presentedToken} did not exceed. */
  private final long highestToken;

  /**
   * Creates the exception.
   *
   * @param key the lock key the rejected write was made under
   * @param presentedToken the fencing token the rejected caller presented
   * @param highestToken the token already stored at the resource, which {@code presentedToken} did
   *     not exceed
   */
  public FencedOutException(String key, long presentedToken, long highestToken) {
    super(
        "fenced out on "
            + key
            + ": presented token "
            + presentedToken
            + " did not exceed stored token "
            + highestToken);
    this.key = key;
    this.presentedToken = presentedToken;
    this.highestToken = highestToken;
  }

  /**
   * Returns the lock key the rejected write was made under.
   *
   * @return the lock key
   */
  public String key() {
    return key;
  }

  /**
   * Returns the fencing token the rejected caller presented — {@code presentedToken} in the {@code
   * fenced_out} log event and the {@code FENCED_OUT} body.
   *
   * @return the presented token
   */
  public long presentedToken() {
    return presentedToken;
  }

  /**
   * Returns the token already stored at the resource, which the presented token did not exceed —
   * {@code highestToken} in the {@code fenced_out} log event and the {@code FENCED_OUT} body.
   *
   * @return the stored token
   */
  public long highestToken() {
    return highestToken;
  }
}
