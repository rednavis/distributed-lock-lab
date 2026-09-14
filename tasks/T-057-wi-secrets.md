# T-057 — Workload Identity, secrets and private connectivity

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M5 · **Estimate** 30 min. This is the task that turns "deployed" into "actually connected",
and it is mostly debugging a chain of four hops. If the Secret Manager add-on requires a cluster update
that takes longer than the session, the update wait is unattended time, not extra work.

**Preconditions** — T-052 (both Cloud SQL instances exist with private IPs and the two password secrets
`dlock-lock-db-password` / `dlock-pay-db-password`), T-053 (`dlock-app` GSA and its role bindings), T-055
(five workloads applied, KSAs present **without** the Workload Identity annotation, paydb-backed pods
deliberately not ready), T-056 (etcd healthy). No pod can currently read a secret or reach a database.

**Goal** — Complete the KSA→GSA→Secret Manager principal chain, project both DB passwords into the pods
that need them, and prove private-IP connectivity from a pod to each Cloud SQL instance.

## 1. Why this task exists

Workload Identity has **two bindings in two directions** and forgetting the second is the standard cause of
`PermissionDenied` from a pod that visibly "has the right service account"
([05 §5.9](../docs/05-infrastructure.md#gcp-wi)). Doing this properly is also what lets the project claim
NFR-12/13 honestly: no service-account JSON key, no password in a manifest, no public IP on either
database, and no IAM database role because the private-IP-plus-password path needs none.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| The exact principal chain, the six roles, secret-scoped accessor, and the **not granted** list | [05 §5.9](../docs/05-infrastructure.md#gcp-wi) |
| KSA annotation `iam.gke.io/gcp-service-account: dlock-app@dlock-lab.iam.gserviceaccount.com`, one KSA per workload | [05 §5.7](../docs/05-infrastructure.md#gcp-k8s), [C5 §5.6](../docs/contracts/C5-config-build-and-naming.md#ct5-naming) |
| Secret ids `dlock-<db>-db-password`; passwords arrive as `*_PASSWORD` env, never a config key in a file | [C5 §5.7](../docs/contracts/C5-config-build-and-naming.md#ct5-env), [C5 §5.1](../docs/contracts/C5-config-build-and-naming.md#ct5-config) |
| Datasource URLs point at private IPs; `ipv4_enabled = false` + `private_network` is the posture | [05 §5.4](../docs/05-infrastructure.md#gcp-tf) |
| Two databases on two instances — one URL must never be reused for the other | [ADR-003](../docs/adr/ADR-003-two-databases-two-instances.md) |
| Readiness must fail while the datasource is unreachable | [C4 §4.10](../docs/contracts/C4-observability.md#ct4-health) |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report**
([04 §4.4](../docs/04-contracts.md#c-precedence)).

## 3. Deliverables

| Path | What |
|---|---|
| `deploy/terraform/modules/gke/main.tf` *(modify)* | enable the cluster's Secret Manager add-on; ensure `roles/iam.workloadIdentityUser` covers **all five** KSA members |
| `deploy/terraform/modules/cloudsql/main.tf` *(modify if needed)* | `roles/secretmanager.secretAccessor` for `dlock-app` **on that module's secret**, not the project |
| `deploy/terraform/envs/dev/outputs.tf` *(modify)* | expose both `private_ip` values for URL substitution |
| `deploy/k8s/secrets/spc-lock-db.yaml`, `spc-pay-db.yaml` | `SecretProviderClass` per database, syncing to a Kubernetes Secret |
| `deploy/k8s/<module>/serviceaccount.yaml` *(modify ×5)* | the WI annotation |
| `deploy/k8s/<module>/deployment.yaml` *(modify)* | CSI volume + mount, `*_PASSWORD` via `secretKeyRef`, datasource URL from a placeholder |
| `deploy/k8s/apply.sh` *(modify)* | substitute both private IPs read from Terraform output |
| `deploy/k8s/README.md` *(modify)* | the chain diagram and the four-step debug ladder below |

## 4. Specification

**Terraform.** Add nothing broader than the §5.9 table. Each accessor binding is on a single
`google_secret_manager_secret` resource so a workload can read one version and cannot list, create or
destroy secrets. The five `google_service_account_iam_member` bindings take member
`serviceAccount:dlock-lab.svc.id.goog[dlock/<ksa>]` — one per workload KSA, on the `dlock-app` service
account. Re-check T-053's output before adding: if a binding already exists, do not duplicate it, and if
T-053 granted the accessor role at project scope, tighten it and say so.

**Projection.** One `SecretProviderClass` per database, referencing
`projects/dlock-lab/secrets/dlock-<db>-db-password/versions/latest`, with a `secretObjects` block syncing
into a Kubernetes Secret whose key is consumed by `secretKeyRef` as `LOCK_DATASOURCE_PASSWORD` /
`PAYMENTS_DATASOURCE_PASSWORD` (spelling per [C5 §5.7](../docs/contracts/C5-config-build-and-naming.md#ct5-env)).
Pin `versions/latest` deliberately and note the trade-off in the README: a rotated password takes effect on
pod restart only.

**Who mounts what.** `lock-server` → the lockdb secret. `payment-resource` and `rail-proxy` → the paydb
secret. `rail-stub` → none (it has no datasource). `payout-executor` → mount only if its module actually
configures a datasource; check its `application.yaml` first and report if the answer differs from this
spec rather than mounting "just in case".

**URLs.** `jdbc:postgresql://<private-ip>:5432/<lockdb|paydb>` with the IP substituted by `apply.sh` from
`terraform output`. No Cloud SQL Auth Proxy sidecar and no `cloud-sql-instances` annotation: the private-IP
path is the decision, and adding a proxy would be a contract change, not an improvement.

**The debug ladder** for the README, in this order, because each step rules out one hop: (1) the pod's
metadata server reports `dlock-app@…` as its service-account email; (2) a token request from the pod
succeeds; (3) the mounted CSI file exists and is non-empty; (4) TCP 5432 to the private IP connects.
Whichever step first fails names the misconfiguration exactly.

## 5. Acceptance criteria

1. `terraform -chdir=deploy/terraform/envs/dev plan` shows no unexpected IAM drift and applies cleanly.
2. `gcloud secrets get-iam-policy dlock-pay-db-password` lists `dlock-app` with `secretAccessor`; the
   **project** IAM policy does not grant that role.
3. All five KSAs carry the annotation: `kubectl -n dlock get sa -o yaml | grep -c gcp-service-account` = 5.
4. `grep -rniE 'password: *[^$]' deploy/k8s deploy/terraform` finds no literal password value.
5. `grep -rn 'cloud-sql-proxy\|cloudsql-instances\|\.json' deploy/k8s` returns nothing (no proxy, no key).
6. All five Deployments report `Available`, and `/actuator/health/readiness` shows the datasource `UP` on
   each pod that has one.
7. A pod can open TCP 5432 to both private IPs; neither instance has a public IP.
8. `deploy/k8s/README.md` contains the four-step ladder and the `versions/latest` rotation caveat.

## 6. Verification

```
terraform -chdir=deploy/terraform/envs/dev apply
BUILD_ID=a1b2c3d ./deploy/k8s/apply.sh && kubectl -n dlock get deploy
kubectl -n dlock exec deploy/payment-resource -- sh -c 'wget -qO- --header="Metadata-Flavor: Google" \
  http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/email'
kubectl -n dlock exec deploy/payment-resource -- sh -c 'ls -l /var/run/secrets/dlock && nc -z -w3 <pay-private-ip> 5432; echo $?'
kubectl -n dlock exec deploy/payment-resource -- wget -qO- localhost:8080/actuator/health/readiness
gcloud sql instances describe dlock-pg-pay --format='value(settings.ipConfiguration.ipv4Enabled)'
```

Expected: the email is `dlock-app@dlock-lab.iam.gserviceaccount.com`; `nc` exits 0; readiness reports
`UP` with a `db` component; the last command prints `False`.

## 7. Out of scope

Password rotation automation, Secret Manager CMEK, `PodMonitoring` and log-based metrics (M6), Cloud SQL
IAM database authentication (deliberately not used), NetworkPolicy, and any change to the etcd StatefulSet
(T-056 owns it).

## 8. Hazards

- **Env-only secret consumption silently yields nothing.** The synced Kubernetes Secret exists only while
  some pod mounts the CSI volume; a `secretKeyRef` without the corresponding `volumeMount` leaves the pod
  in `CreateContainerConfigError` — or worse, reading a stale Secret from a previous attempt.
- **The second binding is the one people forget** — `workloadIdentityUser` *on the GSA*, member = the KSA.
  Fixing the annotation alone changes nothing ([05 §5.9](../docs/05-infrastructure.md#gcp-wi)).
- The member string is namespace-qualified: `[dlock/lock-server]`. A wrong namespace fails identically to a
  missing binding.
- Never download a service-account JSON key, and never paste a password into a ConfigMap "for now" — a
  temporary literal in a manifest is the leak this whole task exists to avoid (NFR-12).
- Crossing the two URLs is undetectable at deploy time: paydb Flyway migrations would run against `lockdb`.
  Verify the database name inside each URL, not just that the pod connected.

## 9. On completion

Mark the T-057 row done in `tasks/README.md`. Record whether T-053's bindings needed tightening, and which
workloads ended up mounting which secret.
