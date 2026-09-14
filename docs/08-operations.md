# 08 — Operations: runbook, game day, on-call {#operations}

Scope: how this service is **run** once it exists. Signal identifiers are contract — [C4 §4.2](contracts/C4-observability.md#ct4-metrics), [C4 §4.5](contracts/C4-observability.md#ct4-logs). Targets, windows and thresholds live in `docs/06-observability-and-slo.md`. This file consumes both and redefines neither.

All figures are **ASSUMPTIONS** for a fictional mid-size payment service provider; none is production data.

## 8.1 Operating principles as commitments {#ops-principles}

An aspiration has no consequence attached; a commitment does. The consequence column is the only part that changes behaviour.

| # | Commitment | Consequence if broken |
|---|---|---|
| 1 | **Page on symptoms, ticket on causes.** A page means a payout is late, wrong or blocked. Elevated lease expiry is a cause and gets a ticket. | A cause-based page that fires with no user impact is deleted, not tuned. |
| 2 | **Every alert is actionable, novel, runbook-linked.** No §8.2 entry ⇒ the alert does not ship. | Reviewer blocks the PR that adds the policy. |
| 3 | **The two must-be-zero counters are never "expected".** `lock.fenced.out` and `rail.duplicate.attempted` ([C4 §4.4](contracts/C4-observability.md#ct4-zero)) are always caller defects. | Normalising a non-zero baseline is a postmortem action item, never a threshold change. |
| 4 | **Fail closed, and never relieve pressure by opening.** Acquire failure blocks the payout (INV-07). The kill switches ([C5 §5.2](contracts/C5-config-build-and-naming.md#ct5-killswitches)) exist to *demonstrate* corruption in the project. | Flipping a fencing kill switch during an incident is itself a Sev-1. |
| 5 | **The error-budget policy has teeth.** Budget exhausted ⇒ feature work stops, reliability work only, until the trailing window recovers. | The decision belongs to the written policy in `06 §6.6`, not to the loudest voice in the incident channel. |
| 6 | **The runbook is executable by a tired stranger.** Concrete commands; never "investigate the issue". | A failed game day (§8.4) whose action item is fixing the runbook. |
| 7 | **Toil is measured, then deleted.** Recurring manual work enters §8.7 with an hours/month estimate the week it is noticed. | Unmeasured toil silently becomes the job. |
| 8 | **No unowned alert.** The rotation is shared with a sibling team (§8.6); an unstaffed page is an unmonitored service. | §8.9 blocks: the service is not done. |

## 8.2 The runbook — one entry per alert {#ops-runbook}

Entry names **must** match the alert-policy names in `06 §6.7` one-for-one, both directions. Name drift here is a broken link from a Cloud Monitoring `documentation` field, discovered at 03:00. Fixed shape: meaning and user impact · first three diagnostics · likely causes in real-frequency order · remediation and rollback · escalation · DO NOT. Assume `kubectl -n dlock` and an `etcdctl` with cluster endpoints exported.

### 8.2.1 `fenced-out` — page {#rb-fenced-out}

- **Means / impact.** A write arrived with a token at or below the stored fence and was rejected — at the paydb row (`UPDATE … WHERE fence < :token`, [C1 §1.6](contracts/C1-database-schemas.md#ct1-fenced)) or at the rail-proxy high-water mark ([C3 §3.5](contracts/C3-http-surfaces.md#ct3-railproxy)). **No integrity impact**: INV-05 held, the write was refused, one payout is delayed and will be re-claimed. The signal is about a *client that believed it still held the lock*.
- **First three.** 1) `kubectl logs deploy/payment-resource | grep fenced_out` → `owner_id`, `lock.key`, presented vs stored token. 2) `increase(lock_lease_expired_total[15m])` — did that owner lose its lease first? 3) Pause evidence for that owner: `kubectl logs <pod> --previous`, GC log, CPU-throttle and OOM events.
- **Causes, by real frequency.** (1) client stop-the-world pause or CPU throttling longer than the lease; (2) hold time exceeding `lock.default.ttl` with too small a `lock.client.safety-margin` ([C5 §5.1](contracts/C5-config-build-and-naming.md#ct5-config)); (3) Autopilot eviction freezing the holder mid-critical-section; (4) partition to lock-server; (5) token regression in the backend — §8.2.9.
- **Remediate / rollback.** Fix the *client*: raise TTL or shorten the hold, correct the safety margin, remove the pause. Re-drive the delayed payout through the normal claim path, never by hand-writing the row. If a lock or client release correlates: `kubectl rollout undo deploy/lock-server` (or the client) and re-check within one scrape interval.
- **Escalate.** Fences continuing *after* the owner restarts, or a presented token lower than one already observed for that key: stop the executors and treat as INV-04 (§8.2.9).
- **DO NOT.** **Do NOT disable the fencing check to stop the alerts** — not `payment.fencing.enabled=false`, not `rail.proxy.fencing.enabled=false`. That is removing the smoke detector because the kitchen is on fire: the alert stops, the stale writes start landing, and the next symptom is a finance break found days later by reconciliation instead of in seconds by a counter. Also: do not "bump the fence" to let the write through, and do not re-submit to the rail manually.

### 8.2.2 `rail-duplicate-attempted` — page {#rb-rail-duplicate}

- **Means / impact.** The proxy blocked a second submission of one payout to the non-idempotent rail (INV-02). No loss *yet* — but two executors believed they owned the same payout, so this is a claim/lease boundary defect, not a rail defect.
- **First three.** 1) `grep rail_duplicate_attempted` → `payout.id`, both `owner_id`s, both tokens. 2) `psql paydb -c "select * from rail_submission where payout_id = …"` — exactly one row must exist, with its outcome. 3) `psql paydb -c "select state, fence from payout p join account a … where p.id = …"` — is the claim history consistent with a single owner?
- **Causes, by frequency.** (1) executor retried after `RAIL_AMBIGUOUS` instead of parking the payout ([C3 §3.7](contracts/C3-http-surfaces.md#ct3-ambiguity)); (2) two executor replicas claimed the same payout after a lease expiry; (3) an operator re-ran a payout manually; (4) INV-06 breach in the backend.
- **Remediate / rollback.** Confirm exactly one submission reached the rail (proxy table plus rail lookup), resolve the payout by its recorded outcome, then fix the retry path. Roll `payout-executor` back to the last release with a clean 24 h of this counter.
- **Escalate.** Immediately to the payments duty manager if the rail lookup shows **two** accepted submissions — realised financial loss and a customer-facing event.
- **DO NOT.** Do not relax the unique constraint on payout id in the proxy, do not delete a `rail_submission` row to "unblock", do not retry an ambiguous submission without a rail lookup first.

### 8.2.3 `payout-success-slo-fast-burn` — page {#rb-payout-burn}

- **Means / impact.** The user-facing SLI (claimed payouts reaching `POSTED`) is burning budget fast. Direct impact: payouts are failing or abandoning now.
- **First three.** 1) `sum by (outcome) (rate(payout_execute_seconds_count[5m]))` — which outcome grows? 2) If `ambiguous`/`failed` dominates: `sum by (outcome) (rate(rail_submission_total[5m]))` — rail or us? 3) If `abandoned` dominates: `rate(lock_acquire_seconds_count{outcome!="granted"}[5m])` — the lock path is refusing; go to §8.2.4.
- **Causes, by frequency.** (1) rail latency or rejections upstream; (2) lock unavailability failing closed; (3) a bad executor release; (4) paydb saturation.
- **Remediate / rollback.** Follow the dominant outcome to its own entry. If a release correlates, `kubectl rollout undo deploy/payout-executor` **first** and diagnose after; the burn rate should turn within 10 min.
- **Escalate.** Budget projected to exhaust inside the window ⇒ invoke the error-budget policy and tell the product owner in the same message, not afterwards.
- **DO NOT.** Do not raise concurrency to "catch up" while the rail is rejecting. Do not widen the retry budget mid-incident ([C3 §3.8](contracts/C3-http-surfaces.md#ct3-timeouts)) — you convert a failure into an amplified one.

### 8.2.4 `lock-acquire-unavailable-burn` — page {#rb-acquire-burn}

- **Means / impact.** Acquires fail or time out beyond the availability SLO. Callers are blocked **by design** (fail closed, INV-07) — correct behaviour, unacceptable duration. All payout execution stalls and the backlog starts to age (§8.2.5 follows).
- **First three.** 1) `etcdctl --write-out=table endpoint status --cluster` and `etcdctl endpoint health --cluster` — quorum and leader. 2) `kubectl get pods -l app=lock-server` plus `/actuator/health/readiness` ([C4 §4.10](contracts/C4-observability.md#ct4-health)). 3) For backend=pg: `gcloud sql instances describe dlock-pg-lock` and connection/lock-wait counts on `lockdb`.
- **Causes, by frequency.** (1) etcd leader election from an Autopilot eviction (budgeted, §8.3); (2) `dlock-pg-lock` failover to its synchronous standby; (3) connection-pool exhaustion in lock-server; (4) a bad lock-server release; (5) genuine quorum loss.
- **Remediate / rollback.** Elections and Cloud SQL failovers self-resolve in seconds — confirm recovery before acting. Pool exhaustion: capture pool metrics, then restart replicas. Quorum loss: member replacement (§8.3). `kubectl rollout undo deploy/lock-server` if a release correlates. Switching `LOCK_BACKEND` is a **planned migration**, never an incident action: the two token spaces are unrelated (INV-04).
- **Escalate.** Quorum unrecoverable, or anyone proposing a snapshot restore — escalate *before* restoring; a restore rewinds revisions and is a correctness event, not a recovery.
- **DO NOT.** Do not make acquire fail open. Do not `etcdctl snapshot restore` to end an outage without the token-advance step (§8.3). Do not delete the etcd PVCs for a "clean cluster".

### 8.2.5 `payout-backlog-aging` — page {#rb-backlog}

- **Means / impact.** `payout_backlog_age_seconds` — the oldest pending payout — exceeds the freshness objective. A real payee is waiting; this is the symptom that survives every internal excuse.
- **First three.** 1) `payout_backlog_age_seconds` trend against `rate(payout_execute_seconds_count[5m])` — stalled or merely slow? 2) `kubectl get deploy payout-executor` — replicas, restarts, evictions. 3) `psql paydb -c "select state, count(*) from payout group by 1"` — piling up in `CLAIMED` (lock or rail stall) or `PENDING` (no executors)?
- **Causes, by frequency.** (1) a lock or rail incident already open; (2) executor replicas evicted and not rescheduled; (3) a poison payout re-claimed in a loop; (4) genuine volume above capacity.
- **Remediate / rollback.** Resolve the upstream entry; park a poison payout explicitly rather than let it block the head of the queue. Roll back only if a release changed claim or ordering behaviour.
- **Escalate.** Age beyond one business cycle ⇒ payments duty manager; this becomes customer communication.
- **DO NOT.** Do not skip the aged payout by mutating state directly. Do not raise concurrency while a poison payout cycles — you multiply lock churn and drain nothing.

### 8.2.6 `lease-expiry-elevated` — ticket {#rb-lease-expiry}

- **Means / impact.** `lock.lease.expired` above the leading-indicator threshold: clients are losing locks, but no fence has fired yet. No direct impact — this is the early warning that precedes §8.2.1.
- **First three.** 1) `increase(lock_lease_expired_total[1h]) by (backend)`. 2) Compare held duration (`lock.held_ms` on the release span, [C4 §4.8](contracts/C4-observability.md#ct4-traces)) against `lock.default.ttl`. 3) `lock_acquire_seconds` p99 — is a slow backend feeding client-side timeouts?
- **Causes, by frequency.** (1) TTL shorter than real hold time; (2) client GC or CPU-throttle pauses; (3) backend latency; (4) a client that stopped heartbeating (`lock.session.lost`).
- **Remediate / rollback.** Right-size TTL and safety margin *together*; shorten the critical section — the external rail call is the long pole. Revert the config change that shortened TTL, if any.
- **Escalate.** Only if it converts into `fenced-out`.
- **DO NOT.** Do not set a very large TTL to make the number go away: you trade a visible expiry for a long unavailability after a real holder death.

### 8.2.7 `etcd-quorum-degraded` / `etcd-elections-over-budget` — page / ticket {#rb-etcd-quorum}

- **Means / impact.** Fewer than three healthy members (page), or elections above the monthly budget (ticket). One election costs **1–2 s** of shard unavailability (ASSUMPTION) — a budgeted expense against the error budget, not an incident. Quorum held: no impact. Quorum lost: all acquires fail closed.
- **First three.** 1) `etcdctl --write-out=table endpoint status --cluster` — leader, raft term, db size. 2) `kubectl get pods -l app=dlock-etcd -o wide` plus `kubectl describe` for the eviction reason and zone spread. 3) `kubectl get pdb dlock-etcd-pdb` — did the PDB actually bound the disruption?
- **Causes, by frequency.** (1) Autopilot bin-pack eviction despite `safe-to-evict: "false"`; (2) PVC/disk latency; (3) node upgrade; (4) starvation from a noisy neighbour.
- **Remediate / rollback.** Let the election complete. For repeat offenders verify the PDB, `topologySpreadConstraints` and the annotation are genuinely applied, then **measure the residual rate against the SLO** instead of arguing about it. Revert any StatefulSet resource or spread change that correlates.
- **Escalate.** Two members down, or a member that will not rejoin ⇒ member replacement (§8.3).
- **DO NOT.** Do not scale the StatefulSet to 2 (an even quorum is worse than three) or to 1 "temporarily".

