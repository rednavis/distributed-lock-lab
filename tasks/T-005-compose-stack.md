# T-005 — Local compose stack

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M0 Foundations · **Estimate** 25 min

**Preconditions** — T-001…T-004 done. You inherit a Gradle monorepo whose `settings.gradle.kts`
registers every module of [C5 `#ct5-modules`](../docs/contracts/C5-config-build-and-naming.md#ct5-modules),
a `gradle/libs.versions.toml` holding all versions, `build-logic` convention plugins, and two empty-but-wired
Flyway migration trees (`lock-server` → lockdb, `payment-resource` → paydb). No container tooling exists yet.

**Goal** — Give the repo a no-cloud local dependency stack (two separate PostgreSQL 16 containers plus a
single-node etcd 3.6) that satisfies NFR-15 and is the substrate every later local run and Testcontainers-free
manual experiment uses — **plus the single shared Dockerfile** that lets that same compose file build and run
the Java services as soon as they exist, so no later milestone has to invent a container build.

## 1. Why this task exists

The two-database rule ([ADR-003](../docs/adr/ADR-003-two-databases-two-instances.md)) is only real if the
local path also has **two servers**, not two schemas in one container: the fencing experiments require killing
or pausing lockdb while paydb keeps serving writes, and one container makes that unexpressible. etcd appears
now, even though the pg backend lands first (M1), so that M3 needs no infrastructure work. Everything after
this task assumes `docker compose up -d` yields the same hostnames and credentials that the GCP manifests use.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| Compose lives in `deploy/compose/`; nothing new at repo root | [C5 `#ct5-layout`](../docs/contracts/C5-config-build-and-naming.md#ct5-layout) |
| Database and user names `lockdb`/`lockapp`, `paydb`/`payapp` | [C5 `#ct5-naming`](../docs/contracts/C5-config-build-and-naming.md#ct5-naming) |
| `spring.datasource.url` = lockdb, `payments.datasource.url` = paydb; `etcd.endpoints`; `lock.backend` default `pg` | [C5 `#ct5-config`](../docs/contracts/C5-config-build-and-naming.md#ct5-config) |
| Env-var spelling — uppercase, `.`→`_`, **hyphens deleted** | [C5 `#ct5-env`](../docs/contracts/C5-config-build-and-naming.md#ct5-env) |
| etcd object names `dlock-etcd`, `dlock-etcd-headless` | [C5 `#ct5-naming`](../docs/contracts/C5-config-build-and-naming.md#ct5-naming) |
| PostgreSQL 16 / etcd 3.6 image versions come from the catalog, never invented | [C5 `#ct5-catalog`](../docs/contracts/C5-config-build-and-naming.md#ct5-catalog) |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report the mismatch.**

## 3. Deliverables

| Path | What |
|---|---|
| `deploy/compose/compose.yaml` | The stack: `lockdb`, `paydb`, `etcd`, plus the app services behind a profile |
| `deploy/images/Dockerfile`, `deploy/images/.dockerignore` | **One** shared multi-stage build parameterised by `ARG MODULE` — the only image definition in the repo, serving all six deployable services; `.dockerignore` excludes `build/`, `.gradle/`, `docs/`, `.terraform/` |
| `deploy/compose/.env.example` | Every variable the file interpolates, with lab-safe defaults |
| `deploy/compose/README.md` | Up/down/reset commands, the port table, and the "why two containers" paragraph |

## 4. Specification

**Services.** Three infra services start by default. The five Java workloads
(`lock-server`, `payment-resource`, `payout-executor`, `rail-proxy`, `rail-stub`) are declared
**under a compose profile named `apps`** — a `build:` block pointing at the shared Dockerfile with
`args: MODULE=<module>`, port, env block, `depends_on` with `condition: service_healthy` — so
`docker compose up -d` at M0 brings infra only, while `--profile apps up -d --build` builds and runs
whichever services already have code. Nothing is ever *pulled* for a Java workload.

**The shared Dockerfile.** `deploy/images/Dockerfile` is written once, here, and is the repo's only image
definition: stage one on a JDK 25 image copies the wrapper, `settings.gradle.kts`,
`gradle/libs.versions.toml`, `build-logic/` and sources and runs `bootJar` for `$MODULE`; stage two on a
JRE 25 base runs as a non-root user, copies the layered jar dependency-first, `EXPOSE 8080`, and calls the
JVM directly — **no shell wrapper around the entrypoint** (a wrapper swallows `SIGTERM`). No module name
appears in any `COPY`/`RUN`: the module is `ARG MODULE`, so one file serves all six deployable services and
a module still without code simply is not built. **T-054 reuses this exact file for the Artifact Registry
push — it does not write a second one.** A module skeleton that cannot yet produce a `bootJar` is expected
to fail its build until its M1/M2 task lands; that is a missing service, not a missing Dockerfile.

**Ports and identity.** Both PostgreSQL containers listen on 5432 internally; host ports differ.

| Service | Container name | Internal | Host | Database / user | Notes |
|---|---|---|---|---|---|
| `lockdb` | `dlock-lockdb` | 5432 | 5433 | `lockdb` / `lockapp` | separate volume; the one that gets killed in experiments |
| `paydb` | `dlock-paydb` | 5432 | 5434 | `paydb` / `payapp` | separate volume; must stay up when lockdb dies |
| `etcd` | `dlock-etcd` | 2379, 2380 | 2379, 2380 | — | single node, `new` initial-cluster-state, advertise on the container name |

Add a network alias `dlock-etcd-headless` on the etcd service so the pinned `etcd.endpoints` default resolves
locally without an override.

**Healthchecks.** Each PostgreSQL service uses `pg_isready` scoped to **its own** user and database (a bare
`pg_isready` reports the server up before the app database exists). etcd uses `etcdctl endpoint health`.
Give each an interval of a few seconds, a short timeout, and a `start_period`; `retries` such that total grace
is roughly 30 s. Healthchecks are what `depends_on` in the `apps` profile keys off.

**Credentials and env.** Passwords come from `.env` interpolation with a documented weak local default;
`.env` itself is git-ignored-by-convention and never committed (only `.env.example`). App placeholder env
blocks use the contract env-var spellings and point at container hostnames, not `localhost`.

**Persistence.** One named volume per database, plus one for etcd. No bind mounts of host data directories.

**No DDL here.** Rely on the image's `POSTGRES_DB`/`POSTGRES_USER` bootstrap only. Do not add an init-SQL
directory: schema is Flyway's, owned by the two migration trees, per
[C1 `#ct1-migrations`](../docs/contracts/C1-database-schemas.md#ct1-migrations).

## 5. Acceptance criteria

1. `deploy/compose/compose.yaml` parses: `docker compose -f deploy/compose/compose.yaml config` exits 0.
2. Default `up` starts exactly three containers; `docker compose config --profiles` lists `apps`.
3. All three infra services report `healthy` within 60 s of a cold start.
4. `psql` on host port 5433 reaches database `lockdb` as `lockapp`, and 5434 reaches `paydb` as `payapp`;
   neither database is visible on the other port.
5. Stopping `lockdb` leaves `paydb` healthy and queryable — verified, not assumed.
6. `etcdctl` against `localhost:2379` reports a healthy single-member cluster.
7. No file contains a hard-coded password outside `.env.example`; every env-var name matches `#ct5-env`.
8. Image tags for PostgreSQL and etcd match the versions in `gradle/libs.versions.toml`.
9. `deploy/images/Dockerfile` exists, contains exactly one `ARG MODULE` and no module name in any
   `COPY`/`RUN`, and `docker build --build-arg MODULE=lock-server` gets as far as running the Gradle
   `bootJar` task in stage one (it may then fail for want of an application class until T-016 — record which
   it was). The file itself must not be the thing that fails.
10. `docker compose --profile apps config` resolves a `build:` block per Java service, each with its own
    `MODULE` arg and none with a registry image reference.

## 6. Verification

```
docker compose -f deploy/compose/compose.yaml config -q
docker compose -f deploy/compose/compose.yaml up -d
docker compose -f deploy/compose/compose.yaml ps        # 3 services, State=healthy
psql "postgresql://lockapp@localhost:5433/lockdb" -c '\conninfo'
psql "postgresql://payapp@localhost:5434/paydb"   -c '\conninfo'
docker compose -f deploy/compose/compose.yaml stop lockdb
psql "postgresql://payapp@localhost:5434/paydb"   -c 'select 1'   # still 1 row
docker exec dlock-etcd etcdctl endpoint health
docker compose -f deploy/compose/compose.yaml --profile apps config | grep -c 'MODULE'   # 5 services
docker build --build-arg MODULE=lock-server -f deploy/images/Dockerfile .                # stage one runs
docker compose -f deploy/compose/compose.yaml down -v
```

## 7. Out of scope

Per-module Dockerfiles — there are none and there never will be: the one `ARG MODULE` file delivered here is
the whole story, consumed by the T-007 container workflow, by `--profile apps` from M1 onward, and by T-054's
registry push. Kubernetes manifests and Terraform (M5). Testcontainers wiring
(owned by the integration tests in M1). Any migration SQL (T-010 onward).

## 8. Hazards

Two PostgreSQL services in one file invite a single shared volume or a single password variable — that
silently recouples the databases the project exists to keep apart ([C5 `#ct5-config`](../docs/contracts/C5-config-build-and-naming.md#ct5-config),
closing paragraph). Second trap: declaring the app services without a profile makes `up` fail on missing images
and makes the stack look broken at M0. Third: a healthcheck that omits `-d`/`-U` passes before the app database
is created, so the first app start races the bootstrap.

## 9. On completion

Mark the T-005 row done in `tasks/README.md`, record the host ports there in one line (later tasks read them),
and note any deviation from the port table above.
