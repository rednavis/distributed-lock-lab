# Task board

**63 implementation specifications, `T-001` … `T-075`.** This file is the **ledger** — the single
source of truth for what is actually done.

| Document | Answers |
|---|---|
| **This file** | What is *done*, and what is *claimed* |
| [`../docs/12-parallelization-map.md`](../docs/12-parallelization-map.md) | What can I start *right now* |
| [`../ROADMAP.md`](../ROADMAP.md) | What each milestone must satisfy to be *exited* |
| [`../docs/10-delivery-plan.md`](../docs/10-delivery-plan.md) | *Why* the work is ordered this way |

---

## 1. How to use this board

**Task ids are identifiers, not a schedule.** `T-023` may legitimately land before `T-011`. What
constrains order is the **Blocked by** column — a task whose blockers are unmerged cannot be
implemented, because the files it edits do not exist yet.

1. Find a row whose **Blocked by** is `—` or fully merged, and whose Status is `Not started`.
2. Cross-check [`12-parallelization-map.md`](../docs/12-parallelization-map.md) — it groups these into
   lanes and flags the ones that unblock the most other work.
3. Open or find the GitHub issue, comment `/claim`, and get assigned.
4. Read the specification and **the contracts it cites**. Execute exactly that task.
5. Update your row here **in the same pull request**, and stop.

**One claim at a time** per contributor until you have landed one. Claims lapse after 14 days without a
draft pull request or a progress comment — no explanation owed, no fault implied.

Updating this file is part of every task's [definition of done](../CONTRIBUTING.md#7-definition-of-done).
A task whose row was never updated is indistinguishable from a task never done, and somebody will redo
it.

## 2. Status legend

| Status | Meaning |
|---|---|
| `Not started` | No work on disk. Default |
| `Claimed` | Assigned to a contributor. The Notes column names them and the date |
| `In review` | A pull request is open. Notes link it |
| `Done` | Every acceptance criterion verified by running the specification's §6 commands |
| `Deviated` | Done, but the specification was not followed exactly. Notes **must** state the deviation and why |
| `Blocked` | Cannot proceed. Notes **must** name what decision is needed and from whom |
| `Split` | Scope exceeded the specification. Notes point at the reserved id carrying the remainder |
| `Skipped` | Deliberately not done. Notes **must** name the forfeited claim — what the project can no longer demonstrate |

## 3. Ledger

**Progress: 8 of 63 done · 0 claimed.**

> The board and the GitHub issues are two views of the same thing. Issues are the working surface;
> **this ledger is the durable record.** If they disagree, this file is authoritative and the
> discrepancy is worth reporting.

### M0 — Foundations

Blocks every Java task in the repository. [`T-001`](T-001-monorepo-skeleton.md) is the single highest
priority in the project.

