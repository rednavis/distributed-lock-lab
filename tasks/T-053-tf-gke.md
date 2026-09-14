# T-053 — Terraform module: GKE Autopilot

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M5 (GCP infrastructure) · **Estimate** 30 min (write + `validate` only; the first real
`apply` is T-054+). If the implementer also wants to apply and smoke-test the cluster, that is a second
session — split it out rather than rushing both.

**Preconditions** — T-050, T-051, T-052. You inherit `envs/dev` wiring `module "network"` (outputs include
`pods_range_name`, `services_range_name`) and `module "sql_lock"` / `module "sql_pay"` (outputs include
`secret_id` each). No cluster, service account or IAM binding exists yet.

**Goal** — Write the `gke` module: one Autopilot cluster whose `ip_allocation_policy` names both secondary
ranges, the `dlock-app` Google service account, and the least-privilege IAM plus Workload Identity bindings
for the five workload KSAs.

## 1. Why this task exists

Autopilot removes node management but not networking or identity, and both are where this project's deployments
actually fail: an unnamed secondary range kills cluster creation, and a missing
`roles/iam.workloadIdentityUser` binding kills every pod that "has the right service account"
([05 §5.9](../docs/05-infrastructure.md#gcp-wi)). Encoding both bindings in Terraform, with no downloaded
JSON key anywhere, is the security posture the project claims (NFR-12, NFR-13).

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| Cluster `dlock-gke`; Google SA `dlock-app`; K8s object name = module name | [C5 §5.6](../docs/contracts/C5-config-build-and-naming.md#ct5-naming) |
| Module named `gke`, resource local names `this` / `app` | [C5 §5.6](../docs/contracts/C5-config-build-and-naming.md#ct5-naming) |
| Module inputs/outputs (`cluster_endpoint`, `cluster_ca`, `workload_identity_pool`) | [05 §5.4](../docs/05-infrastructure.md#gcp-tf) |
| The exact principal chain and the six roles, and what is **not** granted | [05 §5.9](../docs/05-infrastructure.md#gcp-wi) |
| Autopilot constraints | [05 §5.8](../docs/05-infrastructure.md#gcp-autopilot) |
| Evictions accepted as budgeted unavailability | [ADR-005](../docs/adr/ADR-005-gke-autopilot-eviction-as-budgeted-unavailability.md) |
| Terraform 1.15 / google 7.x | [C5 §5.3](../docs/contracts/C5-config-build-and-naming.md#ct5-catalog) |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report**, per
[04 §4.4](../docs/04-contracts.md#c-precedence).

## 3. Deliverables

| Path | What |
|---|---|
| `deploy/terraform/modules/gke/versions.tf` | google provider requirement |
| `deploy/terraform/modules/gke/variables.tf` | `project_id`, `region`, `cluster_name`, `network_self_link`, `subnet_self_link`, `pods_range_name`, `services_range_name`, `k8s_namespace`, `workload_ksas` (list), `secret_ids` (list), `artifact_repo_id`, `deletion_protection`, `labels` |
| `deploy/terraform/modules/gke/cluster.tf` | the Autopilot cluster |
| `deploy/terraform/modules/gke/iam.tf` | the service account and all bindings |
| `deploy/terraform/modules/gke/outputs.tf` | `cluster_endpoint`, `cluster_ca` (sensitive), `workload_identity_pool`, `service_account_email` |
| `deploy/terraform/modules/gke/README.md` | the §5.9 principal chain reproduced, the two-bindings-two-directions warning, the role table with the least-privilege reason per row |
| `deploy/terraform/envs/dev/main.tf` (modify) | `module "gke"` fed from the network and both sql modules |
| `deploy/terraform/envs/dev/outputs.tf` (modify) | cluster endpoint and SA email; `cluster_ca` marked sensitive |

## 4. Specification

**Cluster** (`google_container_cluster "this"`): `enable_autopilot = true`; `location = var.region`
(regional, which Autopilot requires); `network`/`subnetwork` from the inputs; `ip_allocation_policy` with
`cluster_secondary_range_name = var.pods_range_name` and `services_secondary_range_name = var.services_range_name`;
`private_cluster_config` with private nodes and a public endpoint (a private endpoint needs a bastion, which
this project does not build — say so in the README); `release_channel = "REGULAR"`; `deletion_protection` from the
variable; `workload_identity_config` is implicit under Autopilot — do not fight the provider if it rejects an
explicit block, report instead. Do **not** declare `node_config`, `node_pool`, or `remove_default_node_pool`:
Autopilot manages nodes and those arguments are rejected or silently ignored.

**Identity.** One `google_service_account "app"` with `account_id = "dlock-app"`. Bindings, all narrow:

| Binding | Resource type | Scope |
|---|---|---|
| `roles/secretmanager.secretAccessor` | `google_secret_manager_secret_iam_member`, `for_each` over `var.secret_ids` | the two secrets, **never the project** |
| `roles/monitoring.metricWriter` | `google_project_iam_member` | project |
| `roles/logging.logWriter` | `google_project_iam_member` | project |
| `roles/cloudtrace.agent` | `google_project_iam_member` | project |
| `roles/artifactregistry.reader` | repository-scoped IAM member | the `dlock` repo |
| `roles/iam.workloadIdentityUser` | `google_service_account_iam_member`, `for_each` over `var.workload_ksas` | **on `dlock-app`**, member `serviceAccount:dlock-lab.svc.id.goog[<ns>/<ksa>]` |

`workload_ksas` defaults to the five workload names of C5 §5.6 — `lock-server`, `payment-resource`,
`payout-executor`, `rail-proxy`, `rail-stub` — plus `harness` if the harness runs in-cluster; namespace
`dlock`. Build the member strings from `var.project_id`, `var.k8s_namespace` and the list, so a typo cannot
diverge from the manifests.

**Forbidden grants.** No `roles/editor`, no `roles/cloudsql.*`, no `*.admin`, and **no
`google_service_account_key` resource of any kind**. Cloud SQL is reached by private IP with a DB password, so
no IAM database role is needed at all. A key resource in this file is an automatic task failure.

**Artifact Registry.** The repo itself is T-054's; accept `artifact_repo_id` as an input and, if the repo does
not exist yet, take the reader binding at project level **only** as a temporary measure clearly marked TODO
with the task id that narrows it — or better, defer that one binding to T-054 and say so.

## 5. Acceptance criteria

1. `terraform -chdir=deploy/terraform/envs/dev fmt -check -recursive` exits 0; `validate` reports valid.
2. `grep -n 'secondary_range_name' deploy/terraform/modules/gke/cluster.tf` shows exactly two hits, both referencing the network module's outputs via variables — no literal `pods`/`services` strings retyped in this module.
3. `enable_autopilot = true` is present and `grep -rn 'node_pool\|node_config' deploy/terraform/modules/gke` returns nothing.
4. `grep -rn 'workloadIdentityUser' deploy/terraform/modules/gke/iam.tf` shows a binding on the service account resource, not on the project, and its member string contains `.svc.id.goog[`.
5. `grep -rn 'secretmanager' deploy/terraform/modules/gke/iam.tf` shows a **secret-scoped** member resource, not `google_project_iam_member`.
6. `grep -rniE 'service_account_key|roles/editor|roles/owner|\.admin' deploy/terraform/` returns nothing.
7. Every role in the 05 §5.9 table appears exactly once in `iam.tf`, and the module README lists each with its least-privilege justification.
8. `cluster_ca` output is declared `sensitive = true`.

## 6. Verification

`terraform -chdir=deploy/terraform/envs/dev init -backend=false && terraform -chdir=deploy/terraform/envs/dev validate`
— expected valid. Run the four greps of §5 (2,3,4,6) and confirm the stated hit counts. Record in the module
README, as the T-054+ post-apply checks and **do not run them now**:
`gcloud container clusters describe dlock-gke --region europe-central2 --format='value(autopilot.enabled)'`;
`gcloud container clusters get-credentials dlock-gke --region europe-central2`; and the identity smoke test —
a pod using KSA `dlock/lock-server` fetching `dlock-lock-db-password`.

## 7. Out of scope

Artifact Registry (T-054), observability/budget modules and `PodMonitoring` (M6, and it is a CRD not a
Terraform resource), the Kubernetes manifests and the KSA objects themselves plus their
`iam.gke.io/gcp-service-account` annotations (later M5 tasks under `deploy/k8s/`), the etcd StatefulSet (M3
deploy), and the real `apply` and teardown (T-059).

## 8. Hazards

- **Trap 4 fires here, not in T-051:** if `ip_allocation_policy` omits or misnames either range, Autopilot creation fails outright. This is the most common first-run failure in the whole project.
- **Two bindings, two directions** ([05 §5.9](../docs/05-infrastructure.md#gcp-wi)): the project-level role grants alone are not enough; without `workloadIdentityUser` *on the GSA* the pod gets `PermissionDenied` while looking correctly configured. Forgetting the KSA annotation later produces the same symptom from the other side.
- Autopilot rejects several familiar cluster arguments; adding them to "be explicit" turns a valid plan into an apply error. Prefer omission ([05 §5.8](../docs/05-infrastructure.md#gcp-autopilot)).
- `deletion_protection` defaults true on GKE too (trap 3) — keep it a variable.
- Never download a service-account JSON key; it is unnecessary here and is the commonest cloud credential leak.
- No git commands ([ADR-011](../docs/adr/ADR-011-no-git-initialisation-yet.md)).

## 9. On completion

Mark T-053 done in `tasks/README.md`. Note whether the artifact-registry reader binding was deferred to T-054
or granted at project level with a TODO, and any Autopilot argument the provider rejected.
