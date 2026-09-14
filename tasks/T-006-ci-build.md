# T-006 — CI: the build workflow

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M0 Foundations · **Estimate** 25 min

**Preconditions** — T-001…T-005 done. You inherit a Gradle 9.5 Kotlin-DSL monorepo that builds green from a
clean checkout (`./gradlew build`), Spotless + google-java-format wired through `build-logic`, and
`deploy/compose/` for local dependencies. `.github/` does not exist yet — this task creates it.

**Goal** — Add the single primary GitHub Actions workflow that formats, compiles, tests and reports on every
push and pull request, ordered so the cheapest possible failure happens first.

## 1. Why this task exists

The whole doc set treats naming and formatting as contract, and a contract that is not mechanically enforced
degrades within a week. Ordering matters as much as coverage: a Spotless violation must fail in under a minute
rather than after a full Testcontainers run, because a 12-minute wait to learn about an import order teaches
contributors to skip CI. This workflow is also the only place the project proves the claim "green from a clean
clone" — a developer's warm Gradle cache hides missing declarations that a fresh runner exposes.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| Workflows live in `.github/workflows/` | [C5 `#ct5-layout`](../docs/contracts/C5-config-build-and-naming.md#ct5-layout) |
| Java 25 toolchain, Gradle 9.5, Spotless + google-java-format | [C5 `#ct5-catalog`](../docs/contracts/C5-config-build-and-naming.md#ct5-catalog) |
| `lock-api` must have no third-party dependency | [C2 `#ct2-zero-dep`](../docs/contracts/C2-java-api.md#ct2-zero-dep) |
| Module/Gradle project names used in job names | [C5 `#ct5-modules`](../docs/contracts/C5-config-build-and-naming.md#ct5-modules) |
| No credential in a file; secrets only via GitHub secrets | [C5 `#ct5-env`](../docs/contracts/C5-config-build-and-naming.md#ct5-env) |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report the mismatch.**

## 3. Deliverables

| Path | What |
|---|---|
| `.github/workflows/build.yml` | The primary build workflow described below |
| `docs/../tasks/README.md` (row) | Ledger update only (see §9) |

Also permitted: a one-paragraph "CI" section appended to `deploy/compose/README.md` **only** if it states that
CI does not start the compose stack.

## 4. Specification

**Triggers.** `push` on the default branch, `pull_request` targeting it, and `workflow_dispatch`.
Concurrency group keyed on workflow name plus ref, with `cancel-in-progress: true` — a force-push must not
leave two runs competing for the same cache.

**Permissions.** Top-level `contents: read` and nothing else; jobs widen only if they must. No write token in
a workflow that only builds.

**Job order.** Two jobs, sequenced by `needs`, so the fast one gates the slow one.

| # | Job | Runs | Fails on | Typical |
|---|---|---|---|---|
| 1 | `format` | `./gradlew spotlessCheck` only | any formatting or license-header deviation | < 90 s |
| 2 | `build` (`needs: format`) | `./gradlew build` then the simulation test task | compile error, test failure, Spotless (again, transitively) | 5–10 min |

**Steps common to both jobs.** Checkout at a pinned major-version action; set up the JDK via the
`temurin` distribution at the catalog's Java version; enable Gradle caching through the official Gradle
action with cache **write** limited to the default branch (pull-request runs read the cache but must not
poison it); run the wrapper, never a system `gradle`. Add `--no-daemon` is unnecessary — do not add it; do add
`--stacktrace` on the build job so a failure is diagnosable from the log alone.

**Simulation test.** The deterministic simulation suite is the project's cheap correctness signal and must run on
every PR. Invoke the Gradle task the test convention plugin registers for it. Confirm the task exists with
`./gradlew tasks --all`; if it does not, register it in the test convention plugin as a JVM test task filtered
to class names ending `SimulationTest`, excluded from `test`, and **succeeding with zero matching classes** —
at M0 there are none, and a task that fails on an empty set blocks the whole milestone.

**Artifacts.** Always upload the test reports, including on failure (`if: always()`): the aggregated HTML/XML
under each module's `build/reports/tests` and `build/test-results`, as one artifact named for the run, with a
short retention (7 days is right for a project). This artifact is the only forensic trail once the runner is gone.

**Timeouts.** `timeout-minutes` on both jobs — 10 for `format`, 30 for `build`. An un-timed job that hangs on
a container pull burns the whole free minute budget.

**No cloud, no compose.** This workflow must not authenticate to GCP, start `deploy/compose/`, or need Docker
for anything beyond what Testcontainers starts on its own (nothing, at M0).

## 5. Acceptance criteria

1. `.github/workflows/build.yml` exists and is valid YAML that `actionlint` (or `yq`) parses without error.
2. The workflow declares `concurrency` with `cancel-in-progress: true`.
3. Top-level `permissions` is present and grants no more than `contents: read`.
4. Job `build` declares `needs: format`; `spotlessCheck` appears in `format` and in no earlier step.
5. Both jobs declare `timeout-minutes`.
6. A test-report upload step exists guarded by `if: always()`.
7. `./gradlew spotlessCheck` and `./gradlew build` both pass locally from a clean `build/` directory.
8. The simulation test task is listed by `./gradlew tasks --all` and exits 0 with no matching test classes.
9. Grep finds no literal password, key, or project id in the workflow.

## 6. Verification

```
yq '.' .github/workflows/build.yml > /dev/null && echo yaml-ok
actionlint .github/workflows/build.yml          # if installed; zero findings
./gradlew clean spotlessCheck
./gradlew build --stacktrace
./gradlew tasks --all | grep -i simulation
grep -nEi 'password|secret_key|dlock-lab' .github/workflows/build.yml   # expect no match
```

## 7. Out of scope

Terraform, container image, CodeQL and Dependabot pipelines (**T-007** owns all four). Release tagging and
publication (M7). Any GCP authentication or Workload Identity Federation (M5). Adding actual simulation tests
(M4, T-040 onward).

## 8. Hazards

Putting `spotlessApply` in CI instead of `spotlessCheck` makes the build mutate the checkout and pass forever —
check only. Second trap: allowing pull-request runs to write the Gradle cache lets a fork poison later builds.
Third: `./gradlew build` on a warm local machine can pass while CI fails because a module relies on an
undeclared dependency — always validate from a clean state before declaring this task done.

## 9. On completion

Mark the T-006 row done in `tasks/README.md`, note the exact simulation-test task name there (T-007 and the M4
tasks reference it), and record whether the task had to be registered or already existed.