### 8.2.8 `etcd-db-size-high` — ticket {#rb-etcd-size}

- **Means / impact.** Backend db size is approaching the quota. Past it etcd goes **read-only**: every acquire fails and the alarm must be disarmed by hand. No impact yet; total loss of acquires if ignored.
- **First three.** 1) `etcdctl endpoint status --write-out=table` — db size vs `--quota-backend-bytes`. 2) `etcdctl alarm list`. 3) Last successful compaction from the maintenance job's logs.
- **Causes, by frequency.** (1) the compaction job failing silently; (2) defrag never run, so freed pages are never reclaimed; (3) high write churn from short-lived locks; (4) quota set too low.
- **Remediate / rollback.** The §8.3 compact-then-defrag sequence, one member at a time, then `etcdctl alarm disarm` if `NOSPACE` was raised. Maintenance only — no rollback.
- **Escalate.** Size grows again within a day of a successful defrag ⇒ suspect a leaked watch or an unbounded key.
- **DO NOT.** Do not defrag all members at once — each blocks while it defragments, so you manufacture the outage you were preventing.

### 8.2.9 `token-regression-detected` — page, highest severity {#rb-token-regression}

- **Means / impact.** A token was observed at or below one already seen for the same key: INV-04 is breached. Fencing is now decoration — every `fence <` check still runs and still passes while protecting nothing. Impact is potentially unbounded and silent; this is the class of bug that reaches the ledger.
- **First three.** 1) Stop the writers: `kubectl scale deploy/payout-executor --replicas=0`. 2) `psql paydb -c "select id, fence from account order by fence desc limit 20"` against the backend's current revision or sequence value. 3) Establish what happened to the backend: etcd snapshot restore, `lockdb` restore, or a reset `fencing_token_seq` ([C1 §1.7](contracts/C1-database-schemas.md#ct1-seq)).
- **Causes, by frequency.** (1) an etcd snapshot restore rewinding the revision counter; (2) a `lockdb` point-in-time restore without advancing the sequence; (3) a sequence reset by a migration or a manual `setval`; (4) two clusters serving one keyspace.
- **Remediate / rollback.** Advance the token space **past the highest token any resource has ever stored** — max `fence` across `account` and `rail_high_water`, plus a wide margin — before accepting traffic: throwaway writes for etcd, `setval` forward for Postgres. Then reconcile every write in the window. There is no rollback for an issued token; only advance.
- **Escalate.** Immediately, to the payments duty manager and the postmortem owner. Assume data impact until reconciliation proves otherwise.
- **DO NOT.** Do not resume executors before the advance. Do not move a stored fence downward. Do not treat a backend snapshot restore as a routine recovery step ever again.