| Task | Title | Blocked by | Status | Notes |
|---|---|---|---|---|
| [T-001](T-001-monorepo-skeleton.md) | Monorepo skeleton and Gradle settings | — | **Deviated** | [@HvorostenkoAlexander](https://github.com/HvorostenkoAlexander), 2026-09-15 · [#11](https://github.com/rednavis/distributed-lock-lab/issues/11). Proof: `./gradlew projects && ./gradlew build`. Deviations: root applies the core `base` plugin, otherwise `build` resolves to `buildEnvironment` and builds nothing; no README in `.github/workflows/` (§3 conflicts with the no-`.github/` rule); catalog wiring left to Gradle's default for T-002. CI `spotlessCheck` and `:lock-api:dependencies` cannot pass before T-003. **Fan-out: blocks all Java work** |
| [T-002](T-002-version-catalog.md) | The version catalog | T-001 | **Deviated** | [@HvorostenkoAlexander](https://github.com/HvorostenkoAlexander), 2026-09-15 · [#12](https://github.com/rednavis/distributed-lock-lab/issues/12). Proof: `./gradlew help && ./gradlew build`; 18 of 18 `#ct5-catalog` aliases. No version moved forward — all 14 pinned coordinates resolve; the 4 via-BOM libraries are managed by the Spring Boot 4.1.0 BOM. Deviations: two aliases C5 does not list, `junit-jupiter` and `junit-platform-launcher` (versions via `junit-bom`), added to the `test` bundle, because Gradle 9 fails every test run without the launcher and T-003 needs both (contract gap in `#ct5-catalog`); `flyway-postgresql` and `spring-boot-plugin` reuse the `flyway-core` and `spring-boot-bom` version keys so each pair moves in lockstep. `good first issue` |
| [T-003](T-003-convention-plugins.md) | Convention plugins, Spotless, Google Java Style | T-002 | **Deviated** | [@HvorostenkoAlexander](https://github.com/HvorostenkoAlexander), 2026-09-15 · [#13](https://github.com/rednavis/distributed-lock-lab/issues/13). Proof: `./gradlew build spotlessCheck`; `lock-api` compile and runtime classpaths report "No dependencies". `-Xlint` suppression kept: `-processing`, only in `dlock.java-conventions` (the Lombok modules), because with Lombok active javac flags every unclaimed annotation such as `@Test` and `-Werror` fails; no `-this-escape` needed yet. `harness` is on `dlock.java-conventions` — the contract does not say it needs a Spring context (open question). No `--add-exports` workaround: google-java-format 1.28.0 formatted a scratch file under both a JDK 21 and a JDK 25 Gradle daemon. Versions moved forward (C5 §5.3 allows it) to the ones the Spring Boot 4.1.0 BOM manages, because otherwise the services resolved newer JUnit and AssertJ, and compiled against a newer Lombok than they processed with: `junit-bom` 5.13.0 → 6.0.3, `assertj` 3.27.3 → 3.27.7, `lombok` 1.18.38 → 1.18.46 (compiles on javac 25). The `#ct5-catalog` table still shows the old three. The same BOM is ahead of the catalog on `postgresql` (42.7.11), `flyway` (12.4.0), `testcontainers` (2.0.5) and `opentelemetry` (1.62.0); the task that first consumes each will hit the same split. Deviations: `lock-api` applies a fourth plugin, `dlock.api-conventions` (no Lombok), instead of `dlock.library-conventions`, because §3 puts `lock-client` on the library plugin while §4 and the C5 `lombok` row give it Lombok — the contract is followed; an internal `dlock.java-base` plugin holds the setup all four share. The root applies `dlock.root-conventions` (`base` plus Spotless for the root and `build-logic` scripts), which §3 does not list. `.gitignore` also ignores `.kotlin/`, the Kotlin compiler's local session and error-log directory that `kotlin-dsl` creates in `build-logic`; §3 does not list `.gitignore`, which T-001 §3 extends for build state. `bootJar` is skipped while a service has no Java sources, because it cannot resolve a main class. Spec defect: in AC-4 an unused import fails `spotlessJavaCheck`, not `compileJava` (javac has no such lint). **Fan-out: unblocks T-004…T-008** |
| [T-004](T-004-lock-api-types.md) | `lock-api`: the contract types | T-003 | **Deviated** | [@HvorostenkoAlexander](https://github.com/HvorostenkoAlexander), 2026-09-15 · [#14](https://github.com/rednavis/distributed-lock-lab/issues/14). Proof: `./gradlew :lock-api:build :lock-api:test spotlessCheck`; the `lock-api` compile classpath reports "No dependencies"; both §6 greps are silent. Deviation: no `LockException`. C2 `#ct2-exceptions` pins "All extend `RuntimeException`" and names no project supertype, and C5 `#ct5-modules` lists the `lock-api` types without one, so the four exceptions extend `RuntimeException` directly; §3's supertype row and §4's stop rule apply only if C2 were silent, and it is not. `FencedOutException` exposes `key()`, `presentedToken()` and `highestToken()`: the stored token takes the name that C3 pins in the `FENCED_OUT` body and C4 pins in the `fenced_out` event. `lock-api/build.gradle.kts` is unchanged, because the test bundle already comes from its convention plugin (`dlock.api-conventions`, T-003, not `dlock.library-conventions`). Contract note: C5 `#ct5-modules` lists neither `LockStore` nor `SessionRegistry` under `lock-api` and names `SessionRegistry` under `lock-server` `core`, while C2 `#ct2-spi` puts both in `dev.lock.api`, as §2 asks. `@implNote` needs `-tag 'implNote:a:Implementation Note:'` when Javadoc is generated. **Fan-out: six modules depend on this** |
| [T-005](T-005-compose-stack.md) | Local compose stack | T-003 | **Deviated** | [@HvorostenkoAlexander](https://github.com/HvorostenkoAlexander), 2026-09-15 · [#15](https://github.com/rednavis/distributed-lock-lab/issues/15). Host ports: lockdb 5433, paydb 5434, etcd 2379/2380; under `--profile apps`, lock-server 8081, payment-resource 8082, rail-proxy 8083, payout-executor 8084, rail-stub 8090 (all 8080 in the container). No deviation from the §4 port table. Proof, in `deploy/compose`, with no `.env`: `docker compose config -q && docker compose up -d --wait`; all three were `healthy` 6 s after a cold start, and with lockdb stopped, paydb stayed healthy and writable. Password variables: `SPRING_DATASOURCE_PASSWORD` for lockdb and `PAYMENTS_DATASOURCE_PASSWORD` for paydb. AC-9: `docker build --build-arg MODULE=lock-server -f deploy/images/Dockerfile .` runs `:lock-server:bootJar`, which is SKIPPED because the module has no Java sources yet (T-003); the Dockerfile then stops at its jar check with `no bootJar for lock-server`. With a throwaway application class (not committed), the same file built an image that served `/actuator/health` as a non-root user, with `java` as PID 1, and shut down gracefully on SIGTERM. Deviations, as what · why · blast radius · contract impact: (1) the context filter is `deploy/images/Dockerfile.dockerignore`, not `.dockerignore` · BuildKit ignores a plain `.dockerignore` beside the Dockerfile when the context is the repository root, and a root `.dockerignore` would be a new root file (`#ct5-layout`) · T-054 and T-007 should look for this name · none. (2) the catalog gains `postgres-image` 16.15, `etcd-image` 3.6.14 and `temurin-image` 25.0.4_7, which compose and the Dockerfile repeat as literal tags · §2 and AC-8 need the tags to match the catalog, and C5 allows no version outside it · a version bump edits the catalog and the tag in one commit · a gap in the `#ct5-catalog` table, which lacks the three rows. (3) `.gitignore` re-includes `.env.example` · `.env.*` ignored the template §3 requires · one line · none. (4) `compose.yaml` holds the two lab-only passwords as interpolation defaults, equal to `.env.example`, so `.env` is optional · §4 asks for "a documented weak local default", and AC-1 and §6 run `config` without `.env`; AC-7 ("no hard-coded password outside `.env.example`") is read as "no password that `.env` cannot override" · a password change edits `.env.example` and the default in `compose.yaml` together · none. Spec defect: §4 and §8 say `-U`/`-d` keep `pg_isready` from passing before the app database exists, but `pg_isready` checks only that the server accepts connections; the race is closed by `-h 127.0.0.1`, because the image's bootstrap server listens on the Unix socket only. The five Java services have no healthcheck yet (T-005 asks for none, and no service has code), so dependencies between them use `service_started`; T-042 expects lock-server, payment-resource, rail-proxy and rail-stub to report `healthy`, so the task that gives a service Actuator should add its compose healthcheck. `good first issue` |
| [T-006](T-006-ci-build.md) | CI: the build workflow | T-003 | **Deviated** | [@HvorostenkoAlexander](https://github.com/HvorostenkoAlexander), 2026-09-17 · [#16](https://github.com/rednavis/distributed-lock-lab/issues/16). **Simulation task: `simulationTest`.** It did not exist and had to be registered; it lives in `dlock.java-base`, so all eight Java modules carry one (`harness:simulationTest`, `lock-api:simulationTest`, …) and T-007 and the M4 tasks should invoke `./gradlew simulationTest`. It is excluded from `test` and green on an empty set — every module reports NO-SOURCE until T-043 writes the first `*SimulationTest`. Proof, from an empty `build/`: `./gradlew clean spotlessCheck`, `./gradlew build --stacktrace` and `./gradlew simulationTest` are all BUILD SUCCESSFUL, and `lock-api:test` (`ApiContractTest`) stayed green; `./gradlew tasks --all` lists the task in eight modules; `docker run --rm rhysd/actionlint` (digest `sha256:b1934ee5`) reports zero findings on the workflow, because neither `yq` nor `actionlint` was installed on the host; the §6 credential grep over the workflow is silent. The split was verified with a throwaway `harness/src/test/java/dev/lock/harness/ScratchSimulationTest.java` (not committed): `:harness:simulationTest` ran it and `:harness:test` did not. Deviations, as what · why · blast radius · contract impact: (1) `simulationTest` is registered in `dlock.java-base` rather than in a test convention plugin · there is no such plugin — the whole test setup lives in `java-base`, and a sixth convention plugin is not listed in C5 `#ct5-modules` · the task exists in all eight Java modules and one `./gradlew simulationTest` walks them all · none. (2) `build-logic/src/main/kotlin/dlock.java-base.gradle.kts` is edited although §3 lists only the workflow and this row · §4 itself orders the task to be registered in the test convention plugin if it does not already exist · a T-003 file is changed outside its own task · none, but §3 is incomplete. (3) `test` sets `isFailOnNoMatchingTests = false` · Gradle counts an exclusion as a filter and fails with "No tests found for given includes" the moment it removes a module's last test class, which is what `harness` becomes after T-043; reproduced here · a mistyped command-line `--tests` pattern now reports zero tests instead of failing, and no assertion is weakened · none. (4) `java-version: '25'` stays a literal in the workflow where §4 asks for the catalog's Java version · the catalog has no Java row and `#ct5-catalog` defines none; the same literal is already the toolchain in `dlock.java-base` (T-003) · a Java upgrade must edit `dlock.java-base` and `build.yml` together · a gap in `#ct5-catalog`, which has no toolchain row. (5) job `detect` ("gradle gate") is deleted · `settings.gradle.kts` has been on master since `f818be7`, so its condition can never be false again, and §4 describes exactly two jobs · CI no longer self-skips if T-001 is ever reverted · none. (6) the `lock-api` zero-dependency step is kept although §4 does not describe it · §2 names C2 `#ct2-zero-dep` among the contracts this workflow must obey, and T-007 does not adopt the check · none · none. Spec defect: Preconditions state "`.github/` does not exist yet — this task creates it", but it holds seven authored workflows including `build.yml`; this task reshapes that file, as this row's previous note assumed. No observability in this task's scope (DoD gate 4). |
| [T-007](T-007-ci-supporting.md) | CI: infra, container, CodeQL, Dependabot, templates | T-006 | **Deviated** | [@HvorostenkoAlexander](https://github.com/HvorostenkoAlexander), 2026-09-17 · [#17](https://github.com/rednavis/distributed-lock-lab/issues/17). **The image push is currently skipped**: `container.yml` pushes only when the repository variable `ARTIFACT_REGISTRY_PUSH` is `true`, and it is unset until Artifact Registry exists (T-054); today every leg also skips its build, because none of the five services has code. **No placeholder Terraform file was added**: with Terraform 1.15.9, `fmt -check -recursive`, `init -backend=false` and `validate` all exit 0 on the empty `deploy/terraform`, and so does `tflint --recursive` (v0.64.0). Proof: `actionlint` (docker `rhysd/actionlint`) reports zero findings on all workflows; `yq` parses every workflow, `dependabot.yml` and issue form; the §6 grep for `:latest`, `credentials_json` and `-----BEGIN` under `.github/` is silent; no workflow step of this task runs `git`; `./gradlew --no-daemon --no-build-cache testClasses` (the CodeQL build) and `./scripts/check-docs.sh` pass. AC-6 was checked on a real image in a throwaway copy with a scratch application class (not committed): `docker buildx build --sbom=true --provenance=mode=max` for `lock-server` produced an attestation manifest with the `https://spdx.dev/Document` and `https://slsa.dev/provenance/v1` predicates, and syft read an SPDX-2.3 SBOM of 157 packages from the image. The CodeQL build can only be proved by a real run. Deviations, as what · why · blast radius · contract impact: (1) the templates stay `.github/PULL_REQUEST_TEMPLATE.md` and `.github/ISSUE_TEMPLATE/bug.yml`, extended with the §4 checklist and fields, and the new form is `design-question.yml`, instead of `pull_request_template.md`, `bug_report.md` and `design_question.md` · the files already existed and CONTRIBUTING links them; the PR template names differ only in case, and GitHub issue forms are YAML, not Markdown · T-075 also plans a `bug_report.md` · none. (2) `terraform.yml` is renamed to `infra.yml` instead of adding a second workflow beside it; its job is `terraform`, and it runs `init -backend=false` and `validate` in every directory that holds a `.tf` file, falling back to `deploy/terraform` itself while there is none · two workflows would run the same checks, and ADR-008 D1 puts every `.tf` file under `envs/<env>/` and `modules/<noun>/`, so a single `validate` in `deploy/terraform` would pass without checking anything once T-050 lands · none · none. (3) Dependabot has five entries, not four: `docker-compose` on `/deploy/compose` and `docker` on `/deploy/images`, instead of `docker` on `/deploy/compose` · the `docker` ecosystem reads only Dockerfiles and would find no image in `deploy/compose`, and the Dockerfile lives in `deploy/images` · an image update changes only the literal tag, so its pull request must raise the matching `*-image` catalog version before merge, as `dependabot.yml` says · none. The `terraform` entry uses `directories: ["/deploy/terraform/envs/*", "/deploy/terraform/modules/*"]` instead of `directory: "/deploy/terraform"` · Dependabot's Terraform file fetcher reads only the `.tf` files directly in the directory it is given (plus local-path modules they reference), and ADR-008 D1 leaves `deploy/terraform` itself without one, so the table's directory would never match; `directories` accepts globs, `directory` does not · until T-050 creates `envs/dev`, the entry finds no Terraform configuration and its weekly job reports an error on the Dependabot tab (no pull-request check fails); no later task mentions Dependabot, so the entry is kept rather than deferred · none. (4) AC-2 is applied to this task's three workflows (`infra`, `container`, `codeql`) · "every workflow" reads two ways, and `pages.yml` deliberately keeps `cancel-in-progress: false` while `labels.yml` deletes labels and must not be cut off mid-sync · `dco`, `docs`, `labels` and `pages` keep their own settings · none. (5) `terraform_version: '1.15.9'` and `tflint_version: v0.64.0` are literals in `infra.yml` · `#ct5-catalog` has no Terraform or tflint row, although ADR-008 D4 calls the Terraform row the single statement of intent, and an unpinned `latest` would let a tflint release break CI · a Terraform or tflint upgrade edits the workflow · a gap in `#ct5-catalog`. (6) the repository variables `ARTIFACT_REGISTRY_PUSH`, `GCP_WORKLOAD_IDENTITY_PROVIDER` and `GCP_CI_SERVICE_ACCOUNT` are named here · §4 requires a conditional push and Workload Identity Federation but pins no names · no M5 task creates a GitHub federation pool or a CI service account, and T-054 pushes with the operator's credentials, so the push stays off until someone does · a gap in `#ct5-naming` and 05 §5.9. (7) the matrix holds five modules without `harness`, while T-054 builds six images · AC-5 requires exactly five · T-054 and T-007 disagree about `harness` · none. (8) CodeQL builds with `./gradlew --no-daemon --no-build-cache testClasses` and no Gradle cache · the tracer must see the compilers in its process tree, and restored outputs would make compilation UP-TO-DATE and the scan falsely clean · none · none. (9) the `detect` job in `codeql.yml` is deleted · Java sources exist since T-004, so its condition can no longer be false · none · none. Spec defects: the preconditions assume `.github/` is empty; §8 assumes the `docker` ecosystem reads compose files; the §4 Dependabot table points `terraform` at `/deploy/terraform`, which holds no `.tf` file under ADR-008's layout; §3 calls `.md` files "issue forms". No observability in this task's scope (DoD gate 4). |
| [T-008](T-008-repo-front-matter.md) | Repo front matter and non-goals | T-003 | **Deviated** | [@HvorostenkoAlexander](https://github.com/HvorostenkoAlexander), 2026-09-18 · [#18](https://github.com/rednavis/distributed-lock-lab/issues/18). **M0 is closed with this task.** Exit check from [10 §10.2](../docs/10-delivery-plan.md#dp-milestones), each item confirmed here: `./gradlew build spotlessCheck` BUILD SUCCESSFUL in a clean tree; `./gradlew projects` lists all nine modules of `#ct5-modules` plus the `build-logic` included build; `./gradlew :lock-api:dependencies --configuration compileClasspath` reports "No dependencies". **Outstanding:** "CI blocks merge on failure" holds only in part — branch protection on `master` requires `docs checks` and `dco`, and the `build` workflow is not yet a required status check, so a red Gradle build does not block a merge. Someone with admin rights has to add it; no task specifies that. Proof of this task: the first fenced block in `README.md` is the fencing transcript (tokens 41 and 42, "rejected" twice), and the first build command appears 95 lines below it; the nine non-goal rows match [00 §0.5](../docs/00-charter.md#ch-nongoals) line for line, plus the never-cut sentence; `diff <(curl -sL apache.org/licenses/LICENSE-2.0.txt) LICENSE` is empty; the AC-7 grep for an email address, "working days" or "within N" over `SECURITY.md` is silent; `./scripts/check-docs.sh` passes all four checks over 1566 links. Deviations, as what · why · blast radius · contract impact: (1) `LICENSE` and `NOTICE` are unchanged · `LICENSE` is already byte-identical to the upstream text, and Apache-2.0 contains no copyright line — only the `Copyright [yyyy] [name of copyright owner]` template in its APPENDIX, which belongs in source headers, not in the licence; filling it in would break AC-6's byte-identity and ADR-014's "verbatim", and the copyright line with the year is already in `NOTICE` · none · none. (2) `SECURITY.md` keeps GitHub private vulnerability reporting as its single channel rather than a public issue · §4 calls an issue "acceptable", not required, and the file's own secrets section forbids a public issue as a pointer to the secret · none · none. (3) the README sections §4 does not list are handled three ways: the contracts-are-authoritative text and the "where to start" table are folded into §12, Contributing and License become a short tail after it, the five badges and the documentation-site link move down into §11 so that only the opening sentence precedes the demonstration, and two sections are **removed** — "What gets built" (its diagram is replaced by the one §4 asks for in section 5) and the nine-row "Module | Responsibility" table (each module's purpose is now the comment beside it in the §4.8 tree, and `#ct5-modules` remains cited) · §4 fixes the order of twelve sections and is silent about the rest, while §2 requires reconciling with the published README instead of replacing it; §8 names the conventional-README pull as the hazard, and a badge wall above the demonstration is exactly that · a reader who linked the old "What gets built" anchor loses it · none. (4) the Toolchain table carries three rows that are not in the catalog — Java 25, Gradle 9.5.0 and Terraform 1.15 — each naming its real source in the table · AC-9 wants every version to match `gradle/libs.versions.toml`, but the catalog has neither a toolchain nor a Terraform row; the values live in `dlock.java-base`, `gradle-wrapper.properties` and C5 §5.4 · a Java or Gradle upgrade edits the same two files T-006 already names · a gap in `#ct5-catalog`, recorded for the third time (T-006, T-007, T-008). (5) the repository map prints `docs/contracts/` as C1..C5 and adds `deploy/images/`, `tasks/`, `config/` and `scripts/` · `#ct5-layout` says "C1..C6" although only five contracts exist ([04 §4.2](../docs/04-contracts.md#c-routing)), and the four directories exist in the tree but not in the contract's listing · a reader comparing the README tree with C5 sees two different trees · `#ct5-layout` needs both corrections. (6) the fencing transcript is labelled an illustration with example token values · `T-042` has not been written, and §8 forbids publishing a number nobody measured · the transcript is replaced by captured output when T-042 lands, as the README says · none. (7) `CONTRIBUTING.md` gains subsections inside §1 and §6 instead of new numbered sections · the numbered headings are public anchors (`#7-definition-of-done`, `#10-recording-a-deviation` and others) cited by the issue template, `AGENTS.md`, task specifications and `docs/`; renumbering would break them · none · none. (8) AC-10 is read as forbidding real names from the problem domain — companies, partners, products — not technology names, so PostgreSQL, etcd, GitHub and GCP remain · a literal reading of "no real product name" would make the README unwritable, and [00 §0.3](../docs/00-charter.md#ch-customer) is about the domain. The same reading covers the owner's GitHub organisation, `rednavis`, which appears in every clone URL, badge and advisory link and cannot be removed while the repository is published there ([ADR-012](../docs/adr/ADR-012-git-and-public-publication.md)); it is not a name from the modelled domain · none · none. (9) the ledger's own progress line is changed from "0 of 63 done · 1 claimed" to "8 of 63 done · 0 claimed" although §3 names only this task's row · nobody has touched that line since T-001 was claimed, and §9 requires this task to record that M0 is closed — leaving the counter would have the same file assert both "0 done" and "M0 is closed" eight lines apart · every later task should move the counter with its own row · none. **Outstanding, outside this task's deliverables:** [`ROADMAP.md`](../ROADMAP.md) "Where the project is now" is also unchanged since T-001 — it still reads "Current milestone M0", "0 of 63 · 1 claimed" and "In flight: T-001", which contradicts the README section that links it. §3 does not list `ROADMAP.md`, so it is left for a maintainer edit or a follow-up task rather than changed here. Spec defects: the preconditions say "five workflows" where `.github/workflows/` holds eight; §4 asks for "the eleven `docs/` files" where there are thirteen numbered files plus the index; the §6 link one-liner does not strip `#fragment`, so it reported all fourteen anchored links in the new README as DEAD while `./scripts/check-docs.sh links`, which does strip it, passes — the script is the real check; §7 and §9 cite [ADR-011](../docs/adr/ADR-011-no-git-initialisation-yet.md), superseded by ADR-012, and its "CI authored but not executed" wording, although CI has been executing since T-006; §4 requires a copyright line in `LICENSE` that Apache-2.0 does not have. Note: the catalog carries `junit-bom` 6.0.3 while CONTRIBUTING §11 still lists "JUnit 5" — left alone, it names the family, not the version. No observability in this task's scope (DoD gate 4). |

### M1 — PostgreSQL lock backend

| Task | Title | Blocked by | Status | Notes |
|---|---|---|---|---|
| [T-010](T-010-lockdb-migration.md) | Flyway migration: lockdb schema | T-004 | Not started | **Fan-out: unblocks T-011, T-013, T-014, T-015.** `good first issue` |
| [T-011](T-011-pg-tryacquire.md) | `PostgresLockStore.tryAcquire` | T-010 | Not started | `critical-path`, `safety` |
| [T-012](T-012-pg-renew-release.md) | `PostgresLockStore` renew, release, inspect | T-011 | Not started | `critical-path` |
| [T-013](T-013-session-registry.md) | SessionRegistry and heartbeat persistence | T-010 | Not started | ∥ with T-011 |
| [T-014](T-014-expiry-sweeper.md) | ExpirySweeper and the expiry signal | T-010 | Not started | ∥ with T-011 |
| [T-015](T-015-force-revoke.md) | forceRevoke and the revocation audit trail | T-010 | Not started | ∥ with T-011 |
| [T-016a](T-016-lock-server-rest.md) | lock-server HTTP: conventions, error envelope, L1–L3 | T-012, T-013 | Not started | Part A. Header binding, validation, exception→code mapper |
| [T-016b](T-016-lock-server-rest.md) | lock-server HTTP: L4–L8 (acquire, renew, release, revoke, info) | T-016a | Not started | **Mandatory, not optional.** T-017, T-025 and T-041 all need L4–L8 |
| [T-017](T-017-pg-testcontainers.md) | Testcontainers matrix for the Postgres backend | T-016b | Not started | **Checkpoint: the backend becomes provable locally** |

### M2 — Protected resource and executor

Runs **fully in parallel with M3** — different modules entirely.

| Task | Title | Blocked by | Status | Notes |
|---|---|---|---|---|
| [T-020](T-020-paydb-migration.md) | Flyway migration: paydb schema | T-004 | Not started | **Fan-out** |
| [T-021](T-021-fenced-repositories.md) | payment-resource: account and ledger repositories | T-020 | Not started | `safety` — fence point (a) |
| [T-022](T-022-payment-resource-rest.md) | payment-resource HTTP surface, fenced-out signal | T-021 | Not started | `safety` |
| [T-023](T-023-rail-stub.md) | rail-stub: the non-idempotent external rail | T-004 | Not started | **Best first Java task** — zero dependencies, needs nothing from M1. `good first issue` |
| [T-024](T-024-rail-proxy.md) | rail-proxy: the fencing gate | T-020, T-023 | Not started | `safety` — fence point (c) |
| [T-025](T-025-payout-executor.md) | payout-executor: the state machine | T-022, T-024, T-016b | Not started | `critical-path` |
| [T-026](T-026-kill-switches.md) | The two fencing kill switches | T-025 | Not started | `safety` — read C5 §5.2 before touching |
| [T-027](T-027-m2-integration.md) | Integration test: no duplicate submission | T-026 | Not started | M2 exit gate |

### M3 — etcd backend

`T-030`…`T-033` need only `lock-api`. **The etcd backend does not wait for the PostgreSQL backend** —
only the parity suite does.

| Task | Title | Blocked by | Status | Notes |
|---|---|---|---|---|
| [T-030](T-030-etcd-tryacquire.md) | `EtcdLockStore.tryAcquire` | T-004 | Not started | `safety` — `ModRevision` captured **at grant time** |
| [T-031](T-031-etcd-lease-session.md) | etcd lease per session and keepAlive | T-030 | Not started | |
| [T-032](T-032-etcd-renew-release.md) | `EtcdLockStore` renew, release, inspect | T-031 | Not started | |
| [T-033](T-033-etcd-watch.md) | Watch-based `awaitRelease` | T-032 | Not started | |
| [T-034a](T-034-backend-parity.md) | Backend parity suite over both stores | T-033, T-017 | Not started | Part A, criteria 1–5 |
| [T-034b](T-034-backend-parity.md) | Backend parity: executor on etcd, divergence table | T-034a, T-027 | Not started | **M3's exit criterion. Not optional** |

### M4 — Client SDK and the correctness proof

| Task | Title | Blocked by | Status | Notes |
|---|---|---|---|---|
| [T-040](T-040-sdk-session.md) | lock-client: session, heartbeat, conservative expiry | T-016b | Not started | `critical-path` |
| [T-041](T-041-sdk-acquire.md) | lock-client: bounded acquire, full jitter, reentrancy | T-040 | Not started | **Fan-out: unblocks T-042…T-047** |
| [T-042](T-042-fencing-demo.md) | **THE fencing experiment: SIGSTOP** | T-041, T-027 | Not started | ★ **The central claim.** `critical-path`, `safety` |
| [T-043a](T-043-sim-test.md) | Deterministic simulation: world, seeds, INV-01/04/05/06 | T-041 | Not started | Part A |
| [T-043b](T-043-sim-test.md) | Deterministic simulation: resource model, INV-02/03, shrinking | T-043a | Not started | **Without this, INV-02/03 are never asserted (SC-06)** |
| [T-044](T-044-linearizability.md) | Linearizability history recorder and export | T-041 | Not started | Recorder only; does **not** carry T-043b's scope |
| [T-045](T-045-load-generator.md) | Load generator and the violation detector | T-041 | Not started | ∥ |
| [T-046](T-046-fault-matrix.md) | Fault-injection matrix runner | T-041 | Not started | ∥ |
| [T-047](T-047-executor-sdk-migration.md) | payout-executor onto the SDK | T-041, T-025 | Not started | Gives `lock-client` its only production caller |

### M5 — Cloud infrastructure

> [!WARNING]
> **`T-053` onward bills real money.** `T-050`…`T-052` and `T-054` are write-and-validate only and cost
> nothing — those can be done today, by anyone, with no cloud account. Read
> [`05-infrastructure.md`](../docs/05-infrastructure.md#gcp-cost) before applying anything.

| Task | Title | Blocked by | Status | Notes |
|---|---|---|---|---|
| [T-050](T-050-tf-root.md) | Terraform root, dev environment and the budget alert | — | Not started | **Authorable now.** Owns the only budget — must exist before the first apply |
| [T-051](T-051-tf-network.md) | Terraform module: network | — | Not started | **Authorable now**, write + validate |
| [T-052](T-052-tf-cloudsql.md) | Terraform module: cloudsql, two instances | — | Not started | **Authorable now**, write + validate |
| [T-053](T-053-tf-gke.md) | Terraform module: GKE Autopilot | T-051, M4 | Not started | `needs:cloud` — **first real apply, the meter starts here** |
| [T-054](T-054-tf-artifacts.md) | Terraform module: Artifact Registry, image build | T-053 | Not started | `needs:cloud` |
| [T-055](T-055-k8s-services.md) | Kubernetes manifests: the six services | T-054 | Not started | `needs:cloud` |
| [T-056](T-056-k8s-etcd.md) | Kubernetes: etcd StatefulSet on Autopilot | T-055 | Not started | `needs:cloud` — PDB + zone spread constraints |
| [T-057](T-057-wi-secrets.md) | Workload Identity, secrets, private connectivity | T-056 | Not started | `needs:cloud` — paydb pods stay unready until this lands |
| [T-058](T-058-console-walkthrough.md) | Console walkthrough: the click path | T-057 | Not started | Prose; **drafting is possible now** |
| [T-059](T-059-teardown.md) | Teardown, `deletion_protection`, orphan verification | T-058 | Not started | `needs:cloud` — **run the same day as T-050…T-058** |

### M6 — Observability and SRE

The **offline half** — runbooks, alert definitions, SLO objects — needs no cluster and no Java.

| Task | Title | Blocked by | Status | Notes |
|---|---|---|---|---|
| [T-060](T-060-metrics.md) | LockMetrics instrumentation | T-027 | Not started | |
| [T-061](T-061-podmonitoring.md) | PodMonitoring and metric-arrival verification | T-060, T-055 | Not started | `needs:cloud` — **the named-port trap fails silently** |
| [T-062](T-062-structured-logs.md) | Structured JSON logging | T-027 | Not started | ∥ with T-060 |
| [T-063](T-063-log-based-metrics.md) | Log-based metrics in Terraform | T-062 | Not started | Authorable offline |
| [T-064](T-064-slo-objects.md) | SLO objects in Terraform | T-061 | Not started | Authorable offline |
| [T-065](T-065-alert-policies.md) | Alert policies in Terraform | T-064 | Not started | **Twelve policies** — split a/b is expected |
| [T-066](T-066-dashboard.md) | The dashboard, committed as JSON | T-064 | Not started | Build in console and **export** — do not hand-author |
| [T-067](T-067-tracing.md) | OpenTelemetry tracing | T-027 | Not started | ∥ |
| [T-068](T-068-runbook.md) | The runbook, one entry per alert | — | Not started | **Authorable now. Pure prose.** `good first issue` |
| [T-069](T-069-game-day.md) | Game day: fire every alert on purpose | T-065, T-068 | Not started | **Checkpoint: the service is now *operated*** |

### M7 — Benchmark and publication

| Task | Title | Blocked by | Status | Notes |
|---|---|---|---|---|
| [T-070](T-070-benchmark-runner.md) | Benchmark protocol runner, environment capture | T-034b | Not started | Every number needs its command, environment and date |
| [T-071](T-071-failover-experiments.md) | Failover experiments, both backends | T-070, T-061 | Not started | `needs:cloud` — destructive, run **after** observability works |
| [T-072](T-072-comparison-table.md) | Fill the backend comparison table | T-071 | Not started | Must regenerate from committed raw data |
| [T-073](T-073-fencing-writeup.md) | The fencing experiment write-up | T-042 | Not started | Needs T-042's captures on disk |
| [T-074](T-074-readme-final.md) | README final pass and design condensation | T-072, T-073 | Not started | |
| [T-075](T-075-publication-checklist.md) | Publication checklist | T-074 | Not started | |

## 4. Reserved ids

`T-009` · `T-018` · `T-019` · `T-028` · `T-029` · `T-035`…`T-039` · `T-048` · `T-049`

**Split capacity, not spare scope.** When a task exceeds its specification, the remainder takes the next
reserved id in that milestone's gap. Never `T-017b`. They exist so that one split does not require
renumbering 63 tasks and invalidating every cross-reference in the doc set.
See [`CONTRIBUTING.md` §9](../CONTRIBUTING.md#9-when-a-task-turns-out-to-be-bigger-than-it-looked).

## 5. Three checkpoints worth pausing at

- **After `T-017`** — the PostgreSQL lock backend is *provable locally*. Mutual exclusion, expiry and
  monotonic tokens are demonstrated by tests, not asserted by prose. A good moment to re-read the ADRs
  before layering the payout path on top.
- **After [`T-042`](T-042-fencing-demo.md)** — the fencing experiment runs and produces the two-run
  contrast. **This is the first point at which the project is worth showing anyone.** If effort has to
  stop, stop here, not mid-M5.
- **After `T-069`** — a deployed, instrumented and *operated* service: SLOs, alerts, dashboards, traces,
  runbook, game-day record. Everything after this is measurement and writing.

## 6. Pointers

| Need | File |
|---|---|
| How to claim, branch, and land work | [`../CONTRIBUTING.md`](../CONTRIBUTING.md) |
| Additional rules for AI contributors | [`../AGENTS.md`](../AGENTS.md) |
| What can be started right now | [`../docs/12-parallelization-map.md`](../docs/12-parallelization-map.md) |
| Authoritative names and precedence | [`../docs/04-contracts.md`](../docs/04-contracts.md) and [`../docs/contracts/`](../docs/contracts/) |
| Why the tasks are grouped this way | [`../docs/10-delivery-plan.md`](../docs/10-delivery-plan.md) |
| Decisions not to relitigate | [`../docs/adr/`](../docs/adr/) |

**If a specification and a contract disagree, the contract wins** — open a
[contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) rather than reconciling them in
code.
