# T-004 — `lock-api`: the contract types

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone:** M0 Foundations · **Estimate:** 30 min

**Preconditions** — **T-001** (module tree, `dev/lock/api` source root exists), **T-002** (catalog),
**T-003** (`lock-api` builds under `dlock.library-conventions` with Java 25, `-Werror`, Spotless, and a
compile classpath proven empty). `lock-api/src/main/java/dev/lock/api/` currently holds only `.gitkeep`.

**Goal** — Author every type in C2 §2.2–2.5 as compilable signatures with full Javadoc and no method
bodies beyond what the language forces, while keeping `lock-api`'s dependency count at zero.

## 1. Why this task exists

`lock-api` is the one artifact both lock backends and all five services share; it is what makes "the
Postgres and etcd backends are observably identical" a type-checked statement instead of a hope
(`#ct2-spi`). Writing it before any implementation means the pg backend cannot quietly shape the SPI
around a `DataSource`. The zero-dependency rule is the load-bearing constraint here — everything else in
this task is transcription.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| Zero third-party dependencies, JDK-only types, no annotations at all | `docs/contracts/C2-java-api.md#ct2-zero-dep` |
| `LockService` — six methods, exact signatures and return semantics | `docs/contracts/C2-java-api.md#ct2-lockservice` |
| `LockHandle`, `LockInfo`, `LockOutcome` — exact components and order | `docs/contracts/C2-java-api.md#ct2-records` |
| The four exceptions and their retry verdicts | `docs/contracts/C2-java-api.md#ct2-exceptions` |
| `LockStore` and `SessionRegistry` in package `dev.lock.api` | `docs/contracts/C2-java-api.md#ct2-spi` |
| Token propagation: parameter or handle, **never** a thread-local | `docs/contracts/C2-java-api.md#ct2-propagation` |
| Thread-safety and nullability rules to document on each type | `docs/contracts/C2-java-api.md#ct2-threading` |
| Package name `dev.lock.api`; module owns nothing else | `docs/contracts/C5-config-build-and-naming.md#ct5-modules` |

**Precedence:** if this spec and a contract disagree, **the contract wins** — stop and report, quoting
both (`docs/04-contracts.md#c-precedence`). C2 shows the signatures verbatim; copy from C2, not from
this spec's prose.

## 3. Deliverables

| Path (under `lock-api/src/main/java/dev/lock/api/`) | What |
|---|---|
| `LockService.java` | The six-method caller-facing interface |
| `LockHandle.java`, `LockInfo.java`, `LockOutcome.java` | The three records |
| `LockException.java` | Common supertype (see §4 for the "if C2 is silent" rule) |
| `LockLostException.java`, `FencedOutException.java`, `ContentionException.java`, `NotLeaderException.java` | The four failures |
| `LockStore.java`, `SessionRegistry.java` | The backend SPI |
| `package-info.java` | Package Javadoc: what this module is, the zero-dependency rule, the propagation rule |
| `lock-api/src/test/java/dev/lock/api/ApiContractTest.java` | Compile-level guard tests (see §4) |
| `lock-api/build.gradle.kts` (modify) | Must still declare **no** `dependencies` beyond the test bundle |

## 4. Specification

**Transcribe, do not design.** Method names, parameter names, parameter order, and return types come
from C2 §2.2 and §2.5 exactly. `LockService` has `acquire`, `tryAcquire`, `renew`, `release`, `inspect`,
`forceRevoke`. `LockStore` has `tryInsert`, `extend`, `deleteIfOwner`, `read`, `revoke`, `reapExpired`;
`SessionRegistry` has the session lifecycle including `heartbeat` and `closeSession`. Record components
appear in the contract's order (`LockHandle`: key, ownerId, sessionId, fencingToken,
clientDeadlineNanos, serverExpiry).

**Javadoc is the deliverable, not decoration.** Carry over C2's `@return`, `@throws` and `@implNote`
text, and make these four semantics unmissable in prose:

| Semantic | Must appear on |
|---|---|
| `Optional.empty()` from `acquire`/`tryAcquire` = "not mine, do not proceed" — an expected outcome, never retried in a tight loop | `LockService.acquire`, `tryAcquire` |
| `Optional.empty()` from `renew`/`extend` = **LOST NOW**, terminal, must not be treated as transient | `LockService.renew`, `LockStore.extend` |
| `inspect`/`read` are advisory and never usable for a correctness decision | `LockService.inspect`, `LockStore.read` |
| `clientDeadlineNanos` is authoritative (monotonic); `serverExpiry` is informational wall-clock | `LockHandle` |

