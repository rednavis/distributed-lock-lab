# T-002 — The version catalog

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone:** M0 Foundations · **Estimate:** 20 min

**Preconditions** — **T-001** done. You inherit `settings.gradle.kts` with ten Gradle projects, a root
`build.gradle.kts` with no dependency logic, the Gradle 9.5 wrapper, and the full directory tree. No
module declares a dependency yet, and `gradle/libs.versions.toml` does not exist.

**Goal** — Author `gradle/libs.versions.toml` containing every alias in the C5 catalog table so that no
version literal ever needs to appear anywhere else in the repository.

## 1. Why this task exists

`lock-client` and `lock-server` exchange a wire contract; if one drifts onto a different jetcd or
Jackson the mismatch is invisible until a deserialisation failure *inside a live critical section*, and
CI stayed green because each module built fine alone (`#ct5-catalog`). A single catalog makes that class
of drift unrepresentable. It is written before any module has dependencies so no module ever gets the
chance to hard-code one.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| Every alias, coordinate and version; the "versions only here" rule | `docs/contracts/C5-config-build-and-naming.md#ct5-catalog` |
| Which module may use which alias | `#ct5-catalog` (Used-by column) + `#ct5-modules` |
| `lock-api` has zero third-party dependencies (NFR-16) | `docs/contracts/C2-java-api.md#ct2-zero-dep` |
| Catalog file location (`gradle/libs.versions.toml`) | `docs/contracts/C5-config-build-and-naming.md#ct5-layout` |

**Precedence:** if this spec and a contract disagree, **the contract wins** — stop and report, quoting
both (`docs/04-contracts.md#c-precedence`). A version you cannot find in `#ct5-catalog` is a contract
gap, not an invitation to pick one.

## 3. Deliverables

| Path | What |
|---|---|
| `gradle/libs.versions.toml` | The catalog: `[versions]`, `[libraries]`, `[plugins]`, `[bundles]` |
| `settings.gradle.kts` (modify) | Confirm the default `libs` catalog resolves from that path; add no second catalog |
| `docs/contracts/C5-config-build-and-naming.md` | **Do not edit.** Listed only to say so explicitly |

## 4. Specification

Transcribe the `#ct5-catalog` table alias for alias. Rules for the transcription:

| Catalog row shape | How it is encoded |
|---|---|
| Version given as a number (e.g. `postgresql` 42.7.5) | A `[versions]` entry keyed by the alias, referenced with `version.ref` |
| Version given as **"via BOM"** | A `[libraries]` entry with **no version at all** — the version arrives from the imported platform |
| `spring-boot-bom`, `otel-bom`, `junit-bom`, `testcontainers-bom` | `[libraries]` entries that will be consumed with `platform(...)`; their versions are real `[versions]` entries |
| `spring-boot-plugin`, `spotless-plugin` | `[plugins]` entries with `id` + `version.ref`, so `build-logic` applies them by alias |
| `google-java-format` | A `[libraries]` entry — it is a Spotless *step* dependency, not a plugin |

Alias naming: keep the exact alias strings from the contract table, converted to Gradle accessors by
Gradle's own dash-to-dot rule (`spring-boot-web` → `libs.spring.boot.web`). Do **not** invent shorter
aliases, and do not add a version for anything the table marks "via BOM".

Define three `[bundles]` to keep module build files short and reviewable: a **test** bundle (assertj +
whatever JUnit artifacts are needed beyond the BOM), a **service-observability** bundle (actuator +
micrometer-prometheus), and a **flyway** bundle (`flyway-core` + `flyway-postgresql`, which must always
move together). Bundles are convenience only — they must not smuggle an alias into a module the Used-by
column excludes; notably nothing in any bundle may end up on `lock-api`'s classpath.

Add a comment header to the file stating: versions move **forward only**, the file is the single source
of truth, and a change here is reviewed as a contract-adjacent change. Record next to `jetcd-core` that
it is `lock-server` only, and next to `lombok` that `lock-api` is excluded.

## 5. Acceptance criteria

1. `gradle/libs.versions.toml` parses: `./gradlew help` succeeds with no catalog warning.
2. Every alias in the `#ct5-catalog` table appears in the file — 18 rows, none missing, none extra.
3. No `[libraries]` entry for a "via BOM" row carries a `version` or `version.ref`.
4. `./gradlew build` still reports `BUILD SUCCESSFUL` (the catalog is declared, not yet consumed).
5. `grep -rnE '"[0-9]+\.[0-9]+(\.[0-9]+)?"' --include='*.gradle.kts' .` returns no matches.
6. Exactly one version catalog is declared in `settings.gradle.kts`.
7. Three bundles exist and none of them contains `lombok`, `jetcd-core`, or a Spring artifact plus a
   test artifact in the same bundle.

## 6. Verification

```
./gradlew help && ./gradlew build
grep -c '^[a-z]' gradle/libs.versions.toml        # sanity: alias lines present in all four tables
grep -rnE '"[0-9]+\.[0-9]+' --include='*.gradle.kts' .   # expect: no output
```
Expected: both Gradle invocations `BUILD SUCCESSFUL`; the grep for version literals silent.

## 7. Out of scope

Consuming the catalog — `plugins {}` / `dependencies {}` blocks belong to **T-003** (convention
plugins) and to each module's first implementation task. Do not add dependencies to any module here.
Terraform and provider versions (**M5**, `ADR-008`) are not Gradle catalog entries. Docker base image
tags (**M5**) are out of scope even though the same no-literal-versions principle applies.

## 8. Hazards

- The version catalog is touched by nearly every contributor. Keep catalog edits in their own commit so they rebase cleanly ([12 §12.6](../docs/12-parallelization-map.md#pm-collisions)).
- Putting a version on a "via BOM" library defeats the platform: Gradle prefers the explicit version and
  a Spring Boot upgrade then silently leaves that one artifact behind.
- Adding Lombok to a shared bundle is the single easiest way to break `#ct2-zero-dep`, and CI's
  dependency-count assertion for `lock-api` will not exist until T-003/T-008 — the mistake would sit
  undetected for several tasks.
- If a published version in the table no longer resolves, move **forward** and note it; never pin back.

## 9. On completion

Mark the T-002 row done in `tasks/README.md`; list any version you moved forward, with old → new, in
the Notes column, and in the pull request ([`CONTRIBUTING.md` §10](../CONTRIBUTING.md#10-recording-a-deviation)).
