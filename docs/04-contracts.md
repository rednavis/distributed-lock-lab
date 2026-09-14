# 04 — The contract set (index) {#contracts}

## 4.1 What this file is {#c-purpose}

This is the **index** to the authoritative contract set under `docs/contracts/`. The contracts pin
every name a downstream implementation session is allowed to use: tables and columns, SQL statements,
Java signatures, HTTP paths and error codes, headers, metrics, log events, config keys, and cloud
resource names. Nothing here is new — this file only routes.

**Any change to a contract is a BREAKING change.** A renamed column, a widened exception, a new error
code, a changed retry-safety verdict: each invalidates the task specs that were written against it.
The procedure is: amend the contract → add a §4.5 row → revisit *every* dependent task spec → then
implement. *Why:* the whole point of freezing names before code is that eight modules and two lock
backends compile against the same vocabulary. *Failure mode:* a silent contract edit leaves half the
tasks written against the old name, and the mismatch surfaces as a green build with a broken fence.

## 4.2 Routing table {#c-routing}

| ID | Path | Pins | Anchors defined |
|---|---|---|---|
| **C1** | `docs/contracts/C1-database-schemas.md` | `lockdb` and `paydb` DDL, the acquire/renew/release statements, the fenced `UPDATE`, `fencing_token_seq`, Flyway naming | `#ct1-scope` `#ct1-lockdb` `#ct1-acquire` `#ct1-renew` `#ct1-paydb` `#ct1-fenced` `#ct1-seq` `#ct1-migrations` |
| **C2** | `docs/contracts/C2-java-api.md` | `lock-api` zero-dependency rule, `LockService`, `LockHandle`/`LockInfo`/`LockOutcome`, exception hierarchy, `LockStore`/`SessionRegistry` SPI, client SDK, token propagation, threading/nullability | `#ct2-zero-dep` `#ct2-lockservice` `#ct2-records` `#ct2-exceptions` `#ct2-spi` `#ct2-sdk` `#ct2-propagation` `#ct2-threading` |
| **C3** | `docs/contracts/C3-http-surfaces.md` | All four HTTP surfaces, error-code catalogue with retry-safety, the three custom headers, the ambiguous-outcome contract, per-hop timeouts and retry budgets | `#ct3-conventions` `#ct3-errors` `#ct3-lock` `#ct3-pay` `#ct3-railproxy` `#ct3-railstub` `#ct3-ambiguity` `#ct3-timeouts` |
| **C4** | `docs/contracts/C4-observability.md` | Metric names/types/tags, the cardinality budget, the two must-be-zero counters, structured log event schema and required fields, log-label promotion, log-based metrics, OpenTelemetry spans and attributes, the `/actuator/prometheus` scrape contract, health/readiness | `#ct4-scope` `#ct4-metrics` `#ct4-cardinality` `#ct4-zero` `#ct4-logs` `#ct4-promotion` `#ct4-lbm` `#ct4-traces` `#ct4-scrape` `#ct4-health` `#ct4-nonspec` |
| **C5** | `docs/contracts/C5-config-build-and-naming.md` | Config keys and defaults, the two kill switches, version catalog, module inventory, repo layout, naming conventions, environment variables, the deliberately-fixed list | `#ct5-config` `#ct5-killswitches` `#ct5-catalog` `#ct5-modules` `#ct5-layout` `#ct5-naming` `#ct5-env` `#ct5-fixed` |

Reading order for a fresh implementation session: **C5 → C1 → C2 → C3 → C4**. C5 tells you which modules
exist and what they are called; C1 gives the state they operate on; C2 the in-process API; C3 the wire;
C4 what every module must emit while doing it.

## 4.3 Where is X pinned? {#c-lookup}

