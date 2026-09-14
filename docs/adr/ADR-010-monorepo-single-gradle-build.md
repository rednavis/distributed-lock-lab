# ADR-010 — One repository, one Gradle build, one version list {#adr10}

| Field | Value |
|---|---|
| **Status** | **Accepted**, 2026-08-21 |
| **Decider** | Project owner / architect |
| **Scope** | Repository and build structure for all nine modules plus `build-logic` and `deploy` |
| **Contracts** | [C2 §2.1](../contracts/C2-java-api.md#ct2-zero-dep) (the `lock-api` zero-dependency rule) · [C5 §5.3](../contracts/C5-config-build-and-naming.md#ct5-catalog) (version catalog is the only place versions appear) · [C5 §5.4](../contracts/C5-config-build-and-naming.md#ct5-modules) · [C5 §5.5](../contracts/C5-config-build-and-naming.md#ct5-layout) · [C5 §5.6](../contracts/C5-config-build-and-naming.md#ct5-naming) |
| **Requirements** | [00 §0.4](../00-charter.md#ch-success) · [00 §0.8 D-01](../00-charter.md#ch-deferred) (trunk-based development is *future intent* until a repo exists) |
| **Related** | [ADR-008](ADR-008-terraform-layout-state-and-provider-versions.md) (infra in the same repo), [ADR-011](ADR-011-no-git-initialisation-yet.md) (why the VCS half is still theoretical) |

## 10.1 Context {#adr10-context}

The deliverable is not a library. It is a **claim about a system**: that a fencing token, issued by one of
two interchangeable backends, carried by a client, presented by a worker, and checked independently by a
resource and by a proxy, prevents a duplicate payment. Verifying that claim requires compiling and running
nine modules and two lock backends **against the same vocabulary at the same instant**.

That vocabulary is frozen in [C1–C5](../04-contracts.md#c-routing), and a contract change is by definition
a breaking change across several modules at once ([04 §4.1](../04-contracts.md#c-purpose)). Any structure
that makes such a change land in more than one atomic unit is a structure in which the contract set is
advisory. The counter-force is real: a monorepo builds more than you changed, and one bad module can block
everything.

## 10.2 Decision {#adr10-decision}

| # | Rule |
|---|---|
| D1 | **One repository** holds all Java modules, `deploy/terraform`, `deploy/k8s`, `deploy/compose`, `docs/` (contracts and ADRs) and `.github/workflows/`. Layout pinned in [C5 §5.5](../contracts/C5-config-build-and-naming.md#ct5-layout). |
| D2 | **One Gradle build**, Kotlin DSL. `settings.gradle.kts` is the only place a module is declared; `build.gradle.kts` at the root applies convention plugins and contains **no versions and no per-module logic**. |
| D3 | **One version list**: `gradle/libs.versions.toml`. A version literal anywhere else is a defect ([C5 §5.3](../contracts/C5-config-build-and-naming.md#ct5-catalog)). Java 25, Spring Boot 4.1, PostgreSQL 16, etcd 3.6, Terraform 1.15 are stated once. |
| D4 | **`build-logic` convention plugins configure all modules identically**: Java 25 toolchain, Spotless + google-java-format, test conventions, and the Lombok policy (`@RequiredArgsConstructor` and `@Slf4j` only). Nine modules, one config, instead of nine drifting copies. |
| D5 | **Trunk-based development, short-lived branches, no release branches, no per-module versioning.** Modules are built from one commit and images are tagged `<module>:<short-sha>` ([C5 §5.6](../contracts/C5-config-build-and-naming.md#ct5-naming)) — the sha *is* the version, so "which lock-api is that server running?" is never a question. Currently intent, not practice ([ADR-011](ADR-011-no-git-initialisation-yet.md)). |
| D6 | **A contract change and every consumer of it land in one commit.** Renaming a column, an error code or an exception touches migrations, server, client, executor, proxy and harness together, or it is not done. |
| D7 | **`lock-api` has zero dependencies — no exceptions, enforced by the build**, not by good manners. See §10.3. |
| D8 | Migrations live **inside the owning module** (`lock-server` owns lockdb, `payment-resource` owns paydb); there is no shared migrations directory, because that is how two databases acquire each other's tables. |

Operator surface (the whole build, from the root):

```
./gradlew build                 # compile + unit tests, all modules
./gradlew spotlessCheck         # formatting is a build failure, not a review comment
./gradlew :harness:test         # the correctness scenarios
./gradlew :lock-server:bootRun --args='--lock.backend=etcd'
```

## 10.3 Why the zero-dependency rule is the load-bearing part {#adr10-zerodep}

`lock-api` is the only module every other module may depend on, and it is dependency-free
([C2 §2.1](../contracts/C2-java-api.md#ct2-zero-dep), [C5 §5.4](../contracts/C5-config-build-and-naming.md#ct5-modules)).
That is not tidiness. Three properties depend on it:

| Property | Mechanism | Failure mode if `lock-api` gains dependencies |
|---|---|---|
| Client and server **provably agree** without either importing the other's internals | Both compile against the same signature-only module | The contract acquires a runtime; agreement becomes "same Spring version", and the seam stops being checkable. |
| `payment-resource` can hold the **token type** while having **no ability to talk to the lock service** | `lock-api` contains no client, no HTTP, no config | If a client or a Spring auto-configuration leaked into `lock-api`, the resource *could* ask "am I fenced?" — and the entire proof (an independent resource that has never heard of the lock service) collapses into a circular argument. |
| The **pg / etcd SPI seam** stays a real seam | `LockStore` / `SessionRegistry` are plain interfaces ([C2 §2.5](../contracts/C2-java-api.md#ct2-spi)) | A jetcd or JDBC type on the interface makes one backend privileged, and the measured comparison compares two things that are no longer symmetric. |

So the convention plugin **fails the build** if `lock-api` declares any non-test dependency — including
Lombok, Jackson annotations and Spring. Enforcement is cheap; the property is not recoverable once lost,
because by then a dozen call sites depend on the leak.

## 10.4 Consequences {#adr10-consequences}

**Positive**

- A breaking contract change is one atomic, reviewable diff — the property that makes [04 §4.4](../04-contracts.md#c-precedence) (contract wins) enforceable rather than aspirational.
- No internal artifact publishing, no `SNAPSHOT` resolution, no diamond of mutually incompatible `lock-api` versions.
- Infrastructure and the code whose topology it encodes review together: a new metric and its `PodMonitoring` and its alert policy arrive in one commit.
- One formatter, one toolchain, one test convention; onboarding is `./gradlew build`.

**Negative**

- The build grows superlinearly with modules; without configuration cache and build cache, a one-line change eventually costs a full compile.
- No module boundary is defended by a repository boundary, so an accidental dependency (`payout-executor` → `lock-server` internals) is easy to add and must be caught by review or by an explicit dependency rule.
- Everything shares a fate: a broken `build-logic` plugin breaks all nine modules at once.
- `deploy/` and `docs/` changes trigger Java CI unless path filters are configured.

**What we accept**

- Single-repo blast radius, in exchange for atomic cross-module correctness.
- The dependency-hygiene rules (D7, no `impl`/`util` packages) are conventions with a build check behind only the most important one.

## 10.5 Alternatives considered {#adr10-alternatives}

| Alternative | Why rejected |
|---|---|
| **Repo per module**, `lock-api` published to Artifact Registry | Directly defeats the purpose: a contract change becomes a publish plus N dependency bumps, and during the interval the modules disagree. A published-artifact `lock-api` also invites version skew between the client and the server that must agree. |
| Two repos: **lock service** and **payments demo** | Superficially clean, and it splits the exact diff a reviewer must see whole (token issuance and token enforcement). The fencing experiment lives across that line. |
| Monorepo with **per-module Gradle builds** (composite / included builds) | Faster isolation, but reintroduces per-module version resolution and makes "one commit, one system" harder to prove for no benefit at this size. |
| Maven | Excluded by the toolchain decision; also worse at convention plugins, which is how nine modules stay identical. |
| **Versions in each module's build file** | Guarantees drift, and the drift shows up as a runtime `NoSuchMethodError` in one of nine services. |
| Allow Lombok / Spring in `lock-api` "just for convenience" | Convenience now, unprovable contract later — see §10.3. This is the rule that must not bend. |
| Git submodules to share `lock-api` | All the coordination cost of many repos plus a checkout hazard, and no atomic commit. |

## 10.6 Revisit when {#adr10-revisit}

| Trigger | Action |
|---|---|
| Clean `./gradlew build` exceeds a few minutes | Enable configuration cache and remote build cache, add CI path filters; split the repo only after caching has been tried and measured. |
| A second consumer outside this repo genuinely needs `lock-api` | Publish `lock-api` **from** the monorepo (still built from one commit) rather than moving it out. |
| An illegal module dependency lands twice | Promote review convention to an enforced dependency rule in `build-logic`. |
| Anything is proposed for `lock-api`'s dependency list | Default answer is no; the burden of proof is on the addition, and §10.3 names what is lost. |
| Real releases with support windows appear | Only then reconsider per-module versioning; until then `<module>:<short-sha>` from trunk is strictly simpler and strictly more traceable. |
