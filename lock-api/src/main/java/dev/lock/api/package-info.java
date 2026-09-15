/*
 * Copyright 2026 The distributed-lock-lab Authors
 *
 * Part of distributed-lock-lab. The operator it models, Northwind Pay, a mid-size payment
 * service provider, is fictional.
 *
 * Licensed under the Apache License, Version 2.0. SPDX-License-Identifier: Apache-2.0
 */
/**
 * The lock contract that both lock backends and all five services share (C2): the caller-facing
 * {@link dev.lock.api.LockService}, the records {@link dev.lock.api.LockHandle}, {@link
 * dev.lock.api.LockInfo} and {@link dev.lock.api.LockOutcome}, the four failures, and the backend
 * SPI {@link dev.lock.api.LockStore} and {@link dev.lock.api.SessionRegistry}. Signatures only: no
 * implementation lives here.
 *
 * <h2>Zero dependencies</h2>
 *
 * <p>The compile classpath of this module is the JDK and nothing else — no Spring, no JDBC driver,
 * no etcd client, no JSON library, no logging facade, and no annotations of any kind (C2 §2.1). CI
 * enforces it as a dependency-count assertion (NFR-16), not as a convention. Once one annotation or
 * framework type appears in a signature, the SPI can no longer be implemented outside that runtime.
 *
 * <p>Consequently time is {@code long} nanoseconds or {@link java.time.Instant}, never a framework
 * clock; identifiers are {@code String}; failures are the exceptions of this package, never a
 * wrapped driver exception or store status; and absence is {@link java.util.Optional}, never {@code
 * null} and never a nullability annotation. This module emits no logs and no metrics.
 *
 * <h2>Token propagation</h2>
 *
 * <p>A fencing token reaches a callee as an explicit {@code long fencingToken} parameter or inside
 * the {@link dev.lock.api.LockHandle}; across an async, executor or reactive boundary it is
 * captured explicitly into the task's arguments; between processes it is the {@code
 * X-Fencing-Token} header (C2 §2.7).
 *
 * <p>It is <strong>never</strong> carried in a thread-local variable, an MDC entry, a request scope
 * or a scoped value. The first time work moves to another thread pool, such a value is silently
 * absent: the write is attempted with a null or zero token, and either it is rejected as fenced for
 * the wrong reason or, worse, a code path defaults to "no token" and skips the check. An explicit
 * parameter turns that into a compile error.
 *
 * <h2>Threading and nullability</h2>
 *
 * <p>Implementations of {@code LockService}, {@code LockStore} and {@code SessionRegistry} are
 * thread-safe and shareable as singletons; the three records are immutable and free to share. No
 * parameter and no return value is ever {@code null}. Blank strings are rejected with {@link
 * IllegalArgumentException}, which is a caller bug, not a lock outcome. Durations are {@link
 * java.time.Duration}; deadlines inside a JVM are {@link System#nanoTime()}; wall-clock values are
 * UTC {@code Instant}s and display-only (C2 §2.8).
 */
package dev.lock.api;