| Hunting for | Go to | Note |
|---|---|---|
| `lock_entry`, `lock_session`, `lock_revocation` columns | C1 `#ct1-lockdb` | `lock_entry_expiry_idx`, `lock_entry_session_idx` pinned here too |
| `account`, `payout`, `ledger_entry`, `rail_submission`, `rail_high_water` columns | C1 `#ct1-paydb` | includes `payout.state` and `direction`/`outcome` enum values |
| The acquire statement (insert-or-steal-if-expired) | C1 `#ct1-acquire` | renew/release in `#ct1-renew` |
| The fenced `UPDATE … WHERE fence < :token` | C1 `#ct1-fenced` | fence point (a); rail high-water fence point (c) in C3 `#ct3-railproxy` |
| Why one global sequence, not a per-row version | C1 `#ct1-seq` | etcd `ModRevision` equivalence argued here |
| Flyway migration filenames | C1 `#ct1-migrations` | two separate migration trees, one per database |
| Java type signatures (`LockService`, `LockHandle`, `LockInfo`, `LockOutcome`) | C2 `#ct2-lockservice`, `#ct2-records` | signatures only; no bodies anywhere in the contract set |
| Exception semantics — `LockLostException`, `FencedOutException`, `ContentionException`, `NotLeaderException` | C2 `#ct2-exceptions` | which are retryable, which are terminal |
| `LockStore` / `SessionRegistry` SPI (the pg vs etcd seam) | C2 `#ct2-spi` | both backends are first-class; the SPI is what keeps them so |
| How the token reaches a callee | C2 `#ct2-propagation` | explicit parameter or header — **never** a thread-local |
| Thread-safety and nullability rules | C2 `#ct2-threading` | |
| HTTP paths (lock-server, payment-resource, rail-proxy, rail-stub) | C3 `#ct3-lock`, `#ct3-pay`, `#ct3-railproxy`, `#ct3-railstub` | |
| Error codes and whether a retry is safe | C3 `#ct3-errors` | the single source for retry-safety verdicts |
| `X-Fencing-Token`, `X-Owner-Id`, `X-Idempotency-Key` | C3 `#ct3-conventions` | which surfaces require which |
| `RAIL_AMBIGUOUS` handling / non-idempotent rail | C3 `#ct3-ambiguity` | why a timeout may never be blindly retried |
| Per-hop timeouts, retry budgets, lease-vs-timeout arithmetic | C3 `#ct3-timeouts` | interacts with `lock.client.safety-margin` |
| Metric names, types and tags (`lock.acquire`, `lock.fenced.out`, `payout.execute`, `rail.submission`, …) | C4 `#ct4-metrics` | tag values are closed sets; `#ct4-cardinality` is the budget that forbids per-payout tags |
| The counters whose healthy value is exactly zero | C4 `#ct4-zero` | `rail.duplicate.attempted` and `lock.fenced.out` with `payment.fencing.enabled=true` |
| Log event names (`lock_granted`, `fenced_out`, `rail_ambiguous`, …) and required fields | C4 `#ct4-logs` | field promotion to labels in `#ct4-promotion`; log-based metrics in `#ct4-lbm` |
| Trace span names and required attributes | C4 `#ct4-traces` | `lock.token` on every span is the load-bearing attribute |
| The scrape contract — `/actuator/prometheus`, port name `http-metrics`, `PodMonitoring` | C4 `#ct4-scrape` | the named-port trap fails silently; verification order given there |
| Health and readiness endpoints | C4 `#ct4-health` | what C4 deliberately leaves to `docs/06-observability-and-slo.md`: `#ct4-nonspec` |
| Config keys and defaults (`lock.backend`, `lock.default.ttl`, `lock.session.ttl`, `lock.client.safety-margin`, `rail.stub.*`, both datasource URLs) | C5 `#ct5-config` | |
| The kill switches `payment.fencing.enabled`, `rail.proxy.fencing.enabled` | C5 `#ct5-killswitches` | they exist to *demonstrate* corruption, not to be shipped off |
| Module list and Gradle project names | C5 `#ct5-modules` | repo layout in `#ct5-layout` |
| Dependency versions (Java 25, Spring Boot 4.1, PostgreSQL 16, etcd 3.6, Terraform 1.15) | C5 `#ct5-catalog` | version catalog is the only place versions appear |
| GCP and Kubernetes names (`dlock-lab`, `europe-central2`, `dlock-gke`, `dlock-pg-lock`, `dlock-pg-pay`, service accounts, secrets, `dlock-etcd`) | C5 `#ct5-naming`, `#ct5-env` | |
| What is deliberately hard-coded | C5 `#ct5-fixed` | if it is on that list, adding a config key is a contract change |

## 4.4 Precedence {#c-precedence}

**If a task spec and a contract disagree, the contract wins and the task spec is wrong.**

| Situation | Correct action |
|---|---|
| Task spec names a column/path/metric the contract does not | **Stop.** Report the mismatch, quoting both. Do not implement either version. |
| Task spec omits a field the contract requires | Implement the contract; note the gap in the report. |
| Contract is silent on something the task needs | **Stop.** Silence is not permission to invent a name — request a contract amendment (§4.5). |
| Two contracts disagree with each other | **Stop.** Report; do not pick a winner. |

*Why:* an implementer who "fixes" the discrepancy locally produces a module that compiles alone and
fails at every integration point. *Failure mode:* a plausible synonym — `fencing_token` in `paydb`
instead of the pinned `fence` — costs a schema migration and every query written against it.