### 8.2.10 `metrics-target-down` — ticket {#rb-target-down}

- **Means / impact.** `PodMonitoring` is not scraping a pod, or `up` is 0. Every alert above is now blind. Nothing is visibly wrong — which is the danger.
- **First three.** 1) `kubectl port-forward <pod> 8080` then curl `/actuator/prometheus`. 2) `kubectl get pod <pod> -o jsonpath='{.spec.containers[*].ports}'` — the container port must be named **`http-metrics`** ([C4 §4.9](contracts/C4-observability.md#ct4-scrape)); a wrong name fails silently. 3) `kubectl describe podmonitoring` for a selector mismatch.
- **Causes, by frequency.** (1) named-port drift; (2) a label selector not matching a new workload; (3) the actuator endpoint not exposed in config; (4) a cardinality blow-up causing scrape timeouts.
- **Remediate / rollback.** Fix the port name or selector and re-verify in the order C4 §4.9 prescribes; revert the manifest change that renamed the port.
- **Escalate.** Blind for longer than the fast-burn window ⇒ count the elapsed time as unmeasured budget and say so in the review.
- **DO NOT.** Do not add per-payout tags to "improve" a metric — the cardinality budget ([C4 §4.3](contracts/C4-observability.md#ct4-cardinality)) is what keeps scrapes inside their timeout.

### 8.2.11 `etcd-wal-fsync-p99-high` — ticket {#rb-etcd-disk}

- **Means / impact.** WAL fsync p99 on one or more etcd members is above the threshold, so raft entries commit slowly. **No impact yet**: a member that cannot fsync inside the election timeout is a member that loses leadership, and each election is 1–2 s of budgeted unavailability (§8.2.7, `06 §6.5.1`). This is the cause ticket that precedes an election storm.
- **First three.** 1) `etcdctl --write-out=table endpoint status --cluster` — leader, raft term, db size. 2) `histogram_quantile(0.99, sum by (pod, le) (rate(etcd_disk_wal_fsync_duration_seconds_bucket[5m])))` — one member or all three? One member is a disk; all three is the node pool or the region. 3) `kubectl get pvc -l app=dlock-etcd -o wide` plus the StorageClass — disk type and provisioned size, because IOPS scale with size; then `kubectl describe node` for CPU pressure.
- **Causes, by real frequency.** (1) the volume on a balanced rather than an SSD class; (2) Autopilot CPU throttling or a noisy neighbour, which shows as fsync latency without any disk metric moving; (3) db-size growth making every commit heavier (§8.2.8); (4) a volume small enough that its IOPS ceiling is the constraint.
- **Remediate / rollback.** Move the `volumeClaimTemplate` to an SSD class and roll **one member at a time**, confirming quorum between each; do not resize under load. Revert any StatefulSet storage or resource change that correlates.
- **Escalate.** All three members degrading together, or fsync p99 above the election timeout — escalate before the elections start, and treat it as §8.2.7.
- **DO NOT.** Do not raise the election timeout to make the number go away: you lengthen every real failover and forfeit the S5 time-to-leader claim. Do not defrag as a reflex — it adds IO to a disk that is already the bottleneck.

### 8.2.12 `pg-connection-saturation` — ticket {#rb-pg-connections}

- **Means / impact.** Backends on `dlock-pg-lock` are above 80 % of `max_connections`. Acquires still succeed; at 100 % lock-server cannot obtain a connection, every acquire fails closed, and this ticket becomes the §8.2.4 page. The window between the two is the entire value of this alert.
- **First three.** 1) `gcloud sql instances describe dlock-pg-lock` — tier and the `max_connections` flag actually in effect. 2) `psql "$LOCKDB_URL" -c "select state, count(*) from pg_stat_activity group by 1"` — active versus `idle in transaction`; the second is a leak, not load. 3) `hikaricp_connections_active` and `hikaricp_connections_pending` per lock-server replica × replica count, compared against `max_connections`.
- **Causes, by frequency.** (1) replica count × pool size exceeding the instance ceiling after a scale-up nobody re-checked; (2) a leaked transaction leaving sessions `idle in transaction`; (3) a long expiry sweep holding connections; (4) an instance tier too small for the fleet.
- **Remediate / rollback.** Cap the pool so `replicas × maximumPoolSize` stays under 80 % of `max_connections`, and roll the config. Record, then terminate, confirmed `idle in transaction` sessions with `pg_terminate_backend`. Revert the scale-up or pool change that correlates. A tier change is a **planned** change with a maintenance window, not an incident action.
- **Escalate.** Connections exhausted and acquires failing ⇒ this is now an S1 availability incident: escalate under §8.2.4, not as this ticket.
- **DO NOT.** Do not raise `max_connections` beyond what the tier's memory supports — you trade refused connections for an OOM-restarted primary. Do not add lock-server replicas to "spread the load": each brings its own pool and makes the count worse. Do not switch `LOCK_BACKEND` to etcd to dodge it (§8.2.4 — planned migration only).

