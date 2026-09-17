# deploy/compose

The local, no-cloud stack (NFR-15): two PostgreSQL 16 servers and a single-node etcd 3.6. Behind the
`apps` profile, it also runs the five Java services, built from source with the repository's one
[Dockerfile](../images/Dockerfile). It needs no Google Cloud account, only Docker with Compose v2.
Later tasks rely on the hostnames, ports and credentials below, so change them only together with
[the ledger](../../tasks/README.md).

## Run it

Every command runs from this directory.

```sh
docker compose up -d                           # lockdb, paydb, etcd
docker compose ps                              # all three reach "healthy" within about 30 s
docker compose --profile apps up -d --build    # also build and start the Java services
docker compose stop lockdb                     # the experiment move: paydb keeps serving
docker compose down                            # stop, keep the data
docker compose down -v                         # reset: stop and delete all three volumes
```

`.env` is optional. Without it, `compose.yaml` falls back to two lab-only passwords, the same values
as in [`.env.example`](.env.example). To change them, run `cp .env.example .env` and edit `.env`, which
is git-ignored and never committed. These two placeholders are the only passwords in the repository.
Never reuse them anywhere else.

## Ports

| Service | Container | Host port → container | Database / user | Notes |
|---|---|---|---|---|
| `lockdb` | `dlock-lockdb` | 5433 → 5432 | `lockdb` / `lockapp` | own volume `lockdb-data`; the one the experiments kill |
| `paydb` | `dlock-paydb` | 5434 → 5432 | `paydb` / `payapp` | own volume `paydb-data`; must stay up when lockdb dies |
| `etcd` | `dlock-etcd` | 2379, 2380 | — | single member, own volume `etcd-data`; network alias `dlock-etcd-headless` |
| `lock-server` | *(profile `apps`)* | 8081 → 8080 | lockdb | `LOCK_BACKEND=pg` |
| `payment-resource` | *(profile `apps`)* | 8082 → 8080 | paydb | |
| `rail-proxy` | *(profile `apps`)* | 8083 → 8080 | paydb | |
| `payout-executor` | *(profile `apps`)* | 8084 → 8080 | — | |
| `rail-stub` | *(profile `apps`)* | 8090 → 8080 | — | |

Inside the network, services reach each other by service name: `lockdb:5432`, `paydb:5432`, and
`dlock-etcd-headless:2379` for etcd. That etcd name is the `etcd.endpoints` default in
[C5 §5.1](../../docs/contracts/C5-config-build-and-naming.md#ct5-config), so no override is needed.
The application host ports are the ones the later task specifications already use with `curl`. Host
port 8080 stays free for a plain `./gradlew bootRun`.

```sh
psql "postgresql://lockapp@localhost:5433/lockdb"     # password: SPRING_DATASOURCE_PASSWORD (.env.example)
psql "postgresql://payapp@localhost:5434/paydb"       # password: PAYMENTS_DATASOURCE_PASSWORD (.env.example)
docker exec dlock-etcd etcdctl endpoint health        # etcdctl ships in the image; none needed on the host
curl -s localhost:2379/health                         # {"health":"true",...}
```

## Why two PostgreSQL containers

The two databases are separate servers, not two schemas in one container
([ADR-003](../../docs/adr/ADR-003-two-databases-two-instances.md)). The fencing experiments stop or
pause lockdb while paydb keeps accepting writes. With one server, stopping the lock backend would also
stop the protected resource, and the experiment could not be run at all. For the same reason, the two
databases never share a volume or a password variable: `SPRING_DATASOURCE_PASSWORD` belongs to lockdb
and `PAYMENTS_DATASOURCE_PASSWORD` to paydb ([C5 §5.7](../../docs/contracts/C5-config-build-and-naming.md#ct5-env)).
Each variable is used by the database container and by the services that connect to it, so the two
sides cannot drift apart. There is no init SQL here. The containers create only the database and its
user, and Flyway owns every schema
([C1 §1.8](../../docs/contracts/C1-database-schemas.md#ct1-migrations)).

## Health checks

The `apps` services wait for `service_healthy` on the databases they use.

- **PostgreSQL:** `pg_isready -h 127.0.0.1 -U <user> -d <database>`, scoped to the database's own
  user and database. The probe goes over TCP on purpose. During first start-up, the image runs a
  bootstrap server that listens only on the Unix socket. A socket probe would report "ready" before
  the application database exists, and the first service to start would race the bootstrap.
- **etcd:** `etcdctl endpoint health`, in exec form, because the etcd image has no shell.

## Java services

Each service under the `apps` profile builds `deploy/images/Dockerfile` with its own `MODULE` build
argument, and none of them names a registry image. A service that has no code yet fails its image
build with `no bootJar for <module>`. That failure is expected until the milestone task that adds
the service's application class, for example T-016 for `lock-server`. The builds share one Gradle
cache and use it one at a time, so `--build` with several services runs their Gradle steps in turn.

## Image versions

The PostgreSQL, etcd and Temurin versions live in
[`gradle/libs.versions.toml`](../../gradle/libs.versions.toml), as `postgres-image`, `etcd-image`
and `temurin-image` ([C5 §5.3](../../docs/contracts/C5-config-build-and-naming.md#ct5-catalog)).
`compose.yaml` and the Dockerfile repeat them as literal tags, because neither can read the catalog.
When you bump one, change the catalog entry and the tag in the same commit.
