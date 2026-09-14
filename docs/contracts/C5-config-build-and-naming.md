# C5 - Configuration, build and naming

Contract document. Binding on every module, manifest and Terraform file in the project. Satisfies
FR-09 (backend selection by configuration), FR-27 (fail-closed is not configurable), FR-29 (the two
experiment switches), NFR-16 (one build, one version catalog). Domain is the fictional PSP.

Rule of the document: **if a name appears here, it is pinned.** Parallel work sessions that invent a
second spelling produce manifests that do not match the Terraform and a client that does not match
the server. Divergence is the failure mode this file exists to prevent.

## 5.1 Configuration table {#ct5-config}

Runtime-safe means: changeable without a restart, via `/actuator/refresh`-style rebinding of an
`@ConfigurationProperties` bean. Everything else requires a pod restart because it is read once at
wiring time (a datasource URL, a backend choice) and re-reading it mid-flight would leave two
half-configured code paths live at once.

| Key | Type | Default | Read by | Effect | Runtime-safe |
|---|---|---|---|---|---|
| `lock.backend` | enum `pg`\|`etcd` | `pg` | `lock-server` (`dev.lock.server.core`) | Selects which `LockStore` implementation is wired - `store.pg` or `store.etcd` | No - store is a singleton chosen at startup; switching live would split the key space across two token sources |
| `lock.default.ttl` | duration | `30s` | `lock-server` | Lease length applied to a grant when the caller names none | Yes - affects only grants issued after the change |
| `lock.session.ttl` | duration | `15s` | `lock-server` (`SessionRegistry`) | How long a session survives without a heartbeat before its locks are released | Yes - but shortening it below the client heartbeat period mass-expires live sessions |
| `lock.client.safety-margin` | fraction | `0.30` | `lock-client` | The client's local deadline is `lease * (1 - margin)` from the heartbeat send time on a monotonic clock, so the client gives up before the server does | Yes |
| `payment.fencing.enabled` | boolean | `true` | `payment-resource` | When false, the conditional `UPDATE ... WHERE fence < :token` degenerates to an unconditional update - see [5.2](#ct5-killswitches) | Yes, deliberately - the experiment must flip it without a redeploy |
| `rail.proxy.fencing.enabled` | boolean | `true` | `rail-proxy` | When false, the `rail_high_water` check is skipped - see [5.2](#ct5-killswitches) | Yes, deliberately |
| `rail.stub.latency-ms` | int ms | `250` | `rail-stub` | Injected think time before responding; used to widen the window in which a lease can expire mid-call | Yes |
| `rail.stub.failure-rate` | fraction | `0.0` | `rail-stub` | Probability of a `REJECTED` reply | Yes |
| `rail.stub.duplicate-ack-rate` | fraction | `0.0` | `rail-stub` | Probability that a submission is accepted but the reply is lost, manufacturing `RAIL_AMBIGUOUS` | Yes |
| `etcd.endpoints` | csv list | `http://dlock-etcd-headless:2379` | `lock-server` (`store.etcd`) | Cluster membership for the jetcd client | No |
| `spring.datasource.url` | JDBC URL | (none - must be set) | `lock-server` | **lockdb** on `dlock-pg-lock`; user `lockapp` | No |
| `payments.datasource.url` | JDBC URL | (none - must be set) | `payment-resource`, `rail-proxy` | **paydb** on `dlock-pg-pay`; user `payapp` | No |

Two databases, two keys, deliberately. The lock service must be able to be down, slow, or failed over
while paydb is healthy, and the reverse; one shared URL would quietly couple them and make the
experiment unrunnable.

## 5.2 The two kill switches {#ct5-killswitches}

`payment.fencing.enabled` and `rail.proxy.fencing.enabled` are the **most valuable configuration in
the repository.** They are the only reason the central claim of the project is demonstrated rather than
asserted.

| Switch | On (default) | Off | Enforcement point |
|---|---|---|---|
| `payment.fencing.enabled` | `UPDATE account SET balance_minor = ..., fence = :token WHERE account_id = :id AND fence < :token`; a stale writer gets 0 rows and a `FencedOutException` | Same statement without the `fence < :token` predicate; a stale writer wins and overwrites a newer balance | paydb row (`account.fence`, `ledger_entry.fence`) |
| `rail.proxy.fencing.enabled` | Submission rejected with outcome `FENCED` unless `presented_token > rail_high_water.highest_token` | High-water mark still recorded, never compared; a stale token submits to the rail | `rail-proxy` process, separate from both the lock service and paydb |

**Why this is the experiment.** Run the harness scenario with both switches on: a worker's lease
expires mid-rail-call, the new holder proceeds, the old holder wakes and is refused twice - once by
the proxy (`fenced_out`, `resource=rail`) and once by paydb (`resource=account`). Zero duplicate
submissions, ledger balanced (INV-01). Now flip both off and change **nothing else** - same lock
service, same backend, same token generation, same test. You get a duplicate submission on a
non-idempotent rail and a ledger whose entries no longer sum to the balance.

That is the whole lesson in one diff: **mutual exclusion did not provide the safety; the fence did.**
A reader who has only ever been told "use a lock" can see the exact component that was carrying the
correctness, because it is the only thing that changed.

**Operational warning.** Off is a *test fixture*, not an operating mode. They default to true, are
logged at `WARN` at startup when false (FR-29), and each false value raises a permanent alert for as
long as it is set. They must never be turned off outside a harness run. Turning them off to silence a
`lock.fenced.out` alert is **removing the smoke detector because the kitchen is on fire**: a
fenced-out write is not a malfunction, it is the system reporting that a stale writer was stopped. Mute
the alarm and the next stale writer is not stopped, it is *served* - and the damage is an irreversible
external payment, not a log line. If `lock.fenced.out` fires steadily, the bug is upstream (lease too
short, heartbeat starved, GC pause, backend failover), and the runbook says so.

## 5.3 Version catalog inventory {#ct5-catalog}

One `gradle/libs.versions.toml`, rendered here as a table. Versions are current at time of writing;
re-check and move **forward** when touched, never pin backwards.

| Alias | Coordinate | Version | Used by |
|---|---|---|---|
| `spring-boot-bom` | `org.springframework.boot:spring-boot-dependencies` | 4.1.0 | all Spring modules (platform import) |
| `spring-boot-plugin` | `org.springframework.boot` (Gradle plugin) | 4.1.0 | `lock-server`, `payment-resource`, `payout-executor`, `rail-proxy`, `rail-stub` |
| `spring-boot-web` | `org.springframework.boot:spring-boot-starter-web` | via BOM | `lock-server`, `payment-resource`, `rail-proxy`, `rail-stub` |
| `spring-boot-jdbc` | `org.springframework.boot:spring-boot-starter-jdbc` | via BOM | `lock-server`, `payment-resource`, `rail-proxy` |
| `spring-boot-actuator` | `org.springframework.boot:spring-boot-starter-actuator` | via BOM | all services |
| `postgresql` | `org.postgresql:postgresql` | 42.7.5 | `lock-server`, `payment-resource`, `rail-proxy` |
| `flyway-core` | `org.flywaydb:flyway-core` | 11.8.0 | `lock-server` (lockdb), `payment-resource` (paydb) |
| `flyway-postgresql` | `org.flywaydb:flyway-database-postgresql` | 11.8.0 | same two |
| `jetcd-core` | `io.etcd:jetcd-core` | 0.8.5 | `lock-server` only |
| `micrometer-prometheus` | `io.micrometer:micrometer-registry-prometheus` | via BOM | all services |
| `otel-bom` | `io.opentelemetry:opentelemetry-bom` | 1.49.0 | all services (trace export) |
| `lombok` | `org.projectlombok:lombok` | 1.18.38 | all Java modules except `lock-api` |
| `junit-bom` | `org.junit:junit-bom` | 5.13.0 | all test source sets |
| `assertj` | `org.assertj:assertj-core` | 3.27.3 | all test source sets |
| `awaitility` | `org.awaitility:awaitility` | 4.3.0 | `harness`, lease-expiry tests |
| `testcontainers-bom` | `org.testcontainers:testcontainers-bom` | 1.21.0 | `lock-server`, `payment-resource`, `rail-proxy`, `harness` |
| `spotless-plugin` | `com.diffplug.spotless` (Gradle plugin) | 7.0.3 | `build-logic` (applied to all) |
| `google-java-format` | `com.google.googlejavaformat:google-java-format` | 1.28.0 | `build-logic` (Spotless step) |

**The rule the catalog enforces: no version number appears anywhere except the catalog.** No literal
version in a `build.gradle.kts`, a Dockerfile base tag chosen ad hoc, or a manifest. The reason is
concrete: `lock-client` and `lock-server` exchange a wire contract; if one drifts onto a different
Jackson or jetcd, the mismatch is invisible until a deserialisation error in a live critical section,
and CI passed because each module built fine alone. `lock-api` additionally has **zero third-party
dependencies** (NFR-16), so it cannot drag a version anywhere.

## 5.4 Module inventory {#ct5-modules}

Nine modules plus `build-logic`.

| Module | Purpose | Depends on | One-sentence reason to exist |
|---|---|---|---|
| `lock-api` | Wire and Java contract: `LockService`, `LockHandle`, `LockInfo`, `LockOutcome`, `LockLostException`, `FencedOutException`, `ContentionException`, `NotLeaderException` (`dev.lock.api`) | nothing | A dependency-free contract is the only way client and server can be proved to agree without one importing the other's guts. |
| `lock-server` | Lease/token authority; `core` (`SessionRegistry`, lease clock), `store.pg`, `store.etcd`, `web` | `lock-api` | The single writer of `fencing_token_seq` and of `lock_entry`, so token monotonicity has exactly one owner. |
| `lock-client` | SDK: conservative monotonic deadline, heartbeat loop, `checkStillHeld()` (signature pinned by [C2 §2.6](C2-java-api.md#ct2-sdk) as `void … throws LockLostException`; an `is`-prefixed boolean name is wrong because the check raises rather than merely answers) (`dev.lock.client`) | `lock-api` | Correct lease arithmetic is the part every caller gets wrong, so it is written once and reused. |
| `payment-resource` | paydb owner: payouts, ledger, balances; enforces `fence < :token` (`dev.lock.payments.resource`) | `lock-api` | Enforcement point (a) must live in a process that has no idea a lock service exists, or the test proves nothing. |
| `payout-executor` | The worker: claim, lock, re-read, submit, post (`dev.lock.payments.executor`) | `lock-api`, `lock-client` | The only module that composes the whole critical section, and therefore the only place the ordering rules can be read end to end. |
| `rail-proxy` | Records the attempt before forwarding; enforces `rail_high_water` (`dev.lock.rail.proxy`) | `lock-api` | Enforcement point (c) - the guard you build when the downstream cannot be made idempotent and cannot be changed. |
| `rail-stub` | Deliberately non-idempotent fake rail with injectable latency, failure and duplicate-ack (`dev.lock.rail.stub`) | nothing | The hazard has to be real and reproducible, otherwise fencing is protecting against a hypothetical. |
| `harness` | Scenario runner, chaos injectors, invariant checkers (`dev.lock.harness`) | all of the above | Turns the claim "fencing is what makes this safe" into a repeatable experiment with a pass/fail result. |
| `deploy` | Terraform 1.15 root + envs, K8s manifests, `docker compose` local path (no Java) | none (build-only) | Infrastructure lives in the same repo and the same review as the code whose topology it encodes. |
| `build-logic` | Convention plugins: Java 25 toolchain, Spotless + google-java-format, Lombok limited to `@RequiredArgsConstructor`/`@Slf4j`, test conventions | catalog | Nine modules configured identically by one plugin instead of nine drifting copies of the same block. |

## 5.5 Repository layout {#ct5-layout}

```
distributed-lock-lab/
  settings.gradle.kts          # module registry + version catalog wiring; the only place modules are declared
  build.gradle.kts             # applies convention plugins only - no versions, no per-module logic
  gradle/libs.versions.toml    # the single source of every version (see 5.3)
  build-logic/                 # convention plugins: toolchain, format, test, Lombok policy
  lock-api/                    # dev.lock.api - dependency-free contract
  lock-server/                 # dev.lock.server.{core,store.pg,store.etcd,web} + lockdb migrations
  lock-client/                 # dev.lock.client - SDK with the conservative deadline
  payment-resource/            # dev.lock.payments.resource + paydb migrations
  payout-executor/             # dev.lock.payments.executor - the critical section
  rail-proxy/                  # dev.lock.rail.proxy - high-water fencing, attempt-before-forward
  rail-stub/                   # dev.lock.rail.stub - the non-idempotent hazard
  harness/                     # dev.lock.harness - scenarios, chaos, invariant checks
  deploy/
    terraform/                 # root module + env dirs; state in gs://dlock-tfstate
    k8s/                       # manifests per workload, one dir per k8s SA
    compose/                   # local no-cloud path (NFR-15)
  docs/                        # this document set; docs/contracts/ = C1..C6, docs/adr/ = decisions
  .github/workflows/           # build, check, image publish
```

Migrations sit **inside the owning module** (`lock-server` owns lockdb, `payment-resource` owns paydb)
because a shared migrations directory is how two databases silently acquire each other's tables.

## 5.6 Naming conventions {#ct5-naming}

| Namespace | Rule | Examples (pinned) | Anti-example |
|---|---|---|---|
| GCP project / region | Fixed for the project | `dlock-lab`, `europe-central2` | any other region - Cloud SQL and GKE must be co-located |
| GCP network | `dlock-` prefix, lowercase, hyphens | `dlock-vpc`, `dlock-subnet`, secondary ranges `pods`, `services` | `dlock_vpc` (underscore is invalid) |
| GCP compute / data | `dlock-<kind>[-<role>]` | `dlock-gke` (Autopilot), `dlock-pg-lock` (REGIONAL), `dlock-pg-pay` (ZONAL) | `dlock-postgres-1` - encodes no role |
| GCP IAM / secrets | Google SA `dlock-app`; secrets `dlock-<db>-db-password` | `dlock-lock-db-password`, `dlock-pay-db-password` | one shared password secret |
| Artifact Registry | repo `dlock`; image path `dlock/<module>` | `dlock/lock-server` | `dlock/lockserver` |
| K8s objects | Object name **equals the module name**, one k8s SA per workload | `lock-server`, `payment-resource`, `payout-executor`, `rail-proxy`, `rail-stub`; StatefulSet `dlock-etcd` + headless Service `dlock-etcd-headless` | `lock-svc`, `payments` |
| Terraform module names | Noun, snake_case, no `tf`/`module` in the name | `module "network"`, `module "sql_lock"`, `module "gke"` | `module "tf_network_module"` |
| Terraform resource local names | `this` for the sole resource of a type in a module; otherwise the role | `google_sql_database_instance "this"`, `google_secret_manager_secret "pay_db_password"` | `google_sql_database_instance "dlock_pg_pay"` - duplicates the `name` attribute |
| Databases / users | Lowercase, no prefix | databases `lockdb`, `paydb`; users `lockapp`, `payapp` | `lock_db` |
| Tables / columns | Singular table, snake_case column, `<entity>_id` PK | `lock_entry`, `lock_session`, `lock_revocation`, `account`, `payout`, `ledger_entry`, `rail_submission`, `rail_high_water` | `payouts`, `id` |
| Indexes / sequences | `<table>_<column-role>_idx`; `<column>_seq` | `lock_entry_expiry_idx`, `lock_entry_session_idx`, `fencing_token_seq` | `idx_lock_entry_1` |
| Enum values | UPPER_SNAKE in the column, never abbreviated | `PENDING`…`ABANDONED`; `DEBIT`/`CREDIT`; `ACKED`/`REJECTED`/`TIMEOUT`/`FENCED` | `SUBMITTED` for `RAIL_SUBMITTED` |
| Image tags | Immutable `<module>:<short-sha>`; `:latest` is never deployed | `dlock/rail-proxy:9f3c1ab` | `:latest`, `:dev` |
| K8s labels | `app.kubernetes.io/name=<module>`, `/part-of=dlock-lab`, `/version=<short-sha>`, `/component=lock|payments|rail|harness` | as listed | ad-hoc `tier=backend` |
| K8s annotations | `dlock-lab/<thing>` custom keys; standard keys keep their upstream prefix | `dlock-lab/fencing-enabled`, `iam.gke.io/gcp-service-account` | `fencing=off` |
| Java packages | `dev.lock.<area>[.<sub>]`, singular area, no `impl`/`util` packages | the ten pinned roots in [5.4](#ct5-modules) | `dev.lock.common`, `dev.lock.server.impl` |
| HTTP headers | `X-` + Title-Case-Hyphen | `X-Fencing-Token`, `X-Owner-Id`, `X-Idempotency-Key` | `X-Fence` |
| Metrics / log events | Metric `<area>.<thing>[.<unit>]` dotted lowercase; log `event` snake_case past tense | `lock.acquire`, `payout.backlog.age.seconds`; `lock_granted`, `fenced_out` | `lockAcquireTime`, `event="Lock granted"` |

## 5.7 Environment variables {#ct5-env}

One convention, mechanical in both directions, because half these keys are set by a manifest and read
by Spring relaxed binding:

1. Uppercase the key. 2. Replace `.` with `_`. 3. **Delete hyphens** (do not convert them). 4. Prefix nothing.

| Config key | Environment variable |
|---|---|
| `lock.backend` | `LOCK_BACKEND` |
| `lock.default.ttl` | `LOCK_DEFAULT_TTL` |
| `lock.client.safety-margin` | `LOCK_CLIENT_SAFETYMARGIN` |
| `payment.fencing.enabled` | `PAYMENT_FENCING_ENABLED` |
| `rail.proxy.fencing.enabled` | `RAIL_PROXY_FENCING_ENABLED` |
| `rail.stub.duplicate-ack-rate` | `RAIL_STUB_DUPLICATEACKRATE` |
| `payments.datasource.url` | `PAYMENTS_DATASOURCE_URL` |

Step 3 is the one people get wrong: `LOCK_CLIENT_SAFETY_MARGIN` binds to nothing, the property keeps
its default, and the run looks fine while the client's deadline is 30% wider than intended - a silent
correctness regression from a typo. Passwords arrive as `*_PASSWORD` env vars projected from Secret
Manager (`dlock-lock-db-password`, `dlock-pay-db-password`); no credential is ever a config key in a
file (NFR-12).

## 5.8 Deliberately not configurable {#ct5-fixed}

| Not a switch | Why it is hard-coded |
|---|---|
| Fail-closed on acquire failure (FR-27) | A `lock.fail-open` flag would eventually be set to true during an incident by someone trying to clear a backlog, and that is precisely the duplicate-payment path. Making it inexpressible is the control. |
| Automatic retry of a rail submission (FR-20) | A retry after a `TIMEOUT` is a second submission to a non-idempotent rail. Retry is a human decision informed by `rail_submission`, never a tunable. |
| Whether `RAIL_AMBIGUOUS` blocks submission (FR-23) | An "allow ambiguous" escape hatch converts an unresolved state into a duplicate payment. |
| Token source per backend | pg uses `fencing_token_seq`, etcd uses `ModRevision`. Choosing otherwise breaks per-key monotonicity (FR-02). |
| Whether release is compare-and-delete (FR-05) | An unconditional release lets a stale process free the new holder's lock. |
| Payout state machine transitions (FR-22) | A configurable FSM is an unaudited FSM. |
| Ledger being double-entry (FR-25) | INV-01 (`balance = sum(ledger)`) has no meaning if single-sided posting is an option. |
| Metric tag sets (NFR-09) | Keys, ids and tokens must not reach metrics; a configurable tag set is how cardinality explodes at 03:00. |

**Fewer switches is the design choice, not an omission.** Every flag is a state the system can be in,
and the test matrix is the product of them; twelve booleans is 4,096 configurations, of which we would
test three. Worse, a flag is a *pre-authorised bad decision* - it lets a tired operator disable a
safety property at the exact moment judgment is poorest, with no code review. So the project ships
**exactly two** switches, both defaulting to safe, both alarmed while set, both existing only to make
the fencing lesson visible ([5.2](#ct5-killswitches)). Everything else that could be wrong is instead
a thing that cannot be expressed.
