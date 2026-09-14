# T-003 — Convention plugins, Spotless and Google Java Style

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone:** M0 Foundations · **Estimate:** 30 min (at the limit; if the `-Werror` clean-up of the
generated stubs overruns, stop after the java convention plugin builds and hand the spring convention
plugin to a follow-up session, noting it in `tasks/README.md`)

**Preconditions** — **T-001** (ten Gradle projects, empty source trees, Gradle 9.5 wrapper) and
**T-002** (`gradle/libs.versions.toml` complete, not yet consumed). No module has a `build.gradle.kts`;
nothing applies a plugin.

**Goal** — Make `build-logic` the single place where compilation, formatting, Lombok policy and test
setup are configured, and apply it to all nine modules so every module is configured identically.

## 1. Why this task exists

Nine modules configured by one plugin instead of nine drifting copies of the same block
(`#ct5-modules`). More sharply: this project's whole claim is that two lock backends are *observably
identical*, and that argument is weaker if the two store implementations compile under different
warning settings. `-Werror` plus google-java-format also removes review bandwidth spent on style so it
can be spent on lease arithmetic.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| Java 25 toolchain, Spotless + google-java-format, Lombok limited to `@RequiredArgsConstructor`/`@Slf4j`, test conventions in `build-logic` | `docs/contracts/C5-config-build-and-naming.md#ct5-modules` (`build-logic` row) |
| Plugin/library aliases and the no-version-literals rule | `docs/contracts/C5-config-build-and-naming.md#ct5-catalog` |
| Which modules are Spring Boot applications | `#ct5-catalog` (`spring-boot-plugin` Used-by) + `#ct5-modules` |
| `lock-api` gets **no** Lombok and no third-party dependency | `docs/contracts/C2-java-api.md#ct2-zero-dep` |
| Single Gradle build, Kotlin DSL | `docs/adr/ADR-010-monorepo-single-gradle-build.md` |

**Precedence:** if this spec and a contract disagree, **the contract wins** — stop and report both
(`docs/04-contracts.md#c-precedence`).

## 3. Deliverables

| Path | What |
|---|---|
| `build-logic/settings.gradle.kts`, `build-logic/build.gradle.kts` | Included build; applies `kotlin-dsl`; resolves the root catalog |
| `build-logic/src/main/kotlin/dlock.java-conventions.gradle.kts` | Toolchain, compiler args, Spotless, Lombok policy, test conventions |
| `build-logic/src/main/kotlin/dlock.spring-conventions.gradle.kts` | Applies java-conventions + Spring Boot plugin + BOM + actuator/micrometer |
| `build-logic/src/main/kotlin/dlock.library-conventions.gradle.kts` | For non-application Java modules (`lock-api`, `lock-client`) — no Boot plugin, no fat jar |
| `config/spotless/license-header.txt` | The license/attribution header applied to every `.java` file |
| `lock-api/build.gradle.kts` … `harness/build.gradle.kts` (8 files) | One line each: which convention plugin applies. `deploy` gets none |
| `README.md` (repo root) | **Already exists — do not replace it.** Update only the *Getting started* block so the build commands it shows are the ones this task makes real |

## 4. Specification

**java-conventions** must set: a Java toolchain of **25** (language version, not just source/target, so
the build is reproducible off a different local JDK); compiler args `-Xlint:all` and `-Werror`;
UTF-8 encoding; `-parameters`. Test conventions: JUnit Platform, the JUnit BOM plus the assertj alias
from the catalog, `maxParallelForks` conservative (lease-timing tests are wall-clock sensitive — one or
two forks, never `availableProcessors()`), and test logging that shows failed and skipped tests.

Lombok is added as `compileOnly` + `annotationProcessor` (both main and test) **by
java-conventions, with an explicit exclusion for `lock-api`** — implement the exclusion by *not*
applying java-conventions' Lombok block when the project name is `lock-api`, or by giving `lock-api` a
convention plugin that omits it. Prefer the second: a positive rule beats a negative special case. The
policy that only `@RequiredArgsConstructor` and `@Slf4j` may be used is enforced in review and by a
Spotless custom rule or a grep-based `check` task that fails on any other `lombok.` import.

