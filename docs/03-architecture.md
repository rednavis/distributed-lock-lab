# 03 — Technical architecture {#arch}

Scope: the shape of the system, who owns what, how the fencing token travels, and what the design
refuses to do. Every identifier here is a citation into [04 contracts](04-contracts.md); requirements
are cited as `FR-nn` / `NFR-nn` / `INV-nn` ([01](01-requirements.md#br-fr)); every number is an
**ASSUMPTION** from [01 §1.8](01-requirements.md#br-assumptions), never production data. The
scenario is a fictional mid-size payment service provider.

## 3.1 Architecture at a glance {#arch-glance}

```
  +--- harness: scenarios | chaos (pause/kill/partition) | INV-01..08 checks | bench ---+
      | drives                                                              asserts |
      v                                                                             v
  +--------------+  acquire/renew/release L1-L8   +--------------------------------+
  | payout-      |------------------------+       |          lock-server           |
  | executor     |  heartbeat             |       | web -> core (SessionRegistry,  |
  | (the worker) |<-- lock-client (SDK) --+-----> | lease clock, token minting)    |
  |  deadline, checkStillHeld, onLockLost |       | store.pg    |    store.etcd    |
  +---+------+----------------------------+       +-----+--------------+-----------+
      |      | (2) submit  X-Fencing-Token              |              |
      |      v                                   +------v-----+  +-----v----------+
      |  +----------------+  POST /submit        | dlock-pg-  |  | dlock-etcd     |
      |  |   rail-proxy   |  (no token, no key)  | lock       |  | 3-replica STS  |
      |  | fence point (c)|-------------------->  (lockdb,    |  | ModRevision =  |
      |  | rail_high_water|<--ACK/DECLINE/t-out  | REGIONAL)  |  | token          |
      |  +--------+-------+     +-----------+    +------------+  +----------------+
      |           |             | rail-stub |
      |           | intent      | non-      |    lock-api: zero-dependency types,
      | (1) claim | BEFORE      | idempotent|      shared by every box above
      |  (3) post | fwd FR-19   +-----------+    deploy: Terraform + K8s + compose
      v           v                             build-logic: toolchain, Spotless
  +----------------------------------------------------+   +--------------+
  |  payment-resource | payout FSM | ledger | balance  |-->| dlock-pg-pay |
  |  fence point (a): UPDATE .. WHERE fence < :token   |   | (paydb,ZONAL)|
  +----------------------------------------------------+   +--------------+
```

Module inventory, Gradle names and dependency edges: [C5 §5.4](contracts/C5-config-build-and-naming.md#ct5-modules).
HTTP surfaces: [C3 §3.3](contracts/C3-http-surfaces.md#ct3-lock) onward.

## 3.2 Component responsibilities {#arch-components}

| Component | Owns | Does NOT own | Failure behaviour | Scaling posture |
|---|---|---|---|---|
| `lock-api` | Types, exceptions, the SPI shape ([C2 §2.1](contracts/C2-java-api.md#ct2-zero-dep)) | Any behaviour, any transport, any framework | Cannot fail at runtime — no code paths | N/A (compile-time artifact) |
| `lock-server` | Lease authority; the **only** minter of tokens; grant state; force-revoke (FR-08) | Any knowledge of payouts, money, or what a key protects | Unavailable ⇒ callers get no grant and **fail closed** (FR-27); it never grants on doubt | Stateless web tier, horizontally scalable; all state in the backend. Single shard ([§3.8](#arch-scale)) |
| `lock-client` (SDK) | Conservative local deadline, heartbeat loop, `checkStillHeld`, `onLockLost` ([C2 §2.6](contracts/C2-java-api.md#ct2-sdk)) | Safety. It is a liveness device ([§3.5](#arch-sdk)) | Heartbeat failure ⇒ deadline lapses ⇒ lock-lost fires locally before the server expires the lease | In-process library; one session per pod |
| `payout-executor` | Composition: claim → lock → re-read (FR-14) → submit → post | Fencing logic (it only *presents* the token) | Crash mid-payout leaves a claimed payout that expiry + recovery-by-state resolves (FR-24), never by elapsed time | 12 pods at peak (A-05), autoscaled, evictable |
| `payment-resource` | `paydb`: payout FSM, ledger, balance; **fence point (a)** ([C1 §1.6](contracts/C1-database-schemas.md#ct1-fenced)) | Lock grants; `rail_high_water` — it must never touch it | Rejects a stale token with 409 `FENCED_OUT` and zero rows affected (INV-05) | Stateless tier over one zonal Postgres; write rate trivially small |
| `rail-proxy` | Submission ledger; **fence point (c)**, the persisted `rail_high_water` high-water mark; records intent before forwarding (FR-19) | The rail's semantics; retrying a submit — **never** (FR-20) | Ambiguity is recorded as `outcome='TIMEOUT'` and returned as `RAIL_AMBIGUOUS` ([C3 §3.7](contracts/C3-http-surfaces.md#ct3-ambiguity)) | Stateless tier; the unique index on `payout_id` is what actually enforces INV-02 |
| `rail-stub` | Deliberate hazard: non-idempotent, injectable latency/decline/timeout/duplicate-ack (FR-21) | Nothing. Zero dependencies | Behaves badly on purpose | Single replica; it is the environment, not the system |
| `harness` | Scenarios, chaos injection, invariant checkers, benchmark driver (NFR-07) | Production paths — it is never on the payout path | A failing check fails the build; that is the point | Runs as a job, not a service |
| `deploy` + `build-logic` | Terraform root + envs, K8s manifests, local `docker compose` (NFR-15); toolchain, Spotless + google-java-format, Lombok limited to `@RequiredArgsConstructor`/`@Slf4j` | Product code | A bad apply is rolled back progressively ([08](08-operations.md)); build failures are build-time only | N/A |

## 3.3 Module boundaries as a design statement {#arch-boundaries}

**`lock-api` has zero third-party dependencies (NFR-16, [C2 §2.1](contracts/C2-java-api.md#ct2-zero-dep)).**
Six modules depend on it. If it carried Spring, Jackson or a metrics facade, every consumer would
inherit that version choice and the contract — "a grant is a lease plus a strictly increasing token"
— would be entangled with a framework's lifecycle. *Failure mode:* the SDK cannot be embedded in an
application pinning a different Spring Boot line, so the first real adopter forks the token type and
tokens stop being comparable.

**Both enforcement points live in processes the lock service does not control** — the load-bearing
decision of the whole project. If the `fence < :token` check ran inside `lock-server`, the service would
be validating its own grants: a stale holder the server already forgot would be rejected by the
component that forgot it. The class of bug being demonstrated — *the server was right, the holder was
wrong, and the resource believed the holder* — cannot occur in that topology, so the negative control
([UC-03](01-requirements.md#br-uc03)) would be unreachable and the fencing kill switches
([C5 §5.2](contracts/C5-config-build-and-naming.md#ct5-killswitches)) would have nothing to switch off.

**The two points are independent of each other, too.** `rail_high_water` lives in `paydb` but is
written **only** by `rail-proxy` ([C1 §1.5](contracts/C1-database-schemas.md#ct1-paydb)). Letting
`payment-resource` advance it would collapse two guards into one: the claim would advance the mark,
and a later bug that skipped the resource entirely would find the mark already ahead of it.

**Token propagation is an explicit parameter or an HTTP header, never a thread-local**
([C2 §2.7](contracts/C2-java-api.md#ct2-propagation)). A required argument makes INV-07 a compile
error rather than a code review finding.

## 3.4 Four flows, with the token at every hop {#arch-flows}

**Flow A — happy-path payout (UC-01).**

1. `payout-executor` polls `GET /v1/payouts?state=PENDING`; picks payout `P` on account `A`. Its SDK
   session is already open (`POST /v1/sessions`), heartbeating every ~5 s (A-04).
2. `POST /v1/locks/payout:{A}/acquire` → 200 with token **T**, minted by the backend: a never-reset
   sequence (pg) or the `ModRevision` of the winning compare-and-swap (etcd), captured **at grant
   time** ([C1 §1.7](contracts/C1-database-schemas.md#ct1-seq)).
3. Executor **re-reads** `P` (FR-14): still `PENDING`, funds sufficient (A-09).
4. `checkStillHeld` returns normally (it throws rather than answering `false` — [C2 §2.6](contracts/C2-java-api.md#ct2-sdk)). `POST /v1/payouts/{P}/claim` with `X-Fencing-Token: T` → resource runs
   `UPDATE ... WHERE fence < T`, advances `account.fence` to T, moves `P` to `CLAIMED`. **1 row.**
5. `checkStillHeld` returns normally. `POST /v1/rail/submissions` with `X-Fencing-Token: T` and
   `X-Idempotency-Key` = P's stable key. Proxy checks `T > rail_high_water.highest_token` for `A`,
   advances it in the same transaction, inserts `rail_submission` (intent, FR-19), then forwards
   `POST /submit` to `rail-stub` — **which sees no token and no key**. It `ACK`s with a rail
   reference; the proxy records `ACKED` and returns it.
6. `POST /v1/payouts/{P}/post` with `X-Fencing-Token: T` → both ledger legs and the balance update in
   one transaction, under the predicate pinned in
   [C1 §1.6](contracts/C1-database-schemas.md#ct1-fenced); `P` → `POSTED`. Then
   `DELETE /v1/locks/payout:{A}` with T; the session stays open for the next payout.

**Flow B — contended acquire with bounded wait (UC-02).**

1. `W1` and `W2` both acquire on `payout:{A}`. The backend serialises: `W1` gets T1; `W2` gets 409
   `HELD_BY_OTHER` — an outcome, not an error ([C3 §3.2](contracts/C3-http-surfaces.md#ct3-errors)).
2. `W2` retries with jittered exponential backoff, 50 ms → 1 s, ≤ 5 attempts ([C3 §3.8](contracts/C3-http-surfaces.md#ct3-timeouts), FR-28).
3. Budget exhausted → `CONTENTION_EXCEEDED`; `W2` **skips the payout and re-queues it**, executing
   nothing (FR-27). Failing open is not configurable.
4. If `W1` releases first, `W2` acquires with **T2 > T1**, re-reads `P`, finds it `POSTED` and does
   nothing — here the re-read, not the lock, prevents the double payout.

**Flow C — the paused worker (UC-03), the negative control.**

1. `W1` holds T1 inside the critical section and is stopped (`SIGSTOP`, a 20 s GC pause, a throttled
   Autopilot pod) for longer than the lease. The server expires the grant.
2. `W2` acquires and receives **T2 > T1**; it claims (`account.fence` → T2) and submits
   (`rail_high_water` → T2).
3. `W1` resumes believing it still holds the lock. Its SDK deadline has passed, so `onLockLost` has
   fired and `checkStillHeld` throws `LockLostException` — **liveness** stops most runs here.
4. With that check bypassed (the experiment), `W1` presents T1 anyway: the resource runs
   `UPDATE ... WHERE fence < T1` → **0 rows** → 409 `FENCED_OUT` plus `lock.fenced.out` and the
   `fenced_out` event ([C4 §4.2](contracts/C4-observability.md#ct4-metrics)); the proxy rejects it
   because T1 is not highest for `A`, then the `payout_id` unique index rejects it again (FR-18).
5. With `payment.fencing.enabled=false`, step 4 succeeds and the ledger corrupts — the measured
   demonstration, run only in the harness.

**Flow D — ambiguous rail outcome (UC-05).**

1. Executor submits under T. Proxy has already written intent (FR-19).
2. `rail-stub` times out (A-07: ~0.5% injected). The proxy read timeout (8 s) is **inside** the
   executor's (rail timeout + 2 s), so the proxy observes the ambiguity before its caller gives up.
3. Proxy records `outcome = 'TIMEOUT'` and returns `RAIL_AMBIGUOUS`. **It never retries.**
4. Executor moves `P` to the ambiguous state and releases the lock. No ledger rows are written.
5. `P` is now unsubmittable by any worker under any token (FR-23) — the state, not the lock, is the
   guard. Reconciliation resolves it to `POSTED` or `FAILED` via the rail's read-only lookup by client
   reference (A-08), SLA 4 h before escalation (A-10). Retrying step 1 instead would be a second
   non-idempotent submit: a duplicate payment, INV-02 lost.

## 3.5 The SDK, and the safety/liveness split {#arch-sdk}

| Mechanism | Class | What it buys | What it cannot do |
|---|---|---|---|
| Conservative local deadline from `System.nanoTime()`, computed from the **send** time of the acquire and reduced by `lock.client.safety-margin` (FR-11) | **Liveness** | The client gives up before the server does, so wasted work stops early | Nothing about safety. A paused process' clock arithmetic is also paused |
| `onLockLost` callback (FR-12) | **Liveness** | Application code can abort in flight instead of discovering the loss from a 409 | Cannot interrupt a syscall already in flight |
| `checkStillHeld` before every side effect (FR-13) | **Liveness** | Narrows the window between "I believe I hold it" and the side effect | **Cannot close it.** The lease can lapse in the nanoseconds after the call returns normally ([C2 §2.6](contracts/C2-java-api.md#ct2-sdk)) |
| The fencing token at points (a) and (c) | **Safety** | A stale holder's write is rejected by the resource itself | Nothing about liveness — it rejects, it does not wait |

**Opinion.** A lock service whose SDK does not tell the application it lost the lock will cause an
incident even though the fencing token keeps the data correct: the application sits inside a critical
section it no longer owns, every write rejected, until some timeout unwinds it — and the operator sees
a stalled worker and a rising `payout.backlog.age.seconds` with no explanation. Safety without liveness
is a correct system that pages you at 3 a.m. Confusing the two is the classic error here.

## 3.6 Failure catalogue {#arch-failures}

| Failure | What the user sees | Mechanism that handles it | Deliberately NOT handled |
|---|---|---|---|
| Worker crash mid-payout (UC-04) | Payout completes later, same amount | Session death releases grants (FR-04); recovery decided by state + submission record (FR-24) | Automatic resubmit of an ambiguous payout |
| Worker pause past the lease (UC-03) | Nothing; the other worker did the work | Fence points (a) and (c); SDK lock-lost as a liveness aid | Preventing the pause. Autopilot throttling is assumed |
| Clock skew between workers | Nothing | Server-side expiry only; the client uses a **monotonic** clock, never wall time | Wall-clock synchronisation as a correctness input |
| Lock service unavailable (UC-08) | Payouts queue; backlog age alerts | Fail closed (FR-27); bounded jittered retry (FR-28) | Fail-open mode. It is not configurable, on purpose |
| etcd leader election | ≤ 2 s of shard unavailability (NFR-02) | Budgeted against the error budget; PDB + `topologySpreadConstraints` + safe-to-evict | Eliminating elections on Autopilot — impossible, so it is measured instead |
| lockdb primary failover | Acquire errors for the failover window | Regional Cloud SQL synchronous standby; no committed grant or fence lost (NFR-05) | Zero-downtime failover |
| lockdb restored from backup | Nothing visible — **the dangerous one** | Operational procedure forbids rewinding the sequence (INV-04); alert on token regression | Automatic detection *before* the first stale write. This is why etcd is recommended |
| Rail timeout / ambiguity, or a duplicate ack | Payout held ambiguous up to 4 h (A-10) | Intent before forwarding; `TIMEOUT` outcome and payout state `RAIL_AMBIGUOUS`; reconciliation via rail lookup; `rail.duplicate.attempted` must be exactly 0 ([C4 §4.4](contracts/C4-observability.md#ct4-zero)) | Automatic retry, ever; recovering money already sent twice |
| Network partition, worker ↔ lock service | Payout delayed | Heartbeat failure → local deadline lapses → lock-lost | Preserving the grant across the partition |
| Operator force-revoke (UC-06) | Stuck lock cleared | Revoke advances the token (FR-08), so the revoked holder is fenced; operator + reason required, audited | Revoke as routine automation. It is break-glass |
| Both fencing kill switches off | Corrupted ledger — on purpose | Nothing. That is the experiment | Shipping with them off |

## 3.7 Consistency and availability posture {#arch-cap}

**This system fails closed and prefers CP.** A payout is a non-reversible side effect against a
non-idempotent rail (A-07), and the cost matrix is asymmetric by orders of magnitude: a delayed payout
is a support ticket measured in minutes, a duplicated payout is money gone and a trust event. So when
the backend is unreachable or its answer is uncertain, the correct behaviour is **no grant, no side
effect** — and FR-27 makes that non-configurable, so no future incident-driven "just let it through"
flag can exist. The consequence is honest: lock unavailability converts directly into payout latency,
which is why `payout.backlog.age.seconds` is the **symptom SLI that pages** while acquire error rate
is a cause that tickets ([C4 §4.2](contracts/C4-observability.md#ct4-metrics)). etcd gives
linearizable compare-and-swap; the PostgreSQL backend gives single-primary serialisation — equivalent
while exactly one primary exists, and dependent on **procedure** at restore time. That is why etcd is
the recommendation rather than a preference.

## 3.8 Scale posture: project versus V1 {#arch-scale}

Arithmetic on the assumed workload (A-01…A-05 — all invented):

| Quantity | Project derivation | Result |
|---|---|---|
| Payout rate | 2,000/h peak ÷ 3600 | **0.56/s** |
| Lock ops per payout | 1 acquire + 1 release + 0–2 renews (section 200–800 ms vs 15 s TTL) → 0.56 × 4 | 2–4 → **≈ 2.2 ops/s** |
| Heartbeats + total | 12 pods × 1 session ÷ 5 s = 2.4/s, plus the above | **< 5 ops/s** |
| paydb writes per payout | claim + 2 ledger legs + balance + state = 5 | **≈ 3 writes/s** |
| Live lock keys | 5,000 × ~200 B of value + metadata | **≈ 1 MB** of etcd state |

A single etcd Raft group serves thousands of writes/s and its default backend quota is gigabytes. The
project is **three orders of magnitude** below one shard's capacity, and a V1 at 100× the project (≈ 220 lock
ops/s) still is. That is the honest reason **key-space sharding and leader balancing are out of
scope** ([00](00-charter.md)) — arithmetic, not difficulty. The trigger to revisit is
[DR-03](01-requirements.md#br-deferred): write load approaching one group's measured
ceiling, state outgrowing the quota, or one election's blast radius becoming unacceptable. Sharding a
5 ops/s workload would cost a routing layer, a rebalancing story, and cross-shard token
comparability — a *correctness* hazard, since tokens are monotonic only within a group.

## 3.9 Security posture {#arch-security}

| Concern | Project | What V1 must add |
|---|---|---|
| Service-to-service authN | **None. Network isolation only (A-13.)** This single assumption disqualifies the project from production | mTLS or signed service tokens on every hop; the token header is not a credential |
| AuthZ | None | Per-key authorisation: who may lock what, who may revoke |
| Workload → GCP identity | **Workload Identity Federation**: each workload runs as its own Kubernetes SA bound to a least-privilege Google SA (NFR-13). No JSON key ever exists | Same, plus per-environment separation |
| Credentials and data | Secrets in Secret Manager, mounted at runtime, **never** in source or committed Terraform state; synthetic fixtures only, no personal or payment data (NFR-12) | Short-lived IAM database auth instead of passwords; real PII handling and retention |
| Audit trail | Every transition, submission and break-glass action attributable to an actor **and a token** (FR-30); force-revoke requires operator identity and a ≥8-char reason (FR-08) | Tamper-evident storage, separation of duties on revoke |
| Telemetry leakage | No metric carries a key, payout id, account id or token (NFR-09); those live in structured logs only ([C4 §4.3](contracts/C4-observability.md#ct4-cardinality)) | Log redaction review |
| Skipped deliberately | Rate limits/quotas, multi-tenancy, key encryption at rest beyond GCP defaults, supply-chain attestation | All of the above |

## 3.10 Deployment topology {#arch-topology}

| Aspect | Local (`docker compose`, NFR-15) | GCP dev |
|---|---|---|
| Orchestration | Compose, one container per module | **GKE Autopilot**, project `dlock-lab`, region `europe-central2` |
| Lock backend | Postgres container, or single-node etcd | `dlock-pg-lock` **REGIONAL** Cloud SQL (synchronous standby, to demonstrate failover) or `dlock-etcd` 3-replica StatefulSet |
| Protected resource | Postgres container | `dlock-pg-pay` **ZONAL** Cloud SQL (cost) |
| Why two instances | Same reason as GCP: one instance would take out lock and resource together and destroy the failover experiment | as left |
| Rail | `rail-stub` container | `rail-stub` Deployment, 1 replica |
| Observability | Actuator + Prometheus container | `/actuator/prometheus` on named port `http-metrics`, scraped by `PodMonitoring` ([C4 §4.9](contracts/C4-observability.md#ct4-scrape)); OpenTelemetry traces |
| Autopilot consequence | none | Bin-packing and eviction cause **more leader elections** than Standard would. Mitigated with a PodDisruptionBudget, `topologySpreadConstraints` across zones and the safe-to-evict annotation, then **measured** against NFR-02 |
| Fencing experiment | Runnable locally from **T-042** | Same scenarios, real network |
| Cost control | free | Budget alert configured **before** the first apply (NFR-14) |

Operator entry points — full sequences in [08 operations](08-operations.md):
`./gradlew :harness:runScenario --args=...` · `kubectl -n dlock rollout status sts/dlock-etcd` ·
`gcloud sql instances describe dlock-pg-lock --project dlock-lab` · `psql -c "table rail_high_water"`.

## 3.11 Technology choices {#arch-tech}

| Decision | Chosen | Alternatives rejected | Why | When the choice flips |
|---|---|---|---|---|
| Lock substrate (recommended) | **etcd 3.6**, `ModRevision` as the token (FR-10) | ZooKeeper, Consul, Redis/Redlock, custom Raft | Linearizable CAS, and monotonic tokens **by construction** rather than by procedure | If the org already runs ZooKeeper well, use it; Redlock never, for money |
| Lock substrate (taught first) | **PostgreSQL 16 `lockdb`** | — | The state is inspectable with `SELECT`, which makes leases and tokens teachable; and most teams already have Postgres | It stops being sufficient when a restore can rewind the sequence (INV-04) and no procedure enforces otherwise |
| Backend seam | One `LockStore`/`SessionRegistry` SPI ([C2 §2.5](contracts/C2-java-api.md#ct2-spi)) | Two independent services | Keeps both backends first-class and makes the measured comparison apples-to-apples | Never; the comparison is a deliverable |
| Protected resource DB | Separate **`paydb`** instance | One shared instance | Sharing would fail lock and resource together and void the failover experiment | Never in this project; cost-driven merges are a production anti-pattern here |
| Cluster | **GKE Autopilot** | GKE Standard, plain VMs | Less toil, and the eviction pressure makes the election-budget lesson real | Standard, if node placement for the etcd StatefulSet becomes the dominant cost |
| Ledger | Double-entry, immutable rows, balance derived (FR-25, INV-03) | Balance column only | An auditable trail and an assertable invariant | Never |
| Rail interface | Deliberately non-idempotent stub (FR-21) | Idempotency-key rail | With an idempotency key the lock is nearly unnecessary — the hazard must exist for the project to mean anything | If the real rail offers keys, use them **and** keep the fence |
| Metrics | Micrometer → Managed Service for Prometheus | Self-hosted Prometheus | No storage toil; the scrape contract is the only sharp edge | Self-host if PromQL features or retention demand it |
| Build | Gradle 9.5 Kotlin DSL + version catalog, one root build | Maven, multi-repo | One vocabulary across nine modules; the catalog is the only place versions appear ([C5 §5.3](contracts/C5-config-build-and-naming.md#ct5-catalog)) | Never in this repo |
| Tests | Testcontainers + JUnit 5, both backends | Mocked stores | A mocked lock store cannot reproduce a lease expiry race | Never |
