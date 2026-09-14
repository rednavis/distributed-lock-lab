# T-055 — Kubernetes manifests: the six services

> **Picking this up?** Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) first, then claim the matching
> issue and work on a branch. Finished means all six
> [definition-of-done gates](../CONTRIBUTING.md#7-definition-of-done), not five. **If anything below
> disagrees with a [contract](../docs/04-contracts.md), the contract wins** — open a
> [contract change issue](../.github/ISSUE_TEMPLATE/contract-change.yml) instead of implementing
> either version. Update this task's row in [the ledger](README.md) in the same pull request.


**Milestone** M5 · **Estimate** 30 min for the manifests plus a first `apply` that reaches
`Running` for the two services with no database dependency. If you also chase readiness for the
paydb-backed pods, split: those cannot go ready until T-057 projects the password.

**Preconditions** — T-053 (Autopilot cluster `dlock-gke` exists and `gcloud container clusters
get-credentials` works), T-054 (six images pushed as `…/dlock/<module>:$BUILD_ID`). You inherit an empty
cluster: no namespace, no workload, no `PodMonitoring`. `deploy/k8s/` does not exist yet.

**Goal** — Write the namespace, ConfigMap, ServiceAccounts, five Deployments, four ClusterIP Services and
the harness Job for the six deployable modules, with the named metrics port every later scrape depends on.

## 1. Why this task exists

This is where the design becomes a running topology, and two decisions in it are load-bearing rather than
boilerplate: `payout-executor` runs **two replicas so contention is real** ([ADR-004](../docs/adr/ADR-004-payout-executor-as-the-protected-operation.md)),
and every container port is **named** `http-metrics`, because Managed Prometheus selects on the name and a
mismatch fails silently with empty queries and no error ([C4 §4.9](../docs/contracts/C4-observability.md#ct4-scrape)).
Autopilot also refuses to run anything that does not declare requests, so sizing is mandatory, not tuning.

## 2. Contracts to obey

| What | Pinned by |
|---|---|
| Object name = module name; one KSA per workload; label and annotation keys; image tag form | [C5 §5.6](../docs/contracts/C5-config-build-and-naming.md#ct5-naming) |
| Object inventory: kinds, replica counts, requests, probes, spread constraints, per-workload fields | [05 §5.7](../docs/05-infrastructure.md#gcp-k8s) |
| Container port `8080` **named `http-metrics`**, path `/actuator/prometheus` | [C4 §4.9](../docs/contracts/C4-observability.md#ct4-scrape) |
| Readiness fails when the lock backend is unreachable; liveness must not touch it | [C4 §4.10](../docs/contracts/C4-observability.md#ct4-health) |
| Env var spelling — uppercase, dots to `_`, **hyphens deleted** | [C5 §5.7](../docs/contracts/C5-config-build-and-naming.md#ct5-env) |
| Config defaults mirrored in the ConfigMap; the two kill switches default ON | [C5 §5.1](../docs/contracts/C5-config-build-and-naming.md#ct5-config), [C5 §5.2](../docs/contracts/C5-config-build-and-naming.md#ct5-killswitches) |
| Requests ≥250m/512Mi, 1:1–1:6.5 vCPU:GiB, no privileged pods | [05 §5.8](../docs/05-infrastructure.md#gcp-autopilot) |

**Precedence: if this spec and a contract disagree, the CONTRACT wins — stop and report**
([04 §4.4](../docs/04-contracts.md#c-precedence)).

## 3. Deliverables

| Path | What |
|---|---|
| `deploy/k8s/namespace.yaml` | Namespace `dlock` with `app.kubernetes.io/part-of=dlock-lab` |
| `deploy/k8s/configmap.yaml` | ConfigMap `dlock-config` — non-secret defaults only |
| `deploy/k8s/<module>/serviceaccount.yaml` ×5 | KSAs `lock-server`, `payout-executor`, `payment-resource`, `rail-proxy`, `rail-stub` (**no** WI annotation yet — T-057) |
| `deploy/k8s/<module>/deployment.yaml` ×5 | the five Deployments |
| `deploy/k8s/<module>/service.yaml` ×4 | ClusterIP for all but `payout-executor` |
| `deploy/k8s/harness/job.yaml` | Job `harness`, `restartPolicy: Never`, created `suspend: true` |
| `deploy/k8s/apply.sh` | substitutes the image tag, applies in dependency order, waits for rollout |
| `deploy/k8s/README.md` | what is applied, in what order, and the known-not-ready state before T-057 |

## 4. Specification

**Common to every Deployment.** One container named after the module; image
`europe-central2-docker.pkg.dev/dlock-lab/dlock/<module>` with the tag substituted from a
`__IMAGE_TAG__` placeholder by `apply.sh` (never `:latest`); the four `app.kubernetes.io/*` labels with
`/component` set to `lock`, `payments`, `rail` or `harness`; `serviceAccountName` = module name; requests
`cpu: 500m`, `memory: 512Mi` and **no** limits (Autopilot equalises them, and a memory limit below the
heap turns a GC pause into an OOMKill); `securityContext` non-root, no privilege escalation, read-only
root filesystem; `terminationGracePeriodSeconds: 30`; `containerPort: 8080` with `name: http-metrics`;
`envFrom` the ConfigMap; readiness `/actuator/health/readiness` and liveness `/actuator/health/liveness`
with distinct thresholds — readiness may flap, liveness must not, so give liveness a longer period and
`failureThreshold` such that a slow GC cannot restart a healthy pod.

**Per workload.**

| Workload | Replicas | Distinguishing fields |
|---|---|---|
| `lock-server` | 3 | `LOCK_BACKEND` from the ConfigMap; `topologySpreadConstraints` on `topology.kubernetes.io/zone`, `maxSkew: 1`, `whenUnsatisfiable: ScheduleAnyway`; ClusterIP `lock-server:8080` |
| `payout-executor` | 2 | no Service (no inbound traffic); `LOCK_CLIENT_SAFETYMARGIN`; paydb env; the replica count is the experiment, do not "tidy" it to 1 |
| `payment-resource` | 1 | annotation `dlock-lab/fencing-enabled`; env `PAYMENT_FENCING_ENABLED`, `PAYMENTS_DATASOURCE_URL`, password via `secretKeyRef` |
| `rail-proxy` | 1 | `RAIL_PROXY_FENCING_ENABLED`; paydb env for the persisted high-water table |
| `rail-stub` | 1 | `RAIL_STUB_DUPLICATEACKRATE`; no database |
| `harness` (Job) | — | `restartPolicy: Never`, `backoffLimit: 0`, `cluster-autoscaler.kubernetes.io/safe-to-evict: "false"`; created suspended so no scenario runs by accident |

**ConfigMap.** Only keys from [C5 §5.1](../docs/contracts/C5-config-build-and-naming.md#ct5-config), in
env-var form, and **no password and no full JDBC URL containing credentials**. Both kill switches are
`true` here; flipping one is a deliberate experiment (T-042, T-046), not a deployment default.

**Services.** ClusterIP, `port: 8080`, `targetPort: http-metrics` **by name**, and the service port also
named `http-metrics` so a `PodMonitoring` selector cannot miss.

**`apply.sh`.** `set -euo pipefail`; require `BUILD_ID`; apply namespace → ConfigMap → SAs → Deployments →
Services; then `kubectl rollout status` per Deployment with an explicit timeout and a non-zero exit.

## 5. Acceptance criteria

1. `kubectl apply --dry-run=server -f deploy/k8s/ -R` succeeds against the real cluster.
2. Every container in `deploy/k8s/**/deployment.yaml` declares `name: http-metrics` on port 8080 —
   `grep -c 'http-metrics'` equals the number of containers plus the four Services' two occurrences each.
3. `grep -rn 'SAFETY_MARGIN\|DUPLICATE_ACK\|FENCING_ENABLED=' deploy/k8s` returns nothing (wrong spellings).
4. `grep -rn 'password\|PASSWORD:' deploy/k8s/configmap.yaml` returns nothing.
5. `payout-executor` has `replicas: 2` and no `Service` manifest; `lock-server` has `replicas: 3`.
6. `kubectl -n dlock get deploy` shows `lock-server` and `rail-stub` fully available after `apply.sh`.
7. `deploy/k8s/README.md` states that paydb-backed pods stay not-ready until T-057, and why.

## 6. Verification

```
gcloud container clusters get-credentials dlock-gke --region europe-central2 --project dlock-lab
BUILD_ID=a1b2c3d ./deploy/k8s/apply.sh
kubectl -n dlock get pods -o wide          # lock-server across three distinct NODEs/zones
kubectl -n dlock port-forward deploy/lock-server 8080:8080 &
curl -s localhost:8080/actuator/prometheus | grep -c '^lock_'
kubectl -n dlock get svc lock-server -o jsonpath='{.spec.ports[0].targetPort}'   # expect http-metrics
```

Expected: `lock-server` 3/3 and `rail-stub` 1/1 Running; the metrics grep is non-zero; paydb-backed pods
report a config/secret error rather than crash-looping on a bad image.

## 7. Out of scope

etcd StatefulSet, PDB and its Service (T-056); Workload Identity annotations, Secret Manager projection
and private-IP wiring (T-057); the `PodMonitoring` CRD, dashboards and alerts (M6); running the harness
Job (M7); HPA, NetworkPolicy, Ingress and any service mesh — none is in the inventory.

## 8. Hazards

- **The named-port trap** ([C4 §4.9](../docs/contracts/C4-observability.md#ct4-scrape)): a port named
  `http`, or unnamed, matches nothing later and reports no error. Fix it here, not in M6.
- Autopilot mutates undersized requests upward and bills the mutated value — set them explicitly.
- A read-only root filesystem needs an `emptyDir` at `/tmp` for the Boot loader; omit it and the JVM
  fails to start with an unhelpful temp-dir error.
- Do not add `LOCK_CLIENT_SAFETY_MARGIN`: the underscore binds to nothing and the default silently
  survives ([C5 §5.7](../docs/contracts/C5-config-build-and-naming.md#ct5-env)).

## 9. On completion

Mark the T-055 row done in `tasks/README.md`; record the `BUILD_ID` applied and which pods were left
deliberately not-ready pending T-057.