Order within the set: contracts are peers. No contract overrides another; conflicts escalate.

## 4.5 Contract change log {#c-changelog}

| Date | Contracts | Change | Task specs to revisit |
|---|---|---|---|
| 2026-08-21 | C1, C2, C3, C5 | **Initial contract set.** Schemas (two-database rule, acquire/renew/release, fenced write, one global sequence), Java API/SPI and exception hierarchy, four HTTP surfaces with error catalogue and ambiguity handling, configuration/build/naming inventory. | All — this is the baseline. |
| 2026-08-21 | C4 | **C4 (observability) added — the initial set is now complete (C1–C5).** Pins the metric catalogue and its tag value sets, the cardinality budget, the two must-be-zero counters, the structured log event schema and promoted fields, log-based metrics, OpenTelemetry span names/attributes, the `/actuator/prometheus` + `PodMonitoring` scrape contract (container port name `http-metrics`), and health/readiness. Supersedes the earlier instruction to treat the project brief's identifier list as authoritative. | Any task spec that emits a metric, log event or span — telemetry names are now contract, not free choice. |
| 2026-08-21 | C1, C2, C3, C4, C5 | **Cross-contract reconciliation — five identifier conflicts closed.** (a) `LockOutcome` is now **only** the `forceRevoke` result record (C2 §2.3); C1 §1.3's acquire column is renamed "Acquire result" (`GRANTED`/`CONTENDED`) and C3 L4's 200 body is renamed **`LockGrant`** — acquire returns `Optional<LockHandle>` in Java. (b) The release/renew predicate is pinned to **`lock_key` + `fencing_token` only**: `owner_id` dropped from C1's release `DELETE` and `session_id` + `owner_id` from C1's renew `UPDATE`; C2 SPI narrowed to `extend(String key, long fencingToken, Duration ttl)` and `deleteIfOwner(String key, long fencingToken)`; C3 L6 now reads `(lock_key, fencing_token)`. Justification: the token comes from one global sequence (etcd: the cluster-wide revision), so it is unique per grant and owner/session are redundant for safety — this replaced a three-way conflict in which the pinned SQL bound a `:owner_id` the pinned signature could not supply. (c) C3's `FENCED_OUT` `resourceType` enum `account`/`ledger`/**`payout`** → `account`/`ledger`/**`rail`**, identical to the `resource` tag on `lock.fenced.out`, with **C4 §4.2 named as the source of truth** for that value set. (d) Revoke response field `newFloorToken` (C3 L8) → **`newTokenFloor`** (C2's record spelling). (e) SDK held-check `isStillHeld()` (C5 §5.4) → **`checkStillHeld()`** (C2 §2.6 wins; the call can raise `LockLostException`, so an `is`-prefixed boolean name is wrong). Also: C4 §4.11 said log-router Terraform lives in `infra/` → **`deploy/terraform/`** (C5 §5.5; no `infra/` tree exists). Verified already correct, unchanged: `RAIL_AMBIGUOUS` as the only ambiguous-outcome value (no `UNKNOWN`), no `PgLockStore` reference (the class is `PostgresLockStore`), Terraform state bucket `gs://dlock-tfstate`, and the three custom health indicators `lockBackend`/`payDb`/`railStub` with the fail-closed readiness rule. | Any task spec touching acquire/release/renew signatures or SQL, the revoke API, the `FENCED_OUT` body, the client SDK held-check, or Terraform paths — the old spellings are now wrong, not merely alternative. |
| 2026-08-21 | C2, C5 | **`checkStillHeld` semantics converged — the rename above fixed the name but left the return type split.** C2 §2.6 pinned `boolean checkStillHeld(LockHandle handle)` while the task specs and ADR-006 were written against a call that *throws*. Resolved as an architect decision in favour of the throw: C2 §2.6 now pins **`void checkStillHeld(LockHandle handle) throws LockLostException`**, C2 §2.4's `LockLostException` row records that the method throws directly rather than reporting a verdict, and C5 §5.4's module note quotes the signature. Rationale: the design intent is that the application aborts loudly the moment the local deadline passes, and a boolean invites `if (checkStillHeld(h)) { … }` with no `else` — a silently skipped payout instead of a loud abort. A convenience boolean *alongside* the throwing method is the same defect under a second name and is likewise forbidden. Prose that read "returns `true`" / "is false" is restated as "returns normally" / "throws" in ADR-006 D3 and 03-technical-architecture §§3.4, 3.5. | T-040 (SDK session: contracts row, §4 deadline arithmetic, AC-4, hazards) and any later spec that gates a side effect on the held check — a boolean return is now wrong, not merely alternative. |