## 8.3 Routine operations {#ops-routine}

| Operation | Procedure | Failure mode it prevents |
|---|---|---|
| **Deploy** | Build → Spotless and tests → image to Artifact Registry by digest → `kubectl apply` → watch readiness. Never ship lock-server and a fencing-related client in one change. | Two variables in one blast radius. |
| **Progressive rollout** | `maxSurge: 1, maxUnavailable: 0`; hold at one replica for a full burn window; promote only if both must-be-zero counters are 0 and acquire p99 is flat. | A regression that only appears under contention. |
| **Rollback** | `kubectl rollout undo deploy/<name>`, verify within one scrape interval. Rollback is the **first** action when a release correlates, not the last. | Debugging live on the broken version. |
| **Schema change** | Flyway, additive-only, deploy-before-use ([C1 §1.8](contracts/C1-database-schemas.md#ct1-migrations)). Never a migration that touches `fencing_token_seq`. | An INV-04 breach delivered by a migration. |
| **Cloud SQL maintenance** | Window off the payout peak. `dlock-pg-lock` is REGIONAL, so patching triggers a failover — treat each window as a scheduled §8.2.4 and confirm recovery. `dlock-pg-pay` is ZONAL: patching is an outage, so drain executors first. | A routine window read as an incident; a ZONAL restart mid-critical-section. |
| **Secret rotation** | Add a Secret Manager version → rolling restart to re-project → verify connections → **disable** the old version → destroy after one full window. | Destroying a version still referenced by an un-restarted pod. |
| **Backup verification** | Monthly: restore the latest `lockdb` and etcd backups into a scratch instance and *read* them. An unverified backup is a belief. | Discovering the backup is unusable during §8.2.9. |

**The etcd operational five** — the only etcd commands worth memorising:

```bash
# 1. Health and leader
etcdctl --write-out=table endpoint status --cluster
etcdctl endpoint health --cluster
# 2. Backup: state is small and precious. Schedule it, then verify it.
etcdctl snapshot save /backup/etcd-$(date +%F-%H%M).db
etcdctl --write-out=table snapshot status /backup/etcd-<stamp>.db
# 3. Compact, THEN defragment, ONE MEMBER AT A TIME (each blocks while it runs).
#    Skipping this is the read-only trap: at quota etcd refuses writes until the alarm is disarmed.
REV=$(etcdctl endpoint status --write-out=json | jq '.[0].Status.header.revision')
etcdctl compact "$REV"
etcdctl defrag --endpoints=<one-member>      # repeat per member; never --cluster in one shot
etcdctl alarm list && etcdctl alarm disarm   # only once size is back under quota
# 4. Replace a dead member. Order matters: REMOVE, then ADD, then START.
etcdctl member remove <MEMBER_ID>
etcdctl member add dlock-etcd-2 --peer-urls=https://<pod-dns>:2380
#    then start it with --initial-cluster-state existing. Adding before removing, or starting
#    with state=new, either loses quorum or forms a second cluster serving the same keyspace.
# 5. Watch the lock keyspace live - the single most useful debugging command here.
etcdctl watch --prefix /locks/ -w json
```

## 8.4 The game-day plan {#ops-gameday}

Run monthly and before any milestone is called done. Fire **every** alert on purpose: an alert that has never fired is a hypothesis. Record detection latency — it is part of incident response whether measured or not. Latency targets are **ASSUMPTIONS**.

| # | Scenario | Injection | Expected signal chain | Latency to record | A failed exercise looks like |
|---|---|---|---|---|---|
| 1 | Zombie holder writes | Pause a holder past its lease, then write (harness fencing demo, runnable from T-042) | `fenced_out` log → `lock.fenced.out` → page → §8.2.1 | log line → notification, ≤ 5 min | No alert; or an alert with no `owner_id`/token in the payload |
| 2 | Duplicate submission | Force a second claim of one payout against the rail-stub | proxy blocks → `rail.duplicate.attempted` → page | ≤ 5 min | The second submission reaches the stub — INV-02 is not really enforced |
| 3 | Lock service gone | `kubectl scale deploy/lock-server --replicas=0` | acquires fail **closed** → availability burn → page; backlog ages | ≤ 5 min to page | Acquires fail open, or executors crash-loop instead of parking work |
| 4 | etcd leader loss under load | Delete the leader pod while the load generator runs | brief acquire errors → recovery < 2 s → election counted against budget | time to recovery, not to page | Unavailability far past 2 s, or the retry budget amplifies into a herd |
| 5 | Quorum loss | Delete two of three members | acquires fail closed; quorum page | ≤ 5 min | Someone reaches for `snapshot restore` without the token advance |
| 6 | Cloud SQL failover | `gcloud sql instances failover dlock-pg-lock` | pg-backend acquires stall then recover; readiness flaps | stall duration | Sessions or leases treated as valid across the failover window |
| 7 | Lease starvation | Load generator with TTL below hold time | `lock.lease.expired` rises → ticket, **before** any fence fires | time to ticket | The leading indicator is silent and the fence is the first news |
| 8 | Token rewind | Restore an etcd snapshot into a scratch cluster and replay | regression detected → highest-severity page | ≤ 1 min | Nothing detects it — the worst result, and the reason this exercise exists |
| 9 | Telemetry blind | Rename the container port away from `http-metrics` | `metrics-target-down` ticket | ≤ 15 min | Silence: the project is unmonitored and did not notice |
| 10 | Read the alert cold | Open each incident and read its `documentation` field as if just woken | It says what to do first | n/a | It says "investigate" — then fix the runbook, not the alert |

**Why the practice is only believable with the failure clause.** The sentence that earns credibility is *"we ran a game day where we killed the leader under load and confirmed failover was under two seconds — and we found the runbook was wrong."* An exercise that always passes is theatre: it proves the team can run a script, not that the service can be operated. A game day with no findings means the scenarios were too easy, and the next one is made harder.

## 8.5 Blameless postmortem template {#ops-postmortem}

Blameless means the write-up asks *what made this action reasonable at the time*, never *who did it*. A postmortem that names a person has stopped producing information, because the next engineer will hide.

| Section | Content | Rule |
|---|---|---|
| Title / date / authors | One line, symptom first | Written by the responders, not by a manager |
| Severity and user impact | Payouts delayed or mis-stated, duration, count | Impact in user terms first, internals second |
| Error budget consumed | Fraction of the window, per SLO | Ties the incident to §8.1 #5 |
| Timeline (UTC) | One line per event; detection and mitigation marked | Distinguish *happened* from *noticed* |
| Detection | Which alert, or "a human noticed" | "A human noticed" is itself an action item |
| Contributing causes | Plural, conditions not people | Stop at the first *fixable* condition, not the first blameable one |
| Recovery | What helped, and what was tried and did not | Failed attempts are the most reusable content |
| What went well · where we got lucky | Both kept, explicitly | Luck is an unbudgeted dependency; naming it funds the fix |
| Action items | Owner, date, tracker id; classed prevent / detect / mitigate | No item without an owner; never "the team" |
| Runbook diff | The §8.2 edit this incident produced | An incident that changes no runbook probably taught nothing |
| Invariant check | Did INV-01…INV-08 hold, and which one caught it? | Names the control that worked, so it is protected |

Triggers: any page, any non-zero must-be-zero counter, any customer-visible delay, any manual data fix. Published within five business days, readable by someone outside the team.

## 8.6 On-call design {#ops-oncall}

| Question | Position |
|---|---|
| Why not a four-person rotation alone? | Four primaries is **one week in four** carrying a 24/7 pager. That is not a schedule, it is a slow attrition problem: sleep debt compounds, the same person is always the one who was up last night, and the first resignation leaves a one-in-three rotation for those who remain. |
| Then what? | **Share the rotation with a sibling team** — target one-in-six to one-in-eight primary. That also forces the runbook to be good enough for a non-author, which is exactly the property §8.2 is written for. |
| Structure | Primary plus secondary, where secondary is the escalation path and not a second pager. Business-hours handoff with a written handover note. Follow-the-sun only if a second site genuinely exists. |
| Compensation and recovery | On-call is compensated, and a night page buys the next morning off. Unwritten, this is an unfunded liability. |
| Load ceiling | **ASSUMPTION: ≤ 2 pages per primary shift.** Above that, paging quality *is* the incident and the next sprint is alert triage, per §8.1 #1. |
| What qualifies an engineer for the pager | (1) shipped a change to at least two of lock-server / payout-executor / rail-proxy; (2) can explain fencing and why failing closed is correct; (3) ran a §8.4 game day as *primary responder* at least once; (4) already holds the access — GKE, both Cloud SQL instances, Secret Manager read, dashboards — *before* the shift, not during it; (5) shadowed one full shift with the outgoing primary; (6) knows the two commands that stop the bleeding: `kubectl rollout undo` and `kubectl scale deploy/payout-executor --replicas=0`. |
| Anti-qualification | Nobody takes the pager for a component they have never deployed, and nobody is on-call in their first two weeks. |

## 8.7 Toil register {#ops-toil}

Toil is manual, repetitive, automatable, of no enduring value, and scales with load. Hours are **ASSUMPTIONS**.

| Toil | h/mo | Automation that removes it | Priority |
|---|---|---|---|
| etcd compact plus per-member defrag | 2.0 | CronJob running the §8.3 sequence, with the size ticket as backstop | High — also a correctness risk (read-only trap) |
| Backup verification restores | 3.0 | Scheduled restore-into-scratch job that asserts a successful read | High — an unverified backup is a belief |
| Fenced-out triage (log → owner → pause evidence) | 2.5 | Promote `owner_id` and `lock.key` to labels ([C4 §4.6](contracts/C4-observability.md#ct4-promotion)) so the alert payload answers steps 1–2 | High |
| Re-driving payouts delayed by a fence | 1.5 | Automatic re-claim with backoff; park after N attempts | Medium |
| Secret-rotation restarts | 1.0 | Rotation pipeline: new version → rolling restart → verify → disable old | Medium |
| Reading dashboards for the weekly review | 1.5 | Generated SLO report built from the same queries | Medium |
| Cloud SQL maintenance babysitting | 1.0 | Pre-window executor drain plus a post-window assertion | Low |
| Manual game-day setup | 2.0 | Scripted injections in `harness`, one target per scenario | Low — but it is what makes §8.4 monthly rather than aspirational |
| **Total ≈ 14.5** | | ≈ 9 % of one engineer-month. **Ceiling 25 %** — crossing it makes toil reduction the next sprint's top item. | |

## 8.8 Capacity and cost review cadence {#ops-cost}

| Cadence | Review | Trigger to act |
|---|---|---|
| Weekly, 30 min | SLI trend, budget burn, pages per shift, both must-be-zero counters, top three toil items | Any budget on track to exhaust; any page count above the ceiling |
| Monthly | Peak vs provisioned: acquire QPS, `lock.held.current` / `lock.waiters.current`, etcd db size and election count, Cloud SQL CPU and connections, backlog-age p99 | Sustained above 60 % of any headroom, or elections above budget |
| Monthly | Cost actual vs the [§5.10](05-infrastructure.md#gcp-cost) estimate (**ASSUMPTION ≈ EUR 390–480/mo if left running continuously; ≈ EUR 13–16 per focused day under the apply-and-destroy pattern**), split by the three largest line items | Variance above 20 %, or an idle resource surviving two reviews |
| Quarterly | Capacity forecast at 2× and 5× payout volume; re-examine the REGIONAL/ZONAL split and raw-metric retention | A forecast crossing a headroom line within two quarters |
| Quarterly | Error-budget policy, toil ceiling, and alert inventory — delete anything that never fired *and* never would | Any alert with no game-day exercise behind it |
| Per change | A cost delta named in the PR whenever a change adds a resource, a metric series, or a log field | No number, no merge |

## 8.9 Operational readiness checklist {#ops-readiness}

Nothing is "done" until every line passes; each maps to a milestone gate in `docs/10-delivery-plan.md`.

| # | Gate | Evidence |
|---|---|---|
| 1 | SLIs implemented and scraped, for **both** backends | `/actuator/prometheus` verified per [C4 §4.9](contracts/C4-observability.md#ct4-scrape); `up` == 1 for every workload |
| 2 | SLOs and a written error-budget policy with consequences | `06 §6.6`, agreed by the product owner, not just by engineering |
| 3 | Every alert in `06 §6.7` has a §8.2 entry and vice versa | A one-for-one name audit in both directions |
| 4 | Every alert fired on purpose at least once, latency recorded | A dated §8.4 results table |
| 5 | Both must-be-zero counters at zero for a full window with fencing on | Dashboard export |
| 6 | Fencing proven at **both** points, with the negative control (switch off ⇒ corruption reproduced) | T-042 harness output; `lock.fenced.out` split by `resource` |
| 7 | Token monotonicity survives restart, failover **and** restore | INV-04 evidence including the post-restore advance |
| 8 | Rollback rehearsed and timed for every deployable | Game-day log |
| 9 | Backups taken **and** restored for `lockdb` and etcd | Restore log with a successful read |
| 10 | etcd maintenance automated; quota and alarm behaviour exercised | CronJob plus a deliberate NOSPACE exercise |
| 11 | Autopilot election rate measured against the budget | Elections per month vs the SLO |
| 12 | On-call staffed per §8.6; sibling team agreed; qualifications met | A named rotation with no unowned page |
| 13 | The runbook executed by someone who did not write it | Scenario 10 signed off by that person |
| 14 | Toil register populated with owners, total under the 25 % ceiling | §8.7 |
| 15 | Postmortem template in the repo and used once (a game day counts) | One published document |
| 16 | Kill switches default enabled everywhere; misuse documented as Sev-1 | Config audit against [C5 §5.2](contracts/C5-config-build-and-naming.md#ct5-killswitches) |
