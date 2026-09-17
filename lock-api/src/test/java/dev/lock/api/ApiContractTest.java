/*
 * Copyright 2026 The distributed-lock-lab Authors
 *
 * Part of distributed-lock-lab. The operator it models, Northwind Pay, a mid-size payment
 * service provider, is fictional.
 *
 * Licensed under the Apache License, Version 2.0. SPDX-License-Identifier: Apache-2.0
 */
package dev.lock.api;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.io.IOException;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Guards on the contract types of C2 §2.2–2.5 (T-004 §4). */
class ApiContractTest {

  private static final Instant EXPIRY = Instant.parse("2026-01-01T00:00:00Z");

  private static final List<Class<? extends RuntimeException>> RETRY_SAFE =
      List.of(ContentionException.class, NotLeaderException.class);

  private static final List<Class<? extends RuntimeException>> TERMINAL =
      List.of(LockLostException.class, FencedOutException.class);

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\t\n"})
  void recordsRejectABlankKey(String blank) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new LockHandle(blank, "owner-1", "session-1", 1, 0, EXPIRY));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new LockInfo(blank, "owner-1", 1, EXPIRY, EXPIRY, 0));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new LockOutcome(blank, true, "owner-1", 1, 2));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " "})
  void recordsRejectABlankOwnerOrSession(String blank) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new LockHandle("payout:1", blank, "session-1", 1, 0, EXPIRY));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new LockHandle("payout:1", "owner-1", blank, 1, 0, EXPIRY));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new LockInfo("payout:1", blank, 1, EXPIRY, EXPIRY, 0));
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1, Long.MIN_VALUE})
  void recordsRejectAZeroOrNegativeFencingToken(long token) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new LockHandle("payout:1", "owner-1", "session-1", token, 0, EXPIRY));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new LockInfo("payout:1", "owner-1", token, EXPIRY, EXPIRY, 0));
  }

  @Test
  void recordsRejectNullRatherThanCarryIt() {
    assertThatNullPointerException()
        .isThrownBy(() -> new LockHandle(null, "owner-1", "session-1", 1, 0, EXPIRY));
    assertThatNullPointerException()
        .isThrownBy(() -> new LockHandle("payout:1", "owner-1", "session-1", 1, 0, null));
    assertThatNullPointerException()
        .isThrownBy(() -> new LockInfo("payout:1", "owner-1", 1, null, EXPIRY, 0));
    assertThatNullPointerException()
        .isThrownBy(() -> new LockOutcome("payout:1", false, null, 0, 0));
  }

  @Test
  void lockHandleEqualityIsComponentWise() {
    LockHandle handle = new LockHandle("payout:1", "owner-1", "session-1", 42, 1_000, EXPIRY);

    assertThat(new LockHandle("payout:1", "owner-1", "session-1", 42, 1_000, EXPIRY))
        .isEqualTo(handle)
        .hasSameHashCodeAs(handle);
    assertThat(new LockHandle("payout:1", "owner-1", "session-1", 43, 1_000, EXPIRY))
        .isNotEqualTo(handle);
    assertThat(new LockHandle("payout:1", "owner-1", "session-2", 42, 1_000, EXPIRY))
        .isNotEqualTo(handle);
    assertThat(new LockHandle("payout:1", "owner-1", "session-1", 42, 2_000, EXPIRY))
        .isNotEqualTo(handle);
  }

  @Test
  void retrySafeFailuresAreDistinguishableFromTerminalOnesByTypeAlone() {
    for (Class<? extends RuntimeException> retrySafe : RETRY_SAFE) {
      for (Class<? extends RuntimeException> terminal : TERMINAL) {
        assertThat(retrySafe.isAssignableFrom(terminal))
            .as(
                "catching %s must not catch %s",
                retrySafe.getSimpleName(), terminal.getSimpleName())
            .isFalse();
        assertThat(terminal.isAssignableFrom(retrySafe))
            .as(
                "catching %s must not catch %s",
                terminal.getSimpleName(), retrySafe.getSimpleName())
            .isFalse();
      }
    }
    // No flag to consult: the type is the verdict.
    Stream.concat(RETRY_SAFE.stream(), TERMINAL.stream())
        .forEach(
            type -> {
              assertThat(type.getDeclaredMethods())
                  .noneMatch(method -> isBoolean(method.getReturnType()));
              assertThat(type.getDeclaredFields()).noneMatch(field -> isBoolean(field.getType()));
            });
  }

  @Test
  void fencedOutExceptionExposesTheKeyAndBothTokenValues() {
    FencedOutException fenced = new FencedOutException("payout:acct-7", 41, 42);

    assertThat(fenced.key()).isEqualTo("payout:acct-7");
    assertThat(fenced.presentedToken()).isEqualTo(41);
    assertThat(fenced.highestToken()).isEqualTo(42);
    assertThat(fenced).hasMessageContaining("41").hasMessageContaining("42");
  }

  @Test
  void theServiceAndTheSpiDeclareExactlyTheSignaturesOfC2() {
    assertThat(signatures(LockService.class))
        .containsExactlyInAnyOrder(
            "Optional<LockHandle> acquire(String key, String ownerId, Duration ttl, Duration maxWait)",
            "Optional<LockHandle> tryAcquire(String key, String ownerId, Duration ttl)",
            "Optional<LockHandle> renew(LockHandle handle)",
            "boolean release(LockHandle handle)",
            "Optional<LockInfo> inspect(String key)",
            "LockOutcome forceRevoke(String key, String operator, String reason)");
    assertThat(signatures(LockStore.class))
        .containsExactlyInAnyOrder(
            "Optional<LockHandle> tryInsert(String key, String ownerId, String sessionId, Duration ttl)",
            "Optional<LockHandle> extend(String key, long fencingToken, Duration ttl)",
            "boolean deleteIfOwner(String key, long fencingToken)",
            "Optional<LockInfo> read(String key)",
            "LockOutcome revoke(String key, String operator, String reason)",
            "int reapExpired(Instant now)",
            "String backendId()");
    assertThat(signatures(SessionRegistry.class))
        .containsExactlyInAnyOrder(
            "String openSession(String ownerId, Duration ttl)",
            "boolean heartbeat(String sessionId, Duration ttl)",
            "int closeSession(String sessionId)",
            "Set<String> locksOf(String sessionId)");
  }

  @Test
  void theRecordsDeclareExactlyTheComponentsOfC2InOrder() {
    assertThat(components(LockHandle.class))
        .containsExactly(
            "String key",
            "String ownerId",
            "String sessionId",
            "long fencingToken",
            "long clientDeadlineNanos",
            "Instant serverExpiry");
    assertThat(components(LockInfo.class))
        .containsExactly(
            "String key",
            "String ownerId",
            "long fencingToken",
            "Instant acquiredAt",
            "Instant expiresAt",
            "int waiterCount");
    assertThat(components(LockOutcome.class))
        .containsExactly(
            "String key",
            "boolean revoked",
            "String previousOwnerId",
            "long previousToken",
            "long newTokenFloor");
  }

  @Test
  void noTypeInThePackageReachesBeyondTheJdkOrCarriesAnAnnotation() throws Exception {
    List<Class<?>> types = packageTypes();
    assertThat(types)
        .contains(
            LockService.class,
            LockHandle.class,
            LockInfo.class,
            LockOutcome.class,
            LockLostException.class,
            FencedOutException.class,
            ContentionException.class,
            NotLeaderException.class,
            LockStore.class,
            SessionRegistry.class);

    List<String> violations = new ArrayList<>();
    for (Class<?> type : types) {
      List<Type> referenced = new ArrayList<>(Arrays.asList(type.getGenericInterfaces()));
      List<AnnotatedElement> elements = new ArrayList<>();
      elements.add(type);
      if (type.getGenericSuperclass() != null) {
        referenced.add(type.getGenericSuperclass());
      }
      for (Field field : type.getDeclaredFields()) {
        referenced.add(field.getGenericType());
        elements.add(field);
      }
      List<Executable> executables = new ArrayList<>(Arrays.asList(type.getDeclaredMethods()));
      executables.addAll(Arrays.asList(type.getDeclaredConstructors()));
      for (Executable executable : executables) {
        if (executable instanceof Method method) {
          referenced.add(method.getGenericReturnType());
        }
        referenced.addAll(Arrays.asList(executable.getGenericParameterTypes()));
        referenced.addAll(Arrays.asList(executable.getGenericExceptionTypes()));
        elements.add(executable);
        elements.addAll(Arrays.asList(executable.getParameters()));
      }
      referenced.stream()
          .flatMap(ApiContractTest::rawTypes)
          .filter(raw -> !raw.isPrimitive())
          .map(Class::getName)
          .filter(name -> !name.startsWith("java.") && !name.startsWith("dev.lock."))
          .forEach(name -> violations.add(type.getSimpleName() + " references " + name));
      elements.stream()
          .filter(element -> element.getDeclaredAnnotations().length > 0)
          .forEach(element -> violations.add(type.getSimpleName() + " annotates " + element));
    }
    assertThat(violations).isEmpty();
  }

  private static boolean isBoolean(Class<?> type) {
    return type == boolean.class || type == Boolean.class;
  }

  private static List<String> signatures(Class<?> type) {
    return Arrays.stream(type.getDeclaredMethods())
        .map(
            method ->
                simpleName(method.getGenericReturnType())
                    + " "
                    + method.getName()
                    + Arrays.stream(method.getParameters())
                        .map(p -> simpleName(p.getParameterizedType()) + " " + p.getName())
                        .collect(joining(", ", "(", ")")))
        .toList();
  }

  private static List<String> components(Class<?> record) {
    return Arrays.stream(record.getRecordComponents())
        .map(component -> simpleName(component.getGenericType()) + " " + component.getName())
        .toList();
  }

  /** {@code java.util.Optional<dev.lock.api.LockHandle>} becomes {@code Optional<LockHandle>}. */
  private static String simpleName(Type type) {
    return type.getTypeName().replaceAll("\\b(?:[a-z]\\w*\\.)+", "");
  }

  /** Every class a type mentions, type arguments and bounds included. */
  private static Stream<Class<?>> rawTypes(Type type) {
    return switch (type) {
      case Class<?> c when c.isArray() -> rawTypes(c.getComponentType());
      case Class<?> c -> Stream.of(c);
      case ParameterizedType p ->
          Stream.concat(
              rawTypes(p.getRawType()),
              Arrays.stream(p.getActualTypeArguments()).flatMap(ApiContractTest::rawTypes));
      case GenericArrayType g -> rawTypes(g.getGenericComponentType());
      case WildcardType w ->
          Stream.concat(Arrays.stream(w.getUpperBounds()), Arrays.stream(w.getLowerBounds()))
              .flatMap(ApiContractTest::rawTypes);
      case TypeVariable<?> v -> Arrays.stream(v.getBounds()).flatMap(ApiContractTest::rawTypes);
      default -> throw new AssertionError("unexpected type " + type);
    };
  }

  /** Every top-level and nested type compiled into {@code dev.lock.api}, found on disk. */
  private static List<Class<?>> packageTypes() throws IOException, URISyntaxException {
    Path classes =
        Path.of(LockService.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    try (Stream<Path> files = Files.list(classes.resolve("dev/lock/api"))) {
      return files
          .map(file -> file.getFileName().toString())
          .filter(name -> name.endsWith(".class") && !name.equals("package-info.class"))
          .<Class<?>>map(
              name -> load("dev.lock.api." + name.substring(0, name.length() - ".class".length())))
          .toList();
    }
  }

  private static Class<?> load(String name) {
    try {
      return Class.forName(name);
    } catch (ClassNotFoundException e) {
      throw new AssertionError(e);
    }
  }
}
