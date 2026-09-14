# T-001 — Monorepo skeleton and Gradle settings

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone:** M0 Foundations · **Estimate:** 25 min

**Preconditions** — none. **This is the first task, and it blocks every other Java task in the
repository.**

You inherit a documentation-only repository: `docs/` (13 documents, `docs/contracts/C1..C5`,
`docs/adr/ADR-000..014`), `tasks/` (63 specifications and the ledger), the community health files, and
`.github/` (issue and pull-request templates, and CI workflows that are already authored — the `build`
job self-skips until `settings.gradle.kts` exists, which is what this task creates). **No build files
and no Java sources exist.**

Do not create or overwrite anything under `docs/`, `.github/`, or the root community health files —
`README.md`, `CONTRIBUTING.md`, `LICENSE` and their siblings are already written and reviewed. This
task adds the build, and nothing else.

**Goal** — Create the directory tree and the Gradle multi-project registry so that
`./gradlew projects` lists all ten Gradle projects and `./gradlew build` succeeds on an empty build.

## 1. Why this task exists

ADR-010 fixes one Gradle build over the whole project rather than nine independent builds, because the
`lock-api` contract must be compiled once and shared by both backends and every service. Declaring the
module set first — before a single class exists — makes the module boundary a decision rather than an
accident of where somebody put a file. It also gives every later task a settled place to write to.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| Module list, Gradle project names, dependency direction | `docs/contracts/C5-config-build-and-naming.md#ct5-modules` |
| Directory tree, including `deploy/{terraform,k8s,compose}` and `.github/workflows/` | `docs/contracts/C5-config-build-and-naming.md#ct5-layout` |
| Migrations live inside the owning module, never a shared tree | `docs/contracts/C5-config-build-and-naming.md#ct5-layout` |
| Repo/Java package naming (`dev.lock.*`) | `docs/contracts/C5-config-build-and-naming.md#ct5-naming` |
| One Gradle build, Kotlin DSL, no Maven | `docs/adr/ADR-010-monorepo-single-gradle-build.md` |

**Precedence:** if this spec and a contract disagree, **the contract wins** — stop, report the
mismatch quoting both, and implement neither version (`docs/04-contracts.md#c-precedence`).

## 3. Deliverables

| Path | What |
|---|---|
| `settings.gradle.kts` | `rootProject.name`, `pluginManagement` including `build-logic`, `include(...)` for every module, version-catalog wiring |
| `build.gradle.kts` | Root build: no versions, no per-module logic, no plugin bodies yet |
| `gradle/wrapper/gradle-wrapper.properties` + `gradlew`, `gradlew.bat`, `gradle-wrapper.jar` | Wrapper pinned to Gradle 9.5, `distributionType=bin`, checksum line present |
| `.gitignore` | **Already exists.** Verify it covers `build/`, `.gradle/`, `*.tfstate*`, `.terraform/` and IDE dirs; extend if not |
| `lock-api/src/{main,test}/java/dev/lock/api/` … one tree per module | Empty source roots so each module is a real Java project |
| `deploy/terraform/`, `deploy/k8s/`, `deploy/compose/`, `.github/workflows/` | Empty placeholder dirs, each with a one-paragraph `README.md` naming its owning milestone |
| `lock-server/src/main/resources/db/migration/`, `payment-resource/src/main/resources/db/migration/` | The two separate migration trees, empty |
| `tasks/README.md` | **Already exists.** Mark the T-001 row only — see §4 |

## 4. Specification

`settings.gradle.kts` declares exactly the nine modules plus `build-logic` from `#ct5-modules`:
`lock-api`, `lock-server`, `lock-client`, `payment-resource`, `payout-executor`, `rail-proxy`,
`rail-stub`, `harness`, `deploy`. `build-logic` is an *included build* under `pluginManagement`, not a
subproject — it must be buildable before the main build is configured. `deploy` carries no Java source
root; it exists so Terraform and manifests are inside the reviewed build tree.

Each Java module gets `src/main/java/<package path>` and `src/test/java/<package path>` matching the
package column in `#ct5-modules` (`lock-api` → `dev.lock.api`, `lock-server` →
`dev.lock.server`, and so on). Add a `.gitkeep` in each empty leaf so the tree survives on disk.

No module gets a `build.gradle.kts` in this task except `deploy` (which needs none) — per-module build
files are written by T-003 once the convention plugins exist. The root `build.gradle.kts` may contain
only a `tasks.register("printModules")`-style diagnostic or nothing at all; it must contain **no
version literals and no dependency declarations**.

[`tasks/README.md`](README.md) — the ledger — **already exists and is complete**, with all 63 rows,
their blockers and their statuses. Do **not** recreate it. Your only change to it is marking the T-001
row, exactly as every other task does.

## 5. Acceptance criteria

1. `./gradlew --version` reports Gradle **9.5**.
2. `./gradlew projects` lists exactly ten project names and no others.
3. `./gradlew build` completes with `BUILD SUCCESSFUL` and zero compiled classes.
4. `gradle/wrapper/gradle-wrapper.properties` contains `distributionSha256Sum`.
5. Every directory named in `#ct5-layout` exists on disk. `docs/`, `.github/` and the root community
   health files (`README.md`, `CONTRIBUTING.md`, `AGENTS.md`, `LICENSE`, …) are **untouched** apart
   from the single ledger row.
6. `grep -rn '[0-9]\+\.[0-9]\+\.[0-9]\+' settings.gradle.kts build.gradle.kts` returns nothing.
7. `lock-server` and `payment-resource` each have their own `db/migration` dir; no shared one exists.
8. The T-001 row in `tasks/README.md` is marked done, and no other row was edited.

## 6. Verification

```
cd <repo> && ./gradlew --version && ./gradlew projects && ./gradlew build
find . -type d -name migration          # expect exactly two, one per owning module
ls deploy/terraform deploy/k8s deploy/compose .github/workflows
```
Expected: version line `Gradle 9.5`; ten `Project ':...'` lines; `BUILD SUCCESSFUL`; two migration dirs.

## 7. Out of scope

Version catalog contents (**T-002**). Convention plugins, toolchain, Spotless, per-module
`build.gradle.kts` (**T-003**). Any Java type (**T-004**). CI workflow YAML (**T-007/T-008**).
Terraform or manifest content (**M5**). Do not write a Dockerfile — the single shared one is **T-005**'s.

## 8. Hazards

- The repository **is** under git ([ADR-012](../docs/adr/ADR-012-git-and-public-publication.md)). Work on a branch, sign off your commits (`git commit -s`), and open a pull request — see [`CONTRIBUTING.md`](../CONTRIBUTING.md#branches-and-commits). A root `.gitignore` already exists; extend it rather than replacing it.
- Generating the wrapper with a locally installed Gradle of a different version silently pins that
  version. Set the distribution URL to 9.5 explicitly and re-run `./gradlew wrapper` once to converge.
- A stray `include("docs")` or `include("tasks")` turns documentation into a Gradle project; the module
  list in `#ct5-modules` is closed.

## 9. On completion

Mark the T-001 row done in `tasks/README.md`. Record any deviation there in the Deviations column and
per [`CONTRIBUTING.md` §10](../CONTRIBUTING.md#10-recording-a-deviation) — in particular if Gradle 9.5
is unavailable, do **not** fall back silently.