Records must **validate in a compact canonical constructor**: non-blank `key`/`ownerId`/`sessionId`,
`fencingToken > 0`. That is the one place a body is allowed; keep it to argument checks throwing
`IllegalArgumentException`/`NullPointerException` — JDK types only.

**Exception hierarchy.** Each of the four classes documents, in its class Javadoc, the trigger, the
retry verdict from `#ct2-exceptions` (`ContentionException` and `NotLeaderException` retryable;
`LockLostException` and `FencedOutException` terminal), and the operator action. `FencedOutException`
carries the key, the presented token and the stored token as fields, because `#ct2-exceptions` requires
those three values in the `fenced_out` log event. If C2 does not name a common supertype or specify
checked vs unchecked, **stop and request a contract amendment** (`docs/04-contracts.md#c-precedence`,
§4.5) rather than choosing — this decision propagates into every `catch` in the project.

**Tests** in `ApiContractTest` assert, in prose terms: a record rejects a blank key and a zero or
negative fencing token; `LockHandle` equality is component-wise; the retryable exceptions are
distinguishable from the terminal ones by type alone (no boolean flag, no string matching); and
`FencedOutException` exposes both token values. Also assert reflectively that no type in the package
declares a method or field whose type name starts with a non-`java.`/`dev.lock.` package — a cheap
in-module echo of the zero-dependency rule.

## 5. Acceptance criteria

1. `./gradlew :lock-api:build` is `BUILD SUCCESSFUL` under `-Werror`.
2. `./gradlew :lock-api:dependencies --configuration compileClasspath` reports no dependencies.
3. `grep -rn 'import ' lock-api/src/main/java` yields only `java.*` imports — no `org.`, `com.`, `jakarta.`, `lombok.`.
4. All eleven main-source files listed in §3 exist; every public type and public member has Javadoc.
5. No `ThreadLocal` anywhere in the module (`#ct2-propagation`).
6. `./gradlew :lock-api:test` passes; the tests named in §4 all exist.
7. `./gradlew spotlessCheck` passes with the license header present in every file.
8. Every method signature matches C2 character-for-character in name, arity and types.

## 6. Verification

```
./gradlew :lock-api:build :lock-api:test spotlessCheck
./gradlew :lock-api:dependencies --configuration compileClasspath
grep -rn 'import ' lock-api/src/main/java | grep -v 'import java\.'   # expect: no output
grep -rn 'ThreadLocal\|@Slf4j\|Lombok' lock-api/src/                  # expect: no output
```
Expected: green build and tests; "No dependencies" for the compile classpath; both greps silent.

## 7. Out of scope

Any implementation of `LockService`, `LockStore` or `SessionRegistry` — the pg backend is **M1**
(T-010..017), etcd is **M3** (T-030..034). The client SDK surface of `#ct2-sdk`
(`LockClientSession`, `checkStillHeld`, the conservative deadline) is `lock-client` in **M4**. HTTP DTOs,
error codes and headers are C3 and belong to the lock-server web task. No SQL, no config keys, no
metrics — even though `#ct2-exceptions` mentions `lock.fenced.out`.

## 8. Hazards

- `lock-api` is a wire-compatibility surface with six dependents, so this change needs **two maintainer approvals** ([`GOVERNANCE.md` §2](../GOVERNANCE.md#2-what-requires-what)).
- The single most damaging mistake in this task is one `@Nullable`, `@NonNull` or `@Slf4j` — an
  annotation is a dependency, and `#ct2-zero-dep` warns that once an annotation leaks in the SPI becomes
  unimplementable outside that runtime. Express nullability in Javadoc and `Optional` only.
- Conflating "empty because someone else holds it" with "empty because you lost it" is called out in
  `#ct2-spi` as the most common bug in home-grown lock clients. The Javadoc you write here is the
  artifact that prevents it downstream — do not compress it.
- Adding a convenience overload (e.g. `acquire(String,String)`) puts a name in the vocabulary that no
  contract pins; the module surface is closed.

## 9. On completion

Mark T-004 done in `tasks/README.md`. If you had to stop on the checked/unchecked or supertype question,
open a [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) with the proposed §4.5
amendment text, mark the ledger row `Blocked`, and stop ([`CONTRIBUTING.md` §9](../CONTRIBUTING.md#9-when-a-task-turns-out-to-be-bigger-than-it-looked)).
