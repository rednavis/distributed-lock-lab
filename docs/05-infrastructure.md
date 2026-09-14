# 05 — GCP architecture

Written for a reader **learning GCP**. Every name is pinned by
[C5 §5.6](contracts/C5-config-build-and-naming.md#ct5-naming); this file explains *why* resources exist, never renames
them. Every figure is a labelled **ASSUMPTION** — none is production data (fictional mid-size PSP). Traceability:
NFR-02, NFR-05, NFR-12, NFR-13, NFR-14, A-12, SC-07, D-05, D-07.

## 5.1 The GCP concepts this project requires {#gcp-concepts}

| Concept | What it actually is | Why this project needs it | Failure mode when misunderstood |
|---|---|---|---|
| **Project** | Unit of billing, IAM, quota and naming; org/folder above it are optional | A **dedicated** project `dlock-lab` makes blast radius and teardown exact (§5.12) | In a shared project a forgotten Cloud SQL instance bills forever and `destroy` can never be *verified* |
| **Region / zone / scope** | Region = Warsaw (`europe-central2`); zone = isolated failure domain inside it (`-a/-b/-c`), sub-ms apart. Resources are zonal (disk, ZONAL SQL), regional (subnet, REGIONAL SQL, Autopilot) or global (VPC) | Consensus belongs **across zones in one region**; REGIONAL Cloud SQL keeps a synchronous standby in another zone | 3 etcd replicas in one zone survive nothing; cross-region quorum costs 20–150 ms per write; a "regional" cluster does not make a **zonal** database survive a zone loss |
| **IAM** | One sentence: *principal* may perform *role* on *resource*; bindings inherit **downwards** | Least privilege per workload (NFR-13) | The two mistakes below |
| **VPC / subnet / secondary ranges** | VPC global; subnet regional and owns the node range; pods and Services need **separate named secondary ranges** | Autopilot assigns every pod and Service an IP from those ranges | Missing secondary ranges — the most common first-run Autopilot failure (§5.6) |
| **Private Services Access** | Cloud SQL runs in *Google's* VPC; PSA reserves a range in yours and peers the two | The only private path from pods to `dlock-pg-*` | Instance creates fine and **nothing can connect** — the single most-skipped step |
| **Cloud NAT** | Outbound-only egress for nodes with no public IP | A private cluster cannot reach the internet, including public registries | `ImagePullBackOff` with an error that never mentions NAT |
| **Service enablement** | Every API is off until enabled per project (`sqladmin`, `container`, `servicenetworking`, `secretmanager`, `artifactregistry`, `monitoring`, `logging`) | Bootstrap step, §5.5 | First `apply` in a fresh project dies with `SERVICE_DISABLED` |

**The IAM mistake everyone makes, twice.** (1) Granting `roles/editor` because something did not work — it
works, and now the project identity can delete the database. Grant the narrow role and read the error: GCP names
the exact missing permission, so least privilege is a two-minute loop. (2) Confusing the service account
**as an identity** (roles granted *to* it) with the service account **as a resource** (roles granted *on*
it, e.g. impersonation). Workload Identity needs both, which is why it is the step that fails (§5.9).

## 5.2 Target architecture {#gcp-target}

```
 GCP project dlock-lab · region europe-central2 (Warsaw)
 +-------------------------------------------------------------------------+
 | VPC dlock-vpc | subnet 10.10.0.0/20 | pods 10.30/16 | services 10.40/20 |
 |  GKE Autopilot dlock-gke (regional, 3 zones)                            |
 |  | payout-executor x2 --> lock-server x3 ------ priv IP -> dlock-pg-lock
 |  |    +--> payment-resource x1 (fence a) ------ priv IP -> dlock-pg-pay
 |  |    +--> rail-proxy x1 (fence c, high-water) --> rail-stub x1         |
 |  | dlock-etcd StatefulSet x3, SSD PVC <-- client/peer                   |
 |  Artifact Registry (images) · Secret Manager (WI) · Prometheus/Logging  |
 |  PSA 10.20.0.0/16 ==peering==> Google's VPC · Cloud NAT --> egress only |
 +-------------------------------------------------------------------------+
```

Inventory — costs are **ASSUMPTIONS**, order-of-magnitude, list price, `europe-central2` (detail §5.10):

| Resource (pinned name) | Purpose | Why this over the alternative | ~EUR/mo |
|---|---|---|---|
| Project `dlock-lab` | Billing + blast-radius + teardown boundary | A folder inside an existing project cannot be deleted as one unit | 0 |
| VPC `dlock-vpc`, subnet `dlock-subnet`, secondary ranges `pods` / `services` | Private L3 plus the pod and Service IP space Autopilot requires | Default VPC has auto-mode subnets everywhere and no secondary-range control | 0 |
| PSA range + `servicenetworking` connection; Cloud Router + Cloud NAT | Private path to Cloud SQL; egress for private nodes | Public IP + authorized networks exposes the DB (NFR-12); public nodes are cheaper but weaken posture | ~30 |
| GKE Autopilot `dlock-gke` | Runs ~11 pods across 3 zones | Standard means node pools we do not want to own (§5.3) | ~65 fee + 90–140 workload |
| Cloud SQL `dlock-pg-lock` | Lock backend `lockdb` ([C1](contracts/C1-database-schemas.md#ct1-lockdb)) | **REGIONAL** — the synchronous standby is the durability claim behind NFR-05 | ~100 |
| Cloud SQL `dlock-pg-pay` | Protected resource `paydb` ([C1](contracts/C1-database-schemas.md#ct1-paydb)) | **ZONAL** — cost; failing it is not the experiment | ~50 |
| `dlock-etcd` StatefulSet + 3×10 GB SSD PVC | M3 backend; `ModRevision` is the token | GCP has no managed etcd (§5.3) | ~7 |
| Artifact Registry `dlock`, Secret Manager `dlock-{lock,pay}-db-password`, GCS `dlock-tfstate` | Immutable `<module>:<short-sha>` images; DB credentials as projected env vars; Terraform state | Public-registry pulls hit rate limits; no credential in source or state (NFR-12); local state loss is unrecoverable | ~0.3 |
| Managed Prometheus + Cloud Logging, billing budget + alert | Scrape `/actuator/prometheus` ([C4 §4.9](contracts/C4-observability.md#ct4-scrape)); cost guardrail before first apply (NFR-14) | Self-hosted Prometheus is another StatefulSet to babysit | 0–5 |

## 5.3 Why each choice, defended {#gcp-why}

**`europe-central2` (Warsaw).** Lowest RTT from the author, so the latency histograms
([C4 §4.2](contracts/C4-observability.md#ct4-metrics)) measure the lock, not the distance; three zones suffice
for a 3-member Raft group. Cloud SQL and GKE **must** be co-located — a cross-region database adds tens of
milliseconds to every acquire and makes NFR-03 (p99 ≤ 50 ms) unreachable for reasons unrelated to the design.

**GKE Autopilot, with the eviction trade-off accepted.** Autopilot removes node pools, upgrades and "why is
my pod Pending" from a project about locks. The price is real: it **bin-packs and evicts and gives no
node-placement control**, so a 3-replica etcd StatefulSet **will** see more leader elections than on Standard.
Not hidden — **reframed as a budgeted expense**: an election costs 1–2 s of shard unavailability (NFR-02), so
*N* elections/month is a line item charged against the error budget, not an incident. Mitigations:
PodDisruptionBudget `maxUnavailable: 1`, `topologySpreadConstraints` across zones, `safe-to-evict: "false"` —
then **measure** the residual against the SLO; D-05 stays open until one month of data exists (SC-10).
*Failure mode if unmeasured:* the reframe becomes an excuse and a platform electing ten times a day gets
called fine.

**Two Cloud SQL instances, REGIONAL + ZONAL.** One instance for both databases would take out the lock backend
and the protected resource **in the same event**, destroying the experiment: you could no longer show that a
lock-backend failover preserves fences while the resource keeps serving. Hence `dlock-pg-lock` REGIONAL
(synchronous standby — NFR-05, INV-04) and `dlock-pg-pay` ZONAL. **The euro cost of that decision is ~EUR
50/month, ~EUR 1.7/day for the standby alone** — the price of the experiment, worth stating rather than
pretending HA is free.

**Self-hosted etcd, because GCP has no managed etcd.** The operational weight is *data*, not an accident:
StatefulSet, zonal PVCs, headless Service, PDB, spread constraints, compaction and defrag, backup of a
consensus store, election noise on a bin-packing platform. **Build-versus-buy verdict:** for a real PSP, if a
managed strongly-consistent store exists (Spanner, or vendor-managed etcd/ZooKeeper), **buy it** — Raft's
correctness is not the hard part, its operations are; self-host only when the workload funds a team that owns
it. This project self-hosts to earn the right to say that with numbers.

## 5.4 Terraform module inventory {#gcp-tf}

Root module composes five children; Terraform 1.15 / google provider 7.x. **Tables only, no HCL** — the
implementer writes it in M5 (T-050…T-059). `PodMonitoring` is a **Kubernetes CRD, not** a Terraform resource (§5.7).

| Module | Purpose | Key inputs | Outputs |
|---|---|---|---|
| `network` | VPC, subnet, secondary ranges, PSA, NAT | `project_id`, `region`, `subnet_cidr`, `pods_cidr`, `services_cidr`, `psa_cidr` | `network_self_link`, `subnet_self_link`, `pods_range_name`, `services_range_name`, `psa_connection_id` |
| `cloudsql` (×2: `sql_lock`, `sql_pay`) | Instance + database + user + password secret | `instance_name`, `availability_type`, `tier`, `db_name`, `db_user`, `network_self_link`, `psa_connection_id` | `private_ip`, `connection_name`, `secret_id` |
| `gke` | Autopilot cluster + identity plumbing | `cluster_name`, `region`, `network_self_link`, `subnet_self_link`, `pods_range_name`, `services_range_name` | `cluster_endpoint`, `cluster_ca`, `workload_identity_pool` |
| `artifacts` | Image storage | `region`, `repo_id` | `repo_url` |
| `observability` | Managed metrics/logs, budget, alert channel | `project_id`, `notification_email`, `budget_amount_eur`, `billing_account` | `notification_channel_id` |

| Module | Declared resources | Arguments that matter, and why |
|---|---|---|
| `network` | `google_compute_network "this"`, `google_compute_subnetwork "this"` | `auto_create_subnetworks = false` (auto mode makes a subnet in every region — noise and cost); `secondary_ip_range` named `pods` / `services` (the names GKE references); `private_ip_google_access = true` (Google APIs without NAT) |
| `network` | `google_compute_global_address "psa"`, `google_service_networking_connection "psa"` | `purpose = VPC_PEERING`, `prefix_length = 16` reserves the range Cloud SQL lives in; the connection is the peering itself and **must exist before Cloud SQL** (§5.6 trap 1) |
| `network` | `google_compute_router`, `google_compute_router_nat "this"` | All subnets, all IP ranges, `AUTO_ONLY` addresses, log errors only — egress for image pulls |
| `cloudsql` | `google_sql_database_instance "this"`, `google_sql_database "this"`, `google_sql_user "app"` | `availability_type` REGIONAL vs ZONAL is the whole §5.3 argument; `ipv4_enabled = false` + `private_network` is the posture; `backup_configuration` with PITR on the lock instance; `deletion_protection`; `depends_on` the PSA connection; `name = lockdb\|paydb`, user `lockapp\|payapp` per C5 |
| `cloudsql` | `random_password`, `google_secret_manager_secret[_version]` | `secret_id = dlock-<db>-db-password` — no password in `.tfvars` or source (NFR-12) |
| `gke` | `google_container_cluster "this"` | `enable_autopilot = true`; `ip_allocation_policy` naming **both** secondary ranges (omitting them is §5.6 trap 4); `private_cluster_config`; `release_channel = REGULAR`; `deletion_protection` |
| `gke` | `google_service_account "app"`, `google_project_iam_member` ×N, `google_service_account_iam_member` ×N | `account_id = dlock-app`; only the narrow roles of §5.9 (NFR-13); `roles/iam.workloadIdentityUser` with member `serviceAccount:dlock-lab.svc.id.goog[ns/ksa]` — the *on-the-resource* direction of §5.1 |
| `artifacts` | `google_artifact_registry_repository "this"`, `google_project_iam_member` | `repository_id = dlock`, `format = DOCKER`, cleanup policy on untagged; `roles/artifactregistry.reader` so pulls need no key file |
| `observability` | `google_project_service` ×N, `google_monitoring_notification_channel "email"`, `google_billing_budget "this"`, `google_logging_metric` ×N | Enable `monitoring`/`logging`/`cloudresourcemanager`; an alert needs a destination before it is written; budget thresholds 50/90/100 % with `all_updates_rule` (NFR-14); log metrics filter on `event` names from [C4 §4.5](contracts/C4-observability.md#ct4-logs) per [C4 §4.7](contracts/C4-observability.md#ct4-lbm) |

## 5.5 State, the GCS backend, and the bootstrap chicken-and-egg {#gcp-state}

| Item | Decision | Why |
|---|---|---|
| Backend | `gcs`, bucket `dlock-tfstate` ([C5 §5.5](contracts/C5-config-build-and-naming.md#ct5-layout) is the source of truth for the name), prefix `dev`; locking automatic | Local state on one laptop is a single point of loss; two concurrent applies cannot interleave |
| Versioning | **On from the first run** | The only cheap recovery from a bad apply or a truncated write |
| Secrets in state | **Assume state holds the DB passwords in plaintext** | Private bucket, uniform bucket-level access, never committed to Git (NFR-12) |
| Bootstrap | Project, billing link, `gcloud services enable`, and the state bucket created **by hand once**; all else Terraform | You cannot store state in a bucket Terraform has not created, nor call a disabled API |

The chicken-and-egg in one line: **the backend must exist before the code that would create it runs.** The accepted
answer is a short documented bootstrap script, with the bucket deliberately *outside* the managed stack so
`terraform destroy` cannot delete the record of what it just destroyed. Remaining APIs are then declared as
`google_project_service` so the second apply is reproducible.

## 5.6 Terraform failure modes this stack teaches the hard way {#gcp-traps}

| # | Failure mode | Symptom | Fix |
|---|---|---|---|
| 1 | `google_service_networking_connection` must exist **before** Cloud SQL, and the ordering is **not always inferred** | Creation fails with an unhelpful peering error, or succeeds and is unreachable | Explicit `depends_on` from each SQL instance to the PSA connection |
| 2 | Destroying the PSA connection while an instance still uses it | `destroy` **hangs a long time, then fails**, leaving a half-torn stack | Destroy databases first, then the network (§5.12 order) |
| 3 | `deletion_protection` defaults to **true** on Cloud SQL and GKE | A `terraform destroy` that "does nothing" or refuses | Set false and `apply` *first*; expect two steps |
| 4 | **Missing secondary ranges** in `ip_allocation_policy` | Autopilot cluster creation fails outright — the most common first-run failure | Declare `pods`/`services` on the subnet, reference both by name |
| 5 | API not enabled, or a private cluster without NAT | `SERVICE_DISABLED` on first apply; every non-Artifact-Registry image `ImagePullBackOff` | Bootstrap enablement (§5.5) — eventually consistent, so one retry is normal; Cloud NAT plus Private Google Access |

## 5.7 Kubernetes object inventory {#gcp-k8s}

Object name equals module name. Tables, not manifests; replica counts are project **ASSUMPTIONS**.

| Kind | Name | Purpose | Fields carrying design intent |
|---|---|---|---|
| Namespace | `dlock` | One namespace, one `PodMonitoring` | label `app.kubernetes.io/part-of=dlock-lab` |
| Deployment | `lock-server` ×3 | The lock service | requests 500m/512Mi; **containerPort 8080 named `http-metrics`**; readiness `/actuator/health/readiness` (must fail when the backend is unreachable, [C4 §4.10](contracts/C4-observability.md#ct4-health)); liveness `/actuator/health/liveness` (must not touch the backend); `topologySpreadConstraints` zone `maxSkew: 1`; env `LOCK_BACKEND` |
| Deployment | `payout-executor` ×2 | Claims and executes payouts — **two replicas is the point**, contention must be real | requests 500m/512Mi; no inbound Service; `LOCK_CLIENT_SAFETYMARGIN` (no underscore inside `SAFETYMARGIN` — [C5 §5.7](contracts/C5-config-build-and-naming.md#ct5-env)) |
| Deployment | `payment-resource` ×1, `rail-proxy` ×1, `rail-stub` ×1 | Fence point (a); fence point (c) with persisted high-water; deliberately non-idempotent rail | requests 500m/512Mi and the named port on each; kill switches `PAYMENT_FENCING_ENABLED`, `RAIL_PROXY_FENCING_ENABLED`; `RAIL_STUB_DUPLICATEACKRATE`; `PAYMENTS_DATASOURCE_URL` → `dlock-pg-pay` private IP with `*_PASSWORD` projected from Secret Manager; annotation `dlock-lab/fencing-enabled` |
| StatefulSet | `dlock-etcd` ×3 | M3 lock backend | `volumeClaimTemplates` 10 GB **`premium-rwo`**; requests 500m/1Gi; `podAntiAffinity` + `topologySpreadConstraints` zone; `cluster-autoscaler.kubernetes.io/safe-to-evict: "false"`; `serviceName: dlock-etcd-headless`; probes on `/health` |
| Service | `dlock-etcd-headless`; then ClusterIP for `lock-server`, `payment-resource`, `rail-proxy`, `rail-stub` | Stable per-member DNS for peer URLs; in-cluster addressing for the rest | `clusterIP: None` on the headless one — Raft peers need identities, not a load balancer; every ClusterIP mirrors port name `http-metrics` from the container |
| PodDisruptionBudget | `dlock-etcd-pdb` | Bounds voluntary disruption | `maxUnavailable: 1` — with three members, quorum survives exactly one |
| ServiceAccount | one per workload | Workload Identity subject | `iam.gke.io/gcp-service-account: dlock-app@dlock-lab.iam.gserviceaccount.com` |
| PodMonitoring (CRD) | `dlock` | Managed Prometheus scrape | `spec.endpoints[].port: http-metrics` (**name, not number**), `interval: 30s`, `path: /actuator/prometheus`, selector on `part-of=dlock-lab` |
| ConfigMap / Job | `dlock-config` / `harness` | Non-secret config; runs correctness and benchmark scenarios | Mirrors [C5 §5.1](contracts/C5-config-build-and-naming.md#ct5-config) defaults only; `restartPolicy: Never` and must not be evicted mid-run |

**The named-port trap, restated because it costs hours:** if the container port is unnamed, or named `http` while
`PodMonitoring` says `http-metrics`, the target never appears and every query returns empty — **no error
anywhere**. Verify in the order given in [C4 §4.9](contracts/C4-observability.md#ct4-scrape).

## 5.8 Autopilot constraints that will bite {#gcp-autopilot}

| Constraint | Consequence here |
|---|---|
| Every container **must** declare CPU and memory requests; Autopilot mutates missing or low values upward | You are billed the mutated value — always set requests explicitly |
| Minimum ≈250m CPU / 512Mi per pod; vCPU:GiB ratio held between 1:1 and 1:6.5; CPU rounded to 250m steps | An 11-pod topology has a cost *floor*; asking for 50m saves nothing and fine-grained sizing is wasted effort |
| No privileged pods, no host-path DaemonSets, no node SSH | Node-level debugging unavailable — observability must be in-process (NFR-08) |
| No node-placement control; **bin-packs and evicts** | The election budget of §5.3; PDB + spread + `safe-to-evict` are mitigations, not guarantees |
| Only Google StorageClasses (**`premium-rwo`** SSD for the etcd PVC), and PVCs are zonal so a pod is pinned to its disk's zone | `standard-rwo` fsync latency inflates Raft commit latency and *causes* elections — the one storage choice with correctness-adjacent effects; an evicted etcd pod must reschedule in its disk's zone, and capacity pressure there appears as long `Pending` |
| Regional cluster management fee charged whether or not workloads run; no scale-to-zero | Idle cluster ≈ EUR 2.2/day (ASSUMPTION); cost control is teardown, not scaling |

## 5.9 Workload Identity: the exact principal chain {#gcp-wi}

```
 Pod --uses--> KSA dlock/lock-server  [annotation iam.gke.io/gcp-service-account]
        v
   GSA dlock-app@dlock-lab.iam.gserviceaccount.com
        ^ roles/iam.workloadIdentityUser ON the GSA, member =
        |   serviceAccount:dlock-lab.svc.id.goog[dlock/lock-server]
        +-- role bindings --> Secret Manager, Monitoring, Logging, Trace, Artifact Registry
```

Two bindings, two directions; forgetting the second is the usual cause of `PermissionDenied` from a pod that "has the
right service account".

| Role | Granted on | Why this is the least privilege that works |
|---|---|---|
| `roles/secretmanager.secretAccessor` | the two secrets, **not the project** | Read one version; cannot list, create or destroy secrets |
| `roles/monitoring.metricWriter` | project | Export only — a compromised pod cannot read dashboards |
| `roles/logging.logWriter` | project | Write structured events; cannot read others' logs |
| `roles/cloudtrace.agent` | project | OTel spans ([C4 §4.8](contracts/C4-observability.md#ct4-traces)) |
| `roles/artifactregistry.reader` | the `dlock` repo | Pull images only |
| `roles/iam.workloadIdentityUser` | on `dlock-app`, member = each KSA | The impersonation edge |
| **Not granted** | `roles/editor`, `roles/cloudsql.admin`, any `*.admin` | Cloud SQL is reached by private IP with a DB password, so no IAM database role is needed at all |

**Never download a service-account JSON key.** A key in an image or a repo is the most common cloud
credential leak there is, and it is unnecessary here (NFR-12, NFR-13).

## 5.10 An honest cost model for this topology {#gcp-cost}

A deliberately larger topology than a minimal demo: ~11 pods, **two** Cloud SQL instances (one REGIONAL), a regional cluster
fee, Cloud NAT, three SSD PVCs. All figures are **ASSUMPTIONS** at list price for `europe-central2`; re-verify first.

| Line item | Configuration (ASSUMPTION) | ~EUR/month | ~EUR/day |
|---|---|---|---|
| Autopilot workloads | 11 pods, **~5.5 vCPU / 7 GiB requested** ([§5.7](#gcp-k8s): 8 × 500m/512Mi + 3 etcd × 500m/1Gi) | 140–220 | 4.7–7.3 |
| GKE cluster management fee | one regional cluster, charged idle | ~65 | ~2.2 |
| Cloud SQL `dlock-pg-lock` REGIONAL | `db-custom-1-3840`, 10 GB SSD, HA | ~100 | ~3.3 |
| Cloud SQL `dlock-pg-pay` ZONAL | `db-custom-1-3840`, 10 GB SSD | ~50 | ~1.7 |
| Cloud NAT | one gateway, light traffic | ~30 | ~1.0 |
| etcd PVCs | 3 × 10 GB `premium-rwo` | ~7 | ~0.2 |
| Artifact Registry + Secret Manager + GCS state; Logging / Monitoring | < 1 GB total; project volume near the free tier | ~0.3; 0–5 | ~0.1 |
| **Total, running continuously** | | **~390–480** | **~13–16** |

**The line items nobody expects** are the cluster management fee and Cloud NAT: both charge per hour for *existing*,
not for being used, and both accrue while you sleep.

**Recommendation — the one-focused-day pattern.** Apply in the morning, run the whole experiment set (failover,
fencing, election measurement, backend benchmark), capture logs, metric screenshots and the comparison numbers, then
`destroy` the same day: **~EUR 13–16 for the day** (ASSUMPTION — the daily column above), and it forces the IaC to
work end to end. A standing environment is D-07, costs **~EUR 390–480/month**, and needs re-costing. **Configure the budget alert before the first apply** (NFR-14): **EUR 50/month**,
thresholds 50 / 90 / 100 %, email channel — and treat any unexpected morning charge as a §5.12 trigger.

## 5.11 Cheaper variants, and what each costs you {#gcp-cheaper}

| Instead of | Use | Saves (ASSUMPTION) | What experiment you lose |
|---|---|---|---|
| Continuous run | Apply → run → `destroy` same day | ~95 % | Nothing but convenience — **the recommended default** |
| `dlock-pg-lock` REGIONAL | ZONAL | ~EUR 50/mo | The synchronous-standby failover proof for NFR-05 / INV-04 |
| Two SQL instances | One instance, two databases | ~EUR 50/mo | The separation argument itself — lock backend and resource now fail together |
| GKE Autopilot | Cloud Run for the stateless services | ~EUR 65/mo fee, plus scale-to-zero | No StatefulSet, so etcd moves elsewhere; loses the eviction/election study D-05 exists to settle |
| Self-hosted etcd | PostgreSQL backend only | ~EUR 7/mo + operational time | The M3 correctness answer and the measured backend comparison — a headline deliverable |
| Cloud NAT; Cloud SQL | Public nodes (project only); Postgres containers in-cluster | ~EUR 30/mo; ~EUR 150/mo | Posture, never acceptable outside a throwaway project; and managed failover, PITR and the credibility of the M1 backend |
| GCP entirely | `docker compose` locally (NFR-15) | 100 % | Zonal failure domains, managed failover, IAM, the SRE telemetry stack — keep this path for iteration, not for the deliverable |

## 5.12 Teardown and orphan verification {#gcp-teardown}

Ordered, because §5.6 trap 2 is a real twenty-minute hang:

| Step | Command | Note |
|---|---|---|
| 1 | `terraform apply -var deletion_protection=false` | Trap 3 — clear protection on Cloud SQL and GKE first |
| 2 | `kubectl delete namespace dlock` | Releases PVCs and any load balancers before the cluster goes |
| 3 | `terraform destroy -target=module.sql_lock -target=module.sql_pay` | Databases before the peering |
| 4 | `terraform destroy` | Everything else, including network and NAT |
| 5 | `gcloud sql instances list` · `gcloud container clusters list` · `gcloud compute disks list` · `gcloud compute addresses list` · `gcloud compute routers list` · `gcloud artifacts repositories list` | Orphan sweep — retained PVC disks and reserved addresses are the usual survivors |
| 6–7 | project cost report the next morning, then `gcloud projects delete dlock-lab` | The report is the only proof that billable resources reached zero (SC-07); project deletion is **the guaranteed-complete teardown** |

**Why a dedicated project is the real answer.** `terraform destroy` deletes only what state knows about: a
console-clicked resource, a `kubectl`-created load balancer, a PVC disk whose reclaim policy retained it, anything
created after the last successful apply — all survive, and in a shared project you can never *prove* the bill is
zero. In a dedicated project, **project deletion removes everything unconditionally**: one command, no inventory, no
argument. That is why §5.1 opens with the project, and why the state bucket sits deliberately outside the managed
stack (§5.5) — delete the project, keep the artifacts.