**Spotless** configuration: `java { googleJavaFormat(<catalog version>) }`, `licenseHeaderFile`,
`removeUnusedImports`, `trimTrailingWhitespace`, `endWithNewline`; also format `*.gradle.kts` with
`ktlint` or at minimum trailing-whitespace/newline steps. Wire `check` to depend on `spotlessCheck`.
The header file names the project, the fictional operator **"Northwind Pay"** (a fictional mid-size payment
service provider), and an Apache-2.0 line. No real company, partner or product name anywhere.

**spring-conventions** applies java-conventions, the `spring-boot-plugin` alias, imports the
`spring-boot-bom` platform, and adds the actuator + micrometer-prometheus bundle from T-002 to every
service. It applies to the five services listed in the `spring-boot-plugin` Used-by column plus
`harness` only if `harness` needs a Spring context — if the contract does not say, leave `harness` on
java-conventions and note the open question.

Each module `build.gradle.kts` applies exactly one convention plugin and, at this stage, **no
dependencies on other modules** — inter-module wiring lands with the tasks that need it. Put nothing in
these files that the convention plugin could own.

## 5. Acceptance criteria

1. `./gradlew build` is `BUILD SUCCESSFUL`; `./gradlew spotlessCheck` passes on an empty source set.
2. `./gradlew javaToolchains` and `./gradlew -q :lock-api:compileJava --info | grep -i release` show
   Java **25** in use for at least one module.
3. `./gradlew :lock-api:dependencies --configuration compileClasspath` shows **no** external module —
   Lombok included.
4. `-Werror` is effective: adding a deliberate raw-type or unused-import in a scratch file makes
   `./gradlew :lock-client:compileJava` fail; remove the scratch file afterwards.
5. `./gradlew spotlessApply` inserts the license header into a header-less `.java` file, and
   `spotlessCheck` then passes.
6. No version literal in any `.gradle.kts`: `grep -rnE '"[0-9]+\.[0-9]+' --include='*.gradle.kts' .`
   is silent (catalog references only).
7. Every module except `deploy` has a `build.gradle.kts` whose `plugins {}` block names exactly one
   `dlock.*-conventions` plugin.

## 6. Verification

```
./gradlew build spotlessCheck && ./gradlew :lock-api:dependencies --configuration compileClasspath
./gradlew javaToolchains
grep -rn 'lombok\.' --include='*.java' lock-api/   # expect: no output
```
Expected: `BUILD SUCCESSFUL`; `lock-api` compile classpath reports "No dependencies"; toolchain 25 listed.

## 7. Out of scope

Any production Java type — `lock-api`'s types are **T-004**. The GitHub Actions workflow that runs
`check`, and the CI dependency-count assertion for NFR-16, belong to **T-007/T-008**. Docker/Jib image
building (**M5**). Testcontainers wiring (first needed in **M1**). Do not add jetcd, Flyway or the
Postgres driver to any module here.

## 8. Hazards

- Do **not** install a pre-commit hook. Formatting is enforced by `spotlessCheck` in CI, which is visible to reviewers; a local hook is invisible and silently diverges between contributors.
- `-Werror` plus `-Xlint:all` will fail on Lombok-generated code paths and on `this-escape` in some
  Spring configurations. Suppress **narrowly and with a comment** (a single `-Xlint:-this-escape` at
  most), never by dropping `-Werror` — a warning budget that starts at zero stays at zero.
- Applying the Spring Boot plugin to `lock-api` or `lock-client` produces a bootJar and, worse, drags a
  framework onto the contract classpath — the exact failure `#ct2-zero-dep` forbids.
- google-java-format on Java 25 needs JVM `--add-exports` flags; if Spotless fails to launch, set them
  in `gradle.properties` for the Spotless step only, and record it as a deviation.

## 9. On completion

Mark T-003 done in `tasks/README.md`. Record: the exact `-Xlint` suppressions you kept and why, whether
`harness` got spring- or java-conventions, and any `--add-exports` workaround.
