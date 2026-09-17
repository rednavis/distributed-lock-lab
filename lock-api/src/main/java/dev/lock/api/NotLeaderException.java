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
 * The addressed node cannot decide for this key right now. <strong>Retry-safe, after a short
 * delay.</strong>
 *
 * <p><strong>Trigger.</strong> The addressed node is not the leader for the key's shard, or an etcd
 * election is in flight.
 *
 * <p><strong>Retry verdict.</strong> Yes, after a short delay.
 *
 * <p><strong>Caller obligation.</strong> Retry, ideally after refreshing routing (NFR-02).
 *
 * <p><strong>Operator action.</strong> None for a single occurrence: an etcd leader election costs
 * at most 2 s of shard unavailability, within a declared monthly budget (NFR-02). Sustained or
 * frequent, follow the {@code etcd-quorum-degraded} / {@code etcd-elections-over-budget} runbook
 * (docs/08 §8.2.7).
 *
 * <p>One of the four failures of this package (C2 §2.4). All four are unchecked, none wraps a
 * driver exception, and each carries its retry verdict in its type alone: {@link
 * ContentionException} and {@code NotLeaderException} are retry-safe; {@link LockLostException} and
 * {@link FencedOutException} are terminal. "Retry-safe" describes retrying the acquire, never the
 * protected side effect.
 */
public class NotLeaderException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message which node was addressed and why it cannot decide
   */
  public NotLeaderException(String message) {
    super(message);
  }
}
