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
import java.util.Set;

/**
 * The session lifecycle half of the backend SPI (C2 §2.5). A client holds one session, kept alive
 * by heartbeat, to which all its locks attach; when the session dies, every lock on it is released
 * (FR-04).
 *
 * <p><strong>Threading.</strong> Implementations are thread-safe and shareable as singletons. Every
 * method is a single round trip, and none blocks uninterruptibly (C2 §2.8).
 *
 * <p><strong>Nullability.</strong> No parameter and no return value is ever {@code null}. A blank
 * string argument is rejected with {@link IllegalArgumentException} (C2 §2.8).
 */
public interface SessionRegistry {

  /**
   * Opens a session.
   *
   * @param ownerId the identity that owns the session
   * @param ttl how long the session lives without a heartbeat
   * @return the id of the new session
   */
  String openSession(String ownerId, Duration ttl);

  /**
   * Keeps a session alive for another {@code ttl}.
   *
   * <p><strong>{@code false} is terminal.</strong> The session is gone and every lock on it is gone
   * with it (FR-04), cascaded via {@code lock_entry_session_idx}. Do not retry the heartbeat.
   *
   * @param sessionId the session to keep alive
   * @param ttl how long the session lives from this heartbeat
   * @return {@code true} if the session was extended; {@code false} if the session is dead — do not
   *     retry
   */
  boolean heartbeat(String sessionId, Duration ttl);

  /**
   * Closes a session and releases every lock attached to it.
   *
   * @param sessionId the session to close
   * @return the number of locks released
   */
  int closeSession(String sessionId);

  /**
   * Lists the locks attached to a session.
   *
   * @param sessionId the session to inspect
   * @return the keys of the locks attached to the session; empty if none
   */
  Set<String> locksOf(String sessionId);
}
